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
package org.apache.jackrabbit.oak.segment.consensus.server;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.io.Closeable;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class BinaryGcStartupTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @After
    public void clear() {
        System.clearProperty("oak.gc.binary.enabled");
        System.clearProperty("oak.gc.binary.interval.ms");
    }

    @Test
    public void binaryGcStartsByDefaultAndPublishesItsStatus() throws Exception {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        ServerContext context = context();

        AeronClusterBootstrapper.startBinaryGc(engine, context, mock(FileStore.class), null);

        ArgumentCaptor<Closeable> resources = ArgumentCaptor.forClass(Closeable.class);
        verify(engine).setBinaryGc(any(Consumer.class), resources.capture());
        Map<String, Object> status = context.binaryGcStatus.get();
        assertEquals(true, status.get("enabled"));
        assertEquals(86_400_000L, status.get("intervalMs"));
        assertEquals(false, status.get("unpinOnDelete"));
        resources.getValue().close();
    }

    @Test
    public void disabledBinaryGcPublishesNoStatusAndInvalidSettingsFailStartup() throws Exception {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        ServerContext context = context();
        System.setProperty("oak.gc.binary.enabled", "false");
        AeronClusterBootstrapper.startBinaryGc(engine, context, mock(FileStore.class), null);
        assertNull(context.binaryGcStatus);
        verify(engine, never()).setBinaryGc(any(), any());

        System.setProperty("oak.gc.binary.enabled", "true");
        System.setProperty("oak.gc.binary.interval.ms", "-5");
        assertThrows(IllegalStateException.class,
            () -> AeronClusterBootstrapper.startBinaryGc(engine, context, mock(FileStore.class), null));
    }

    private ServerContext context() throws Exception {
        return new ServerContext(mock(FileStore.class), mock(NodeStore.class), folder.newFolder().toPath(),
            "http://localhost:8090");
    }
}
