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
package org.apache.jackrabbit.oak.segment.http.server.sse;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContentEventTest {

    @Test
    public void testBuilderDefaultsIdTypeAndAction() {
        ContentEvent event = ContentEvent.builder()
            .timestamp(1234L)
            .build();

        assertEquals("1234", event.getId());
        assertEquals("content", event.getType());
        assertEquals("write", event.getAction());
    }

    @Test
    public void testToJsonAndToSseIncludeOptionalFieldsAndEscapeCharacters() {
        ContentEvent event = ContentEvent.builder()
            .id("evt-1")
            .type(ContentEvent.EventType.BINARY)
            .action(ContentEvent.Action.DELETE)
            .path("/content/doc-1")
            .wallet("0xwallet")
            .organization("acme")
            .timestamp(9876L)
            .message("line1\n\"quoted\"")
            .ipfsCid("QmExample")
            .signature("0xsig")
            .size(42L)
            .contentType("image/png")
            .build();

        String json = event.toJson();
        assertTrue(json.contains("\"id\":\"evt-1\""));
        assertTrue(json.contains("\"type\":\"binary\""));
        assertTrue(json.contains("\"action\":\"delete\""));
        assertTrue(json.contains("\"path\":\"/content/doc-1\""));
        assertTrue(json.contains("\"wallet\":\"0xwallet\""));
        assertTrue(json.contains("\"organization\":\"acme\""));
        assertTrue(json.contains("\"timestamp\":9876"));
        assertTrue(json.contains("\"message\":\"line1\\n\\\"quoted\\\"\""));
        assertTrue(json.contains("\"ipfsCid\":\"QmExample\""));
        assertTrue(json.contains("\"signature\":\"0xsig\""));
        assertTrue(json.contains("\"size\":42"));
        assertTrue(json.contains("\"contentType\":\"image/png\""));

        String sse = event.toSSE();
        assertTrue(sse.contains("event: binary"));
        assertTrue(sse.contains("id: evt-1"));
        assertTrue(sse.contains("data: " + json));
    }
}
