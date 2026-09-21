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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService;
import org.apache.jackrabbit.oak.segment.consensus.server.GlobalStoreServer;
import org.apache.jackrabbit.oak.segment.consensus.server.GlobalStoreServerComponentFactory;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ValidatorLifecycleManagerTest {

    @Test
    public void testActivateRegistersServerInjectsDependenciesAndStartsThroughExecutor() throws Exception {
        ValidatorFactory factory = mock(ValidatorFactory.class);
        ComponentRegistry registry = mock(ComponentRegistry.class);
        ShutdownHookHandler shutdownHookHandler = mock(ShutdownHookHandler.class);
        GlobalStoreServer server = mock(GlobalStoreServer.class);
        ValidatorConfig config = config(8091, "/tmp/segmentstore");
        when(factory.create(config)).thenReturn(server);

        AtomicReference<String> threadName = new AtomicReference<>();
        ValidatorLifecycleManager manager = new ValidatorLifecycleManager(factory, registry, shutdownHookHandler,
            (name, task) -> {
                threadName.set(name);
                task.run();
            });

        setField(manager, "aeronClusterService", mock(AeronClusterService.class));
        setField(manager, "componentFactory", mock(GlobalStoreServerComponentFactory.class));

        manager.activate(config);

        verify(factory).create(config);
        verify(server).setAeronClusterService((AeronClusterService) getField(manager, "aeronClusterService"));
        verify(server).setComponentFactory((GlobalStoreServerComponentFactory) getField(manager, "componentFactory"));
        verify(registry).register("GlobalStoreServer", server);
        verify(shutdownHookHandler).register(server);
        verify(server).start();
        assertEquals("validator-lifecycle-start", threadName.get());
    }

    @Test
    public void testDeactivateStopsServer() throws Exception {
        ValidatorLifecycleManager manager = new ValidatorLifecycleManager(
            mock(ValidatorFactory.class),
            mock(ComponentRegistry.class),
            mock(ShutdownHookHandler.class),
            (name, task) -> { });
        GlobalStoreServer server = mock(GlobalStoreServer.class);
        setField(manager, "server", server);

        manager.deactivate();

        verify(server).stop();
    }

    @Test
    public void testActivateSwallowsStartExceptionsInsideExecutor() throws Exception {
        ValidatorFactory factory = mock(ValidatorFactory.class);
        ComponentRegistry registry = mock(ComponentRegistry.class);
        ShutdownHookHandler shutdownHookHandler = mock(ShutdownHookHandler.class);
        GlobalStoreServer server = mock(GlobalStoreServer.class);
        ValidatorConfig config = config(8092, "/tmp/segmentstore");
        when(factory.create(config)).thenReturn(server);
        doThrow(new RuntimeException("boom")).when(server).start();

        ValidatorLifecycleManager manager = new ValidatorLifecycleManager(factory, registry, shutdownHookHandler,
            (name, task) -> task.run());

        manager.activate(config);

        verify(factory).create(config);
        verify(registry).register("GlobalStoreServer", server);
        verify(shutdownHookHandler).register(server);
        verify(server).start();
    }

    private static ValidatorConfig config(int port, String storeDirectory) {
        return new ValidatorConfig() {
            @Override
            public int http_port() {
                return port;
            }

            @Override
            public String store_directory() {
                return storeDirectory;
            }

            @Override
            public boolean consensus_enabled() {
                return true;
            }

            @Override
            public String consensus_mode() {
                return "aeron";
            }

            @Override
            public String consensus_self_url() {
                return "";
            }

            @Override
            public String consensus_peers() {
                return "";
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return ValidatorConfig.class;
            }
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ValidatorLifecycleManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = ValidatorLifecycleManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
