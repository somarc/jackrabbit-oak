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
package org.apache.jackrabbit.oak.segment.consensus.config;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeConfigValueResolverTest {

    private static final String NAMESPACE = "test";
    private static final String PROP_VALUE = "oak.test.value";
    private static final String PROP_INT = "oak.test.int";

    @Before
    public void setUp() {
        RuntimePropertyOverrideRegistry.clear(NAMESPACE);
        System.clearProperty(PROP_VALUE);
        System.clearProperty(PROP_INT);
    }

    @After
    public void tearDown() {
        RuntimePropertyOverrideRegistry.clear(NAMESPACE);
        System.clearProperty(PROP_VALUE);
        System.clearProperty(PROP_INT);
    }

    @Test
    public void testOverrideWinsOverSystemProperty() {
        System.setProperty(PROP_VALUE, "system");
        RuntimePropertyOverrideRegistry.setOverrides(
            NAMESPACE,
            Collections.singletonMap(PROP_VALUE, "osgi")
        );

        assertEquals("osgi", RuntimeConfigValueResolver.readString(PROP_VALUE, "default"));
    }

    @Test
    public void testReadIntWithEnvFallbackUsesSystemProperty() {
        System.setProperty(PROP_INT, "42");

        assertEquals(42, RuntimeConfigValueResolver.readInt(PROP_INT, "OAK_TEST_INT", 7));
    }

    @Test
    public void testConfiguredValueReflectsOverrideAndSystemProperty() {
        assertFalse(RuntimeConfigValueResolver.hasConfiguredValue(PROP_VALUE));

        System.setProperty(PROP_VALUE, "system");
        assertTrue(RuntimeConfigValueResolver.hasConfiguredValue(PROP_VALUE));

        System.clearProperty(PROP_VALUE);
        RuntimePropertyOverrideRegistry.setOverrides(
            NAMESPACE,
            Collections.singletonMap(PROP_VALUE, "osgi")
        );
        assertTrue(RuntimeConfigValueResolver.hasConfiguredValue(PROP_VALUE));
    }
}
