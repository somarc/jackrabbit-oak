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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Oak FileStore Flush Runtime Tuning",
    description = "Optional OSGi overrides for FileStore flush batching."
)
public @interface FileStoreFlushTuningConfig {

    @AttributeDefinition(
        name = "Flush Interval (ms)",
        description = "Override oak.filestore.flush.ms. Negative preserves existing behavior; 0 disables scheduled flush cadence."
    )
    long flush_interval_ms() default -1;

    @AttributeDefinition(
        name = "Flush Batch",
        description = "Override oak.filestore.flush.batch. Negative preserves existing behavior."
    )
    int flush_batch() default -1;
}
