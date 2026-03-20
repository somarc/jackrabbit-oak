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

import io.aeron.Aeron;
import io.aeron.driver.status.SystemCounterDescriptor;
import io.prometheus.client.Gauge;
import org.agrona.concurrent.status.CountersReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.aeron.driver.status.StreamCounter.CHANNEL_OFFSET;
import static io.aeron.driver.status.StreamCounter.SESSION_ID_OFFSET;
import static io.aeron.driver.status.StreamCounter.STREAM_ID_OFFSET;
import static io.aeron.driver.status.PublisherLimit.PUBLISHER_LIMIT_TYPE_ID;
import static io.aeron.driver.status.PublisherPos.PUBLISHER_POS_TYPE_ID;
import static io.aeron.driver.status.ReceiverPos.RECEIVER_POS_TYPE_ID;
import static io.aeron.driver.status.SenderLimit.SENDER_LIMIT_TYPE_ID;
import static io.aeron.driver.status.PerImageIndicator.PER_IMAGE_TYPE_ID;
import static org.agrona.concurrent.status.CountersReader.KEY_OFFSET;
import static org.agrona.concurrent.status.CountersReader.MAX_KEY_LENGTH;
import static org.agrona.concurrent.status.CountersReader.RECORD_ALLOCATED;
import static org.agrona.concurrent.status.CountersReader.metaDataOffset;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Exposes Aeron Cluster metrics to Prometheus.
 * 
 * <p>Reads from Aeron's CountersReader and exposes metrics via Prometheus SimpleClient
 * for monitoring cluster health, message throughput, and error rates.
 * 
 * <p>Tracks:
 * <ul>
 *   <li>System counters (MediaDriver health, errors, etc.)</li>
 *   <li>Stream counters (publisher/subscriber positions, limits)</li>
 *   <li>Dynamic stream registration (adds/removes streams as they appear/disappear)</li>
 * </ul>
 */
public class AeronPrometheusMetrics implements AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(AeronPrometheusMetrics.class);
    private static final String AERON_METRICS_THREAD_NAME = "aeron-prometheus-metrics";
    
    private final CountersReader reader;
    private final ScheduledExecutorService executor;
    private final List<Gauge> systemGauges = new ArrayList<>();
    private final Map<Integer, StreamGauge> streamGauges = new ConcurrentHashMap<>();
    private volatile boolean closed = false;
    
    /**
     * Create Aeron metrics exporter.
     * 
     * @param aeron Aeron instance (from ClusteredServiceContainer)
     */
    public AeronPrometheusMetrics(Aeron aeron) {
        if (aeron == null) {
            throw new IllegalArgumentException("Aeron instance cannot be null");
        }
        this.reader = aeron.countersReader();
        
        // Register system counters
        registerSystemCounters();
        
        // Start background thread to update stream counters
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, AERON_METRICS_THREAD_NAME);
            t.setDaemon(true);
            return t;
        });
        
        executor.scheduleWithFixedDelay(this::updateStreamCounters, 0, 10, TimeUnit.SECONDS);
        
        log.info("✅ Aeron Prometheus metrics initialized");
        log.info("   System counters: {}", systemGauges.size());
    }
    
    /**
     * Register all Aeron system counters as Prometheus gauges.
     */
    private void registerSystemCounters() {
        for (SystemCounterDescriptor scd : SystemCounterDescriptor.values()) {
            try {
                Gauge gauge = Gauge.build()
                    .name("aeron_" + sanitize(scd.name()))
                    .help(scd.label())
                    .register();
                
                systemGauges.add(gauge);
                
                log.debug("Registered Aeron system counter: {}", scd.name());
            } catch (Exception e) {
                // Some system counters may not be available immediately or may not exist
                // This is normal - log at debug level instead of warn to reduce noise
                log.debug("Skipping Aeron system counter {}: {}", scd.name(), e.getMessage());
            }
        }
    }
    
    /**
     * Update all metrics (system counters and stream counters).
     * Called periodically and before Prometheus scrape.
     */
    public void updateMetrics() {
        if (closed) {
            return;
        }
        
        // Update system counters
        int index = 0;
        for (SystemCounterDescriptor scd : SystemCounterDescriptor.values()) {
            try {
                if (index < systemGauges.size()) {
                    long value = reader.getCounterValue(scd.id());
                    systemGauges.get(index).set(value);
                }
            } catch (Exception e) {
                log.debug("Failed to read system counter: {}", scd.name(), e);
            }
            index++;
        }
        
        // Update stream counters
        updateStreamCounters();
    }
    
    /**
     * Update stream counters (publishers, subscribers, etc.).
     * Dynamically registers/unregisters streams as they appear/disappear.
     */
    private void updateStreamCounters() {
        if (closed) {
            return;
        }
        
        try {
            java.util.Set<Integer> activeStreamCounterIds = new java.util.HashSet<>();
            
            reader.forEach((counterId, label) -> {
                if (registerIfStreamCounter(counterId)) {
                    activeStreamCounterIds.add(counterId);
                }
            });
            
            // Update existing stream counter values
            for (StreamGauge streamGauge : streamGauges.values()) {
                try {
                    if (reader.getCounterState(streamGauge.counterId) == RECORD_ALLOCATED) {
                        long value = reader.getCounterValue(streamGauge.counterId);
                        streamGauge.gauge.labels(
                            String.valueOf(streamGauge.sessionId),
                            String.valueOf(streamGauge.streamId),
                            streamGauge.channel
                        ).set(value);
                    }
                } catch (Exception e) {
                    log.debug("Failed to update stream counter gauge {}", streamGauge.counterId, e);
                }
            }
            
            // Remove gauges for counters that no longer exist
            streamGauges.keySet().stream()
                .filter(id -> !activeStreamCounterIds.contains(id))
                .forEach(id -> {
                    streamGauges.remove(id);
                    // Note: Prometheus SimpleClient doesn't support unregistering metrics
                    // Gauges will remain but won't be updated (set to 0 or last value)
                    log.debug("Stream counter {} no longer active", id);
                });
        } catch (Exception e) {
            log.debug("Failed to update stream counters", e);
        }
    }
    
    /**
     * Register a stream counter if it matches known types.
     */
    private boolean registerIfStreamCounter(int counterId) {
        int typeId = reader.getCounterTypeId(counterId);
        
        // Check if this is a stream counter type we care about
        if ((typeId >= PUBLISHER_LIMIT_TYPE_ID && typeId <= RECEIVER_POS_TYPE_ID)
            || typeId == SENDER_LIMIT_TYPE_ID
            || typeId == PER_IMAGE_TYPE_ID
            || typeId == PUBLISHER_POS_TYPE_ID) {
            
            // Already registered?
            if (streamGauges.containsKey(counterId)) {
                return true;
            }
            
            try {
                DirectBuffer keyBuffer = new UnsafeBuffer(
                    reader.metaDataBuffer(), 
                    metaDataOffset(counterId) + KEY_OFFSET, 
                    MAX_KEY_LENGTH);
                
                String label = reader.getCounterLabel(counterId);
                int sessionId = keyBuffer.getInt(SESSION_ID_OFFSET);
                int streamId = keyBuffer.getInt(STREAM_ID_OFFSET);
                String channel = keyBuffer.getStringAscii(CHANNEL_OFFSET);
                
                String metricName = streamMetricName(typeId);
                
                // Create Prometheus gauge with labels
                Gauge gauge = Gauge.build()
                    .name("aeron_" + sanitize(metricName))
                    .help(label)
                    .labelNames("session_id", "stream_id", "channel")
                    .register();
                
                // Initialize gauge value
                gauge.labels(
                    String.valueOf(sessionId),
                    String.valueOf(streamId),
                    channel
                ).set(0);
                
                streamGauges.put(counterId, new StreamGauge(gauge, counterId, sessionId, streamId, channel));
                
                log.debug("Registered Aeron stream counter: {} (sessionId={}, streamId={}, channel={})", 
                    metricName, sessionId, streamId, channel);
                
                return true;
            } catch (Exception e) {
                log.debug("Failed to register stream counter {}", counterId, e);
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * Update all gauge values before Prometheus scrape.
     * This should be called by MetricsHandler before exporting metrics.
     */
    public void updateGaugeValues() {
        updateMetrics(); // Same as updateMetrics()
    }

    private static String streamMetricName(int typeId) {
        if (typeId == PUBLISHER_LIMIT_TYPE_ID) {
            return "pub_limit";
        }
        if (typeId == SENDER_LIMIT_TYPE_ID) {
            return "snd_limit";
        }
        if (typeId == RECEIVER_POS_TYPE_ID) {
            return "rcv_pos";
        }
        if (typeId == PER_IMAGE_TYPE_ID) {
            return "sub_pos";
        }
        if (typeId == PUBLISHER_POS_TYPE_ID) {
            return "pub_pos";
        }
        return "stream_counter_" + typeId;
    }
    
    /**
     * Sanitize metric name for Prometheus (lowercase, replace _ with .).
     */
    private static String sanitize(String s) {
        return s.toLowerCase(Locale.ENGLISH).replace('_', '.');
    }
    
    /**
     * Close metrics exporter and cleanup resources.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        
        log.info("Closing Aeron Prometheus metrics exporter...");
        
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("✅ Aeron Prometheus metrics exporter closed");
    }
    
    /**
     * Internal class to hold stream gauge metadata.
     */
    private static class StreamGauge {
        final Gauge gauge;
        final int counterId;
        final int sessionId;
        final int streamId;
        final String channel;
        
        StreamGauge(Gauge gauge, int counterId, int sessionId, int streamId, String channel) {
            this.gauge = gauge;
            this.counterId = counterId;
            this.sessionId = sessionId;
            this.streamId = streamId;
            this.channel = channel;
        }
    }
}
