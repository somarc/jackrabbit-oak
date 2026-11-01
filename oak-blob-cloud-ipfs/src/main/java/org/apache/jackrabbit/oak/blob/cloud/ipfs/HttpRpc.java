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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import org.apache.jackrabbit.oak.commons.json.JsonObject;
import org.apache.jackrabbit.oak.commons.json.JsopTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * HTTP calls to an IPFS service (the Kubo RPC API or the IPFS Cluster REST
 * API) on the JDK HTTP client.
 *
 * <p>Requests reuse pooled connections and send the configured
 * {@code Authorization} header. The request timeout bounds both the wait for a
 * response and any pause while its body is read, so a stalled transfer fails
 * instead of hanging. {@link #retry} repeats calls that fail in transport, or
 * with a 502, 503 or 504 from a proxy, with capped exponential backoff; service
 * errors are not retried.</p>
 */
final class HttpRpc {

    private static final Logger LOG = LoggerFactory.getLogger(HttpRpc.class);

    private static final long INITIAL_BACKOFF_MILLIS = 100;
    private static final long MAX_BACKOFF_MILLIS = 5_000;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(502, 503, 504);

    private final String service;
    private final URI base;
    private final String authorization;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final HttpClient http;

    /**
     * @param service    name used in messages, e.g. "Kubo"
     * @param base       base URI that request paths are resolved against
     * @param sslContext custom TLS context, or {@code null} for the JVM default
     */
    HttpRpc(String service, URI base, String authorization, Duration connectTimeout, Duration requestTimeout,
            int maxAttempts, SSLContext sslContext) {
        this.service = service;
        this.base = base;
        this.authorization = authorization;
        this.requestTimeout = requestTimeout;
        this.maxAttempts = maxAttempts;
        HttpClient.Builder builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.http = builder.build();
        String host = base.getHost();
        if (authorization != null && "http".equals(base.getScheme())
                && !"127.0.0.1".equals(host) && !"localhost".equals(host) && !"[::1]".equals(host)) {
            LOG.warn("{} credentials are sent without TLS to {}; use an https endpoint", service, base);
        }
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * Sends one request and returns the body of a 200 response.
     *
     * @throws RpcException for any other status
     */
    InputStream send(String method, String path, String query, BodyPublisher body, String contentType,
                     Duration timeout) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path + (query.isEmpty() ? "" : "?" + query)))
            .timeout(timeout)
            .method(method, body == null ? BodyPublishers.noBody() : body);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<Flow.Publisher<List<ByteBuffer>>> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofPublisher());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted calling " + service + " " + path);
        }
        InputStream responseBody = new IdleTimeoutBody(response.body(), requestTimeout, service + " " + path);
        if (response.statusCode() == 200) {
            return responseBody;
        }
        String detail;
        try (InputStream in = responseBody) {
            detail = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
        }
        throw new RpcException(service + " " + path, response.statusCode(), errorMessage(detail));
    }

    /**
     * Sends a request without a body, with retries, and returns the response text.
     */
    String text(String method, String path, String query) throws IOException {
        return retry(path, () -> readText(send(method, path, query, null, null, requestTimeout)));
    }

    <T> T retry(String name, IoCall<T> call) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.call();
            } catch (InterruptedIOException | FileNotFoundException e) {
                throw e;
            } catch (IOException e) {
                boolean retryable = !(e instanceof RpcException)
                    || RETRYABLE_STATUS.contains(((RpcException) e).status());
                if (!retryable || attempt >= maxAttempts) {
                    throw e;
                }
                long backoff = Math.min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS << (attempt - 1));
                LOG.debug("{} {} failed (attempt {} of {}), retrying in {} ms: {}",
                    service, name, attempt, maxAttempts, backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted retrying " + service + " " + name);
                }
            }
        }
    }

    static String readText(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Kubo errors carry {@code Message}, IPFS Cluster errors {@code message};
     * anything else, such as a proxy page, is returned as text.
     */
    private static String errorMessage(String body) {
        try {
            JsonObject json = JsonObject.fromJson(body, true);
            for (String key : new String[] {"Message", "message"}) {
                String message = json.getProperties().get(key);
                if (message != null) {
                    return JsopTokenizer.decodeQuoted(message);
                }
            }
        } catch (RuntimeException e) {
            // Not a JSON error object.
        }
        return body.trim();
    }

    @FunctionalInterface
    interface IoCall<T> {
        T call() throws IOException;
    }

    /**
     * An error response from the service.
     */
    static final class RpcException extends IOException {

        private final int status;

        RpcException(String call, int status, String message) {
            super(call + " failed (HTTP " + status + "): " + message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /**
     * A response body that fails when no data arrives within the idle timeout,
     * instead of blocking the reading thread indefinitely.
     */
    static final class IdleTimeoutBody extends InputStream implements Flow.Subscriber<List<ByteBuffer>> {

        private static final Object END = new Object();

        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final Duration idleTimeout;
        private final String command;
        private volatile Flow.Subscription subscription;
        private Iterator<ByteBuffer> buffers = Collections.emptyIterator();
        private ByteBuffer buffer;
        private IOException failure;
        private boolean done;

        IdleTimeoutBody(Flow.Publisher<List<ByteBuffer>> publisher, Duration idleTimeout, String command) {
            this.idleTimeout = idleTimeout;
            this.command = command;
            publisher.subscribe(this);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            queue.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            queue.add(throwable);
        }

        @Override
        public void onComplete() {
            queue.add(END);
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) == -1 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (failure != null) {
                throw failure;
            }
            if (length == 0) {
                return 0;
            }
            while (buffer == null || !buffer.hasRemaining()) {
                if (buffers.hasNext()) {
                    buffer = buffers.next();
                    continue;
                }
                if (done) {
                    return -1;
                }
                next();
            }
            int count = Math.min(length, buffer.remaining());
            buffer.get(target, offset, count);
            return count;
        }

        @SuppressWarnings("unchecked")
        private void next() throws IOException {
            Object item;
            try {
                item = queue.poll(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw fail(new InterruptedIOException("Interrupted reading " + command));
            }
            if (item == null) {
                throw fail(new HttpTimeoutException(command + " sent no data for " + idleTimeout));
            }
            if (item == END) {
                done = true;
            } else if (item instanceof Throwable) {
                Throwable cause = (Throwable) item;
                throw fail(cause instanceof IOException ? (IOException) cause
                    : new IOException(command + " response failed", cause));
            } else {
                buffers = ((List<ByteBuffer>) item).iterator();
                subscription.request(1);
            }
        }

        private IOException fail(IOException e) {
            failure = e;
            cancel();
            return e;
        }

        @Override
        public void close() {
            cancel();
        }

        private void cancel() {
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
        }
    }
}
