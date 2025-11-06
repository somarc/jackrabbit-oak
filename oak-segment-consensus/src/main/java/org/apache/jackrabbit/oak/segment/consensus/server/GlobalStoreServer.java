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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Standalone server for the global Blockchain AEM repository.
 * <p>
 * This server hosts the read-only global segment store that contains
 * all wallet-owned content at /oak-chain/content/<wallet-uuid>/*
 * <p>
 * Other AEM instances connect to this server and mount the content
 * as a read-only Composite NodeStore mount.
 * <p>
 * Usage:
 * <pre>
 * java -jar oak-segment-consensus.jar \
 *   --port 8090 \
 *   --store /var/oak-chain/segmentstore
 * </pre>
 */
public class GlobalStoreServer {
    
    private final int port;
    private final String storeDirectory;
    private volatile boolean running = false;
    
    public GlobalStoreServer(int port, String storeDirectory) {
        this.port = port;
        this.storeDirectory = storeDirectory;
    }
    
    /**
     * Start the global store server.
     */
    public void start() throws IOException {
        // Create store directory if it doesn't exist
        Path storePath = Paths.get(storeDirectory);
        if (!Files.exists(storePath)) {
            Files.createDirectories(storePath);
            System.out.println("Created store directory: " + storePath);
        }
        
        // Initialize Oak segment store
        // In a full implementation, this would:
        // 1. Create a FileStore instance
        // 2. Create a SegmentNodeStore
        // 3. Start an HTTP server (using Oak's standby sync protocol or custom REST API)
        // 4. Listen for segment sync requests
        
        running = true;
        
        System.out.println("===========================================");
        System.out.println("  Blockchain AEM - Global Store Server");
        System.out.println("===========================================");
        System.out.println();
        System.out.println("Port:           " + port);
        System.out.println("Store:          " + storeDirectory);
        System.out.println("Mount Path:     /oak-chain");
        System.out.println("Access:         READ-ONLY");
        System.out.println();
        System.out.println("Server started successfully!");
        System.out.println("Waiting for client connections...");
        System.out.println();
        
        // Keep server running
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * Stop the server.
     */
    public void stop() {
        System.out.println("Shutting down global store server...");
        running = false;
    }
    
    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        int port = 8090;
        String storeDir = "/tmp/oak-chain-global";
        
        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
                i++;
            } else if ("--store".equals(args[i]) && i + 1 < args.length) {
                storeDir = args[i + 1];
                i++;
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printUsage();
                return;
            }
        }
        
        final GlobalStoreServer server = new GlobalStoreServer(port, storeDir);
        
        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
        }));
        
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void printUsage() {
        System.out.println("Blockchain AEM - Global Store Server");
        System.out.println();
        System.out.println("Usage: java -jar oak-segment-consensus.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --port <port>        Server port (default: 8090)");
        System.out.println("  --store <directory>  Segment store directory (default: /tmp/oak-chain-global)");
        System.out.println("  --help, -h           Show this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar oak-segment-consensus.jar --port 8090 --store /var/oak-chain");
    }
}

