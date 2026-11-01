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

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the federation directory from the charter's EnterpriseRegistry contract. Every view is read at one
 * block, the finalized block on public chains and the latest on a local chain, so a snapshot is consistent;
 * its number and hash are recorded in the snapshot. Before reading, the RPC must report the charter's chain
 * and the registry address must hold code (matching a pinned code hash when one is configured).
 */
public final class RegistryDirectorySource implements DirectorySource {

    /** The few JSON-RPC reads the source needs; {@link #web3j} adapts a Web3j client. */
    public interface Chain {
        long chainId() throws IOException;

        /** The block to read at: {@code [number, hash]}. */
        Object[] block() throws IOException;

        String code(String address, BigInteger block) throws IOException;

        String call(String to, String data, BigInteger block) throws IOException;
    }

    static final int PAGE = 100;
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final String ZERO_BYTES32 = "0x" + "0".repeat(64);
    private static final String[] STATUS = {null, "PENDING", "ACTIVE", "SUSPENDED", "RETIRED"};

    private final Chain chain;
    private final Charter charter;
    private final String codeHash;
    private DirectorySnapshot cached;

    /**
     * @param codeHash keccak256 of the registry's runtime code, 0x-prefixed, or empty to accept any code
     */
    public RegistryDirectorySource(Chain chain, Charter charter, String codeHash) {
        this.chain = chain;
        this.charter = charter;
        this.codeHash = codeHash == null ? "" : codeHash.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public synchronized DirectorySnapshot load() throws IOException {
        long chainId = chain.chainId();
        if (chainId != charter.getChainId()) {
            throw new IOException("RPC reports chain " + chainId + ", charter requires " + charter.getChainId());
        }
        Object[] block = chain.block();
        BigInteger number = (BigInteger) block[0];
        String registry = charter.getRegistry();
        String code = chain.code(registry, number);
        if (code == null || Numeric.cleanHexPrefix(code).isEmpty()) {
            throw new IOException("No contract code at registry " + registry + " in block " + number);
        }
        if (!codeHash.isEmpty() && !codeHash.equals(Hash.sha3(code).toLowerCase(Locale.ROOT))) {
            throw new IOException("Registry code at " + registry + " does not match the pinned code hash");
        }
        long epoch = number(call(number, "directoryEpoch", List.of(), new TypeReference<Uint64>() { }).get(0));
        if (cached != null && cached.getDirectoryEpoch() == epoch) {
            return cached;
        }
        long count = number(call(number, "enterpriseCount", List.of(), new TypeReference<Uint256>() { }).get(0));
        List<Object> entries = new ArrayList<>();
        for (long offset = 0; offset < count; offset += PAGE) {
            List<Type> page = call(number, "enterpriseIds",
                List.of(new Uint256(offset), new Uint256(PAGE)), new TypeReference<DynamicArray<Bytes32>>() { });
            for (Object id : ((DynamicArray<?>) page.get(0)).getValue()) {
                Map<String, Object> entry = entry(number, (Bytes32) id);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        Map<String, Object> json = new TreeMap<>();
        json.put("chainId", chainId);
        json.put("registry", registry);
        json.put("blockNumber", number.longValueExact());
        json.put("blockHash", ((String) block[1]).toLowerCase(Locale.ROOT));
        json.put("directoryEpoch", epoch);
        json.put("entries", entries);
        cached = DirectorySnapshot.fromMap(json);
        return cached;
    }

    private Map<String, Object> entry(BigInteger block, Bytes32 id) throws IOException {
        List<Type> info = call(block, "getEnterprise", List.of(id),
            new TypeReference<Address>() { }, new TypeReference<Address>() { }, new TypeReference<Uint8>() { },
            new TypeReference<Uint8>() { }, new TypeReference<Uint8>() { }, new TypeReference<Uint32>() { },
            new TypeReference<Bytes32>() { }, new TypeReference<Bytes32>() { }, new TypeReference<Address>() { },
            new TypeReference<Address>() { }, new TypeReference<Uint64>() { }, new TypeReference<Uint64>() { },
            new TypeReference<Bytes32>() { }, new TypeReference<Uint64>() { });
        int status = (int) number(info.get(2));
        if (status <= 0 || status >= STATUS.length) {
            return null;
        }
        Map<String, Object> entry = new TreeMap<>();
        entry.put("enterpriseId", hex(id));
        entry.put("status", STATUS[status]);
        entry.put("threshold", number(info.get(4)));
        entry.put("configDigest", orEmpty(hex((Bytes32) info.get(6)), ZERO_BYTES32));
        entry.put("clusterId", orEmpty(hex((Bytes32) info.get(7)), ZERO_BYTES32));
        entry.put("paymentContract", orEmpty(((Address) info.get(8)).getValue().toLowerCase(Locale.ROOT), ZERO_ADDRESS));
        entry.put("signers", addresses(call(block, "getSigners", List.of(id), ADDRESSES)));
        entry.put("endpoints", strings(call(block, "getEndpoints", List.of(id),
            new TypeReference<DynamicArray<Utf8String>>() { })));
        entry.put("namespaces", addresses(call(block, "getNamespaces", List.of(id), ADDRESSES)));
        entry.put("writers", addresses(call(block, "getWriters", List.of(id), ADDRESSES)));
        return entry;
    }

    private static final TypeReference<DynamicArray<Address>> ADDRESSES = new TypeReference<DynamicArray<Address>>() { };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Type> call(BigInteger block, String name, List<Type> inputs, TypeReference<?>... outputs)
            throws IOException {
        Function function = new Function(name, inputs, (List) List.of(outputs));
        String result = chain.call(charter.getRegistry(), FunctionEncoder.encode(function), block);
        List<Type> decoded = FunctionReturnDecoder.decode(result, function.getOutputParameters());
        if (decoded.size() != outputs.length) {
            throw new IOException("Registry " + name + " returned an undecodable result");
        }
        return decoded;
    }

    private static long number(Type value) {
        return ((BigInteger) value.getValue()).longValueExact();
    }

    private static String hex(Bytes32 value) {
        return Numeric.toHexString(value.getValue()).toLowerCase(Locale.ROOT);
    }

    private static String orEmpty(String value, String zero) {
        return zero.equals(value) ? "" : value;
    }

    private static List<String> addresses(List<Type> result) {
        List<String> list = new ArrayList<>();
        for (Object address : ((DynamicArray<?>) result.get(0)).getValue()) {
            list.add(((Address) address).getValue().toLowerCase(Locale.ROOT));
        }
        return list;
    }

    private static List<String> strings(List<Type> result) {
        List<String> list = new ArrayList<>();
        for (Object value : ((DynamicArray<?>) result.get(0)).getValue()) {
            list.add(((Utf8String) value).getValue());
        }
        return list;
    }

    @Override
    public String describe() {
        return "registry:" + charter.getRegistry() + "@" + charter.getChainId();
    }

    /** Reads at the finalized block, or the latest one when {@code latest} (a local chain). */
    public static Chain web3j(Web3j web3j, boolean latest) {
        DefaultBlockParameterName tag = latest ? DefaultBlockParameterName.LATEST : DefaultBlockParameterName.FINALIZED;
        return new Chain() {
            @Override
            public long chainId() throws IOException {
                return check(web3j.ethChainId().send()).getChainId().longValueExact();
            }

            @Override
            public Object[] block() throws IOException {
                EthBlock.Block block = check(web3j.ethGetBlockByNumber(tag, false).send()).getBlock();
                if (block == null) {
                    throw new IOException("RPC returned no " + tag.getValue() + " block");
                }
                return new Object[] {block.getNumber(), block.getHash()};
            }

            @Override
            public String code(String address, BigInteger block) throws IOException {
                return check(web3j.ethGetCode(address, DefaultBlockParameter.valueOf(block)).send()).getCode();
            }

            @Override
            public String call(String to, String data, BigInteger block) throws IOException {
                EthCall call = check(web3j.ethCall(Transaction.createEthCallTransaction(null, to, data),
                    DefaultBlockParameter.valueOf(block)).send());
                if (call.isReverted()) {
                    throw new IOException("Registry call reverted: " + call.getRevertReason());
                }
                return call.getValue();
            }
        };
    }

    private static <T extends Response<?>> T check(T response) throws IOException {
        if (response.hasError()) {
            throw new IOException("RPC error " + response.getError().getCode() + ": " + response.getError().getMessage());
        }
        return response;
    }
}
