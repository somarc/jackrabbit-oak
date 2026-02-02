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

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.osgi.service.component.annotations.Component;

/**
 * Centralized codec for Aeron SBE messages.
 *
 * <p>Extracted from AeronConsensusEngine to normalize header encoding/decoding
 * and support OSGi service decomposition.</p>
 */
@Component(service = AeronMessageCodec.class)
public class AeronMessageCodec {

    public SimpleMessageHeader.HeaderInfo decodeHeader(DirectBuffer buffer, int offset) {
        return SimpleMessageHeader.decode(buffer, offset);
    }

    public int headerLength() {
        return SimpleMessageHeader.ENCODED_LENGTH;
    }

    public int encodePayload(MutableDirectBuffer buffer, int templateId, byte[] payload) {
        int payloadLength = payload != null ? payload.length : 0;
        SimpleMessageHeader.encode(buffer, 0, payloadLength, templateId);
        if (payloadLength > 0) {
            buffer.putBytes(SimpleMessageHeader.ENCODED_LENGTH, payload);
        }
        return SimpleMessageHeader.ENCODED_LENGTH + payloadLength;
    }
}
