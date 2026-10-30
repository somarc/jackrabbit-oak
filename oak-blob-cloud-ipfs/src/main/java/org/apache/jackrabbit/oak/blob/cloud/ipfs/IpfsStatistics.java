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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cumulative counts of IPFS backend operations since the backend started.
 */
final class IpfsStatistics {

    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong writtenBytes = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong readFailures = new AtomicLong();
    private final AtomicLong deletes = new AtomicLong();
    private final AtomicLong deleteFailures = new AtomicLong();
    private final AtomicLong clusterPins = new AtomicLong();
    private final AtomicLong clusterPinFailures = new AtomicLong();
    private final AtomicLong clusterPinMillis = new AtomicLong();

    void written(long bytes) {
        writes.incrementAndGet();
        writtenBytes.addAndGet(bytes);
    }

    void writeFailed() {
        writeFailures.incrementAndGet();
    }

    void read() {
        reads.incrementAndGet();
    }

    void readFailed() {
        readFailures.incrementAndGet();
    }

    void deleted() {
        deletes.incrementAndGet();
    }

    void deleteFailed() {
        deleteFailures.incrementAndGet();
    }

    void clusterPinned(long millis, boolean success) {
        (success ? clusterPins : clusterPinFailures).incrementAndGet();
        clusterPinMillis.addAndGet(millis);
    }

    /**
     * Returns the counters by name; every value only grows.
     */
    Map<String, Long> snapshot() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("writes", writes.get());
        values.put("writtenBytes", writtenBytes.get());
        values.put("writeFailures", writeFailures.get());
        values.put("reads", reads.get());
        values.put("readFailures", readFailures.get());
        values.put("deletes", deletes.get());
        values.put("deleteFailures", deleteFailures.get());
        values.put("clusterPins", clusterPins.get());
        values.put("clusterPinFailures", clusterPinFailures.get());
        values.put("clusterPinMillis", clusterPinMillis.get());
        return values;
    }
}
