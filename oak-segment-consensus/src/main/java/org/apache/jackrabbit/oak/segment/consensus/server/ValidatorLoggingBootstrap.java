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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

final class ValidatorLoggingBootstrap {

    static final String PROP_LOG_DIR = "oak.log.dir";
    static final String PROP_LOG_FILE = "oak.log.file";
    static final String PROP_LOG_LEVEL = "oak.log.level";
    static final String PROP_LOG_MAX_FILE_SIZE = "oak.log.maxFileSize";
    static final String PROP_LOG_MAX_HISTORY_DAYS = "oak.log.maxHistoryDays";
    static final String PROP_LOG_TOTAL_SIZE_CAP = "oak.log.totalSizeCap";
    static final String PROP_LOG_CONSOLE_ENABLED = "oak.log.console.enabled";
    static final String PROP_VALIDATOR_PORT = "oak.validator.port";
    static final String PROP_VALIDATOR_STORE = "oak.validator.store";

    static final String DEFAULT_LOG_FILE = "validator.log";
    static final String DEFAULT_LOG_LEVEL = "INFO";
    static final String DEFAULT_LOG_MAX_FILE_SIZE = "256MB";
    static final String DEFAULT_LOG_MAX_HISTORY_DAYS = "14";
    static final String DEFAULT_LOG_TOTAL_SIZE_CAP = "10GB";
    static final String DEFAULT_LOG_CONSOLE_ENABLED = "true";
    static final String DEFAULT_CONFIG_RESOURCE = "/logback-validator.xml";
    static final String CONSOLE_APPENDER_NAME = "CONSOLE";

    BootstrapResult initialize(int port, String storeDirectory) throws IOException {
        Path logDir = resolveLogDir(storeDirectory);
        Files.createDirectories(logDir);

        setDefaultProperty(PROP_LOG_DIR, logDir.toString());
        setDefaultProperty(PROP_LOG_FILE, DEFAULT_LOG_FILE);
        setDefaultProperty(PROP_LOG_LEVEL, DEFAULT_LOG_LEVEL);
        setDefaultProperty(PROP_LOG_MAX_FILE_SIZE, DEFAULT_LOG_MAX_FILE_SIZE);
        setDefaultProperty(PROP_LOG_MAX_HISTORY_DAYS, DEFAULT_LOG_MAX_HISTORY_DAYS);
        setDefaultProperty(PROP_LOG_TOTAL_SIZE_CAP, DEFAULT_LOG_TOTAL_SIZE_CAP);
        setDefaultProperty(PROP_LOG_CONSOLE_ENABLED, DEFAULT_LOG_CONSOLE_ENABLED);
        setDefaultProperty(PROP_VALIDATOR_PORT, String.valueOf(port));
        setDefaultProperty(PROP_VALIDATOR_STORE, normalizeStoreDirectory(storeDirectory));

        String externalConfigLocation = System.getProperty(ContextInitializer.CONFIG_FILE_PROPERTY);
        boolean externalConfiguration = externalConfigLocation != null;
        if (externalConfiguration) {
            configureLogback(externalConfigLocation);
        } else {
            configureBundledLogback();
        }
        installJulBridge();

        Logger log = LoggerFactory.getLogger(ValidatorLoggingBootstrap.class);
        log.info("Validator logging initialized");
        log.info("Log directory: {}", System.getProperty(PROP_LOG_DIR));
        log.info("Log file: {}", resolveLogFile());
        if (externalConfiguration) {
            log.info("Using external Logback configuration from {}",
                System.getProperty(ContextInitializer.CONFIG_FILE_PROPERTY));
        } else {
            log.info("Using bundled Logback configuration {}", DEFAULT_CONFIG_RESOURCE);
        }

        return new BootstrapResult(
            externalConfiguration,
            Paths.get(System.getProperty(PROP_LOG_DIR)).toAbsolutePath().normalize(),
            resolveLogFile()
        );
    }

    private void configureBundledLogback() throws IOException {
        URL resource = getClass().getResource(DEFAULT_CONFIG_RESOURCE);
        if (resource == null) {
            throw new IOException("Missing bundled logging configuration " + DEFAULT_CONFIG_RESOURCE);
        }

        configureLogback(resource);
    }

    private void configureLogback(String configLocation) throws IOException {
        configureLogback(Paths.get(configLocation).toAbsolutePath().normalize());
    }

    private void configureLogback(URL configUrl) throws IOException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try (InputStream input = configUrl.openStream()) {
            context.reset();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(input);
            if (!Boolean.parseBoolean(System.getProperty(PROP_LOG_CONSOLE_ENABLED, DEFAULT_LOG_CONSOLE_ENABLED))) {
                context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(CONSOLE_APPENDER_NAME);
            }
        } catch (JoranException e) {
            throw new IOException("Failed to configure logging from " + configUrl, e);
        }
        StatusPrinter.printInCaseOfErrorsOrWarnings(context);
    }

    private void configureLogback(Path configPath) throws IOException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            context.reset();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(configPath.toString());
            if (!Boolean.parseBoolean(System.getProperty(PROP_LOG_CONSOLE_ENABLED, DEFAULT_LOG_CONSOLE_ENABLED))) {
                context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(CONSOLE_APPENDER_NAME);
            }
        } catch (JoranException e) {
            throw new IOException("Failed to configure logging from " + configPath, e);
        }
        StatusPrinter.printInCaseOfErrorsOrWarnings(context);
    }

    private void installJulBridge() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        if (!SLF4JBridgeHandler.isInstalled()) {
            SLF4JBridgeHandler.install();
        }
    }

    private static void setDefaultProperty(String key, String value) {
        if (value != null && System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static Path resolveLogDir(String storeDirectory) {
        String configuredLogDir = System.getProperty(PROP_LOG_DIR);
        if (configuredLogDir != null && !configuredLogDir.trim().isEmpty()) {
            return Paths.get(configuredLogDir).toAbsolutePath().normalize();
        }

        if (storeDirectory != null && !storeDirectory.trim().isEmpty()) {
            Path storePath = Paths.get(storeDirectory).toAbsolutePath().normalize();
            Path parent = storePath.getParent();
            if (parent != null) {
                return parent.resolve("logs");
            }
        }

        return Paths.get(".").toAbsolutePath().normalize().resolve("logs");
    }

    private static String normalizeStoreDirectory(String storeDirectory) {
        if (storeDirectory == null || storeDirectory.trim().isEmpty()) {
            return "unknown";
        }
        return Paths.get(storeDirectory).toAbsolutePath().normalize().toString();
    }

    private static Path resolveLogFile() {
        return Paths.get(System.getProperty(PROP_LOG_DIR)).resolve(System.getProperty(PROP_LOG_FILE));
    }

    static final class BootstrapResult {
        private final boolean externalConfiguration;
        private final Path logDir;
        private final Path logFile;

        BootstrapResult(boolean externalConfiguration, Path logDir, Path logFile) {
            this.externalConfiguration = externalConfiguration;
            this.logDir = logDir;
            this.logFile = logFile;
        }

        boolean isExternalConfiguration() {
            return externalConfiguration;
        }

        Path getLogDir() {
            return logDir;
        }

        Path getLogFile() {
            return logFile;
        }
    }
}
