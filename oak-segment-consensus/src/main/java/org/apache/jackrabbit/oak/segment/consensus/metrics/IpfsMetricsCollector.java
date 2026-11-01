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
package org.apache.jackrabbit.oak.segment.consensus.metrics;

import io.prometheus.client.Collector;
import io.prometheus.client.CounterMetricFamily;
import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Exports the IPFS DataStore's operation counts as Prometheus counters, read
 * at scrape time without calling IPFS.
 */
public final class IpfsMetricsCollector extends Collector {

    public static final IpfsMetricsCollector INSTANCE = new IpfsMetricsCollector().register();

    private static final String[][] COUNTERS = {
        {"writes", "oak_ipfs_writes_total", "Binary writes stored in IPFS"},
        {"writtenBytes", "oak_ipfs_written_bytes_total", "Bytes of binaries written to IPFS"},
        {"writeFailures", "oak_ipfs_write_failures_total", "Binary writes that failed"},
        {"reads", "oak_ipfs_reads_total", "Binary reads opened from IPFS"},
        {"readFailures", "oak_ipfs_read_failures_total", "Binary reads that failed to open"},
        {"deletes", "oak_ipfs_deletes_total", "Binary records deleted"},
        {"deleteFailures", "oak_ipfs_delete_failures_total", "Binary deletes that failed"},
        {"clusterPins", "oak_ipfs_cluster_pins_total", "Writes replicated to the IPFS Cluster minimum"},
        {"clusterPinFailures", "oak_ipfs_cluster_pin_failures_total", "Writes that did not reach the IPFS Cluster minimum"},
    };

    private volatile Supplier<IPFSDataStore> source = () -> null;

    private IpfsMetricsCollector() {
    }

    /**
     * Sets where the counts come from; the supplier may return {@code null}.
     */
    public void source(Supplier<IPFSDataStore> source) {
        this.source = source;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> samples = new ArrayList<>();
        IPFSDataStore dataStore = source.get();
        if (dataStore == null) {
            return samples;
        }
        Map<String, Long> statistics = dataStore.getStatistics();
        for (String[] counter : COUNTERS) {
            Long value = statistics.get(counter[0]);
            if (value != null) {
                samples.add(new CounterMetricFamily(counter[1], counter[2], value));
            }
        }
        Long pinMillis = statistics.get("clusterPinMillis");
        if (pinMillis != null) {
            samples.add(new CounterMetricFamily("oak_ipfs_cluster_pin_seconds_total",
                "Time writes waited for IPFS Cluster replication", pinMillis / 1000.0));
        }
        return samples;
    }
}
