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
package org.apache.jackrabbit.oak.segment.consensus.federation;

import org.junit.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_B;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A2;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.WRITER_A;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RegistryDirectorySourceTest {

    private static final String REGISTRY = "0x" + "e1".repeat(20);
    private static final String CODE = "0x6080604052";
    private static final String CLUSTER_ID = "0x" + "c1".repeat(32);
    private static final String CONFIG_DIGEST = "0x" + "d1".repeat(32);
    private static final Charter CHARTER = Charter.of(ENTERPRISE_A, "local", 31337L, REGISTRY);
    private static final String SIGNER_1 = "0x" + "51".repeat(20);
    private static final String SIGNER_2 = "0x" + "52".repeat(20);
    private static final String SIGNER_3 = "0x" + "53".repeat(20);

    /** Serves the registry views with real ABI encodings and counts the calls. */
    private static final class FakeChain implements RegistryDirectorySource.Chain {
        long chainId = 31337L;
        long epoch = 7L;
        String code = CODE;
        final List<String> calls = new ArrayList<>();
        private final Map<String, String> selectors = new HashMap<>();

        FakeChain() {
            for (String signature : new String[] {"directoryEpoch()", "enterpriseCount()",
                "enterpriseIds(uint256,uint256)", "getEnterprise(bytes32)", "getSigners(bytes32)",
                "getEndpoints(bytes32)", "getNamespaces(bytes32)", "getWriters(bytes32)"}) {
                selectors.put(Hash.sha3String(signature).substring(0, 10), signature.substring(0, signature.indexOf('(')));
            }
        }

        @Override
        public long chainId() {
            return chainId;
        }

        @Override
        public Object[] block() {
            return new Object[] {BigInteger.valueOf(1234), "0x" + "AB".repeat(32)};
        }

        @Override
        public String code(String address, BigInteger block) {
            return REGISTRY.equals(address) && block.longValue() == 1234 ? code : "0x";
        }

        @Override
        public String call(String to, String data, BigInteger block) throws IOException {
            String name = selectors.get(data.substring(0, 10));
            calls.add(name);
            boolean a = data.length() > 10 && data.endsWith(Numeric.cleanHexPrefix(ENTERPRISE_A));
            switch (name) {
                case "directoryEpoch":
                    return encode(new Uint64(epoch));
                case "enterpriseCount":
                    return encode(new Uint256(2));
                case "enterpriseIds":
                    return encode(new DynamicArray<>(Bytes32.class, bytes32(ENTERPRISE_A), bytes32(ENTERPRISE_B)));
                case "getEnterprise":
                    return encode(address(SIGNER_1), address("0x" + "0".repeat(40)), new Uint8(a ? 2 : 1),
                        new Uint8(a ? 3 : 0), new Uint8(a ? 2 : 0), new Uint32(a ? 1 : 0),
                        bytes32(a ? CONFIG_DIGEST : "0x" + "0".repeat(64)), bytes32(a ? CLUSTER_ID : "0x" + "0".repeat(64)),
                        address(a ? REGISTRY : "0x" + "0".repeat(40)), address(SIGNER_1), new Uint64(9),
                        new Uint64(2), bytes32(CONFIG_DIGEST), new Uint64(5));
                case "getSigners":
                    return addresses(a ? List.of(SIGNER_1, SIGNER_2, SIGNER_3) : List.of());
                case "getEndpoints":
                    return encode(new DynamicArray<>(Utf8String.class,
                        a ? List.of(new Utf8String("https://a0.example"), new Utf8String("https://a1.example/"))
                            : List.of()));
                case "getNamespaces":
                    return addresses(a ? List.of(NAMESPACE_A2.toUpperCase().replace("0X", "0x"), NAMESPACE_A) : List.of());
                case "getWriters":
                    return addresses(a ? List.of(WRITER_A) : List.of());
                default:
                    throw new IOException("unexpected call " + data);
            }
        }

        private static String encode(Type... values) {
            return "0x" + FunctionEncoder.encodeConstructor(List.of(values));
        }

        private static String addresses(List<String> values) {
            List<Address> list = new ArrayList<>();
            values.forEach(value -> list.add(address(value)));
            return encode(new DynamicArray<>(Address.class, list));
        }

        private static Address address(String value) {
            return new Address(value);
        }

        private static Bytes32 bytes32(String value) {
            return new Bytes32(Numeric.hexStringToByteArray(value));
        }
    }

    @Test
    public void readsEveryEnterpriseAtOneBlock() throws IOException {
        DirectorySnapshot snapshot = new RegistryDirectorySource(new FakeChain(), CHARTER, "").load();
        assertEquals(31337L, snapshot.getChainId());
        assertEquals(REGISTRY, snapshot.getRegistry());
        assertEquals(1234L, snapshot.getBlockNumber());
        assertEquals("0x" + "ab".repeat(32), snapshot.getBlockHash());
        assertEquals(7L, snapshot.getDirectoryEpoch());

        DirectoryEntry a = snapshot.entry(ENTERPRISE_A);
        assertEquals(DirectoryEntry.Status.ACTIVE, a.getStatus());
        assertEquals(CLUSTER_ID, a.getClusterId());
        assertEquals(CONFIG_DIGEST, a.getConfigDigest());
        assertEquals(List.of(SIGNER_1, SIGNER_2, SIGNER_3), a.getSigners());
        assertEquals(2L, a.getThreshold());
        assertEquals(List.of("https://a0.example", "https://a1.example"), a.getEndpoints());
        assertEquals(List.of(NAMESPACE_A, NAMESPACE_A2), a.getNamespaces());
        assertEquals(List.of(WRITER_A), a.getWriters());
        assertEquals(REGISTRY, a.getPaymentContract());

        DirectoryEntry b = snapshot.entry(ENTERPRISE_B);
        assertEquals(DirectoryEntry.Status.PENDING, b.getStatus());
        assertEquals("", b.getClusterId());
        assertEquals("", b.getPaymentContract());
        assertNull(snapshot.ownerOf(SIGNER_1));
    }

    @Test
    public void rereadsOnlyWhenTheDirectoryEpochMoves() throws IOException {
        FakeChain chain = new FakeChain();
        RegistryDirectorySource source = new RegistryDirectorySource(chain, CHARTER, "");
        DirectorySnapshot first = source.load();
        int fullRead = chain.calls.size();
        assertSame(first, source.load());
        assertEquals(fullRead + 1, chain.calls.size());
        chain.epoch = 8L;
        assertEquals(8L, source.load().getDirectoryEpoch());
        assertEquals(2 * fullRead + 1, chain.calls.size());
    }

    @Test
    public void refusesTheWrongChainMissingCodeOrUnpinnedCode() {
        FakeChain wrongChain = new FakeChain();
        wrongChain.chainId = 11155111L;
        assertTrue(assertThrows(IOException.class,
            () -> new RegistryDirectorySource(wrongChain, CHARTER, "").load()).getMessage().contains("31337"));
        FakeChain noCode = new FakeChain();
        noCode.code = "0x";
        assertThrows(IOException.class, () -> new RegistryDirectorySource(noCode, CHARTER, "").load());
        assertThrows(IOException.class,
            () -> new RegistryDirectorySource(new FakeChain(), CHARTER, "0x" + "00".repeat(32)).load());
        assertTrue(noCode.calls.isEmpty());
    }

    @Test
    public void acceptsThePinnedCodeHash() throws IOException {
        String pinned = Hash.sha3(CODE).toUpperCase().replace("0X", "0x");
        assertEquals(7L, new RegistryDirectorySource(new FakeChain(), CHARTER, pinned).load().getDirectoryEpoch());
    }
}
