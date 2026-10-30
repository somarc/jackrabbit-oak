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
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@link IpfsClient} for the Kubo RPC API, built on the JDK HTTP client.
 *
 * <p>Requests reuse pooled connections and send the configured
 * {@code Authorization} header. The request timeout bounds both the wait for a
 * response and any pause while its body is read, so a stalled transfer fails
 * instead of hanging. File content is streamed in both directions. Calls that
 * fail in transport, or with a 502, 503 or 504 from a proxy, are retried with
 * capped exponential backoff; every operation the backend issues is idempotent.
 * Kubo command errors (HTTP 500) are not retried.</p>
 */
final class DefaultIpfsClient implements IpfsClient {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultIpfsClient.class);

    /**
     * Kubo's historical import defaults, pinned so a node's {@code Import.*}
     * settings cannot silently change the CIDs Oak records.
     */
    static final String IMPORT_OPTIONS = "cid-version=0&hash=sha2-256&chunker=size-262144&raw-leaves=false"
        + "&max-file-links=174&trickle=false&pin=true";

    /**
     * Kubo answers an add only after importing the whole file, so the upload
     * timeout grows with the file at this assumed minimum throughput.
     */
    private static final long MIN_UPLOAD_BYTES_PER_SECOND = 1024 * 1024;
    private static final long INITIAL_BACKOFF_MILLIS = 100;
    private static final long MAX_BACKOFF_MILLIS = 5_000;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(502, 503, 504);

    private final IpfsClientSettings settings;
    private final URI base;
    private final HttpClient http;

    DefaultIpfsClient(IpfsClientSettings settings) {
        this(settings, null);
    }

    DefaultIpfsClient(String endpoint) {
        this(IpfsClientSettings.of(endpoint));
    }

    /**
     * Testing seam for a custom TLS context; production uses the JVM default.
     */
    DefaultIpfsClient(IpfsClientSettings settings, SSLContext sslContext) {
        this.settings = settings;
        this.base = settings.apiBase();
        HttpClient.Builder builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(settings.connectTimeout());
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.http = builder.build();
        String host = base.getHost();
        if (settings.authorization() != null && "http".equals(base.getScheme())
                && !"127.0.0.1".equals(host) && !"localhost".equals(host) && !"[::1]".equals(host)) {
            LOG.warn("IPFS API credentials are sent without TLS to {}; use an https endpoint", base);
        }
    }

    @Override
    public Object version() throws Exception {
        return stringField(text("version", ""), "Version");
    }

    @Override
    public String add(File file) throws Exception {
        Duration timeout = settings.requestTimeout()
            .plusSeconds(file.length() / MIN_UPLOAD_BYTES_PER_SECOND);
        String response = retry("add", () -> {
            Multipart body = Multipart.of(BodyPublishers.ofFile(file.toPath()));
            return readText(send("add", IMPORT_OPTIONS, body.publisher, body.contentType, timeout));
        });
        String cid = null;
        for (String line : response.split("\n")) {
            if (line.contains("\"Hash\"")) {
                cid = stringField(line, "Hash");
            }
        }
        if (cid == null) {
            throw new IOException("Kubo add returned no CID");
        }
        // Kubo reports a failure after the first response byte only in an HTTP
        // trailer, which the client cannot see; a recursive pin proves the whole
        // file was imported.
        try {
            text("pin/ls", query("arg", cid) + "&type=recursive");
        } catch (KuboRpcException e) {
            throw new IOException("Kubo add of " + file.length() + " bytes returned " + cid
                + " but did not pin it: " + e.getMessage(), e);
        }
        return cid;
    }

    @Override
    public void pinRemove(String cid) throws Exception {
        text("pin/rm", query("arg", cid));
    }

    @Override
    public InputStream cat(String cid) throws Exception {
        return retry("cat", () -> send("cat", query("arg", cid), null, null, settings.requestTimeout()));
    }

    @Override
    public void ensureDirectory(String path) throws Exception {
        text("files/mkdir", query("arg", path) + "&parents=true");
    }

    @Override
    public void writeFile(String path, byte[] data) throws Exception {
        retry("files/write", () -> {
            Multipart body = Multipart.of(BodyPublishers.ofByteArray(data));
            return readText(send("files/write", query("arg", path) + "&create=true&parents=true&truncate=true",
                body.publisher, body.contentType, settings.requestTimeout()));
        });
    }

    @Override
    public void linkCid(String cid, String path) throws Exception {
        deleteFile(path);
        try {
            text("files/cp", query("arg", "/ipfs/" + cid) + "&" + query("arg", path) + "&parents=true");
        } catch (KuboRpcException e) {
            // A cp retried after a lost response finds the link it already made.
            if (!cid.equals(hashIfExists(path))) {
                throw e;
            }
        }
    }

    @Override
    public byte[] readFile(String path) throws Exception {
        try {
            return retry("files/read", () -> {
                try (InputStream in = send("files/read", query("arg", path), null, null, settings.requestTimeout())) {
                    return in.readAllBytes();
                }
            });
        } catch (KuboRpcException e) {
            if (isNotFound(e)) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public boolean fileExists(String path) throws Exception {
        try {
            text("files/stat", query("arg", path));
            return true;
        } catch (KuboRpcException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<String> listFiles(String path) throws Exception {
        String entries = JsonObject.fromJson(text("files/ls", query("arg", path)), true)
            .getProperties().get("Entries");
        List<String> names = new ArrayList<>();
        if (entries == null || "null".equals(entries)) {
            return names;
        }
        JsopTokenizer tokenizer = new JsopTokenizer(entries);
        tokenizer.read('[');
        if (!tokenizer.matches(']')) {
            do {
                tokenizer.read('{');
                String name = JsonObject.create(tokenizer).getProperties().get("Name");
                if (name != null) {
                    names.add(JsopTokenizer.decodeQuoted(name));
                }
            } while (tokenizer.matches(','));
            tokenizer.read(']');
        }
        return names;
    }

    @Override
    public void deleteFile(String path) throws Exception {
        try {
            text("files/rm", query("arg", path) + "&force=true");
        } catch (KuboRpcException e) {
            if (!isNotFound(e)) {
                throw e;
            }
        }
    }

    private String hashIfExists(String path) throws IOException {
        try {
            String hash = JsonObject.fromJson(text("files/stat", query("arg", path)), true)
                .getProperties().get("Hash");
            return hash == null ? null : JsopTokenizer.decodeQuoted(hash);
        } catch (KuboRpcException e) {
            if (isNotFound(e)) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public long fileSize(String path) throws Exception {
        String size = JsonObject.fromJson(text("files/stat", query("arg", path)), true)
            .getProperties().get("Size");
        if (size == null) {
            throw new IOException("Kubo files/stat returned no size for " + path);
        }
        return Long.parseLong(size);
    }

    /**
     * Kubo reports a missing MFS path as an error whose message contains
     * "file does not exist"; everything else is a real failure.
     */
    static boolean isNotFound(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("does not exist")) {
                return true;
            }
        }
        return false;
    }

    private String text(String command, String query) throws IOException {
        return retry(command, () -> readText(send(command, query, null, null, settings.requestTimeout())));
    }

    private InputStream send(String command, String query, BodyPublisher body, String contentType,
                             Duration timeout) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(command + (query.isEmpty() ? "" : "?" + query)))
            .timeout(timeout)
            .POST(body == null ? BodyPublishers.noBody() : body);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (settings.authorization() != null) {
            request.header("Authorization", settings.authorization());
        }
        HttpResponse<Flow.Publisher<List<ByteBuffer>>> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofPublisher());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted calling Kubo " + command);
        }
        InputStream responseBody = new IdleTimeoutBody(response.body(), settings.requestTimeout(), command);
        if (response.statusCode() == 200) {
            return responseBody;
        }
        String detail;
        try (InputStream in = responseBody) {
            detail = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
        }
        throw new KuboRpcException(command, response.statusCode(), errorMessage(detail));
    }

    private <T> T retry(String command, IoCall<T> call) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.call();
            } catch (InterruptedIOException | FileNotFoundException e) {
                throw e;
            } catch (IOException e) {
                boolean retryable = !(e instanceof KuboRpcException)
                    || RETRYABLE_STATUS.contains(((KuboRpcException) e).status());
                if (!retryable || attempt >= settings.maxAttempts()) {
                    throw e;
                }
                long backoff = Math.min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS << (attempt - 1));
                LOG.debug("Kubo {} failed (attempt {} of {}), retrying in {} ms: {}",
                    command, attempt, settings.maxAttempts(), backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted retrying Kubo " + command);
                }
            }
        }
    }

    private static String readText(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String errorMessage(String body) {
        try {
            String message = JsonObject.fromJson(body, true).getProperties().get("Message");
            if (message != null) {
                return JsopTokenizer.decodeQuoted(message);
            }
        } catch (RuntimeException e) {
            // Not a Kubo error object, e.g. a proxy page.
        }
        return body.trim();
    }

    private static String stringField(String json, String key) throws IOException {
        String value = JsonObject.fromJson(json, true).getProperties().get(key);
        if (value == null) {
            throw new IOException("Kubo response has no " + key + ": " + json);
        }
        return JsopTokenizer.decodeQuoted(value);
    }

    private static String query(String key, String value) {
        return key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T call() throws IOException;
    }

    /**
     * A Kubo RPC error response.
     */
    static final class KuboRpcException extends IOException {

        private final int status;

        KuboRpcException(String command, int status, String message) {
            super("Kubo " + command + " failed (HTTP " + status + "): " + message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /**
     * A single-file multipart/form-data body, as Kubo expects for uploads.
     */
    private static final class Multipart {

        private final BodyPublisher publisher;
        private final String contentType;

        private Multipart(BodyPublisher publisher, String contentType) {
            this.publisher = publisher;
            this.contentType = contentType;
        }

        static Multipart of(BodyPublisher content) {
            String boundary = "oak-" + UUID.randomUUID();
            byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            return new Multipart(
                BodyPublishers.concat(BodyPublishers.ofByteArray(head), content, BodyPublishers.ofByteArray(tail)),
                "multipart/form-data; boundary=" + boundary);
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
                throw fail(new InterruptedIOException("Interrupted reading Kubo " + command));
            }
            if (item == null) {
                throw fail(new HttpTimeoutException("Kubo " + command + " sent no data for " + idleTimeout));
            }
            if (item == END) {
                done = true;
            } else if (item instanceof Throwable) {
                Throwable cause = (Throwable) item;
                throw fail(cause instanceof IOException ? (IOException) cause
                    : new IOException("Kubo " + command + " response failed", cause));
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
