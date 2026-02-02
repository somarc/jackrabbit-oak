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
package org.apache.jackrabbit.oak.segment.agentic.eip8004;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentRegistrationFileTest {
    @Test
    public void buildsRegistrationJson() {
        Eip8004Config config = Eip8004Config.load();
        AgentRegistrationFile file = AgentRegistrationFile.forAgent(
                "0xabc123",
                "validator",
                config,
                List.of("llm-chat", "validator-apis")
        );

        JsonObject json = JsonParser.parseString(file.toJson()).getAsJsonObject();
        assertEquals("https://eips.ethereum.org/EIPS/eip-8004#registration-v1", json.get("type").getAsString());
        assertTrue(json.has("services"));
        assertEquals(1, json.getAsJsonArray("services").size());
        assertTrue(json.getAsJsonObject("metadata").has("agentId"));
    }
}
