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

/**
 * Strategy for determining whether the remote validator is ready to serve the
 * HTTP segment mount.
 */
interface ValidatorHealthProbe {

    /**
     * Probes the configured validator endpoint within the given timeout budget.
     *
     * @param baseUrl base URL of the remote validator
     * @param timeoutMs per-request timeout in milliseconds
     * @return {@code true} when the validator is reachable and serving the
     *         expected endpoint
     */
    boolean isAvailable(String baseUrl, int timeoutMs);
}
