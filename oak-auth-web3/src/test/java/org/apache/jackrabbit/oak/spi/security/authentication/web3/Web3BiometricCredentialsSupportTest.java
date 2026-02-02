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

import org.apache.jackrabbit.oak.spi.security.authentication.credentials.CredentialsSupport;
import org.junit.Test;

import javax.jcr.SimpleCredentials;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class Web3BiometricCredentialsSupportTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";

    @Test
    public void testCredentialClasses() {
        CredentialsSupport support = Web3BiometricCredentialsSupport.getInstance();
        assertTrue(support.getCredentialClasses().contains(Web3BiometricCredentials.class));
    }

    @Test
    public void testGetUserIdForWeb3Credentials() {
        Web3BiometricCredentials creds = createCredentials();
        CredentialsSupport support = Web3BiometricCredentialsSupport.getInstance();
        assertEquals(WALLET, support.getUserId(creds));
    }

    @Test
    public void testGetUserIdForNonWeb3Credentials() {
        CredentialsSupport support = Web3BiometricCredentialsSupport.getInstance();
        assertNull(support.getUserId(new SimpleCredentials("user", new char[0])));
    }

    @Test
    public void testGetAttributesForWeb3Credentials() {
        Web3BiometricCredentials creds = createCredentials();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("k1", "v1");
        creds.setAttributes(attrs);

        CredentialsSupport support = Web3BiometricCredentialsSupport.getInstance();
        assertEquals("v1", support.getAttributes(creds).get("k1"));
    }

    @Test
    public void testGetAttributesForNonWeb3Credentials() {
        CredentialsSupport support = Web3BiometricCredentialsSupport.getInstance();
        assertTrue(support.getAttributes(new SimpleCredentials("user", new char[0])).isEmpty());
    }

    @Test
    public void testSetAttributesForWeb3Credentials() {
        Web3BiometricCredentials creds = createCredentials();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("k1", "v1");

        CredentialsSupport support = Web3BiometricCredentialsSupport.getInstance();
        assertTrue(support.setAttributes(creds, attrs));
        assertEquals("v1", creds.getAttributes().get("k1"));
    }

    @Test
    public void testSetAttributesForNonWeb3Credentials() {
        CredentialsSupport support = Web3BiometricCredentialsSupport.getInstance();
        assertFalse(support.setAttributes(new SimpleCredentials("user", new char[0]), Map.of("k", "v")));
    }

    private static Web3BiometricCredentials createCredentials() {
        byte[] sig = new byte[] {0x30, 0x44, 0x02, 0x20, 0x01, 0x02, 0x03, 0x04};
        byte[] publicKey = new byte[65];
        publicKey[0] = 0x04;
        byte[] challenge = new byte[32];
        return new Web3BiometricCredentials("cred-1", sig, publicKey, challenge, WALLET);
    }
}
