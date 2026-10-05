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

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AeronMessageCodecTest {

    @Test
    public void simpleMessageHeaderRoundTripsEncodedValues() {
        new SimpleMessageHeader();
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[32]);

        int length = SimpleMessageHeader.encode(
            buffer,
            4,
            123,
            SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH
        );

        SimpleMessageHeader.HeaderInfo header = SimpleMessageHeader.decode(buffer, 4);

        assertEquals(SimpleMessageHeader.ENCODED_LENGTH, length);
        assertEquals(123, header.blockLength);
        assertEquals(SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH, header.templateId);
        assertEquals(1, header.schemaId);
        assertEquals(1, header.version);
    }
}
