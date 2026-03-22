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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ValidatorLoggingBootstrapTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final ValidatorLoggingBootstrap bootstrap = new ValidatorLoggingBootstrap();

    @After
    public void tearDown() {
        clearProperty(ValidatorLoggingBootstrap.PROP_LOG_DIR);
        clearProperty(ValidatorLoggingBootstrap.PROP_LOG_FILE);
        clearProperty(ValidatorLoggingBootstrap.PROP_LOG_LEVEL);
        clearProperty(ValidatorLoggingBootstrap.PROP_LOG_MAX_FILE_SIZE);
        clearProperty(ValidatorLoggingBootstrap.PROP_LOG_MAX_HISTORY_DAYS);
        clearProperty(ValidatorLoggingBootstrap.PROP_LOG_TOTAL_SIZE_CAP);
        clearProperty(ValidatorLoggingBootstrap.PROP_LOG_CONSOLE_ENABLED);
        clearProperty(ValidatorLoggingBootstrap.PROP_VALIDATOR_PORT);
        clearProperty(ValidatorLoggingBootstrap.PROP_VALIDATOR_STORE);
        clearProperty("logback.configurationFile");
        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        SLF4JBridgeHandler.uninstall();
    }

    @Test
    public void initializeConfiguresBundledRollingFileAppender() throws Exception {
        Path storeDir = tempFolder.getRoot().toPath().resolve("cluster/validator-0/segmentstore");
        Files.createDirectories(storeDir);

        ValidatorLoggingBootstrap.BootstrapResult result = bootstrap.initialize(8090, storeDir.toString());

        assertFalse(result.isExternalConfiguration());
        assertEquals(storeDir.getParent().resolve("logs").toAbsolutePath().normalize(), result.getLogDir());
        assertTrue(Files.isDirectory(result.getLogDir()));
        assertEquals(result.getLogDir().resolve("validator.log"), result.getLogFile());
        assertEquals("8090", System.getProperty(ValidatorLoggingBootstrap.PROP_VALIDATOR_PORT));
        assertEquals(storeDir.toAbsolutePath().normalize().toString(),
            System.getProperty(ValidatorLoggingBootstrap.PROP_VALIDATOR_STORE));

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> consoleAppender = root.getAppender("CONSOLE");
        Appender<ILoggingEvent> fileAppender = root.getAppender("ROLLING_FILE");

        assertNotNull(consoleAppender);
        assertTrue(fileAppender instanceof RollingFileAppender);

        RollingFileAppender<ILoggingEvent> rollingFileAppender = (RollingFileAppender<ILoggingEvent>) fileAppender;
        assertEquals(result.getLogFile().toString(), rollingFileAppender.getFile());
        assertTrue(rollingFileAppender.getRollingPolicy() instanceof SizeAndTimeBasedRollingPolicy);

        SizeAndTimeBasedRollingPolicy<?> policy =
            (SizeAndTimeBasedRollingPolicy<?>) rollingFileAppender.getRollingPolicy();
        assertEquals(14, policy.getMaxHistory());
        assertTrue(policy.getFileNamePattern().contains("archive/validator.log"));
        assertEquals("10GB", System.getProperty(ValidatorLoggingBootstrap.PROP_LOG_TOTAL_SIZE_CAP));
        assertEquals("256MB", System.getProperty(ValidatorLoggingBootstrap.PROP_LOG_MAX_FILE_SIZE));
    }

    @Test
    public void initializeRespectsExternalConfigurationOverride() throws Exception {
        Path customConfig = tempFolder.getRoot().toPath().resolve("custom-logback.xml");
        Files.writeString(customConfig,
            "<configuration>\n"
                + "  <appender name=\"CUSTOM_CONSOLE\" class=\"ch.qos.logback.core.ConsoleAppender\">\n"
                + "    <encoder><pattern>%msg%n</pattern></encoder>\n"
                + "  </appender>\n"
                + "  <root level=\"ERROR\">\n"
                + "    <appender-ref ref=\"CUSTOM_CONSOLE\"/>\n"
                + "  </root>\n"
                + "</configuration>\n");
        System.setProperty("logback.configurationFile", customConfig.toString());

        ValidatorLoggingBootstrap.BootstrapResult result = bootstrap.initialize(8091, tempFolder.getRoot().toString());

        assertTrue(result.isExternalConfiguration());
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        assertNotNull(root.getAppender("CUSTOM_CONSOLE"));
        assertNull(root.getAppender("ROLLING_FILE"));
    }

    @Test
    public void initializeHonorsExplicitLogDirAndConsoleToggle() throws Exception {
        Path explicitLogDir = tempFolder.getRoot().toPath().resolve("custom-logs");
        System.setProperty(ValidatorLoggingBootstrap.PROP_LOG_DIR, explicitLogDir.toString());
        System.setProperty(ValidatorLoggingBootstrap.PROP_LOG_CONSOLE_ENABLED, "false");

        ValidatorLoggingBootstrap.BootstrapResult result = bootstrap.initialize(8092, tempFolder.getRoot().toString());

        assertEquals(explicitLogDir.toAbsolutePath().normalize(), result.getLogDir());
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        assertNull(root.getAppender("CONSOLE"));
        assertNotNull(root.getAppender("ROLLING_FILE"));
    }

    @Test
    public void initializeBridgesJulIntoSlf4j() throws Exception {
        Path storeDir = tempFolder.getRoot().toPath().resolve("cluster/validator-1/segmentstore");
        Files.createDirectories(storeDir);
        bootstrap.initialize(8093, storeDir.toString());

        ListAppender<ILoggingEvent> appender = TestLogAppenderSupport.attachRoot();
        try {
            java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger("validator.jul");
            julLogger.setLevel(Level.INFO);
            julLogger.info("JUL bridge message");

            assertTrue(TestLogAppenderSupport.contains(appender, "JUL bridge message"));
        } finally {
            TestLogAppenderSupport.detachRoot(appender);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void clearProperty(String property) {
        System.clearProperty(property);
    }
}
