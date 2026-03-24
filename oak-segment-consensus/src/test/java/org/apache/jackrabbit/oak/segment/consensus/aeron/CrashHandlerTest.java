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

import java.io.File;

import io.aeron.exceptions.AeronException;
import io.aeron.exceptions.DriverTimeoutException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static io.aeron.exceptions.AeronException.Category.FATAL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CrashHandlerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldStopHandlesFatalAndTimeoutScenarios() throws Exception {
        CrashHandler handler = new CrashHandler(temporaryFolder.newFolder("crash-stop"), 1);

        assertTrue(handler.shouldStop(new AeronException("fatal", FATAL)));
        assertTrue(handler.shouldStop(new DriverTimeoutException("driver timeout")));
        assertTrue(handler.shouldStop(new AeronException("keepalive timeout")));
        assertFalse(handler.shouldStop(new AeronException("warn")));
    }

    @Test
    public void handleCrashCreatesAndIncrementsCrashMarkers() throws Exception {
        CrashHandler handler = new CrashHandler(temporaryFolder.newFolder("crash-count"), 1);

        handler.handleCrash(new AeronException("boom"));
        assertTrue(handler.hasCrashed());
        assertEquals(1, handler.getCrashCount());
        assertEquals("node-crash-1", handler.getState());

        handler.handleCrash(new AeronException("boom"));
        assertTrue(handler.hasCrashed());
        assertEquals(2, handler.getCrashCount());
        assertEquals("node-crash-2", handler.getState());
    }

    @Test
    public void handleCrashPromotesCrashLoopToForceBootstrap() throws Exception {
        CrashHandler handler = new CrashHandler(temporaryFolder.newFolder("crash-loop"), 1);

        handler.handleCrash(new AeronException("boom-1"));
        handler.handleCrash(new AeronException("boom-2"));
        handler.handleCrash(new AeronException("boom-3"));
        handler.handleCrash(new AeronException("boom-4"));

        assertTrue(handler.shouldForceBootstrap());
        assertEquals("node-force-bootstrap", handler.getState());
    }

    @Test
    public void handleCrashMarksCorruptedStateForBootstrapAndResetClearsMarkers() throws Exception {
        CrashHandler handler = new CrashHandler(temporaryFolder.newFolder("crash-corrupt"), 1);

        handler.handleCrash(new AeronException("corrupted recording state"));
        assertTrue(handler.shouldForceBootstrap());

        handler.reset();
        assertFalse(handler.shouldForceBootstrap());
        assertFalse(handler.hasCrashed());
        assertEquals("None", handler.getState());
    }

    @Test
    public void markAsForceBootstrapAndShutdownSchedulingAreTracked() throws Exception {
        File baseDir = temporaryFolder.newFolder("crash-markers");
        CrashHandler handler = new CrashHandler(baseDir, 1);

        handler.markAsForceBootstrap();
        handler.markAsForceBootstrap();
        handler.markShutdownScheduled();

        assertTrue(handler.shouldForceBootstrap());
        assertTrue(handler.isShutdownScheduled());
        assertEquals("node-force-bootstrap", handler.getState());
    }
}
