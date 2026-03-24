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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import org.apache.jackrabbit.core.data.DataStore;
import org.apache.jackrabbit.oak.commons.PropertiesUtil;
import org.apache.jackrabbit.oak.plugins.blob.AbstractSharedCachingDataStore;
import org.apache.jackrabbit.oak.stats.StatisticsProvider;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IPFSDataStoreServiceTest {

    @Test
    public void testCreateDataStoreAppliesIpfsConfiguration() throws Exception {
        IPFSDataStoreService service = new IPFSDataStoreService();
        service.setStatisticsProvider(StatisticsProvider.NOOP);

        ComponentContext componentContext = mock(ComponentContext.class);
        BundleContext bundleContext = mock(BundleContext.class);
        @SuppressWarnings("rawtypes")
        ServiceRegistration registration = mock(ServiceRegistration.class);
        when(componentContext.getBundleContext()).thenReturn(bundleContext);
        when(bundleContext.registerService(any(String[].class), any(Object.class), any())).thenReturn(registration);

        Map<String, Object> config = new HashMap<>();
        config.put("ipfsApiEndpoint", "/ip4/10.0.0.2/tcp/5001");
        config.put("ipfsFilesRoot", "oak/repo-a/");
        config.put("minRecordLength", 32 * 1024);

        DataStore delegate = service.createDataStore(componentContext, config);
        PropertiesUtil.populate(delegate, config, false);

        assertTrue(delegate instanceof IPFSDataStore);
        IPFSDataStore dataStore = (IPFSDataStore) delegate;
        assertEquals("/ip4/10.0.0.2/tcp/5001", dataStore.getIpfsApiEndpoint());
        assertEquals("/oak/repo-a", dataStore.getIpfsFilesRoot());
        assertEquals(32 * 1024, dataStore.getMinRecordLength());

        verify(bundleContext).registerService(
            org.mockito.ArgumentMatchers.<String[]>argThat(classes -> contains(classes, AbstractSharedCachingDataStore.class.getName())
                && contains(classes, IPFSDataStore.class.getName())),
            same(dataStore),
            any()
        );
    }

    private static boolean contains(String[] classes, String expected) {
        for (String value : classes) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
