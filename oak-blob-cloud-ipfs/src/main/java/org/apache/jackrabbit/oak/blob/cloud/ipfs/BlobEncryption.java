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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Streaming envelope encryption for blobs stored in IPFS: a versioned header
 * followed by AES-256-GCM sealed segments, with a per-blob data key derived by
 * HKDF-SHA256 from the provider's master key and a random salt. Each segment
 * nonce encodes its index and a last-segment flag, and every segment
 * authenticates the header plus the caller's identifier, so tampering,
 * truncation, reordering, appending and cross-blob or cross-identifier swaps
 * all fail decryption. The module README describes the layout.
 */
final class BlobEncryption {

    /** Plaintext bytes per segment for new blobs. */
    public static final int SEGMENT_SIZE = 64 * 1024;

    static final byte[] MAGIC = {(byte) 0x89, 'O', 'K', 'E'};
    static final byte VERSION = 1;
    static final int MIN_SEGMENT_SIZE = 16;
    static final int MAX_SEGMENT_SIZE = 8 * 1024 * 1024;
    static final int SALT_BYTES = 32;
    static final int NONCE_PREFIX_BYTES = 7;
    static final int TAG_BYTES = 16;
    static final int KEY_BYTES = 32;
    static final long MAX_SEGMENT_INDEX = 0xFFFF_FFFFL;

    private static final int FIXED_HEADER_BYTES = MAGIC.length + 1 + 4 + 1;
    private static final byte[] HKDF_INFO = "oak-ipfs-blob/v1/aes-256-gcm".getBytes(US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BlobEncryption() {
    }

    /**
     * Encrypts {@code plaintext} into {@code ciphertext} with the provider's
     * current key. A partially written ciphertext file is deleted on failure.
     */
    public static void encrypt(File plaintext, File ciphertext, String associatedId, BlobKeyProvider keys)
            throws IOException {
        try (InputStream in = Files.newInputStream(plaintext.toPath());
             OutputStream out = Files.newOutputStream(ciphertext.toPath())) {
            encrypt(in, out, associatedId, keys);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(ciphertext.toPath());
            throw e;
        }
    }

    /**
     * Encrypts {@code plaintext} to {@code ciphertext} with the provider's
     * current key, holding at most two segments in memory. Neither stream is closed.
     */
    public static void encrypt(InputStream plaintext, OutputStream ciphertext, String associatedId,
            BlobKeyProvider keys) throws IOException {
        encrypt(plaintext, ciphertext, associatedId, keys, SEGMENT_SIZE);
    }

    static void encrypt(InputStream plaintext, OutputStream ciphertext, String associatedId,
            BlobKeyProvider keys, int segmentSize) throws IOException {
        Objects.requireNonNull(associatedId, "associatedId");
        checkSegmentSize(segmentSize);
        BlobKeyProvider.KeyRef ref = Objects.requireNonNull(keys.current(), "current key");
        if (!StaticBlobKeyProvider.KEY_ID.matcher(ref.keyId()).matches()) {
            throw new IOException("Blob key id must match " + StaticBlobKeyProvider.KEY_ID.pattern());
        }
        byte[] keyId = ref.keyId().getBytes(UTF_8);
        byte[] salt = random(SALT_BYTES);
        byte[] noncePrefix = random(NONCE_PREFIX_BYTES);
        byte[] header = ByteBuffer.allocate(FIXED_HEADER_BYTES + keyId.length + SALT_BYTES + NONCE_PREFIX_BYTES)
                .put(MAGIC).put(VERSION).putInt(segmentSize).put((byte) keyId.length)
                .put(keyId).put(salt).put(noncePrefix).array();
        SecretKey dataKey = deriveDataKey(ref.key(), salt);
        byte[] aad = aad(header, associatedId);
        Cipher cipher = newCipher();
        ciphertext.write(header);

        byte[] current = new byte[segmentSize];
        byte[] next = new byte[segmentSize];
        byte[] sealed = new byte[segmentSize + TAG_BYTES];
        int currentLength = plaintext.readNBytes(current, 0, segmentSize);
        for (long index = 0; ; index++) {
            // a segment is sealed only once we know whether more plaintext follows it
            int nextLength = currentLength == segmentSize ? plaintext.readNBytes(next, 0, segmentSize) : 0;
            boolean last = nextLength == 0;
            if (index > MAX_SEGMENT_INDEX) {
                throw new IOException("Blob exceeds " + (MAX_SEGMENT_INDEX + 1) + " segments");
            }
            try {
                cipher.init(Cipher.ENCRYPT_MODE, dataKey, nonce(noncePrefix, index, last));
                cipher.updateAAD(aad);
                ciphertext.write(sealed, 0, cipher.doFinal(current, 0, currentLength, sealed, 0));
            } catch (GeneralSecurityException e) {
                throw new IOException("Blob encryption failed", e);
            }
            if (last) {
                return;
            }
            byte[] swap = current;
            current = next;
            next = swap;
            currentLength = nextLength;
        }
    }

    /**
     * Opens a decrypting view of {@code ciphertext}. The header is read and the
     * key resolved before returning; segments are verified as they are read and
     * no unverified plaintext is ever returned. Tampering, a wrong key or
     * identifier, truncation or trailing data surface as an {@link IOException}
     * from {@code read}, never as a clean end of stream.
     */
    public static InputStream decrypt(InputStream ciphertext, String associatedId, BlobKeyProvider keys)
            throws IOException {
        Objects.requireNonNull(associatedId, "associatedId");
        byte[] fixed = readHeader(ciphertext, FIXED_HEADER_BYTES);
        if (!Arrays.equals(fixed, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new IOException("Not an encrypted blob: bad magic");
        }
        if (fixed[MAGIC.length] != VERSION) {
            throw new IOException("Unsupported blob encryption version " + (fixed[MAGIC.length] & 0xFF));
        }
        int segmentSize = ByteBuffer.wrap(fixed, MAGIC.length + 1, 4).getInt();
        checkSegmentSize(segmentSize);
        int keyIdLength = fixed[FIXED_HEADER_BYTES - 1] & 0xFF;
        if (keyIdLength == 0) {
            throw new IOException("Encrypted blob header has an empty key id");
        }
        byte[] rest = readHeader(ciphertext, keyIdLength + SALT_BYTES + NONCE_PREFIX_BYTES);
        byte[] header = ByteBuffer.allocate(fixed.length + rest.length).put(fixed).put(rest).array();

        String keyId;
        try {
            keyId = UTF_8.newDecoder().decode(ByteBuffer.wrap(rest, 0, keyIdLength)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Encrypted blob header has a malformed key id", e);
        }
        if (!StaticBlobKeyProvider.KEY_ID.matcher(keyId).matches()) {
            // Not echoed or passed to the provider: the header is not authenticated yet.
            throw new IOException("Encrypted blob header has an invalid key id");
        }
        SecretKey master;
        try {
            master = keys.resolve(keyId);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot resolve blob key id " + keyId, e);
        }
        byte[] salt = Arrays.copyOfRange(rest, keyIdLength, keyIdLength + SALT_BYTES);
        byte[] noncePrefix = Arrays.copyOfRange(rest, keyIdLength + SALT_BYTES, rest.length);
        return new DecryptingInputStream(ciphertext, deriveDataKey(master, salt), noncePrefix,
                aad(header, associatedId), segmentSize);
    }

    /**
     * Returns the plaintext length of an encrypted blob from its header and its
     * total ciphertext length, or {@code -1} if {@code ciphertextStart} does not
     * start an encrypted blob. Reads only the header from the stream.
     */
    static long plaintextLength(InputStream ciphertextStart, long ciphertextLength) throws IOException {
        byte[] fixed = ciphertextStart.readNBytes(FIXED_HEADER_BYTES);
        if (fixed.length < FIXED_HEADER_BYTES || !Arrays.equals(fixed, 0, MAGIC.length, MAGIC, 0, MAGIC.length)
                || fixed[MAGIC.length] != VERSION) {
            return -1;
        }
        int segmentSize = ByteBuffer.wrap(fixed, MAGIC.length + 1, 4).getInt();
        checkSegmentSize(segmentSize);
        long body = ciphertextLength - FIXED_HEADER_BYTES - (fixed[FIXED_HEADER_BYTES - 1] & 0xFF)
            - SALT_BYTES - NONCE_PREFIX_BYTES;
        long segments = Math.max(1, (body + segmentSize + TAG_BYTES - 1) / (segmentSize + TAG_BYTES));
        long plaintext = body - segments * TAG_BYTES;
        if (plaintext < 0) {
            throw new IOException("Encrypted blob of " + ciphertextLength + " bytes is truncated");
        }
        return plaintext;
    }

    /**
     * Peeks at the start of {@code in} for the encrypted-blob magic and resets
     * the stream. This is a format hint, not an integrity check: plaintext may
     * start with the same bytes, and a store holding plaintext can serve it in
     * place of a ciphertext. Callers that sniff must still verify the
     * decrypted or plain content against its identifier.
     *
     * @throws IllegalArgumentException if {@code in} does not support mark
     */
    public static boolean isEncrypted(InputStream in) throws IOException {
        if (!in.markSupported()) {
            throw new IllegalArgumentException("Stream must support mark/reset");
        }
        in.mark(MAGIC.length);
        try {
            return Arrays.equals(in.readNBytes(MAGIC.length), MAGIC);
        } finally {
            in.reset();
        }
    }

    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        Arrays.fill(prk, (byte) 0);
        byte[] okm = new byte[length];
        byte[] block = new byte[0];
        for (int counter = 1, offset = 0; offset < length; counter++) {
            mac.update(block);
            mac.update(info);
            mac.update((byte) counter);
            block = mac.doFinal();
            int n = Math.min(block.length, length - offset);
            System.arraycopy(block, 0, okm, offset, n);
            offset += n;
        }
        Arrays.fill(block, (byte) 0);
        return okm;
    }

    private static SecretKey deriveDataKey(SecretKey master, byte[] salt) throws IOException {
        byte[] ikm = master.getEncoded();
        if (ikm == null || ikm.length != KEY_BYTES) {
            throw new IOException("Blob master key must be an extractable " + KEY_BYTES + "-byte key");
        }
        byte[] okm = null;
        try {
            okm = hkdfSha256(ikm, salt, HKDF_INFO, KEY_BYTES);
            return new SecretKeySpec(okm, "AES");
        } catch (GeneralSecurityException e) {
            throw new IOException("Blob key derivation failed", e);
        } finally {
            Arrays.fill(ikm, (byte) 0);
            if (okm != null) {
                Arrays.fill(okm, (byte) 0);
            }
        }
    }

    private static GCMParameterSpec nonce(byte[] prefix, long index, boolean last) {
        byte[] nonce = ByteBuffer.allocate(NONCE_PREFIX_BYTES + 5)
                .put(prefix).putInt((int) index).put((byte) (last ? 1 : 0)).array();
        return new GCMParameterSpec(TAG_BYTES * 8, nonce);
    }

    private static byte[] aad(byte[] header, String associatedId) {
        byte[] id = associatedId.getBytes(UTF_8);
        return ByteBuffer.allocate(header.length + id.length).put(header).put(id).array();
    }

    private static Cipher newCipher() throws IOException {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IOException("AES/GCM is not available", e);
        }
    }

    private static void checkSegmentSize(int segmentSize) throws IOException {
        if (segmentSize < MIN_SEGMENT_SIZE || segmentSize > MAX_SEGMENT_SIZE) {
            throw new IOException("Unsupported blob encryption segment size " + segmentSize);
        }
    }

    private static byte[] readHeader(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Encrypted blob header is truncated");
        }
        return bytes;
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static final class DecryptingInputStream extends InputStream {

        private final InputStream in;
        private final Cipher cipher;
        private final SecretKey dataKey;
        private final byte[] noncePrefix;
        private final byte[] aad;
        private final byte[] sealed;
        private final byte[] plain;
        private int position;
        private int limit;
        private long index;
        private int lookahead = -1;
        private boolean lastSeen;
        private IOException failure;

        DecryptingInputStream(InputStream in, SecretKey dataKey, byte[] noncePrefix, byte[] aad, int segmentSize)
                throws IOException {
            this.in = in;
            this.cipher = newCipher();
            this.dataKey = dataKey;
            this.noncePrefix = noncePrefix;
            this.aad = aad;
            this.sealed = new byte[segmentSize + TAG_BYTES];
            this.plain = new byte[segmentSize];
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (failure != null) {
                throw new IOException(failure.getMessage(), failure);
            }
            if (len == 0) {
                return 0;
            }
            while (position == limit) {
                if (lastSeen) {
                    return -1;
                }
                try {
                    openNextSegment();
                } catch (IOException e) {
                    failure = e;
                    throw e;
                }
            }
            int n = Math.min(len, limit - position);
            System.arraycopy(plain, position, b, off, n);
            position += n;
            return n;
        }

        @Override
        public int available() {
            return limit - position;
        }

        @Override
        public void close() throws IOException {
            Arrays.fill(plain, (byte) 0);
            in.close();
        }

        private void openNextSegment() throws IOException {
            int length = 0;
            if (lookahead >= 0) {
                sealed[length++] = (byte) lookahead;
            }
            length += in.readNBytes(sealed, length, sealed.length - length);
            boolean last = true;
            if (length == sealed.length) {
                // a full segment is the last one only if nothing follows it
                lookahead = in.read();
                last = lookahead < 0;
            }
            if (length < TAG_BYTES) {
                throw new IOException("Encrypted blob is truncated at segment " + index);
            }
            if (index > MAX_SEGMENT_INDEX) {
                throw new IOException("Encrypted blob exceeds " + (MAX_SEGMENT_INDEX + 1) + " segments");
            }
            try {
                cipher.init(Cipher.DECRYPT_MODE, dataKey, nonce(noncePrefix, index, last));
                cipher.updateAAD(aad);
                limit = cipher.doFinal(sealed, 0, length, plain, 0);
            } catch (GeneralSecurityException e) {
                throw new IOException("Encrypted blob failed authentication at segment " + index
                        + " (tampered, truncated, extended, wrong key or wrong identifier)", e);
            }
            position = 0;
            index++;
            lastSeen = last;
        }
    }
}
