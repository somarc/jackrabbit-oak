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
package org.apache.jackrabbit.oak.segment.consensus.server.lifecycle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ComponentRegistryTest {

    @Test
    public void testRegisterAndGetTypedComponent() {
        ComponentRegistry registry = new ComponentRegistry();
        registry.register("answer", Integer.valueOf(42));

        assertEquals(Integer.valueOf(42), registry.get("answer", Integer.class));
        assertNull(registry.get("answer", String.class));
    }

    @Test
    public void testRegisterIgnoresNulls() {
        ComponentRegistry registry = new ComponentRegistry();
        registry.register(null, "value");
        registry.register("name", null);

        assertNull(registry.get("name", Object.class));
        assertNull(registry.get("missing", Object.class));
    }
}
