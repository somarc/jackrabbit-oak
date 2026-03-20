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
package org.apache.jackrabbit.oak.segment.consensus.config;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

@Component(
    service = FileStoreFlushTuningService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true
)
@Designate(ocd = FileStoreFlushTuningConfig.class)
public final class FileStoreFlushTuningService {

    private static final Logger log = LoggerFactory.getLogger(FileStoreFlushTuningService.class);
    private static final String COMPONENT = "fileStoreFlushTuning";
    private static final String NAMESPACE = FileStoreFlushTuningService.class.getName();
    private static final String FALLBACK_SOURCE = "system-properties";
    private static final String PROP_FLUSH_INTERVAL_MS = "oak.filestore.flush.ms";
    private static final String PROP_FLUSH_BATCH = "oak.filestore.flush.batch";

    @Activate
    protected void activate(FileStoreFlushTuningConfig config) {
        apply(config);
    }

    @Modified
    protected void modified(FileStoreFlushTuningConfig config) {
        apply(config);
    }

    @Deactivate
    protected void deactivate() {
        RuntimePropertyOverrideRegistry.clear(NAMESPACE);
        RuntimePropertySourceRegistry.markSource(COMPONENT, FALLBACK_SOURCE);
        log.info("FILESTORE_FLUSH_TUNING_SOURCE source={} action=deactivate", FALLBACK_SOURCE);
    }

    private void apply(FileStoreFlushTuningConfig config) {
        Map<String, String> overrides = new LinkedHashMap<>();
        if (config.flush_interval_ms() >= 0) {
            overrides.put(PROP_FLUSH_INTERVAL_MS, String.valueOf(config.flush_interval_ms()));
        }
        if (config.flush_batch() >= 0) {
            overrides.put(PROP_FLUSH_BATCH, String.valueOf(config.flush_batch()));
        }

        RuntimePropertyOverrideRegistry.setOverrides(NAMESPACE, overrides);
        RuntimePropertySourceRegistry.markSource(COMPONENT, overrides.isEmpty() ? FALLBACK_SOURCE : "osgi-config-admin");
        log.info("FILESTORE_FLUSH_TUNING_SOURCE source={} overrides={}",
            RuntimePropertySourceRegistry.getSource(COMPONENT, FALLBACK_SOURCE), overrides.keySet());
    }
}
