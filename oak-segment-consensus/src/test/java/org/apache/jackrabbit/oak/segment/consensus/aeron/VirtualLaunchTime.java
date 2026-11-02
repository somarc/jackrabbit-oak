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

import java.util.function.LongSupplier;

/** A launcher clock whose pauses advance it instantly, so a launch can wait out a liveness timeout in a test. */
final class VirtualLaunchTime implements LongSupplier, AeronClusterLauncher.Pause {

    private long now = 1_000_000L;
    private long paused;
    private int pauses;

    @Override
    public long getAsLong() {
        return now;
    }

    @Override
    public void pause(long millis) {
        now += millis;
        paused += millis;
        pauses++;
    }

    long paused() {
        return paused;
    }

    int pauses() {
        return pauses;
    }
}
