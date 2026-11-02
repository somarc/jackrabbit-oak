/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.mount;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Remote segment fetches that failed while serving the current request.
 *
 * <p>Oak reports any segment it cannot load as a {@code SegmentNotFoundException}, whether the owner
 * reclaimed the segment or the network failed. Remote segment reads run on the request thread, so the
 * HTTP segment reader records the cause here, and an error answer for the request uses it to tell a remote
 * failure from local corruption: see {@link #failureAnswer()}. Nothing is recorded outside a request.
 */
public final class MountReadFailures {

    /** Why a remote segment could not be read. */
    public enum Kind {
        /** The owner answered 404: it no longer holds the segment, so the mounted head is stale. */
        MISSING,
        /** The request failed or the owner answered with another error: the endpoint is unreachable. */
        TRANSPORT
    }

    /** One failed fetch from {@code endpoint}. */
    public record Failure(@NotNull String endpoint, @NotNull Kind kind, @NotNull String detail) {
    }

    /** The error to answer instead of a 500 when remote reads made the request fail. */
    public record Answer(@NotNull String code, @NotNull String message) {
    }

    private static final class Request {
        private final RemoteMounts mounts;
        private final List<Failure> failures = new ArrayList<>();
        private Answer answer;
        private boolean resolved;

        private Request(RemoteMounts mounts) {
            this.mounts = mounts;
        }
    }

    private static final ThreadLocal<Request> CURRENT = new ThreadLocal<>();

    private MountReadFailures() {
    }

    /** Starts recording for a request on this thread with no mounts to report to. */
    public static void begin() {
        begin(RemoteMounts.NONE);
    }

    /**
     * Starts recording for a request on this thread, discarding anything left from an earlier one.
     *
     * @param mounts the remote mounts serving the request's read view; failures are reported to them
     */
    public static void begin(@Nullable RemoteMounts mounts) {
        CURRENT.set(new Request(mounts != null ? mounts : RemoteMounts.NONE));
    }

    /** The failures recorded since {@link #begin()}. */
    @NotNull
    public static List<Failure> current() {
        Request request = CURRENT.get();
        return request == null ? List.of() : List.copyOf(request.failures);
    }

    /**
     * When remote segment reads failed during this request and one of its mounts made them, reports them to
     * those mounts (once per request: a reclaimed segment refreshes the mount's head, a transport failure counts
     * against its circuit breaker) and returns the 503 to answer instead of a 500: {@code mount_unavailable} when
     * an endpoint could not be reached, otherwise {@code mount_stale}. Returns null when the failure is local.
     */
    @Nullable
    public static Answer failureAnswer() {
        Request request = CURRENT.get();
        if (request == null || request.failures.isEmpty()) {
            return null;
        }
        if (!request.resolved) {
            request.resolved = true;
            List<Failure> failures = List.copyOf(request.failures);
            if (request.mounts.reportReadFailures(failures)) {
                request.answer = answerFor(failures);
            }
        }
        return request.answer;
    }

    private static Answer answerFor(List<Failure> failures) {
        String endpoints = String.join(", ", failures.stream().map(Failure::endpoint).distinct().toList());
        if (failures.stream().anyMatch(f -> f.kind() == Kind.TRANSPORT)) {
            return new Answer("mount_unavailable", "Remote mount " + endpoints + " is unreachable");
        }
        return new Answer("mount_stale",
            "Remote mount " + endpoints + " no longer holds the content this view referenced; retry shortly");
    }

    /** Stops recording on this thread. */
    public static void end() {
        CURRENT.remove();
    }

    /** Records a failed fetch; a no-op outside a request. */
    public static void record(@NotNull String endpoint, @NotNull Kind kind, @NotNull String detail) {
        Request request = CURRENT.get();
        if (request != null) {
            request.failures.add(new Failure(endpoint, kind, detail));
        }
    }
}
