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
package org.apache.jackrabbit.oak.plugins.index.lucene.luke;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.apache.jackrabbit.oak.commons.jmx.AnnotatedStandardMBean;
import org.apache.jackrabbit.oak.plugins.index.lucene.IndexCopier;
import org.apache.jackrabbit.oak.plugins.index.lucene.IndexTracker;
import org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexNode;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;
import static org.apache.jackrabbit.oak.commons.IOUtils.humanReadableByteCount;

/**
 * Implementation of LukeIndexStatsMBean that provides LUKE-based
 * index inspection capabilities using the local IndexCopier cache.
 */
public class LukeIndexStatsMBeanImpl extends AnnotatedStandardMBean implements LukeIndexStatsMBean {

    private static final Logger log = LoggerFactory.getLogger(LukeIndexStatsMBeanImpl.class);

    private final IndexTracker indexTracker;
    private final IndexCopier indexCopier;

    public LukeIndexStatsMBeanImpl(@NotNull IndexTracker indexTracker,
                                   @Nullable IndexCopier indexCopier) {
        super(LukeIndexStatsMBean.class);
        this.indexTracker = requireNonNull(indexTracker, "IndexTracker cannot be null");
        this.indexCopier = indexCopier;
    }

    @Override
    public TabularData getLocalIndexDirectories() {
        TabularDataSupport tds;
        try {
            String[] headers = new String[]{"indexPath", "localPath", "size", "exists"};
            CompositeType ct = new CompositeType(
                    "LocalIndexDirectory",
                    "Local Index Directory Information",
                    headers,
                    headers,
                    new OpenType[]{SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN}
            );
            TabularType tt = new TabularType(
                    "LocalIndexDirectories",
                    "Local Index Directories",
                    ct,
                    new String[]{"indexPath"}
            );
            tds = new TabularDataSupport(tt);

            if (indexCopier == null) {
                log.warn("IndexCopier not available - cannot list local directories");
                return tds;
            }

            Set<String> indexPaths = indexTracker.getIndexNodePaths();
            for (String indexPath : indexPaths) {
                try {
                    File localDir = getLocalDirectoryForIndex(indexPath);
                    if (localDir != null) {
                        long size = getDirectorySize(localDir);
                        Object[] values = new Object[]{
                                indexPath,
                                localDir.getAbsolutePath(),
                                humanReadableByteCount(size),
                                localDir.exists()
                        };
                        CompositeDataSupport cds = new CompositeDataSupport(ct, headers, values);
                        tds.put(cds);
                    }
                } catch (Exception e) {
                    log.warn("Error getting local directory for index: " + indexPath, e);
                }
            }
        } catch (OpenDataException e) {
            throw new IllegalStateException("Failed to create tabular data", e);
        }
        return tds;
    }

    @Override
    public TabularData getLukeIndexStats(String indexPath) throws IOException {
        requireNonNull(indexPath, "indexPath cannot be null");

        TabularDataSupport tds;
        try {
            String[] headers = new String[]{"metric", "value"};
            CompositeType ct = new CompositeType(
                    "LukeIndexStat",
                    "LUKE Index Statistic",
                    headers,
                    headers,
                    new OpenType[]{SimpleType.STRING, SimpleType.STRING}
            );
            TabularType tt = new TabularType(
                    "LukeIndexStats",
                    "LUKE Index Statistics",
                    ct,
                    new String[]{"metric"}
            );
            tds = new TabularDataSupport(tt);

            File localDir = getLocalDirectoryForIndex(indexPath);
            if (localDir == null || !localDir.exists()) {
                throw new IOException("Local index directory not found for: " + indexPath);
            }

            try (Directory dir = FSDirectory.open(localDir);
                 IndexReader reader = DirectoryReader.open(dir)) {

                addStat(tds, ct, headers, "Number of Documents", String.valueOf(reader.numDocs()));
                addStat(tds, ct, headers, "Max Doc", String.valueOf(reader.maxDoc()));
                addStat(tds, ct, headers, "Has Deletions", String.valueOf(reader.hasDeletions()));
                addStat(tds, ct, headers, "Number of Fields", String.valueOf(getFieldCount(reader)));
                addStat(tds, ct, headers, "Index Size", humanReadableByteCount(getDirectorySize(localDir)));
                addStat(tds, ct, headers, "Local Path", localDir.getAbsolutePath());
            }
        } catch (OpenDataException e) {
            throw new IllegalStateException("Failed to create tabular data", e);
        }

        return tds;
    }

    @Override
    public String[] getLukeFieldInfo(String indexPath, int maxFields) throws IOException {
        requireNonNull(indexPath, "indexPath cannot be null");
        if (maxFields <= 0) {
            maxFields = 100;
        }

        File localDir = getLocalDirectoryForIndex(indexPath);
        if (localDir == null || !localDir.exists()) {
            throw new IOException("Local index directory not found for: " + indexPath);
        }

        List<String> fieldInfo = new ArrayList<>();
        try (Directory dir = FSDirectory.open(localDir);
             IndexReader reader = DirectoryReader.open(dir)) {

            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                int count = 0;
                for (String field : fields) {
                    if (count++ >= maxFields) {
                        break;
                    }
                    Terms terms = fields.terms(field);
                    if (terms != null) {
                        fieldInfo.add(String.format("Field: %s, Terms: %d, Docs: %d",
                                field,
                                terms.size(),
                                terms.getDocCount()));
                    }
                }
            }
        }

        return fieldInfo.toArray(new String[0]);
    }

    @Override
    public String[] getLukeTermStats(String indexPath, String fieldName, int maxTerms) throws IOException {
        requireNonNull(indexPath, "indexPath cannot be null");
        requireNonNull(fieldName, "fieldName cannot be null");
        if (maxTerms <= 0) {
            maxTerms = 100;
        }

        File localDir = getLocalDirectoryForIndex(indexPath);
        if (localDir == null || !localDir.exists()) {
            throw new IOException("Local index directory not found for: " + indexPath);
        }

        List<String> termStats = new ArrayList<>();
        try (Directory dir = FSDirectory.open(localDir);
             IndexReader reader = DirectoryReader.open(dir)) {

            Terms terms = MultiFields.getTerms(reader, fieldName);
            if (terms != null) {
                TermsEnum termsEnum = terms.iterator(null);
                BytesRef term;
                int count = 0;
                while ((term = termsEnum.next()) != null && count++ < maxTerms) {
                    termStats.add(String.format("Term: %s, Freq: %d, DocFreq: %d",
                            term.utf8ToString(),
                            termsEnum.totalTermFreq(),
                            termsEnum.docFreq()));
                }
            }
        }

        return termStats.toArray(new String[0]);
    }

    @Override
    public String getLukeBasicStats(String indexPath) throws IOException {
        requireNonNull(indexPath, "indexPath cannot be null");

        File localDir = getLocalDirectoryForIndex(indexPath);
        if (localDir == null || !localDir.exists()) {
            throw new IOException("Local index directory not found for: " + indexPath);
        }

        StringBuilder sb = new StringBuilder();
        try (Directory dir = FSDirectory.open(localDir);
             IndexReader reader = DirectoryReader.open(dir)) {

            sb.append("Index: ").append(indexPath).append("\n");
            sb.append("Local Path: ").append(localDir.getAbsolutePath()).append("\n");
            sb.append("Documents: ").append(reader.numDocs()).append("\n");
            sb.append("Max Doc: ").append(reader.maxDoc()).append("\n");
            sb.append("Deletions: ").append(reader.numDeletedDocs()).append("\n");
            sb.append("Fields: ").append(getFieldCount(reader)).append("\n");
            sb.append("Size: ").append(humanReadableByteCount(getDirectorySize(localDir))).append("\n");
        }

        return sb.toString();
    }

    @Override
    public String launchLukeGUI(String indexPath) throws IOException {
        if (indexPath == null || indexPath.isEmpty()) {
            return "LUKE GUI launcher not yet implemented. Use getLukeFieldInfo and getLukeTermStats for inspection.";
        }

        File localDir = getLocalDirectoryForIndex(indexPath);
        if (localDir == null || !localDir.exists()) {
            throw new IOException("Local index directory not found for: " + indexPath);
        }

        // TODO: Implement LUKE GUI launcher
        return String.format("LUKE GUI would launch for index at: %s\nLocal path: %s",
                indexPath, localDir.getAbsolutePath());
    }

    @Override
    public String getLocalIndexPath(String indexPath) {
        requireNonNull(indexPath, "indexPath cannot be null");

        File localDir = getLocalDirectoryForIndex(indexPath);
        if (localDir == null) {
            return "Not found in local cache";
        }
        return localDir.getAbsolutePath();
    }

    @Override
    public String validateLocalIndex(String indexPath) throws IOException {
        requireNonNull(indexPath, "indexPath cannot be null");

        File localDir = getLocalDirectoryForIndex(indexPath);
        if (localDir == null) {
            return "ERROR: Index not found in local cache";
        }
        if (!localDir.exists()) {
            return "ERROR: Local directory does not exist: " + localDir.getAbsolutePath();
        }
        if (!localDir.isDirectory()) {
            return "ERROR: Path is not a directory: " + localDir.getAbsolutePath();
        }

        try (Directory dir = FSDirectory.open(localDir);
             IndexReader reader = DirectoryReader.open(dir)) {
            return String.format("OK: Valid Lucene index with %d documents at %s",
                    reader.numDocs(), localDir.getAbsolutePath());
        }
    }

    @Override
    public TabularData getIndexCacheStatus() {
        return getLocalIndexDirectories();
    }

    // Helper methods

    @Nullable
    private File getLocalDirectoryForIndex(String indexPath) {
        LuceneIndexNode indexNode = null;
        try {
            indexNode = indexTracker.acquireIndexNode(indexPath);
            if (indexNode != null) {
                // Get the searcher and extract directory path from it
                IndexSearcher searcher = indexNode.getSearcher();
                if (searcher != null && searcher.getIndexReader() != null) {
                    IndexReader reader = searcher.getIndexReader();
                    
                    // Try to extract filesystem path from the reader
                    // The directory path is often embedded in the reader's toString()
                    String readerStr = reader.toString();
                    if (readerStr.contains("path=")) {
                        // Extract path from string like "...path=/some/path..."
                        int pathIdx = readerStr.indexOf("path=");
                        if (pathIdx > 0) {
                            String pathPart = readerStr.substring(pathIdx + 5);
                            int endIdx = pathPart.indexOf(' ');
                            if (endIdx > 0) {
                                pathPart = pathPart.substring(0, endIdx);
                            }
                            File dir = new File(pathPart.trim());
                            if (dir.exists()) {
                                return dir;
                            }
                        }
                    }
                }
                
                // Fallback: Use IndexCopier if available
                if (indexCopier != null) {
                    try {
                        org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexDefinition definition = 
                            indexNode.getDefinition();
                        File indexDir = indexCopier.getIndexDir(definition, indexPath, ":index");
                        if (indexDir != null && indexDir.exists()) {
                            return indexDir;
                        }
                    } catch (Exception e) {
                        log.debug("Could not get index directory via IndexCopier", e);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error locating local index directory for: " + indexPath, e);
        } finally {
            if (indexNode != null) {
                indexNode.release();
            }
        }

        return null;
    }

    private int getFieldCount(IndexReader reader) throws IOException {
        Fields fields = MultiFields.getFields(reader);
        if (fields == null) {
            return 0;
        }
        int count = 0;
        for (@SuppressWarnings("unused") String field : fields) {
            count++;
        }
        return count;
    }

    private long getDirectorySize(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return 0;
        }
        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getDirectorySize(file);
                }
            }
        }
        return size;
    }

    private void addStat(TabularDataSupport tds, CompositeType ct, String[] headers,
                         String metric, String value) throws OpenDataException {
        Object[] values = new Object[]{metric, value};
        CompositeDataSupport cds = new CompositeDataSupport(ct, headers, values);
        tds.put(cds);
    }
}

