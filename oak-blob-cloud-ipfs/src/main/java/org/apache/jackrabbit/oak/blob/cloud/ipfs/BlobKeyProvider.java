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

import java.security.GeneralSecurityException;
import java.util.Objects;

import javax.crypto.SecretKey;

/**
 * Supplies the master keys used by {@link BlobEncryption}. New blobs are
 * written with {@link #current()}; existing blobs are read with the key
 * resolved from the key id recorded in their header, so rotating the current
 * key keeps older blobs readable as long as their key stays resolvable.
 */
public interface BlobKeyProvider {

    /**
     * @return the key id and 256-bit AES master key to use for new blobs
     */
    KeyRef current();

    /**
     * @param keyId a key id previously returned by {@link #current()}
     * @return the 256-bit AES master key registered under {@code keyId}
     * @throws GeneralSecurityException if the key id is unknown or unavailable
     */
    SecretKey resolve(String keyId) throws GeneralSecurityException;

    /**
     * A key id with its key material. {@link #toString()} never includes the key.
     */
    record KeyRef(String keyId, SecretKey key) {

        public KeyRef {
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(key, "key");
        }

        @Override
        public String toString() {
            return "KeyRef[keyId=" + keyId + "]";
        }
    }
}
