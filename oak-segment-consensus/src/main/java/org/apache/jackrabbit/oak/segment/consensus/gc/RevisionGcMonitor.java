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
package org.apache.jackrabbit.oak.segment.consensus.gc;

import org.apache.jackrabbit.oak.spi.gc.GCMonitor;
import org.slf4j.helpers.MessageFormatter;

/**
 * Registered on the validator {@code FileStore}: records what Oak reports during a scheduled run.
 * Oak's own logging monitor stays registered, so this one does not log.
 */
public final class RevisionGcMonitor implements GCMonitor {

    /** What Oak reported during one run. */
    static final class Capture {
        boolean compacted;
        String skipReason;
        String error;
        String lastMessage;
        long reclaimedBytes;
        Long sizeAfter;
    }

    private Capture current;
    private volatile String oakStatus = "idle";

    synchronized Capture begin() {
        current = new Capture();
        return current;
    }

    synchronized void end() {
        current = null;
    }

    String oakStatus() {
        return oakStatus;
    }

    @Override
    public synchronized void info(String message, Object... arguments) {
        if (current != null) {
            current.lastMessage = format(message, arguments);
        }
    }

    @Override
    public synchronized void warn(String message, Object... arguments) {
        if (current != null) {
            current.lastMessage = format(message, arguments);
        }
    }

    @Override
    public synchronized void error(String message, Exception exception) {
        if (current != null) {
            current.error = message + ": " + exception;
        }
    }

    @Override
    public synchronized void skipped(String reason, Object... arguments) {
        if (current != null) {
            current.skipReason = format(reason, arguments);
        }
    }

    @Override
    public synchronized void compacted() {
        if (current != null) {
            current.compacted = true;
        }
    }

    @Override
    public synchronized void cleaned(long reclaimedSize, long currentSize) {
        if (current != null) {
            current.reclaimedBytes += reclaimedSize;
            current.sizeAfter = currentSize;
        }
    }

    @Override
    public void updateStatus(String status) {
        oakStatus = status;
    }

    private static String format(String message, Object... arguments) {
        return MessageFormatter.arrayFormat(message, arguments).getMessage();
    }
}
