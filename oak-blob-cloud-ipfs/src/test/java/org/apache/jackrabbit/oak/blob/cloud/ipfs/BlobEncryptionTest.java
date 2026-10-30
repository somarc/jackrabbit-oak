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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BlobEncryptionTest {

    private static final int SEG = BlobEncryption.SEGMENT_SIZE;
    private static final int SMALL = 16;
    private static final String ID = "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b";
    private static final String OTHER_ID = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String K1 = randomKey();
    private static final String K2 = randomKey();
    private static final BlobKeyProvider KEYS = StaticBlobKeyProvider.fromSpec("k1;k1:" + K1);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // ---------------------------------------------------------------- round trips

    @Test
    public void roundTripAtDefaultSegmentBoundaries() throws Exception {
        for (int size : new int[] {0, 1, SEG - 1, SEG, SEG + 1, 3 * SEG}) {
            byte[] data = random(size);
            byte[] ct = encrypt(data, ID, KEYS, SEG);
            int segments = Math.max(1, (size + SEG - 1) / SEG);
            assertEquals("size " + size, headerLength("k1") + size + segments * BlobEncryption.TAG_BYTES, ct.length);
            assertArrayEquals("size " + size, data, decrypt(ct, ID, KEYS));
        }
    }

    @Test
    public void roundTripMultiMegabyte() throws Exception {
        byte[] data = random(5 * 1024 * 1024 + 123);
        assertArrayEquals(data, decrypt(encrypt(data, ID, KEYS, SEG), ID, KEYS));
    }

    @Test
    public void roundTripEverySmallSizeWithTinySegments() throws Exception {
        for (int size = 0; size <= 5 * SMALL + 1; size++) {
            byte[] data = random(size);
            assertArrayEquals("size " + size, data, decrypt(encrypt(data, ID, KEYS, SMALL), ID, KEYS));
        }
    }

    @Test
    public void roundTripThroughFilesAndSingleByteReads() throws Exception {
        byte[] data = random(2 * SEG + 7);
        File plain = folder.newFile();
        File cipher = new File(folder.getRoot(), "cipher");
        Files.write(plain.toPath(), data);
        BlobEncryption.encrypt(plain, cipher, ID, KEYS);
        assertFalse(Arrays.equals(data, Arrays.copyOfRange(Files.readAllBytes(cipher.toPath()), 0, data.length)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = BlobEncryption.decrypt(Files.newInputStream(cipher.toPath()), ID, KEYS)) {
            assertEquals(0, in.available());
            assertEquals(0, in.read(new byte[1], 0, 0));
            for (int b; (b = in.read()) >= 0; ) {
                out.write(b);
                if (out.size() == 1) {
                    assertEquals(SEG - 1, in.available());
                }
            }
            assertEquals(-1, in.read());
            assertEquals(-1, in.read(new byte[8], 0, 8));
        }
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    public void sameInputEncryptsDifferentlyEachTime() throws Exception {
        byte[] data = random(100);
        assertNotEquals(HexFormat.of().formatHex(encrypt(data, ID, KEYS, SMALL)),
                HexFormat.of().formatHex(encrypt(data, ID, KEYS, SMALL)));
    }

    /** Re-implements the version 1 format with raw JDK calls. */
    @Test
    public void ciphertextMatchesDocumentedFormat() throws Exception {
        byte[] data = random(SMALL + 5);
        byte[] ct = encrypt(data, ID, KEYS, SMALL);
        ByteBuffer buf = ByteBuffer.wrap(ct);
        byte[] magic = new byte[4];
        buf.get(magic);
        assertArrayEquals(new byte[] {(byte) 0x89, 0x4F, 0x4B, 0x45}, magic);
        assertEquals(1, buf.get());
        assertEquals(SMALL, buf.getInt());
        byte[] keyId = new byte[buf.get()];
        buf.get(keyId);
        assertEquals("k1", new String(keyId, UTF_8));
        byte[] salt = new byte[32];
        byte[] prefix = new byte[7];
        buf.get(salt).get(prefix);
        byte[] header = Arrays.copyOf(ct, buf.position());
        assertEquals(headerLength("k1"), header.length);

        byte[] dataKey = BlobEncryption.hkdfSha256(Base64.getDecoder().decode(K1), salt,
                "oak-ipfs-blob/v1/aes-256-gcm".getBytes(UTF_8), 32);
        byte[] aad = concat(header, ID.getBytes(UTF_8));
        byte[] seg0 = openSegment(dataKey, prefix, 0, false, aad, Arrays.copyOfRange(ct, header.length, header.length + SMALL + 16));
        byte[] seg1 = openSegment(dataKey, prefix, 1, true, aad, Arrays.copyOfRange(ct, header.length + SMALL + 16, ct.length));
        assertArrayEquals(data, concat(seg0, seg1));
    }

    @Test
    public void hkdfMatchesRfc5869Vectors() throws Exception {
        HexFormat hex = HexFormat.of();
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                hex.formatHex(BlobEncryption.hkdfSha256(hex.parseHex("0b".repeat(22)),
                        hex.parseHex("000102030405060708090a0b0c"), hex.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42)));
        assertEquals("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09"
                        + "da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
                hex.formatHex(BlobEncryption.hkdfSha256(range(0x00, 80), range(0x60, 80), range(0xb0, 80), 82)));
    }

    // ---------------------------------------------------------------- keys and identifiers

    @Test
    public void unknownKeyIdFailsOnOpen() throws Exception {
        byte[] ct = encrypt(random(10), ID, KEYS, SMALL);
        IOException e = assertThrows(IOException.class,
                () -> BlobEncryption.decrypt(new ByteArrayInputStream(ct), ID, StaticBlobKeyProvider.fromSpec("k2;k2:" + K2)));
        assertTrue(e.getMessage(), e.getMessage().contains("k1"));
        assertTrue(e.getCause() instanceof GeneralSecurityException);
    }

    @Test
    public void wrongKeyMaterialFails() throws Exception {
        byte[] ct = encrypt(random(10), ID, KEYS, SMALL);
        assertFailsReading(ct, ID, StaticBlobKeyProvider.fromSpec("k1;k1:" + K2));
    }

    @Test
    public void wrongAssociatedIdFails() throws Exception {
        byte[] ct = encrypt(random(3 * SMALL), ID, KEYS, SMALL);
        assertFailsReading(ct, OTHER_ID, KEYS);
        assertFailsReading(ct, "", KEYS);
    }

    @Test
    public void rotatedKeysKeepOldBlobsReadable() throws Exception {
        byte[] data = random(SEG + 1);
        byte[] oldBlob = encrypt(data, ID, KEYS, SEG);
        BlobKeyProvider rotated = StaticBlobKeyProvider.fromSpec("k2;k1:" + K1 + ";k2:" + K2);
        assertArrayEquals(data, decrypt(oldBlob, ID, rotated));

        byte[] newBlob = encrypt(data, ID, rotated, SEG);
        assertEquals("k2", new String(newBlob, 10, 2, UTF_8));
        assertArrayEquals(data, decrypt(newBlob, ID, rotated));
        assertThrows(IOException.class, () -> BlobEncryption.decrypt(new ByteArrayInputStream(newBlob), ID, KEYS));
    }

    @Test
    public void rejectsUnusableEncryptionKeys() throws Exception {
        assertEncryptFails(fixed("k1", new SecretKeySpec(new byte[16], "AES")));
        assertEncryptFails(fixed("k1", nonExtractableKey()));
        assertEncryptFails(fixed("", new SecretKeySpec(new byte[32], "AES")));
        assertEncryptFails(fixed("x".repeat(256), new SecretKeySpec(new byte[32], "AES")));
        BlobKeyProvider resolvesShortKey = new BlobKeyProvider() {
            @Override
            public KeyRef current() {
                return KEYS.current();
            }

            @Override
            public SecretKey resolve(String keyId) {
                return new SecretKeySpec(new byte[16], "AES");
            }
        };
        byte[] ct = encrypt(random(1), ID, KEYS, SMALL);
        assertThrows(IOException.class, () -> BlobEncryption.decrypt(new ByteArrayInputStream(ct), ID, resolvesShortKey));
    }

    @Test
    public void keyIdsAreLimitedToThePrintablePattern() throws Exception {
        SecretKeySpec key = new SecretKeySpec(Base64.getDecoder().decode(K1), "AES");
        BlobKeyProvider longest = fixed("x".repeat(64), key);
        byte[] data = random(40);
        assertArrayEquals(data, decrypt(encrypt(data, ID, longest, SMALL), ID, longest));
        assertEncryptFails(fixed("x".repeat(65), key));
        assertEncryptFails(fixed("k\n1", key));

        byte[] ct = encrypt(random(5), ID, KEYS, SMALL);
        assertHeaderError(withByte(ct, 10, '\n'), "invalid key id");
    }

    @Test
    public void plaintextLengthFollowsFromTheHeaderAndCiphertextLength() throws Exception {
        for (int segmentSize : new int[] {SMALL, SEG}) {
            for (int size : new int[] {0, 1, segmentSize - 1, segmentSize, segmentSize + 1, 3 * segmentSize,
                    3 * segmentSize + 7}) {
                byte[] ct = encrypt(random(size), ID, KEYS, segmentSize);
                assertEquals(size + "@" + segmentSize, size,
                    BlobEncryption.plaintextLength(new ByteArrayInputStream(ct), ct.length));
            }
        }
        assertEquals(-1, BlobEncryption.plaintextLength(new ByteArrayInputStream(random(100)), 100));
        assertEquals(-1, BlobEncryption.plaintextLength(new ByteArrayInputStream(new byte[3]), 3));
        byte[] ct = encrypt(random(1), ID, KEYS, SMALL);
        assertThrows(IOException.class,
            () -> BlobEncryption.plaintextLength(new ByteArrayInputStream(ct), headerLength("k1") + 3));
    }

    @Test
    public void failedFileEncryptionLeavesNoCiphertext() throws Exception {
        File plain = folder.newFile();
        Files.write(plain.toPath(), random(10));
        File cipher = new File(folder.getRoot(), "cipher");
        assertThrows(IOException.class,
                () -> BlobEncryption.encrypt(plain, cipher, ID, fixed("k1", new SecretKeySpec(new byte[16], "AES"))));
        assertFalse(cipher.exists());
        assertThrows(NullPointerException.class, () -> BlobEncryption.encrypt(plain, cipher, null, KEYS));
        assertFalse(cipher.exists());
    }

    @Test
    public void rejectsInvalidSegmentSizesAndNullIds() {
        assertThrows(IOException.class, () -> encrypt(random(1), ID, KEYS, BlobEncryption.MIN_SEGMENT_SIZE - 1));
        assertThrows(IOException.class, () -> encrypt(random(1), ID, KEYS, BlobEncryption.MAX_SEGMENT_SIZE + 1));
        assertThrows(NullPointerException.class, () -> encrypt(random(1), null, KEYS, SMALL));
        assertThrows(NullPointerException.class,
                () -> BlobEncryption.decrypt(new ByteArrayInputStream(new byte[0]), null, KEYS));
    }

    // ---------------------------------------------------------------- tampering

    @Test
    public void everyTruncationFails() throws Exception {
        byte[] ct = encrypt(random(3 * SMALL + 5), ID, KEYS, SMALL);
        for (int length = 0; length < ct.length; length++) {
            assertFailsReading(Arrays.copyOf(ct, length), ID, KEYS);
        }
    }

    @Test
    public void truncationAtDefaultSegmentBoundaryAndMidSegmentFails() throws Exception {
        byte[] ct = encrypt(random(3 * SEG), ID, KEYS, SEG);
        int header = headerLength("k1");
        int sealed = SEG + BlobEncryption.TAG_BYTES;
        assertFailsReading(Arrays.copyOf(ct, header + sealed), ID, KEYS);
        assertFailsReading(Arrays.copyOf(ct, header + 2 * sealed), ID, KEYS);
        assertFailsReading(Arrays.copyOf(ct, header + sealed + SEG / 2), ID, KEYS);
        assertFailsReading(Arrays.copyOf(ct, header + 2 * sealed + 15), ID, KEYS);
    }

    @Test
    public void emptyBlobTruncatedToHeaderFails() throws Exception {
        byte[] ct = encrypt(new byte[0], ID, KEYS, SMALL);
        assertEquals(headerLength("k1") + 16, ct.length);
        IOException e = assertFailsReading(Arrays.copyOf(ct, headerLength("k1")), ID, KEYS);
        assertTrue(e.getMessage(), e.getMessage().contains("truncated"));
    }

    @Test
    public void trailingBytesFail() throws Exception {
        for (int size : new int[] {0, 5, SMALL, 2 * SMALL}) {
            byte[] ct = encrypt(random(size), ID, KEYS, SMALL);
            assertFailsReading(concat(ct, new byte[1]), ID, KEYS);
            assertFailsReading(concat(ct, new byte[16]), ID, KEYS);
            assertFailsReading(concat(ct, Arrays.copyOfRange(ct, headerLength("k1"), ct.length)), ID, KEYS);
        }
    }

    @Test
    public void everyBitFlipFails() throws Exception {
        byte[] ct = encrypt(random(2 * SMALL + 3), ID, KEYS, SMALL);
        for (int i = 0; i < ct.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] tampered = ct.clone();
                tampered[i] ^= (byte) (1 << bit);
                assertFailsReading(tampered, ID, KEYS);
            }
        }
    }

    @Test
    public void bitFlipsInDefaultSizedBlobFail() throws Exception {
        byte[] ct = encrypt(random(2 * SEG + 1), ID, KEYS, SEG);
        int header = headerLength("k1");
        int sealed = SEG + BlobEncryption.TAG_BYTES;
        for (int position : new int[] {0, 4, 8, header - 1, header, header + sealed - 1, header + sealed + 100, ct.length - 1}) {
            byte[] tampered = ct.clone();
            tampered[position] ^= 0x01;
            assertFailsReading(tampered, ID, KEYS);
        }
    }

    @Test
    public void reorderedDroppedOrDuplicatedSegmentsFail() throws Exception {
        byte[] ct = encrypt(random(3 * SMALL + 4), ID, KEYS, SMALL);
        byte[][] parts = split(ct, SMALL);
        assertArrayEquals(ct, join(parts[0], parts[1], parts[2], parts[3], parts[4]));
        assertFailsReading(join(parts[0], parts[2], parts[1], parts[3], parts[4]), ID, KEYS);
        assertFailsReading(join(parts[0], parts[1], parts[3], parts[2], parts[4]), ID, KEYS);
        assertFailsReading(join(parts[0], parts[1], parts[3], parts[4]), ID, KEYS);
        assertFailsReading(join(parts[0], parts[1], parts[1], parts[2], parts[3], parts[4]), ID, KEYS);
        assertFailsReading(join(parts[0], parts[4]), ID, KEYS);
    }

    @Test
    public void segmentsSwappedBetweenBlobsWithSameKeyAndIdFail() throws Exception {
        byte[] a = encrypt(random(3 * SMALL), ID, KEYS, SMALL);
        byte[] b = encrypt(random(3 * SMALL), ID, KEYS, SMALL);
        byte[][] pa = split(a, SMALL);
        byte[][] pb = split(b, SMALL);
        assertFailsReading(join(pa[0], pa[1], pb[2], pa[3]), ID, KEYS);
        assertFailsReading(join(pa[0], pa[1], pa[2], pb[3]), ID, KEYS);
        assertFailsReading(join(pb[0], pa[1], pa[2], pa[3]), ID, KEYS);
    }

    @Test
    public void failureIsStickyAndNeverAnEndOfStream() throws Exception {
        byte[] ct = encrypt(random(3 * SMALL), ID, KEYS, SMALL);
        try (InputStream in = BlobEncryption.decrypt(new ByteArrayInputStream(Arrays.copyOf(ct, ct.length - SMALL - 16)), ID, KEYS)) {
            assertEquals(SMALL, in.read(new byte[100]));
            assertThrows(IOException.class, () -> in.read(new byte[100]));
            assertThrows(IOException.class, in::read);
            assertThrows(IOException.class, () -> in.skip(1));
        }
    }

    @Test
    public void underlyingReadErrorsPropagate() throws Exception {
        byte[] ct = encrypt(random(3 * SMALL), ID, KEYS, SMALL);
        InputStream broken = new FilterInputStream(new ByteArrayInputStream(ct)) {
            private int remaining = headerLength("k1") + 20;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (remaining <= 0) {
                    throw new IOException("network down");
                }
                int n = super.read(b, off, Math.min(len, remaining));
                remaining -= n;
                return n;
            }
        };
        InputStream in = BlobEncryption.decrypt(broken, ID, KEYS);
        assertEquals("network down", assertThrows(IOException.class, in::readAllBytes).getMessage());
        assertThrows(IOException.class, in::read);
    }

    @Test
    public void closeClosesUnderlyingStream() throws Exception {
        boolean[] closed = new boolean[1];
        InputStream source = new ByteArrayInputStream(encrypt(random(1), ID, KEYS, SMALL)) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        BlobEncryption.decrypt(source, ID, KEYS).close();
        assertTrue(closed[0]);
    }

    @Test
    public void readRejectsInvalidBufferBounds() throws Exception {
        InputStream in = BlobEncryption.decrypt(new ByteArrayInputStream(encrypt(random(1), ID, KEYS, SMALL)), ID, KEYS);
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[4], 3, 2));
    }

    // ---------------------------------------------------------------- header validation

    @Test
    public void rejectsMalformedHeaders() throws Exception {
        byte[] ct = encrypt(random(5), ID, KEYS, SMALL);
        assertHeaderError(withByte(ct, 0, 0x50), "bad magic");
        assertHeaderError(withByte(ct, 4, 2), "version 2");
        assertHeaderError(withInt(ct, 5, BlobEncryption.MIN_SEGMENT_SIZE - 1), "segment size");
        assertHeaderError(withInt(ct, 5, BlobEncryption.MAX_SEGMENT_SIZE + 1), "segment size");
        assertHeaderError(withInt(ct, 5, -1), "segment size");
        assertHeaderError(withByte(ct, 9, 0), "empty key id");
        assertHeaderError(withByte(ct, 10, 0xC3), "malformed key id");
        assertHeaderError(Arrays.copyOf(ct, 9), "truncated");
        assertHeaderError(Arrays.copyOf(ct, headerLength("k1") - 1), "truncated");
        // a different but valid segment size changes the authenticated header
        assertFailsReading(withInt(ct, 5, SMALL + 1), ID, KEYS);
    }

    @Test
    public void detectsMagicWithoutConsumingTheStream() throws Exception {
        byte[] data = random(100);
        BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(encrypt(data, ID, KEYS, SMALL)));
        assertTrue(BlobEncryption.isEncrypted(in));
        assertArrayEquals(data, BlobEncryption.decrypt(in, ID, KEYS).readAllBytes());
        assertFalse(BlobEncryption.isEncrypted(new ByteArrayInputStream("plain text".getBytes(UTF_8))));
        assertFalse(BlobEncryption.isEncrypted(new ByteArrayInputStream(new byte[] {(byte) 0x89, 'O'})));
        ByteArrayInputStream plain = new ByteArrayInputStream(data);
        BlobEncryption.isEncrypted(plain);
        assertArrayEquals(data, plain.readAllBytes());
        assertThrows(IllegalArgumentException.class,
                () -> BlobEncryption.isEncrypted(new FilterInputStream(plain) {
                    @Override
                    public boolean markSupported() {
                        return false;
                    }
                }));
    }

    @Test
    public void keyRefNeverPrintsKeyMaterial() {
        BlobKeyProvider.KeyRef ref = KEYS.current();
        assertEquals("KeyRef[keyId=k1]", ref.toString());
        assertSame(ref, KEYS.current());
        assertThrows(NullPointerException.class, () -> new BlobKeyProvider.KeyRef(null, ref.key()));
        assertThrows(NullPointerException.class, () -> new BlobKeyProvider.KeyRef("k", null));
    }

    // ---------------------------------------------------------------- helpers

    static String randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static byte[] random(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    private static byte[] range(int start, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (start + i);
        }
        return bytes;
    }

    private static int headerLength(String keyId) {
        return 10 + keyId.getBytes(UTF_8).length + 32 + 7;
    }

    private static byte[] encrypt(byte[] data, String id, BlobKeyProvider keys, int segmentSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BlobEncryption.encrypt(new ByteArrayInputStream(data), out, id, keys, segmentSize);
        return out.toByteArray();
    }

    private static byte[] decrypt(byte[] ct, String id, BlobKeyProvider keys) throws IOException {
        try (InputStream in = BlobEncryption.decrypt(new ByteArrayInputStream(ct), id, keys)) {
            return in.readAllBytes();
        }
    }

    /** Fails unless opening or fully reading the ciphertext throws, i.e. never a clean EOF. */
    private static IOException assertFailsReading(byte[] ct, String id, BlobKeyProvider keys) {
        try (InputStream in = BlobEncryption.decrypt(new ByteArrayInputStream(ct), id, keys)) {
            byte[] leaked = in.readAllBytes();
            fail("Expected IOException, read " + leaked.length + " bytes and a clean end of stream");
            return null;
        } catch (IOException expected) {
            return expected;
        }
    }

    private static void assertHeaderError(byte[] ct, String message) {
        IOException e = assertThrows(IOException.class, () -> BlobEncryption.decrypt(new ByteArrayInputStream(ct), ID, KEYS));
        assertTrue(e.getMessage(), e.getMessage().contains(message));
    }

    private static void assertEncryptFails(BlobKeyProvider keys) {
        assertThrows(IOException.class, () -> encrypt(random(1), ID, keys, SMALL));
    }

    private static BlobKeyProvider fixed(String keyId, SecretKey key) {
        return new BlobKeyProvider() {
            @Override
            public KeyRef current() {
                return new KeyRef(keyId, key);
            }

            @Override
            public SecretKey resolve(String id) {
                return key;
            }
        };
    }

    private static SecretKey nonExtractableKey() {
        return new SecretKey() {
            @Override
            public String getAlgorithm() {
                return "AES";
            }

            @Override
            public String getFormat() {
                return null;
            }

            @Override
            public byte[] getEncoded() {
                return null;
            }
        };
    }

    private static byte[] openSegment(byte[] key, byte[] prefix, int index, boolean last, byte[] aad, byte[] sealed)
            throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).put((byte) (last ? 1 : 0)).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed);
    }

    /** Splits into header followed by sealed segments. */
    private static byte[][] split(byte[] ct, int segmentSize) {
        int header = headerLength("k1");
        int sealed = segmentSize + BlobEncryption.TAG_BYTES;
        int count = (ct.length - header + sealed - 1) / sealed;
        byte[][] parts = new byte[count + 1][];
        parts[0] = Arrays.copyOf(ct, header);
        for (int i = 0; i < count; i++) {
            int from = header + i * sealed;
            parts[i + 1] = Arrays.copyOfRange(ct, from, Math.min(ct.length, from + sealed));
        }
        return parts;
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        return join(a, b);
    }

    private static byte[] withByte(byte[] ct, int index, int value) {
        byte[] copy = ct.clone();
        copy[index] = (byte) value;
        return copy;
    }

    private static byte[] withInt(byte[] ct, int index, int value) {
        byte[] copy = ct.clone();
        ByteBuffer.wrap(copy).putInt(index, value);
        return copy;
    }
}
