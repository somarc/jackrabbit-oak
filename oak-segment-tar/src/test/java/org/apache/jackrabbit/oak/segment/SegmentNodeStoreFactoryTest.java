/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.segment;

import static org.apache.sling.testing.mock.osgi.MockOsgi.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Dictionary;
import java.util.Hashtable;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.segment.file.ReadOnlyFileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStoreProvider;
import org.apache.jackrabbit.oak.spi.toggle.FeatureToggle;
import org.apache.sling.testing.mock.osgi.MockOsgi;
import org.junit.Test;

public class SegmentNodeStoreFactoryTest extends SegmentNodeStoreServiceTest {

    private SegmentNodeStoreFactory segmentNodeStoreFactory;

    @Override
    protected void registerSegmentNodeStoreService(boolean customBlobStore) {
        registerSegmentNodeStoreService(customBlobStore, "some-role");
    }

    private void registerSegmentNodeStoreService(boolean customBlobStore, String role) {
        Hashtable<String, Object> properties = new Hashtable<>();

        properties.put("role", role);
        properties.put("customBlobStore", customBlobStore);
        properties.put("repository.home", folder.getRoot().getAbsolutePath());

        // OAK-10367: The call 
        // context.registerInjectActivateService(new SegmentNodeStoreFactory(), properties)
        // isn't working properly anymore. It calls
        // context.bundleContext().registerService(null, new SegmentNodeStoreFactory(), properties).
        // A service registered this way will not be found by
        // context.bundleContext().getServiceReferences(SegmentNodeStoreService.class, null).
        // 
        //segmentNodeStoreFactory = context.registerInjectActivateService(new SegmentNodeStoreFactory(), properties);
        segmentNodeStoreFactory = new SegmentNodeStoreFactory();
        MockOsgi.injectServices(segmentNodeStoreFactory, context.bundleContext(), properties);
        MockOsgi.activate(segmentNodeStoreFactory, context.bundleContext(), (Dictionary<String, Object>) properties);
        context.bundleContext().registerService(SegmentNodeStoreFactory.class, segmentNodeStoreFactory, properties);
    }

    @Test
    public void testReadOnlyCompositeMountRegistersCacheFeatureToggles() throws Exception {
        String role = "composite-mount-oak-chain";
        File directory = new File(folder.getRoot(), "segmentstore-" + role);
        try (FileStore store = FileStoreBuilder.fileStoreBuilder(directory).build()) {
            store.flush();
        }

        registerSegmentNodeStoreService(false, role);
        assertServiceActivated();
        SegmentStoreProvider provider = context.getService(SegmentStoreProvider.class);
        assertNotNull(provider);
        assertTrue(provider.getSegmentStore() instanceof ReadOnlyFileStore);
        assertCacheFeatureTogglesRegistered();

        unregisterSegmentNodeStoreService();
        assertEquals(0, context.getServices(FeatureToggle.class, null).length);
    }

    @Override
    protected void unregisterSegmentNodeStoreService() {
        deactivate(segmentNodeStoreFactory, context.bundleContext());
    }

    @Override
    protected void assertServiceActivated() {
        assertNotNull(context.getService(NodeStoreProvider.class));
    }

    @Override
    protected void assertServiceNotActivated() {
        assertNull(context.getService(NodeStoreProvider.class));
    }

}
