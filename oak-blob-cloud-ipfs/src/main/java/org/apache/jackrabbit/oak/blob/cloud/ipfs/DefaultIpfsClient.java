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

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    private final IpfsClientSettings settings;
    private final HttpRpc rpc;

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
        this.rpc = new HttpRpc("Kubo", settings.apiBase(), settings.authorization(), settings.connectTimeout(),
            settings.requestTimeout(), settings.maxAttempts(), sslContext);
    }

    @Override
    public Object version() throws Exception {
        return stringField(text("version", ""), "Version");
    }

    @Override
    public String add(File file) throws Exception {
        Duration timeout = settings.requestTimeout()
            .plusSeconds(file.length() / MIN_UPLOAD_BYTES_PER_SECOND);
        String response = rpc.retry("add", () -> {
            Multipart body = Multipart.of(BodyPublishers.ofFile(file.toPath()));
            return HttpRpc.readText(send("add", IMPORT_OPTIONS, body.publisher, body.contentType, timeout));
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
        } catch (HttpRpc.RpcException e) {
            throw new IOException("Kubo add of " + file.length() + " bytes returned " + cid
                + " but did not pin it: " + e.getMessage(), e);
        }
        return cid;
    }

    @Override
    public boolean isPinned(String cid) throws Exception {
        try {
            text("pin/ls", query("arg", cid) + "&type=recursive");
            return true;
        } catch (HttpRpc.RpcException e) {
            if (String.valueOf(e.getMessage()).contains("not pinned")) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public String config() throws Exception {
        return text("config/show", "");
    }

    @Override
    public List<String> swarmListenAddresses() throws Exception {
        return IpfsNetworkPosture.strings(
            JsonObject.fromJson(text("swarm/addrs/listen", ""), true).getProperties().get("Strings"));
    }

    @Override
    public void pinRemove(String cid) throws Exception {
        text("pin/rm", query("arg", cid));
    }

    @Override
    public InputStream cat(String cid) throws Exception {
        return rpc.retry("cat", () -> send("cat", query("arg", cid), null, null, settings.requestTimeout()));
    }

    @Override
    public void ensureDirectory(String path) throws Exception {
        text("files/mkdir", query("arg", path) + "&parents=true");
    }

    @Override
    public void writeFile(String path, byte[] data) throws Exception {
        rpc.retry("files/write", () -> {
            Multipart body = Multipart.of(BodyPublishers.ofByteArray(data));
            return HttpRpc.readText(send("files/write", query("arg", path) + "&create=true&parents=true&truncate=true",
                body.publisher, body.contentType, settings.requestTimeout()));
        });
    }

    @Override
    public void linkCid(String cid, String path) throws Exception {
        deleteFile(path);
        try {
            text("files/cp", query("arg", "/ipfs/" + cid) + "&" + query("arg", path) + "&parents=true");
        } catch (HttpRpc.RpcException e) {
            // A cp retried after a lost response finds the link it already made.
            if (!cid.equals(hashIfExists(path))) {
                throw e;
            }
        }
    }

    @Override
    public byte[] readFile(String path) throws Exception {
        try {
            return rpc.retry("files/read", () -> {
                try (InputStream in = send("files/read", query("arg", path), null, null, settings.requestTimeout())) {
                    return in.readAllBytes();
                }
            });
        } catch (HttpRpc.RpcException e) {
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
        } catch (HttpRpc.RpcException e) {
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
        } catch (HttpRpc.RpcException e) {
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
        } catch (HttpRpc.RpcException e) {
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
        return rpc.text("POST", command, query);
    }

    private InputStream send(String command, String query, BodyPublisher body, String contentType,
                             Duration timeout) throws IOException {
        return rpc.send("POST", command, query, body, contentType, timeout);
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
}
