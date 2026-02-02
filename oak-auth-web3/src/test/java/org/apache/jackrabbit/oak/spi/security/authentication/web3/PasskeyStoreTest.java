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
package org.apache.jackrabbit.oak.spi.security.authentication.web3;

import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.PropertyStates;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PasskeyStore}.
 * 
 * <p>Note: Full integration tests require Oak repository setup.
 * These tests cover the RegisteredPasskey data class and basic logic.</p>
 */
public class PasskeyStoreTest {
    
    private static final String VALID_WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String CREDENTIAL_ID = "test-credential-id-123";
    private static final byte[] SAMPLE_PUBLIC_KEY = createSamplePublicKey();
    
    private static byte[] createSamplePublicKey() {
        // P-256 uncompressed public key format: 0x04 + 32 bytes X + 32 bytes Y = 65 bytes
        byte[] key = new byte[65];
        key[0] = 0x04; // Uncompressed point indicator
        for (int i = 1; i < 65; i++) {
            key[i] = (byte) i;
        }
        return key;
    }
    
    // ========================================================================
    // RegisteredPasskey Tests
    // ========================================================================
    
    @Test
    public void testRegisteredPasskeyCreation() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            "iPhone 15",
            "P-256"
        );
        
        assertEquals(CREDENTIAL_ID, passkey.getCredentialId());
        assertEquals(VALID_WALLET, passkey.getWalletAddress());
        assertEquals(now, passkey.getCreatedAt());
        assertEquals(now, passkey.getLastUsedAt());
        assertEquals("iPhone 15", passkey.getDeviceName());
        assertEquals("P-256", passkey.getKeyAlgorithm());
    }
    
    @Test
    public void testRegisteredPasskeyPublicKeyDefensiveCopy() {
        long now = System.currentTimeMillis();
        byte[] originalKey = SAMPLE_PUBLIC_KEY.clone();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            originalKey,
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        // Modify original
        originalKey[0] = (byte) 0xFF;
        
        // Passkey should have original value
        assertNotEquals((byte) 0xFF, passkey.getPublicKey()[0]);
    }
    
    @Test
    public void testRegisteredPasskeyGetPublicKeyDefensiveCopy() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        byte[] key1 = passkey.getPublicKey();
        byte[] key2 = passkey.getPublicKey();
        
        // Should return different array instances
        assertNotSame(key1, key2);
        
        // But same content
        assertArrayEquals(key1, key2);
        
        // Modifying returned array should not affect stored key
        key1[0] = (byte) 0xFF;
        assertNotEquals((byte) 0xFF, passkey.getPublicKey()[0]);
    }
    
    @Test
    public void testRegisteredPasskeyNullDeviceName() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        assertNull(passkey.getDeviceName());
    }
    
    @Test
    public void testRegisteredPasskeyDifferentAlgorithms() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey p256Passkey = new PasskeyStore.RegisteredPasskey(
            "cred-1",
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            "Device 1",
            "P-256"
        );
        
        PasskeyStore.RegisteredPasskey secp256k1Passkey = new PasskeyStore.RegisteredPasskey(
            "cred-2",
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            "Device 2",
            "secp256k1"
        );
        
        assertEquals("P-256", p256Passkey.getKeyAlgorithm());
        assertEquals("secp256k1", secp256k1Passkey.getKeyAlgorithm());
    }
    
    @Test
    public void testRegisteredPasskeyTimestamps() {
        long createdAt = System.currentTimeMillis() - 86400000; // 1 day ago
        long lastUsedAt = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            createdAt,
            lastUsedAt,
            null,
            "P-256"
        );
        
        assertEquals(createdAt, passkey.getCreatedAt());
        assertEquals(lastUsedAt, passkey.getLastUsedAt());
        assertTrue(passkey.getLastUsedAt() > passkey.getCreatedAt());
    }
    
    @Test
    public void testRegisteredPasskeyPublicKeyLength() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        // P-256 uncompressed public key should be 65 bytes
        assertEquals(65, passkey.getPublicKey().length);
    }
    
    @Test
    public void testRegisteredPasskeyEmptyPublicKey() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            new byte[0],
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        assertEquals(0, passkey.getPublicKey().length);
    }
    
    @Test
    public void testRegisteredPasskeyWalletAddressCase() {
        long now = System.currentTimeMillis();
        String upperCaseWallet = "0xABCDEF1234567890ABCDEF1234567890ABCDEF12";
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            upperCaseWallet,
            now,
            now,
            null,
            "P-256"
        );
        
        assertEquals(upperCaseWallet, passkey.getWalletAddress());
    }
    
    // ========================================================================
    // Note: Full PasskeyStore integration tests require Oak repository setup
    // The following tests would be in a separate integration test class:
    // - testRegisterPasskey
    // - testGetPasskey
    // - testGetPasskeys
    // - testUpdateLastUsed
    // - testRemovePasskey
    // - testHasPasskey
    // - testGetPasskeyCount
    // ========================================================================

    @Test
    public void testRegisterPasskeyStoresAndRetrieves() throws Exception {
        FakeTree rootTree = new FakeTree("");
        Tree users = rootTree.addChild("users");
        Tree userTree = users.addChild(VALID_WALLET);

        UserManager userManager = mockUserManager(VALID_WALLET, userTree, true);
        PasskeyStore store = new PasskeyStore(new FakeRoot(rootTree), userManager);

        boolean registered = store.registerPasskey(
            VALID_WALLET,
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            "iPhone 15",
            "P-256"
        );

        assertTrue(registered);

        PasskeyStore.RegisteredPasskey passkey = store.getPasskey(VALID_WALLET, CREDENTIAL_ID);
        assertNotNull(passkey);
        assertEquals(CREDENTIAL_ID, passkey.getCredentialId());
        assertEquals(VALID_WALLET, passkey.getWalletAddress());
        assertEquals("iPhone 15", passkey.getDeviceName());
        assertEquals("P-256", passkey.getKeyAlgorithm());
        assertArrayEquals(SAMPLE_PUBLIC_KEY, passkey.getPublicKey());
    }

    @Test
    public void testRegisterPasskeyRejectsWhenUserMissing() throws Exception {
        FakeTree rootTree = new FakeTree("");
        PasskeyStore store = new PasskeyStore(new FakeRoot(rootTree), mockUserManagerMissing());

        boolean registered = store.registerPasskey(
            VALID_WALLET,
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            null
        );

        assertFalse(registered);
    }

    @Test
    public void testRegisterPasskeyRejectsWhenAuthorizableNotGroup() throws Exception {
        FakeTree rootTree = new FakeTree("");
        Tree users = rootTree.addChild("users");
        Tree userTree = users.addChild(VALID_WALLET);

        UserManager userManager = mockUserManager(VALID_WALLET, userTree, false);
        PasskeyStore store = new PasskeyStore(new FakeRoot(rootTree), userManager);

        boolean registered = store.registerPasskey(
            VALID_WALLET,
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            null
        );

        assertFalse(registered);
    }

    @Test
    public void testRegisterPasskeyRejectsDuplicate() throws Exception {
        FakeTree rootTree = new FakeTree("");
        Tree users = rootTree.addChild("users");
        Tree userTree = users.addChild(VALID_WALLET);

        UserManager userManager = mockUserManager(VALID_WALLET, userTree, true);
        PasskeyStore store = new PasskeyStore(new FakeRoot(rootTree), userManager);

        assertTrue(store.registerPasskey(VALID_WALLET, CREDENTIAL_ID, SAMPLE_PUBLIC_KEY, null));
        assertFalse(store.registerPasskey(VALID_WALLET, CREDENTIAL_ID, SAMPLE_PUBLIC_KEY, null));
    }

    @Test
    public void testGetPasskeysAndCount() throws Exception {
        FakeTree rootTree = new FakeTree("");
        Tree users = rootTree.addChild("users");
        Tree userTree = users.addChild(VALID_WALLET);

        UserManager userManager = mockUserManager(VALID_WALLET, userTree, true);
        PasskeyStore store = new PasskeyStore(new FakeRoot(rootTree), userManager);

        store.registerPasskey(VALID_WALLET, "cred-1", SAMPLE_PUBLIC_KEY, "Device 1");
        store.registerPasskey(VALID_WALLET, "cred-2", SAMPLE_PUBLIC_KEY, "Device 2");

        List<PasskeyStore.RegisteredPasskey> passkeys = store.getPasskeys(VALID_WALLET);
        assertEquals(2, passkeys.size());
        assertEquals(2, store.getPasskeyCount(VALID_WALLET));
    }

    @Test
    public void testUpdateLastUsedAndRemove() throws Exception {
        FakeTree rootTree = new FakeTree("");
        Tree users = rootTree.addChild("users");
        Tree userTree = users.addChild(VALID_WALLET);

        UserManager userManager = mockUserManager(VALID_WALLET, userTree, true);
        PasskeyStore store = new PasskeyStore(new FakeRoot(rootTree), userManager);

        assertTrue(store.registerPasskey(VALID_WALLET, CREDENTIAL_ID, SAMPLE_PUBLIC_KEY, null));

        PasskeyStore.RegisteredPasskey before = store.getPasskey(VALID_WALLET, CREDENTIAL_ID);
        assertNotNull(before);

        assertTrue(store.updateLastUsed(VALID_WALLET, CREDENTIAL_ID));

        PasskeyStore.RegisteredPasskey after = store.getPasskey(VALID_WALLET, CREDENTIAL_ID);
        assertNotNull(after);
        assertTrue(after.getLastUsedAt() >= before.getLastUsedAt());

        assertTrue(store.removePasskey(VALID_WALLET, CREDENTIAL_ID));
        assertFalse(store.hasPasskey(VALID_WALLET, CREDENTIAL_ID));
    }

    private static UserManager mockUserManager(String wallet, Tree userTree, boolean isGroup) throws Exception {
        UserManager userManager = mock(UserManager.class);
        User user = mock(User.class);
        when(user.getPath()).thenReturn(userTree.getPath());
        when(user.isGroup()).thenReturn(isGroup);
        when(userManager.getAuthorizable(wallet)).thenReturn(user);
        return userManager;
    }

    private static UserManager mockUserManagerMissing() throws Exception {
        UserManager userManager = mock(UserManager.class);
        when(userManager.getAuthorizable(anyString())).thenReturn(null);
        return userManager;
    }

    private static final class FakeRoot implements Root {
        private final FakeTree root;

        private FakeRoot(FakeTree root) {
            this.root = root;
        }

        @Override
        public Tree getTree(String path) {
            if ("/".equals(path) || path.isEmpty()) {
                return root;
            }
            String clean = path.startsWith("/") ? path.substring(1) : path;
            String[] parts = clean.split("/");
            FakeTree current = root;
            for (String part : parts) {
                Tree child = current.getChild(part);
                if (child instanceof FakeTree) {
                    current = (FakeTree) child;
                } else {
                    break;
                }
            }
            return current;
        }

        @Override
        public boolean move(String sourcePath, String destPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rebase() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void refresh() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void commit(Map<String, Object> info) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void commit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPendingChanges() {
            return false;
        }

        @Override
        public org.apache.jackrabbit.oak.api.QueryEngine getQueryEngine() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.apache.jackrabbit.oak.api.Blob getBlob(String reference) {
            return null;
        }

        @Override
        public org.apache.jackrabbit.oak.api.ContentSession getContentSession() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.apache.jackrabbit.oak.api.Blob createBlob(java.io.InputStream inputStream) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeTree implements Tree {
        private final String name;
        private final FakeTree parent;
        private final Map<String, FakeTree> children = new LinkedHashMap<>();
        private final Map<String, org.apache.jackrabbit.oak.api.PropertyState> properties = new HashMap<>();
        private boolean removed;
        private final boolean existing;

        private FakeTree(String name) {
            this(name, null, true);
        }

        private FakeTree(String name, FakeTree parent, boolean existing) {
            this.name = name;
            this.parent = parent;
            this.existing = existing;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isRoot() {
            return parent == null;
        }

        @Override
        public String getPath() {
            if (isRoot()) {
                return "/";
            }
            String parentPath = parent.getPath();
            if ("/".equals(parentPath)) {
                return "/" + name;
            }
            return parentPath + "/" + name;
        }

        @Override
        public Status getStatus() {
            return Status.UNCHANGED;
        }

        @Override
        public boolean exists() {
            return existing && !removed;
        }

        @Override
        public Tree getParent() {
            if (parent == null) {
                throw new IllegalStateException("Root has no parent");
            }
            return parent;
        }

        @Override
        public org.apache.jackrabbit.oak.api.PropertyState getProperty(String name) {
            return properties.get(name);
        }

        @Override
        public Status getPropertyStatus(String name) {
            return properties.containsKey(name) ? Status.MODIFIED : null;
        }

        @Override
        public boolean hasProperty(String name) {
            return properties.containsKey(name);
        }

        @Override
        public long getPropertyCount() {
            return properties.size();
        }

        @Override
        public Iterable<? extends org.apache.jackrabbit.oak.api.PropertyState> getProperties() {
            return new ArrayList<>(properties.values());
        }

        @Override
        public Tree getChild(String name) {
            FakeTree child = children.get(name);
            if (child != null) {
                return child;
            }
            return new FakeTree(name, this, false);
        }

        @Override
        public boolean hasChild(String name) {
            FakeTree child = children.get(name);
            return child != null && child.exists();
        }

        @Override
        public long getChildrenCount(long max) {
            long count = children.values().stream().filter(Tree::exists).count();
            return Math.min(count, max);
        }

        @Override
        public Iterable<Tree> getChildren() {
            return new ArrayList<>(children.values());
        }

        @Override
        public Tree addChild(String name) {
            FakeTree child = new FakeTree(name, this, true);
            children.put(name, child);
            return child;
        }

        @Override
        public boolean remove() {
            if (parent == null) {
                return false;
            }
            removed = true;
            parent.children.remove(name);
            return true;
        }

        @Override
        public void setOrderableChildren(boolean enable) {
            // no-op for tests
        }

        @Override
        public boolean orderBefore(String name) {
            return false;
        }

        @Override
        public void setProperty(org.apache.jackrabbit.oak.api.PropertyState property) {
            properties.put(property.getName(), property);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void setProperty(String name, T value) throws IllegalArgumentException {
            if (value instanceof String) {
                setProperty(name, (T) value, (Type<T>) Type.STRING);
            } else if (value instanceof Long) {
                setProperty(name, (T) value, (Type<T>) Type.LONG);
            } else if (value instanceof Boolean) {
                setProperty(name, (T) value, (Type<T>) Type.BOOLEAN);
            } else {
                throw new IllegalArgumentException("Unsupported value type: " + value.getClass());
            }
        }

        @Override
        public <T> void setProperty(String name, T value, Type<T> type) throws IllegalArgumentException {
            properties.put(name, PropertyStates.createProperty(name, value, type));
        }

        @Override
        public void removeProperty(String name) {
            properties.remove(name);
        }
    }
}
