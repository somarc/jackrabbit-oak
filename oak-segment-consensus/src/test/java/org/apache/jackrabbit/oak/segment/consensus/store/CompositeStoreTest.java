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
package org.apache.jackrabbit.oak.segment.consensus.store;

import org.apache.jackrabbit.oak.segment.consensus.store.impl.SimpleCompositeStoreBuilder;
import org.apache.jackrabbit.oak.segment.consensus.store.impl.SimpleGlobalStoreMount;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for the Composite NodeStore integration.
 */
public class CompositeStoreTest {
    
    @Test
    public void testGlobalStoreMountDefaults() {
        GlobalStoreMount mount = new SimpleGlobalStoreMount("/opt/aem/segmentstore-global");
        
        assertEquals("Should use default mount path", "/oak-chain", mount.getMountPath());
        assertEquals("Should use default mount name", "oak-chain-global", mount.getMountName());
        assertTrue("Global store should be read-only", mount.isReadOnly());
        assertEquals("Directory should match", "/opt/aem/segmentstore-global", mount.getGlobalStoreDirectory());
    }
    
    @Test
    public void testGlobalStoreMountCustom() {
        GlobalStoreMount mount = new SimpleGlobalStoreMount(
                "/custom/global/store",
                "/my-chain",
                "my-chain-mount"
        );
        
        assertEquals("Should use custom mount path", "/my-chain", mount.getMountPath());
        assertEquals("Should use custom mount name", "my-chain-mount", mount.getMountName());
        assertTrue("Global store should always be read-only", mount.isReadOnly());
        assertEquals("Directory should match", "/custom/global/store", mount.getGlobalStoreDirectory());
    }
    
    @Test
    public void testCompositeStoreBuilder() {
        CompositeStoreBuilder builder = new SimpleCompositeStoreBuilder();
        
        Object store = builder
                .withLocalStoreDirectory("/opt/aem/segmentstore")
                .withGlobalStoreDirectory("/opt/aem/segmentstore-global")
                .withMountPath("/oak-chain")
                .withMountName("oak-chain-global")
                .build();
        
        assertNotNull("Built store should not be null", store);
        
        // For POC, we return a descriptor map
        assertTrue("Should return a Map descriptor", store instanceof Map);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> descriptor = (Map<String, Object>) store;
        
        assertEquals("Type should be composite", "composite", descriptor.get("type"));
        assertEquals("Local store path should match", "/opt/aem/segmentstore", descriptor.get("localStore"));
        assertEquals("Global store path should match", "/opt/aem/segmentstore-global", descriptor.get("globalStore"));
        assertEquals("Mount path should match", "/oak-chain", descriptor.get("mountPath"));
        assertEquals("Mount name should match", "oak-chain-global", descriptor.get("mountName"));
        assertEquals("Mount should be read-only", true, descriptor.get("mountReadOnly"));
        
        Object mount = descriptor.get("mount");
        assertNotNull("Mount should be included", mount);
        assertTrue("Mount should be GlobalStoreMount", mount instanceof GlobalStoreMount);
    }
    
    @Test
    public void testBuilderWithDefaults() {
        CompositeStoreBuilder builder = new SimpleCompositeStoreBuilder();
        
        Object store = builder
                .withLocalStoreDirectory("/opt/aem/local")
                .withGlobalStoreDirectory("/opt/aem/global")
                .build();
        
        @SuppressWarnings("unchecked")
        Map<String, Object> descriptor = (Map<String, Object>) store;
        
        // Should use defaults for mount path and name
        assertEquals("Should use default mount path", "/oak-chain", descriptor.get("mountPath"));
        assertEquals("Should use default mount name", "oak-chain-global", descriptor.get("mountName"));
    }
    
    @Test(expected = IllegalStateException.class)
    public void testBuilderMissingLocalStore() {
        CompositeStoreBuilder builder = new SimpleCompositeStoreBuilder();
        
        builder
                .withGlobalStoreDirectory("/opt/aem/global")
                .build();  // Should throw - local store not set
    }
    
    @Test(expected = IllegalStateException.class)
    public void testBuilderMissingGlobalStore() {
        CompositeStoreBuilder builder = new SimpleCompositeStoreBuilder();
        
        builder
                .withLocalStoreDirectory("/opt/aem/local")
                .build();  // Should throw - global store not set
    }
    
    @Test(expected = IllegalStateException.class)
    public void testBuilderInvalidMountPath() {
        CompositeStoreBuilder builder = new SimpleCompositeStoreBuilder();
        
        builder
                .withLocalStoreDirectory("/opt/aem/local")
                .withGlobalStoreDirectory("/opt/aem/global")
                .withMountPath("oak-chain")  // Missing leading /
                .build();  // Should throw - invalid mount path
    }
    
    @Test
    public void testBuilderFluency() {
        // Test that builder methods return the builder for fluency
        CompositeStoreBuilder builder = new SimpleCompositeStoreBuilder();
        
        CompositeStoreBuilder result1 = builder.withLocalStoreDirectory("/local");
        assertSame("withLocalStoreDirectory should return same builder", builder, result1);
        
        CompositeStoreBuilder result2 = builder.withGlobalStoreDirectory("/global");
        assertSame("withGlobalStoreDirectory should return same builder", builder, result2);
        
        CompositeStoreBuilder result3 = builder.withMountPath("/path");
        assertSame("withMountPath should return same builder", builder, result3);
        
        CompositeStoreBuilder result4 = builder.withMountName("name");
        assertSame("withMountName should return same builder", builder, result4);
    }
}

