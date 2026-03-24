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
package org.apache.jackrabbit.oak.segment.http;

import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OSGi component that publishes {@link HttpPersistence} as a
 * {@link SegmentNodeStorePersistence} service.
 *
 * <p>In the default mode the persistence service is registered during
 * activation. When {@code lazyMount=true}, registration is delayed until a
 * health probe can reach the remote validator. That lets Sling and the rest of
 * the bundle graph start without blocking on a temporarily unavailable remote
 * store.</p>
 */
@Component(
    // NOTE: When lazyMount=true, we register SegmentNodeStorePersistence dynamically
    // When lazyMount=false (default), we act as SegmentNodeStorePersistence immediately
    service = HttpPersistenceService.class,
    configurationPolicy = ConfigurationPolicy.REQUIRE
)
@Designate(ocd = HttpPersistenceService.Configuration.class)
public class HttpPersistenceService implements SegmentNodeStorePersistence {

    @ObjectClassDefinition(
        name = "HTTP Segment Persistence Configuration",
        description = "Configures HTTP-based segment persistence for read-only remote access"
    )
    public @interface Configuration {
        @AttributeDefinition(
            name = "Global Store URL",
            description = "Base URL of the remote GlobalStoreServer (e.g., http://oak-global-store:8090)"
        )
        String globalStoreUrl();

        @AttributeDefinition(
            name = "Lazy Mount",
            description = "If true, defer SegmentNodeStorePersistence registration until validator is reachable. " +
                          "This allows Sling to start even if the validator is down."
        )
        boolean lazyMount() default false;

        @AttributeDefinition(
            name = "Health Check Interval (seconds)",
            description = "How often to check if the validator is reachable (only used when lazyMount=true)"
        )
        int healthCheckIntervalSeconds() default 10;

        @AttributeDefinition(
            name = "Connection Timeout (ms)",
            description = "Timeout for connecting to the validator during health checks"
        )
        int connectionTimeoutMs() default 3000;
    }

    private static final Logger log = LoggerFactory.getLogger(HttpPersistenceService.class);

    private final ValidatorHealthProbe healthProbe;
    private HttpPersistence delegate;
    private String globalStoreUrl;
    private boolean lazyMount;
    private int healthCheckIntervalSeconds;
    private int connectionTimeoutMs;
    
    // State used only when lazy registration is enabled.
    private BundleContext bundleContext;
    private ServiceRegistration<SegmentNodeStorePersistence> persistenceRegistration;
    private Thread healthCheckThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean validatorAvailable = new AtomicBoolean(false);

    /**
     * Creates the component with the default HTTP-based health probe.
     */
    public HttpPersistenceService() {
        this(new HttpValidatorHealthProbe());
    }

    /**
     * Testing seam that injects a custom validator health probe.
     */
    HttpPersistenceService(ValidatorHealthProbe healthProbe) {
        this.healthProbe = healthProbe;
    }

    @Activate
    protected void activate(BundleContext bundleContext, Configuration config) {
        this.bundleContext = bundleContext;
        this.globalStoreUrl = config.globalStoreUrl();
        this.lazyMount = config.lazyMount();
        this.healthCheckIntervalSeconds = config.healthCheckIntervalSeconds();
        this.connectionTimeoutMs = config.connectionTimeoutMs();

        log.info("Activating HTTP Segment Persistence");
        log.info("  Global Store URL: {}", globalStoreUrl);
        log.info("  Lazy Mount: {}", lazyMount);
        if (lazyMount) {
            log.info("  Health Check Interval: {}s", healthCheckIntervalSeconds);
            log.info("  Connection Timeout: {}ms", connectionTimeoutMs);
        }

        this.delegate = new HttpPersistence(globalStoreUrl);

        if (lazyMount) {
            // Don't block startup - start background health check
            log.info("Lazy mount enabled - starting background validator health check");
            log.info("⏳ SegmentNodeStorePersistence will be registered when validator becomes available");
            startHealthCheckThread();
        } else {
            // Immediate mode - register as SegmentNodeStorePersistence now
            // This maintains backward compatibility
            log.info("Immediate mount mode - registering SegmentNodeStorePersistence now");
            registerPersistenceService();
        }

        log.info("HTTP Segment Persistence activated successfully");
    }

    /**
     * Starts a daemon thread that polls validator health until registration
     * succeeds or the component is deactivated.
     */
    private void startHealthCheckThread() {
        healthCheckThread = new Thread(() -> {
            log.info("Health check thread started for: {}", globalStoreUrl);
            
            while (running.get() && !validatorAvailable.get()) {
                if (checkValidatorHealth()) {
                    log.info("✅ Validator is now reachable at: {}", globalStoreUrl);
                    validatorAvailable.set(true);
                    registerPersistenceService();
                    log.info("✅ SegmentNodeStorePersistence registered - oak-chain mount will now initialize");
                    break;
                } else {
                    log.debug("Validator not yet reachable, will retry in {}s", healthCheckIntervalSeconds);
                }
                
                try {
                    Thread.sleep(healthCheckIntervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            log.info("Health check thread finished");
        }, "http-persistence-health-check");
        
        healthCheckThread.setDaemon(true);
        healthCheckThread.start();
    }

    /**
     * Probes the configured validator endpoint using the configured timeout.
     *
     * @return {@code true} when the validator responds successfully
     */
    private boolean checkValidatorHealth() {
        return healthProbe.isAvailable(globalStoreUrl, connectionTimeoutMs);
    }

    /**
     * Publishes this component as a {@link SegmentNodeStorePersistence} service.
     *
     * <p>Registration happens once per activation and is the signal that allows
     * dependent services to build the remote mount.</p>
     */
    private void registerPersistenceService() {
        if (persistenceRegistration != null) {
            log.warn("SegmentNodeStorePersistence already registered");
            return;
        }

        Dictionary<String, Object> props = new Hashtable<>();
        props.put("globalStoreUrl", globalStoreUrl);
        
        persistenceRegistration = bundleContext.registerService(
            SegmentNodeStorePersistence.class,
            this,
            props
        );
        
        log.info("Registered SegmentNodeStorePersistence service for: {}", globalStoreUrl);
    }

    /**
     * Withdraws the dynamic {@link SegmentNodeStorePersistence} registration.
     */
    private void unregisterPersistenceService() {
        if (persistenceRegistration != null) {
            try {
                persistenceRegistration.unregister();
                log.info("Unregistered SegmentNodeStorePersistence service");
            } catch (IllegalStateException e) {
                // Already unregistered
                log.debug("Service was already unregistered");
            }
            persistenceRegistration = null;
        }
    }
    
    /**
     * Indicates whether the validator has been observed as reachable.
     *
     * @return {@code true} when the latest successful probe has completed
     */
    public boolean isValidatorAvailable() {
        return validatorAvailable.get();
    }
    
    /**
     * Indicates whether lazy registration mode is enabled.
     *
     * @return {@code true} when registration is deferred behind health checks
     */
    public boolean isLazyMount() {
        return lazyMount;
    }
    
    /**
     * Returns the configured remote persistence URL.
     *
     * @return the base URL used by the delegate persistence
     */
    public String getGlobalStoreUrl() {
        return globalStoreUrl;
    }

    @Deactivate
    protected void deactivate() {
        log.info("Deactivating HTTP Segment Persistence");
        
        // Stop health check thread
        running.set(false);
        if (healthCheckThread != null) {
            healthCheckThread.interrupt();
            try {
                healthCheckThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Unregister persistence service
        unregisterPersistenceService();
        
        this.delegate = null;
        log.info("HTTP Segment Persistence deactivated");
    }

    // SegmentNodeStorePersistence methods are fulfilled by the activated delegate.

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager createArchiveManager(
            boolean mmap, boolean offHeapAccess,
            org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor ioMonitor,
            org.apache.jackrabbit.oak.segment.spi.monitor.FileStoreMonitor fileStoreMonitor,
            org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitor remoteStoreMonitor) {
        return delegate.createArchiveManager(mmap, offHeapAccess, ioMonitor, fileStoreMonitor, remoteStoreMonitor);
    }

    @Override
    public boolean segmentFilesExist() {
        return delegate.segmentFilesExist();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile getJournalFile() {
        return delegate.getJournalFile();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile getGCJournalFile() throws java.io.IOException {
        return delegate.getGCJournalFile();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile getManifestFile() throws java.io.IOException {
        return delegate.getManifestFile();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.RepositoryLock lockRepository() throws java.io.IOException {
        return delegate.lockRepository();
    }
}
