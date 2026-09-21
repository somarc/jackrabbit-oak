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

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(
    service = RateLimiterTuningService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true
)
@Designate(ocd = RateLimiterTuningConfig.class)
public final class RateLimiterTuningService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterTuningService.class);

    @Activate
    protected void activate(RateLimiterTuningConfig config) {
        applyConfig(config);
        RateLimiterTuningSourceRegistry.markOsgiSource();
        log.info("RATE_LIMIT_TUNING_SOURCE source=osgi-config-admin enabled={} rps={} burst={} globalRps={} writeRps={} warnLoggingEnabled={} warnLogIntervalMs={} warnLogSampleSize={}",
            config.enabled(),
            config.requests_per_second(),
            config.burst_size(),
            config.global_rps(),
            config.write_rps(),
            config.warn_logging_enabled(),
            config.warn_log_interval_ms(),
            config.warn_log_sample_size());
    }

    @Modified
    protected void modified(RateLimiterTuningConfig config) {
        activate(config);
        log.info("RateLimiterTuningService modified");
    }

    private void applyConfig(RateLimiterTuningConfig config) {
        System.setProperty(RateLimiter.PROP_ENABLED, String.valueOf(config.enabled()));
        System.setProperty(RateLimiter.PROP_REQUESTS_PER_SECOND, String.valueOf(config.requests_per_second()));
        System.setProperty(RateLimiter.PROP_BURST_SIZE, String.valueOf(config.burst_size()));
        System.setProperty(RateLimiter.PROP_GLOBAL_RPS, String.valueOf(config.global_rps()));
        System.setProperty(RateLimiter.PROP_WRITE_RPS, String.valueOf(config.write_rps()));
        System.setProperty(RateLimiter.PROP_WARN_LOGGING_ENABLED, String.valueOf(config.warn_logging_enabled()));
        System.setProperty(RateLimiter.PROP_WARN_LOG_INTERVAL_MS, String.valueOf(config.warn_log_interval_ms()));
        System.setProperty(RateLimiter.PROP_WARN_LOG_SAMPLE_SIZE, String.valueOf(config.warn_log_sample_size()));
    }
}
