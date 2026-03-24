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

import io.aeron.Aeron;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AeronClusterLauncherTest {

    @After
    public void clearProperties() {
        System.clearProperty("oak.cluster.environment");
        System.clearProperty("oak.cluster.session.timeout.minutes");
    }

    @Test
    public void explicitTimeoutOverrideWins() {
        System.setProperty("oak.cluster.environment", "dev");
        System.setProperty("oak.cluster.session.timeout.minutes", "7");

        AeronClusterLauncher.SessionTimeoutConfig config = AeronClusterLauncher.resolveSessionTimeoutConfig();

        assertEquals(7, config.timeoutMinutes);
        assertEquals(TimeUnit.MINUTES.toNanos(7), config.timeoutNs);
        assertEquals("system-property", config.source);
    }

    @Test
    public void devProfileUsesTwoMinutes() {
        System.setProperty("oak.cluster.environment", "dev");

        AeronClusterLauncher.SessionTimeoutConfig config = AeronClusterLauncher.resolveSessionTimeoutConfig();

        assertEquals(2, config.timeoutMinutes);
        assertEquals("environment-profile", config.source);
        assertEquals("dev", config.environment);
    }

    @Test
    public void stagingProfileUsesFiveMinutes() {
        System.setProperty("oak.cluster.environment", "staging");

        AeronClusterLauncher.SessionTimeoutConfig config = AeronClusterLauncher.resolveSessionTimeoutConfig();

        assertEquals(5, config.timeoutMinutes);
        assertEquals("staging", config.environment);
    }

    @Test
    public void unknownProfileFallsBackToProductionDefault() {
        System.setProperty("oak.cluster.environment", "custom");

        AeronClusterLauncher.SessionTimeoutConfig config = AeronClusterLauncher.resolveSessionTimeoutConfig();

        assertEquals(20, config.timeoutMinutes);
        assertEquals("custom", config.environment);
    }

    @Test
    public void invalidExplicitTimeoutFallsBackToProfile() {
        System.setProperty("oak.cluster.environment", "stage");
        System.setProperty("oak.cluster.session.timeout.minutes", "nope");

        AeronClusterLauncher.SessionTimeoutConfig config = AeronClusterLauncher.resolveSessionTimeoutConfig();

        assertEquals(5, config.timeoutMinutes);
        assertEquals("environment-profile", config.source);
    }

    @Test
    public void helperMethodsHandleBlankAndInvalidValues() throws Exception {
        assertNull(invokeParsePositiveInt(null));
        assertNull(invokeParsePositiveInt(" "));
        assertNull(invokeParsePositiveInt("-1"));
        assertNull(invokeParsePositiveInt("nope"));
        assertEquals(Integer.valueOf(3), invokeParsePositiveInt("3"));

        System.clearProperty("aeron.socket.so_sndbuf");
        assertEquals(16, invokeGetPositiveIntProperty("aeron.socket.so_sndbuf", 16));

        System.setProperty("aeron.socket.so_sndbuf", "bad");
        assertEquals(16, invokeGetPositiveIntProperty("aeron.socket.so_sndbuf", 16));

        System.setProperty("aeron.socket.so_sndbuf", "32");
        assertEquals(32, invokeGetPositiveIntProperty("aeron.socket.so_sndbuf", 16));

        assertEquals("first", invokeFirstNonBlank("first", "second"));
        assertEquals("second", invokeFirstNonBlank(" ", "second"));
        assertNull(invokeFirstNonBlank(" ", null));
    }

    @Test
    public void accessorsAndShutdownBehaveWithoutLaunchingCluster() throws Exception {
        AeronClusterLauncher launcher = new AeronClusterLauncher(
            1,
            List.of("node-0", "node-1"),
            new File("."),
            mock(ClusteredService.class),
            mock(AeronClusterAddressResolver.class),
            mock(AeronClusterErrorPolicy.class)
        );

        assertEquals(AeronClusterLauncher.getPortBase(), launcher.getClusterBasePort());
        assertEquals(AeronClusterLauncher.calculatePort(1, 7), AeronClusterLauncher.calculatePort(
            AeronClusterLauncher.getPortBase(), 1, 7));
        assertEquals("node-1", invokeGetHostname(launcher));
        assertNull(launcher.getAeron());

        ClusteredServiceContainer container = mock(ClusteredServiceContainer.class);
        ClusteredServiceContainer.Context context = mock(ClusteredServiceContainer.Context.class);
        Aeron aeron = mock(Aeron.class);
        when(container.context()).thenReturn(context);
        when(context.aeron()).thenReturn(aeron);
        setField(launcher, "container", container);
        assertSame(aeron, launcher.getAeron());

        CrashHandler crashHandler = mock(CrashHandler.class);
        MediaDriverHealthMonitor healthMonitor = mock(MediaDriverHealthMonitor.class);
        setField(launcher, "crashHandler", crashHandler);
        setField(launcher, "healthMonitor", healthMonitor);
        assertSame(crashHandler, launcher.getCrashHandler());
        assertSame(healthMonitor, launcher.getHealthMonitor());

        org.agrona.concurrent.ShutdownSignalBarrier barrier = mock(org.agrona.concurrent.ShutdownSignalBarrier.class);
        setField(launcher, "barrier", barrier);
        launcher.awaitShutdown();
        verify(barrier).await();

        launcher.shutdown();
        assertTrue(((AtomicBoolean) getField(launcher, "shutdownScheduled")).get());
    }

    private static Integer invokeParsePositiveInt(String value) throws Exception {
        Method method = AeronClusterLauncher.class.getDeclaredMethod("parsePositiveInt", String.class);
        method.setAccessible(true);
        return (Integer) method.invoke(null, value);
    }

    private static int invokeGetPositiveIntProperty(String key, int defaultValue) throws Exception {
        Method method = AeronClusterLauncher.class.getDeclaredMethod("getPositiveIntProperty", String.class, int.class);
        method.setAccessible(true);
        return (Integer) method.invoke(null, key, defaultValue);
    }

    private static String invokeFirstNonBlank(String first, String second) throws Exception {
        Method method = AeronClusterLauncher.class.getDeclaredMethod("firstNonBlank", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, first, second);
    }

    private static String invokeGetHostname(AeronClusterLauncher launcher) throws Exception {
        Method method = AeronClusterLauncher.class.getDeclaredMethod("getHostname");
        method.setAccessible(true);
        return (String) method.invoke(launcher);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
