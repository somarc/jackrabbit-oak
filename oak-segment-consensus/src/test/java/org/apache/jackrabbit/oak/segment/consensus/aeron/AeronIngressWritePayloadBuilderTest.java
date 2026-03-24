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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalState;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AeronIngressWritePayloadBuilderTest {

    private final AeronIngressWritePayloadBuilder builder = new AeronIngressWritePayloadBuilder();

    @Test
    public void writeProposalEscapesOptionalFieldsAndEncodesSbeHeader() {
        AeronIngressWritePayloadBuilder.EncodedMessage encoded = builder.buildWriteProposal(
            "0xabc\\\"def",
            "/content/demo",
            null,
            "line1\nline2",
            "sig\tvalue",
            Integer.valueOf(7),
            "bafy123",
            "proposal-1"
        );

        assertEquals(SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL, encoded.templateId);
        assertTrue(encoded.json.contains("\"term\":7"));
        assertTrue(encoded.json.contains("\"contentType\":\"page\""));
        assertTrue(encoded.json.contains("0xabc\\\\\\\"def"));
        assertTrue(encoded.json.contains("line1\\nline2"));
        assertTrue(encoded.json.contains("\"ipfsCid\":\"bafy123\""));
        assertTrue(encoded.json.contains("\"proposalId\":\"proposal-1\""));
        assertPayloadMatches(encoded);
    }

    @Test
    public void writeProposalWithBinaryDefaultsMimeTypeAndIncludesBlobMetadata() {
        AeronIngressWritePayloadBuilder.EncodedMessage encoded = builder.buildWriteProposalWithBinary(
            "0xabc",
            "/content/binary",
            "asset",
            "hello",
            "sig",
            null,
            "blob-123",
            null,
            null,
            null
        );

        assertTrue(encoded.json.contains("\"blobId\":\"blob-123\""));
        assertTrue(encoded.json.contains("\"mimeType\":\"application/octet-stream\""));
        assertFalse(encoded.json.contains("\"term\":"));
        assertPayloadMatches(encoded);
    }

    @Test
    public void deleteProposalOmitsAbsentOptionalFields() {
        AeronIngressWritePayloadBuilder.EncodedMessage encoded = builder.buildDeleteProposal(
            "0xdef",
            "/content/delete",
            null,
            null,
            null
        );

        assertEquals(SimpleMessageHeader.TEMPLATE_ID_DELETE_PROPOSAL, encoded.templateId);
        assertTrue(encoded.json.contains("\"signature\":\"\""));
        assertFalse(encoded.json.contains("\"term\":"));
        assertFalse(encoded.json.contains("\"proposalId\":"));
        assertPayloadMatches(encoded);
    }

    @Test
    public void writeBatchIncludesIntentBlobAndIpfsFields() {
        QueuedProposal first = proposal("proposal-1");
        first.setWalletAddress("0xaaa");
        first.setPath("/content/a");
        first.setContentType(null);
        first.setMessage("hello");
        first.setSignature("sig-a");
        first.setIntentToken("intent-1");

        QueuedProposal second = proposal("proposal-2");
        second.setWalletAddress("0xbbb");
        second.setPath("/content/b");
        second.setContentType("asset");
        second.setMessage("payload");
        second.setSignature("sig-b");
        second.setBlobId("blob-2");
        second.setMimeType("image/png");
        second.setIpfsCid("bafy456");

        AeronIngressWritePayloadBuilder.EncodedMessage encoded =
            builder.buildWriteBatch(List.of(first, second), Integer.valueOf(11));

        assertEquals(SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH, encoded.templateId);
        assertTrue(encoded.json.contains("\"proposalId\":\"proposal-1\""));
        assertTrue(encoded.json.contains("\"intentToken\":\"intent-1\""));
        assertTrue(encoded.json.contains("\"contentType\":\"page\""));
        assertTrue(encoded.json.contains("\"proposalId\":\"proposal-2\""));
        assertTrue(encoded.json.contains("\"blobId\":\"blob-2\""));
        assertTrue(encoded.json.contains("\"mimeType\":\"image/png\""));
        assertTrue(encoded.json.contains("\"ipfsCid\":\"bafy456\""));
        assertTrue(encoded.json.contains("\"term\":11"));
        assertPayloadMatches(encoded);
    }

    @Test
    public void escapeJsonNormalizesNullAndControlCharacters() {
        assertEquals("", builder.escapeJson(null));
        assertEquals("\\\"quote\\\"\\\\slash\\n", builder.escapeJson("\"quote\"\\slash\n"));
    }

    private static QueuedProposal proposal(String proposalId) {
        return new QueuedProposal(proposalId, "0xtx", null, 1L, 2L, ProposalState.PENDING);
    }

    private static void assertPayloadMatches(AeronIngressWritePayloadBuilder.EncodedMessage encoded) {
        SimpleMessageHeader.HeaderInfo header = SimpleMessageHeader.decode(encoded.buffer, 0);
        byte[] payload = new byte[encoded.totalLength - SimpleMessageHeader.ENCODED_LENGTH];
        encoded.buffer.getBytes(SimpleMessageHeader.ENCODED_LENGTH, payload);
        assertEquals(encoded.templateId, header.templateId);
        assertEquals(payload.length, header.blockLength);
        assertEquals(encoded.json, new String(payload, StandardCharsets.UTF_8));
    }
}
