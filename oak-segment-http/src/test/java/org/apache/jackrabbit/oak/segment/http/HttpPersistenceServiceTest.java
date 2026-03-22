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
package org.apache.jackrabbit.oak.segment.http;

import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.util.Dictionary;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;

public class HttpPersistenceServiceTest {

    @Test
    public void testImmediateMountRegistersServiceAndUnregistersOnDeactivate() {
        HttpPersistenceService service = new HttpPersistenceService((url, timeoutMs) -> false);
        BundleContext bundleContext = mock(BundleContext.class);
        @SuppressWarnings("unchecked")
        ServiceRegistration<SegmentNodeStorePersistence> registration = mock(ServiceRegistration.class);
        when(bundleContext.registerService(
            eq(SegmentNodeStorePersistence.class),
            same((SegmentNodeStorePersistence) service),
            any(Dictionary.class)
        )).thenReturn(registration);

        service.activate(bundleContext, config(false, 10, 3000));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Dictionary<String, Object>> propsCaptor = ArgumentCaptor.forClass(Dictionary.class);
        verify(bundleContext).registerService(
            eq(SegmentNodeStorePersistence.class),
            same((SegmentNodeStorePersistence) service),
            propsCaptor.capture()
        );
        assertEquals("http://oak-global-store:8090", propsCaptor.getValue().get("globalStoreUrl"));
        assertFalse(service.isLazyMount());
        assertFalse(service.isValidatorAvailable());
        assertEquals("http://oak-global-store:8090", service.getGlobalStoreUrl());
        assertNotNull(service.getJournalFile());

        service.deactivate();

        verify(registration).unregister();
    }

    @Test
    public void testLazyMountRegistersWhenProbeReportsAvailable() throws Exception {
        HttpPersistenceService service = new HttpPersistenceService((url, timeoutMs) -> true);
        BundleContext bundleContext = mock(BundleContext.class);
        @SuppressWarnings("unchecked")
        ServiceRegistration<SegmentNodeStorePersistence> registration = mock(ServiceRegistration.class);
        when(bundleContext.registerService(
            eq(SegmentNodeStorePersistence.class),
            same((SegmentNodeStorePersistence) service),
            any(Dictionary.class)
        )).thenReturn(registration);

        service.activate(bundleContext, config(true, 1, 250));

        waitFor(() -> service.isValidatorAvailable(), 1000);
        waitFor(() -> {
            try {
                verify(bundleContext, atLeastOnce()).registerService(
                    eq(SegmentNodeStorePersistence.class),
                    same((SegmentNodeStorePersistence) service),
                    any(Dictionary.class)
                );
                return true;
            } catch (AssertionError e) {
                return false;
            }
        }, 1000);

        assertTrue(service.isLazyMount());
        assertTrue(service.isValidatorAvailable());
        verify(bundleContext).registerService(
            eq(SegmentNodeStorePersistence.class),
            same((SegmentNodeStorePersistence) service),
            any(Dictionary.class)
        );

        service.deactivate();

        verify(registration).unregister();
    }

    @Test
    public void testLazyMountDoesNotRegisterWhileProbeStaysUnavailable() throws Exception {
        AtomicInteger probeCalls = new AtomicInteger();
        HttpPersistenceService service = new HttpPersistenceService((url, timeoutMs) -> {
            probeCalls.incrementAndGet();
            return false;
        });
        BundleContext bundleContext = mock(BundleContext.class);

        service.activate(bundleContext, config(true, 1, 250));
        waitFor(() -> probeCalls.get() > 0, 500);

        assertFalse(service.isValidatorAvailable());
        verify(bundleContext, never()).registerService(
            eq(SegmentNodeStorePersistence.class),
            same((SegmentNodeStorePersistence) service),
            any(Dictionary.class)
        );

        service.deactivate();
    }

    private static HttpPersistenceService.Configuration config(boolean lazyMount, int intervalSeconds, int timeoutMs) {
        HttpPersistenceService.Configuration config = mock(HttpPersistenceService.Configuration.class);
        when(config.globalStoreUrl()).thenReturn("http://oak-global-store:8090");
        when(config.lazyMount()).thenReturn(lazyMount);
        when(config.healthCheckIntervalSeconds()).thenReturn(intervalSeconds);
        when(config.connectionTimeoutMs()).thenReturn(timeoutMs);
        return config;
    }

    private static void waitFor(Check check, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.satisfied()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Condition not satisfied within timeout");
    }

    @FunctionalInterface
    private interface Check {
        boolean satisfied();
    }
}
