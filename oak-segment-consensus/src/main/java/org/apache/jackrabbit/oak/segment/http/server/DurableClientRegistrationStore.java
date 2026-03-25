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
package org.apache.jackrabbit.oak.segment.http.server;

import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Durable local persistence for client registrations.
 *
 * <p>The HTTP layer still keeps an in-memory alias map for fast lookup, but the
 * primary registrations are written to disk so restart does not erase client
 * identity state.</p>
 */
public class DurableClientRegistrationStore {
    private static final Logger log = LoggerFactory.getLogger(DurableClientRegistrationStore.class);

    private static final String PREFIX = "wallet.";
    private static final String CLIENT_ID = ".clientId";
    private static final String CLIENT_URL = ".clientUrl";
    private static final String CLIENT_TYPE = ".clientType";
    private static final String REGISTERED_AT = ".registeredAt";
    private static final String LAST_SEEN = ".lastSeen";

    private final Path registrationsFile;

    public DurableClientRegistrationStore(Path storeDirectory) {
        this.registrationsFile = storeDirectory.resolve("client-registrations.properties");
    }

    public synchronized List<ClientRegistration> load() {
        if (!Files.exists(registrationsFile)) {
            return List.of();
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(registrationsFile)) {
            properties.load(inputStream);
        } catch (IOException e) {
            log.warn("Failed to load durable client registrations from {}", registrationsFile, e);
            return List.of();
        }

        Set<String> wallets = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(PREFIX)) {
                continue;
            }
            int suffixIndex = key.indexOf('.', PREFIX.length());
            if (suffixIndex <= PREFIX.length()) {
                continue;
            }
            wallets.add(key.substring(PREFIX.length(), suffixIndex));
        }

        List<ClientRegistration> registrations = new ArrayList<>();
        for (String wallet : wallets) {
            String clientId = properties.getProperty(property(wallet, CLIENT_ID), wallet);
            String clientUrl = properties.getProperty(property(wallet, CLIENT_URL), "wallet://" + wallet);
            String clientType = properties.getProperty(property(wallet, CLIENT_TYPE), ClientRegistration.CLIENT_TYPE_SUPPLY_CHAIN);
            long registeredAt = parseLong(properties.getProperty(property(wallet, REGISTERED_AT)), System.currentTimeMillis());
            long lastSeen = parseLong(properties.getProperty(property(wallet, LAST_SEEN)), registeredAt);
            registrations.add(ClientRegistration.restore(
                clientId,
                clientUrl,
                wallet,
                clientType,
                registeredAt,
                lastSeen
            ));
        }

        return registrations;
    }

    public synchronized void save(Collection<ClientRegistration> registrations) {
        Path parentDirectory = registrationsFile.getParent();
        if (parentDirectory == null || !Files.isDirectory(parentDirectory)) {
            return;
        }

        Properties properties = new Properties();
        for (ClientRegistration registration : registrations) {
            if (registration == null || registration.walletAddress == null || registration.walletAddress.isBlank()) {
                continue;
            }
            String wallet = registration.walletAddress.toLowerCase();
            properties.setProperty(property(wallet, CLIENT_ID), registration.clientId != null ? registration.clientId : wallet);
            properties.setProperty(property(wallet, CLIENT_URL), registration.clientUrl != null ? registration.clientUrl : "wallet://" + wallet);
            properties.setProperty(property(wallet, CLIENT_TYPE), registration.clientType);
            properties.setProperty(property(wallet, REGISTERED_AT), Long.toString(registration.registeredAt));
            properties.setProperty(property(wallet, LAST_SEEN), Long.toString(registration.lastSeen));
        }

        try {
            Path tempFile = registrationsFile.resolveSibling(registrationsFile.getFileName() + ".tmp");
            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                properties.store(outputStream, "Oak Segment Consensus client registrations");
            }
            Files.move(tempFile, registrationsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to persist durable client registrations to {}", registrationsFile, e);
        }
    }

    private static String property(String wallet, String suffix) {
        return PREFIX + wallet + suffix;
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
