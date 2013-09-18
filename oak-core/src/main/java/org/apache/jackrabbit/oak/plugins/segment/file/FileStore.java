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
package org.apache.jackrabbit.oak.plugins.segment.file;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static com.google.common.collect.Lists.newLinkedList;
import static com.google.common.collect.Maps.newHashMap;
import static org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState.EMPTY_NODE;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.jackrabbit.oak.plugins.segment.Journal;
import org.apache.jackrabbit.oak.plugins.segment.RecordId;
import org.apache.jackrabbit.oak.plugins.segment.Segment;
import org.apache.jackrabbit.oak.plugins.segment.SegmentNodeState;
import org.apache.jackrabbit.oak.plugins.segment.SegmentStore;
import org.apache.jackrabbit.oak.plugins.segment.Template;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

public class FileStore implements SegmentStore {

    private static final long SEGMENT_MAGIC = 0x4f616b0a527845ddL;

    private static final long JOURNAL_MAGIC = 0xdf36544212c0cb24L;

    static final UUID JOURNALS_UUID = new UUID(0, 0);

    private static final int FILE_SIZE = 256 * 1024 * 1024;

    private static final String FILE_NAME_FORMAT = "data%05d.tar";

    private final File directory;

    private final LinkedList<TarFile> files = newLinkedList();

    private final Map<String, Journal> journals = newHashMap();

    private final Cache<UUID, Segment> segments =
            CacheBuilder.newBuilder().maximumSize(1000).build();

    public FileStore(File directory, NodeState root) throws IOException {
        checkNotNull(directory).mkdirs();
        this.directory = directory;

        for (int i = 0; true; i++) {
            String name = String.format(FILE_NAME_FORMAT, i);
            File file = new File(directory, name);
            if (file.isFile()) {
                files.add(new TarFile(file, FILE_SIZE));
            } else {
                break;
            }
        }

        for (TarFile tar : files) {
            ByteBuffer buffer = tar.readEntry(JOURNALS_UUID);
            if (buffer != null) {
                checkState(JOURNAL_MAGIC == buffer.getLong());
                int count = buffer.getInt();
                for (int i = 0; i < count; i++) {
                    byte[] b = new byte[buffer.getInt()];
                    buffer.get(b);
                    String name = new String(b, UTF_8);
                    RecordId recordId = new RecordId(
                            new UUID(buffer.getLong(), buffer.getLong()),
                            buffer.getInt());
                    journals.put(name, new FileJournal(
                            this, new SegmentNodeState(this, recordId)));
                }
            }
        }

        if (!journals.containsKey("root")) {
            NodeBuilder builder = EMPTY_NODE.builder();
            builder.setChildNode("root", root);
            journals.put("root", new FileJournal(this, builder.getNodeState()));
        }
    }

    public FileStore(File directory) throws IOException {
        this(directory, EMPTY_NODE);
    }

    public FileStore(String directory) throws IOException {
        this(new File(directory));
    }

    public synchronized void close() {
        for (TarFile file : files) {
            try {
                file.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        files.clear();
        segments.invalidateAll();
        segments.cleanUp();
        System.gc(); // for any memory-mappings that are no longer used
    }

    @Override
    public synchronized Journal getJournal(final String name) {
        Journal journal = journals.get(name);
        if (journal == null) {
            journal = new FileJournal(this, "root");
            journals.put(name, journal);
        }
        return journal;
    }

    @Override
    public Segment readSegment(final UUID id) {
        try {
            return segments.get(id, new Callable<Segment>() {
                @Override
                public Segment call() throws Exception {
                    for (TarFile file : files) {
                        ByteBuffer buffer = file.readEntry(id);
                        if (buffer != null) {
                            checkState(SEGMENT_MAGIC == buffer.getLong());
                            int length = buffer.getInt();
                            int count = buffer.getInt();

                            checkState(id.equals(new UUID(
                                    buffer.getLong(), buffer.getLong())));

                            Collection<UUID> referencedIds =
                                    newArrayListWithCapacity(count);
                            for (int i = 0; i < count; i++) {
                                referencedIds.add(new UUID(
                                        buffer.getLong(), buffer.getLong()));
                            }

                            buffer.limit(buffer.position() + length);
                            return new Segment(
                                    FileStore.this, id,
                                    buffer.slice(), referencedIds,
                                    Collections.<String, RecordId>emptyMap(),
                                    Collections.<Template, RecordId>emptyMap());
                        }
                    }
                    throw new IllegalStateException(
                            "Segment " + id + " not found");
                }
            });
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to load segment " + id, e);
        }
    }

    @Override
    public synchronized void createSegment(
            UUID segmentId, byte[] data, int offset, int length,
            Collection<UUID> referencedSegmentIds,
            Map<String, RecordId> strings, Map<Template, RecordId> templates) {
        int size = 8 + 4 + 4 + 16 + 16 * referencedSegmentIds.size() + length;
        ByteBuffer buffer = ByteBuffer.allocate(size);

        buffer.putLong(SEGMENT_MAGIC);
        buffer.putInt(length);
        buffer.putInt(referencedSegmentIds.size());
        buffer.putLong(segmentId.getMostSignificantBits());
        buffer.putLong(segmentId.getLeastSignificantBits());
        for (UUID referencedSegmentId : referencedSegmentIds) {
            buffer.putLong(referencedSegmentId.getMostSignificantBits());
            buffer.putLong(referencedSegmentId.getLeastSignificantBits());
        }

        int pos = buffer.position();
        buffer.put(data, offset, length);

        try {
            writeEntry(segmentId, buffer.array());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        buffer.position(pos);
        segments.put(segmentId, new Segment(
                this, segmentId, buffer.slice(),
                referencedSegmentIds, strings, templates));
    }

    private void writeEntry(UUID segmentId, byte[] buffer)
            throws IOException {
        if (files.isEmpty() || !files.getLast().writeEntry(
                segmentId, buffer, 0, buffer.length)) {
            String name = String.format(FILE_NAME_FORMAT, files.size());
            TarFile last = new TarFile(new File(directory, name), FILE_SIZE);
            checkState(last.writeEntry(segmentId, buffer, 0, buffer.length));
            files.add(last);
        }
    }

    @Override
    public void deleteSegment(UUID segmentId) {
        // TODO: implement
    }

    synchronized void writeJournals() throws IOException {
        int size = 8 + 4;
        for (String name : journals.keySet()) {
            size += 4 + name.getBytes(UTF_8).length + 16 + 4;
        }

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putLong(JOURNAL_MAGIC);
        buffer.putInt(journals.size());
        for (Map.Entry<String, Journal> entry : journals.entrySet()) {
            byte[] name = entry.getKey().getBytes(UTF_8);
            buffer.putInt(name.length);
            buffer.put(name);
            RecordId head = entry.getValue().getHead();
            buffer.putLong(head.getSegmentId().getMostSignificantBits());
            buffer.putLong(head.getSegmentId().getLeastSignificantBits());
            buffer.putInt(head.getOffset());
        }

        writeEntry(JOURNALS_UUID, buffer.array());
    }

}
