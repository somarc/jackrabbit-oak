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

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

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
}
