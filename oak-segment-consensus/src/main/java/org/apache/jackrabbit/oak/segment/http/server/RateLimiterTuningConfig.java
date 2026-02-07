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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Oak HTTP Rate Limiter Tuning",
    description = "Rate-limiter throughput and log-volume tuning."
)
public @interface RateLimiterTuningConfig {

    @AttributeDefinition(
        name = "Enabled",
        description = "Enable HTTP rate limiting."
    )
    boolean enabled() default true;

    @AttributeDefinition(
        name = "Requests Per Second",
        description = "Per-client request rate."
    )
    int requests_per_second() default 100;

    @AttributeDefinition(
        name = "Burst Size",
        description = "Per-client burst capacity."
    )
    int burst_size() default 200;

    @AttributeDefinition(
        name = "Global Requests Per Second",
        description = "Global request rate limit."
    )
    int global_rps() default 1000;

    @AttributeDefinition(
        name = "Write Requests Per Second",
        description = "Per-wallet write request rate."
    )
    int write_rps() default 10;

    @AttributeDefinition(
        name = "Warn Logging Enabled",
        description = "Emit WARN summaries for throttling."
    )
    boolean warn_logging_enabled() default true;

    @AttributeDefinition(
        name = "Warn Log Interval (ms)",
        description = "Minimum interval between throttling WARN summaries."
    )
    long warn_log_interval_ms() default 30000;

    @AttributeDefinition(
        name = "Warn Log Sample Size",
        description = "Emit summary when this many throttles accumulate before interval."
    )
    int warn_log_sample_size() default 250;
}
