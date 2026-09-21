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
package org.apache.jackrabbit.oak.segment.consensus.eth;

/**
 * Source of Ethereum Beacon Chain epoch data.
 *
 * <p>Implementations are single-provider (beaconcha.in, local beacon node, etc.).
 * Failover and circuit-breaking are handled at a higher level by
 * {@link FallbackBeaconChainProvider}.
 */
public interface BeaconChainProvider {

    /**
     * Returns the current finalized epoch number.
     *
     * @return finalized epoch, or -1 if the provider cannot determine it
     * @throws Exception on unrecoverable fetch or parse error
     */
    long fetchFinalizedEpoch() throws Exception;

    /** Human-readable name for logging and health reporting. */
    String name();
}
