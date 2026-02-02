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

import org.junit.Test;

import javax.jcr.SimpleCredentials;
import javax.security.auth.login.LoginException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.*;

public class Web3BiometricAuthenticationTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";

    @Test
    public void testAuthenticateSuccess() throws Exception {
        KeyPair keyPair = generateP256KeyPair();
        byte[] challenge = "challenge".getBytes();
        byte[] signature = sign(challenge, (ECPrivateKey) keyPair.getPrivate());
        byte[] publicKey = encodePublicKey((ECPublicKey) keyPair.getPublic());

        Web3BiometricCredentials credentials = new Web3BiometricCredentials(
            "cred-1",
            signature,
            publicKey,
            challenge,
            WALLET
        );

        Web3BiometricAuthentication authentication = new Web3BiometricAuthentication(new LocalP256Verifier());

        assertTrue(authentication.authenticate(credentials));
        assertEquals(WALLET, authentication.getUserId());
        assertNotNull(authentication.getUserPrincipal());
        assertEquals(WALLET.toLowerCase(), authentication.getUserPrincipal().getName());
    }

    @Test(expected = LoginException.class)
    public void testAuthenticateInvalidSignatureThrows() throws Exception {
        KeyPair keyPair = generateP256KeyPair();
        byte[] challenge = "challenge".getBytes();
        byte[] signature = sign(challenge, (ECPrivateKey) keyPair.getPrivate());
        signature[0] ^= 0x01;
        byte[] publicKey = encodePublicKey((ECPublicKey) keyPair.getPublic());

        Web3BiometricCredentials credentials = new Web3BiometricCredentials(
            "cred-1",
            signature,
            publicKey,
            challenge,
            WALLET
        );

        Web3BiometricAuthentication authentication = new Web3BiometricAuthentication(new LocalP256Verifier());
        authentication.authenticate(credentials);
    }

    @Test
    public void testAuthenticateUnsupportedCredentialsReturnsFalse() throws Exception {
        Web3BiometricAuthentication authentication = new Web3BiometricAuthentication(new LocalP256Verifier());
        assertFalse(authentication.authenticate(new SimpleCredentials("user", new char[0])));
    }

    @Test(expected = IllegalStateException.class)
    public void testGetUserIdBeforeAuthenticateThrows() {
        Web3BiometricAuthentication authentication = new Web3BiometricAuthentication(new LocalP256Verifier());
        authentication.getUserId();
    }

    @Test(expected = IllegalStateException.class)
    public void testGetPrincipalBeforeAuthenticateThrows() {
        Web3BiometricAuthentication authentication = new Web3BiometricAuthentication(new LocalP256Verifier());
        authentication.getUserPrincipal();
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
