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

import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HttpClientPoolTest {

    @Test
    public void testPoolExposesConfiguredManagerAndStats() {
        HttpClientPool pool = new HttpClientPool(12, 3);
        try {
            PoolingHttpClientConnectionManager manager = pool.getConnectionManager();
            assertEquals(12, manager.getMaxTotal());
            assertEquals(3, manager.getDefaultMaxPerRoute());
            assertNotNull(pool.getHttpClient());
            assertTrue(pool.getPoolStats().contains("Leased=0"));
            assertTrue(pool.getPoolStats().contains("Max=12"));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void testShutdownStopsBackgroundMonitorAndAllowsReflectionMetrics() throws Exception {
        HttpClientPool pool = new HttpClientPool(8, 2);
        try {
            Field poolMonitorField = HttpClientPool.class.getDeclaredField("poolMonitor");
            poolMonitorField.setAccessible(true);
            Thread monitor = (Thread) poolMonitorField.get(pool);

            assertTrue(monitor.getName().contains("http-pool-monitor"));
            assertTrue(monitor.isDaemon());

            Method getPoolStats = monitor.getClass().getDeclaredMethod("getPoolStats");
            getPoolStats.setAccessible(true);
            assertTrue(((String) getPoolStats.invoke(monitor)).contains("Max=8"));

            Method logPoolStats = monitor.getClass().getDeclaredMethod("logPoolStats");
            logPoolStats.setAccessible(true);
            logPoolStats.invoke(monitor);

            pool.shutdown();
            monitor.join(2000L);
            assertFalse(monitor.isAlive());
        } finally {
            pool.shutdown();
        }
    }
}
