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

import org.apache.jackrabbit.oak.api.AuthInfo;
import org.junit.Test;

import javax.jcr.SimpleCredentials;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class Web3BiometricLoginModuleTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";

    @Test
    public void testGetSupportedCredentials() {
        Web3BiometricLoginModule module = new Web3BiometricLoginModule();
        assertTrue(module.getSupportedCredentials().contains(Web3BiometricCredentials.class));
        assertTrue(module.getSupportedCredentials().contains(SimpleCredentials.class));
    }

    @Test
    public void testLoginWithWeb3CredentialsAndCommit() throws Exception {
        Web3BiometricCredentials credentials = createValidCredentials();
        Subject subject = new Subject();
        subject.getPublicCredentials().add(credentials);

        Web3BiometricLoginModule module = new Web3BiometricLoginModule();
        module.initialize(subject, null, new HashMap<>(), new HashMap<>());

        assertTrue(module.login());
        assertTrue(module.commit());
        assertFalse(subject.getPublicCredentials(Web3BiometricCredentials.class).isEmpty());
        assertFalse(subject.getPublicCredentials(AuthInfo.class).isEmpty());
    }

    @Test
    public void testLoginWithPreVerifiedMetamask() throws Exception {
        SimpleCredentials creds = new SimpleCredentials("user", new char[0]);
        creds.setAttribute("web3.metamask.verified", true);
        creds.setAttribute("web3.metamask.address", WALLET);

        Subject subject = new Subject();
        subject.getPublicCredentials().add(creds);

        Web3BiometricLoginModule module = new Web3BiometricLoginModule();
        module.initialize(subject, null, new HashMap<>(), new HashMap<>());

        assertTrue(module.login());
        assertTrue(module.commit());
        assertFalse(subject.getPublicCredentials(AuthInfo.class).isEmpty());
    }

    @Test
    public void testLoginWithSimpleCredentialsBiometric() throws Exception {
        SimpleCredentials creds = new SimpleCredentials("user", new char[0]);
        Web3BiometricCredentials web3 = createValidCredentials();
        creds.setAttribute("web3.biometric.credentialId", web3.getCredentialId());
        creds.setAttribute("web3.biometric.publicKey", web3.getPublicKey());
        creds.setAttribute("web3.biometric.signature", web3.getSignature());
        creds.setAttribute("web3.biometric.challenge", web3.getChallenge());
        creds.setAttribute("web3.biometric.walletAddress", web3.getWalletAddress());

        Subject subject = new Subject();
        subject.getPublicCredentials().add(creds);

        Web3BiometricLoginModule module = new Web3BiometricLoginModule();
        module.initialize(subject, null, new HashMap<>(), new HashMap<>());

        assertTrue(module.login());
    }

    @Test
    public void testLoginWithUnsupportedSimpleCredentialsReturnsFalse() throws Exception {
        SimpleCredentials creds = new SimpleCredentials("user", new char[0]);
        Subject subject = new Subject();
        subject.getPublicCredentials().add(creds);

        Web3BiometricLoginModule module = new Web3BiometricLoginModule();
        module.initialize(subject, null, new HashMap<>(), new HashMap<>());

        assertFalse(module.login());
    }

    @Test(expected = LoginException.class)
    public void testLoginWithInvalidSignatureThrows() throws Exception {
        Web3BiometricCredentials credentials = createValidCredentials();
        byte[] tampered = credentials.getSignature();
        tampered[0] ^= 0x01;

        Web3BiometricCredentials invalid = new Web3BiometricCredentials(
            credentials.getCredentialId(),
            tampered,
            credentials.getPublicKey(),
            credentials.getChallenge(),
            credentials.getWalletAddress()
        );

        Subject subject = new Subject();
        subject.getPublicCredentials().add(invalid);

        Web3BiometricLoginModule module = new Web3BiometricLoginModule();
        module.initialize(subject, null, new HashMap<>(), new HashMap<>());

        module.login();
    }

    @Test
    public void testGetFactoryOptionDefaultOnWrongType() {
        Web3BiometricLoginModule module = new Web3BiometricLoginModule();
        Map<String, Object> options = new HashMap<>();
        options.put("flag", "not-a-boolean");
        module.setFactoryOptions(options);

        boolean value = module.getFactoryOption("flag", true);
        assertTrue(value);
    }

    @Test
    public void testAbortClearsState() throws Exception {
        Web3BiometricCredentials credentials = createValidCredentials();
        Subject subject = new Subject();
        subject.getPublicCredentials().add(credentials);

        Web3BiometricLoginModule module = new Web3BiometricLoginModule();
        module.initialize(subject, null, new HashMap<>(), new HashMap<>());

        assertTrue(module.login());
        assertTrue(module.abort());
    }

    private static Web3BiometricCredentials createValidCredentials() throws Exception {
        KeyPair keyPair = generateP256KeyPair();
        byte[] challenge = "challenge".getBytes();
        byte[] signature = sign(challenge, (ECPrivateKey) keyPair.getPrivate());
        byte[] publicKey = encodePublicKey((ECPublicKey) keyPair.getPublic());

        return new Web3BiometricCredentials(
            "cred-1",
            signature,
            publicKey,
            challenge,
            WALLET
        );
    }

    private static KeyPair generateP256KeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return keyGen.generateKeyPair();
    }

    private static byte[] sign(byte[] message, ECPrivateKey privateKey) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(message);
        return sig.sign();
    }

    private static byte[] encodePublicKey(ECPublicKey publicKey) {
        byte[] x = toBytes32(publicKey.getW().getAffineX());
        byte[] y = toBytes32(publicKey.getW().getAffineY());

        byte[] encoded = new byte[65];
        encoded[0] = 0x04;
        System.arraycopy(x, 0, encoded, 1, 32);
        System.arraycopy(y, 0, encoded, 33, 32);
        return encoded;
    }

    private static byte[] toBytes32(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == 32) {
            return bytes;
        } else if (bytes.length == 33 && bytes[0] == 0) {
            byte[] trimmed = new byte[32];
            System.arraycopy(bytes, 1, trimmed, 0, 32);
            return trimmed;
        } else if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
            return padded;
        } else {
            throw new IllegalArgumentException("Value too large for 32 bytes: " + bytes.length);
        }
    }
}
