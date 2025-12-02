/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.run.luke;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.jackrabbit.oak.run.commons.Command;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLI Command to list all Lucene indexes in a directory.
 * 
 * Usage:
 *   java -jar oak-run-luke.jar list /path/to/repository
 */
public class LukeListCommand implements Command {

    @Override
    public void execute(String... args) throws Exception {
        OptionParser parser = new OptionParser();
        
        OptionSpec<?> helpOpt = parser.accepts("help", "Show help").forHelp();
        OptionSpec<File> nonOptions = parser.nonOptions("repository-path").ofType(File.class);
        
        OptionSet options = parser.parse(args);
        
        if (options.has(helpOpt) || options.valuesOf(nonOptions).isEmpty()) {
            printUsage();
            parser.printHelpOn(System.out);
            return;
        }
        
        File basePath = options.valuesOf(nonOptions).get(0);
        
        if (!basePath.exists()) {
            System.err.println("ERROR: Path does not exist: " + basePath);
            return;
        }
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("LUCENE INDEX DISCOVERY");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("Scanning: " + basePath.getAbsolutePath());
        System.out.println();
        
        List<IndexInfo> indexes = findIndexes(basePath);
        
        if (indexes.isEmpty()) {
            System.out.println("No Lucene indexes found.");
            System.out.println();
            System.out.println("Tips:");
            System.out.println("  - For AEM, try: crx-quickstart/repository/index/");
            System.out.println("  - Look for directories containing 'segments_*' files");
            return;
        }
        
        System.out.printf("Found %d index(es):%n%n", indexes.size());
        System.out.printf("%-60s | %12s | %10s | %s%n", "Index Path", "Documents", "Size", "Status");
        System.out.println(repeatChar('-', 100));
        
        for (IndexInfo info : indexes) {
            String pathDisplay = info.path.length() > 58 ? "..." + info.path.substring(info.path.length() - 55) : info.path;
            System.out.printf("%-60s | %,12d | %10s | %s%n", 
                    pathDisplay, info.numDocs, humanReadableSize(info.size), info.status);
        }
        
        System.out.println();
        System.out.println("To inspect an index:");
        System.out.println("  java -jar oak-run-luke.jar inspect <path-to-index>");
        System.out.println();
        System.out.println("To launch GUI:");
        System.out.println("  java -jar oak-run-luke.jar gui <path-to-index>");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    private void printUsage() {
        System.out.println("LUKE Index Discovery - Find all Lucene indexes");
        System.out.println("==============================================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar oak-run-luke.jar list /path/to/repository");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Find indexes in AEM repository");
        System.out.println("  java -jar oak-run-luke.jar list crx-quickstart/repository/index/");
        System.out.println();
        System.out.println("  # Scan entire repository");
        System.out.println("  java -jar oak-run-luke.jar list crx-quickstart/repository/");
        System.out.println();
    }
    
    private List<IndexInfo> findIndexes(File basePath) {
        List<IndexInfo> indexes = new ArrayList<>();
        scanForIndexes(basePath, indexes, 0);
        return indexes;
    }
    
    private void scanForIndexes(File dir, List<IndexInfo> indexes, int depth) {
        if (depth > 5) return;  // Limit recursion depth
        
        if (!dir.isDirectory()) return;
        
        // Check if this directory contains a Lucene index
        File actualDir = findActualIndexDirectory(dir);
        if (actualDir != null) {
            IndexInfo info = analyzeIndex(actualDir, dir);
            if (info != null) {
                indexes.add(info);
            }
            return;  // Don't recurse into index directories
        }
        
        // Recurse into subdirectories
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !child.getName().startsWith(".")) {
                    scanForIndexes(child, indexes, depth + 1);
                }
            }
        }
    }
    
    private File findActualIndexDirectory(File path) {
        // Check if path itself contains segments
        if (hasSegmentsFile(path)) {
            return path;
        }
        
        // Check data subdirectory (Oak IndexCopier structure)
        File dataDir = new File(path, "data");
        if (dataDir.exists() && hasSegmentsFile(dataDir)) {
            return dataDir;
        }
        
        return null;
    }
    
    private boolean hasSegmentsFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.getName().startsWith("segments") && !f.getName().equals("segments.gen")) {
                return true;
            }
        }
        return false;
    }
    
    private IndexInfo analyzeIndex(File actualDir, File displayDir) {
        IndexInfo info = new IndexInfo();
        info.path = displayDir.getAbsolutePath();
        info.size = getFolderSize(displayDir);
        
        try {
            Directory dir = FSDirectory.open(actualDir);
            DirectoryReader reader = DirectoryReader.open(dir);
            info.numDocs = reader.numDocs();
            info.status = "OK";
            reader.close();
            dir.close();
        } catch (Exception e) {
            info.numDocs = -1;
            info.status = "ERROR: " + e.getMessage();
        }
        
        return info;
    }
    
    private long getFolderSize(File folder) {
        long size = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getFolderSize(file);
                }
            }
        }
        return size;
    }
    
    private String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private String repeatChar(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
    
    private static class IndexInfo {
        String path;
        int numDocs;
        long size;
        String status;
    }
}
