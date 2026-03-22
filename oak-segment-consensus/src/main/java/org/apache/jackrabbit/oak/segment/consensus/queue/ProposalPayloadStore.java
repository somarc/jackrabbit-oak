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
package org.apache.jackrabbit.oak.segment.consensus.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

final class ProposalPayloadStore {
    private static final Logger log = LoggerFactory.getLogger(ProposalPayloadStore.class);

    static final class StoredPayload {
        private final String payloadRef;
        private final long sizeBytes;
        private final String sha256;

        StoredPayload(String payloadRef, long sizeBytes, String sha256) {
            this.payloadRef = payloadRef;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        String getPayloadRef() {
            return payloadRef;
        }

        long getSizeBytes() {
            return sizeBytes;
        }

        String getSha256() {
            return sha256;
        }
    }

    private final Path payloadDirectory;
    private final boolean ephemeralDirectory;
    private final AtomicLong totalBytes = new AtomicLong(0L);

    ProposalPayloadStore(Path payloadDirectory, boolean ephemeralDirectory) {
        this.payloadDirectory = payloadDirectory;
        this.ephemeralDirectory = ephemeralDirectory;
        try {
            Files.createDirectories(payloadDirectory);
            this.totalBytes.set(scanCurrentBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize proposal payload store at " + payloadDirectory, e);
        }
    }

    synchronized StoredPayload storePayload(String proposalId, String message, long maxTotalBytes) {
        if (proposalId == null || proposalId.isEmpty()) {
            throw new IllegalArgumentException("proposalId is required");
        }
        if (message == null || message.isEmpty()) {
            return new StoredPayload(null, 0L, sha256Hex(new byte[0]));
        }

        byte[] payloadBytes = message.getBytes(StandardCharsets.UTF_8);
        String payloadRef = proposalId + ".payload";
        Path target = payloadDirectory.resolve(payloadRef);
        long existingBytes = sizeIfExists(target);
        long projected = totalBytes.get() - existingBytes + payloadBytes.length;
        if (maxTotalBytes > 0L && projected > maxTotalBytes) {
            throw new RejectedExecutionException("payload_spool_capacity_exceeded");
        }

        Path temp = payloadDirectory.resolve(payloadRef + ".tmp");
        try {
            Files.write(temp, payloadBytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteIfExistsQuietly(temp);
            throw new IllegalStateException("Failed to persist proposal payload for " + proposalId, e);
        }

        totalBytes.addAndGet(-existingBytes + payloadBytes.length);
        return new StoredPayload(payloadRef, payloadBytes.length, sha256Hex(payloadBytes));
    }

    String loadPayload(String payloadRef, String expectedSha256) throws IOException {
        if (payloadRef == null || payloadRef.isEmpty()) {
            return "";
        }
        Path payloadPath = payloadDirectory.resolve(payloadRef);
        byte[] payloadBytes = Files.readAllBytes(payloadPath);
        String actualSha256 = sha256Hex(payloadBytes);
        if (expectedSha256 != null && !expectedSha256.isEmpty() && !expectedSha256.equals(actualSha256)) {
            throw new IOException("Payload checksum mismatch for " + payloadRef);
        }
        return new String(payloadBytes, StandardCharsets.UTF_8);
    }

    boolean hasPayload(String payloadRef) {
        if (payloadRef == null || payloadRef.isEmpty()) {
            return true;
        }
        return Files.exists(payloadDirectory.resolve(payloadRef));
    }

    synchronized void deletePayload(String payloadRef, long expectedSizeBytes) {
        if (payloadRef == null || payloadRef.isEmpty()) {
            return;
        }
        Path payloadPath = payloadDirectory.resolve(payloadRef);
        long bytes = sizeIfExists(payloadPath);
        if (bytes <= 0L) {
            bytes = Math.max(0L, expectedSizeBytes);
        }
        deleteIfExistsQuietly(payloadPath);
        if (bytes > 0L) {
            totalBytes.addAndGet(-bytes);
        }
    }

    long getTotalBytes() {
        return Math.max(0L, totalBytes.get());
    }

    Path getPayloadDirectory() {
        return payloadDirectory;
    }

    void close() {
        if (!ephemeralDirectory) {
            return;
        }
        try {
            if (!Files.exists(payloadDirectory)) {
                return;
            }
            Files.walk(payloadDirectory)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(this::deleteIfExistsQuietly);
        } catch (IOException e) {
            log.debug("Failed to cleanup ephemeral payload store {}: {}", payloadDirectory, e.getMessage());
        }
    }

    private long scanCurrentBytes() throws IOException {
        if (!Files.exists(payloadDirectory)) {
            return 0L;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(payloadDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .mapToLong(this::sizeIfExists)
                .sum();
        }
    }

    private long sizeIfExists(Path path) {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private void deleteIfExistsQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Failed to delete payload file {}: {}", path, e.getMessage());
        }
    }

    private static String sha256Hex(byte[] payloadBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payloadBytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
