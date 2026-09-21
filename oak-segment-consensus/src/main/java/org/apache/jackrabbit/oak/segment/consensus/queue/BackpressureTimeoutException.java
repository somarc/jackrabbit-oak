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
package org.apache.jackrabbit.oak.segment.consensus.queue;

/**
 * Exception thrown when backpressure timeout is exceeded.
 * 
 * <p>This occurs when the Aeron cluster cannot keep up with the rate of incoming
 * writes and the pending message queue remains full for longer than the configured
 * timeout period.
 * 
 * <p>This is a retryable error - clients should retry the operation after a delay.
 * 
 * <p>Indicates the Aeron Cluster is under heavy load and cannot process writes at the current rate.
 */
public class BackpressureTimeoutException extends RuntimeException {
    
    private final long pendingMessages;
    private final long maxPendingMessages;
    private final long timeoutMs;
    
    public BackpressureTimeoutException(
            long pendingMessages, 
            long maxPendingMessages, 
            long timeoutMs) {
        super(String.format(
            "Backpressure timeout (%d ms): pending messages (%d) exceeded max (%d). " +
            "Aeron cluster cannot keep up with write rate. This is a retryable error.",
            timeoutMs,
            pendingMessages,
            maxPendingMessages
        ));
        this.pendingMessages = pendingMessages;
        this.maxPendingMessages = maxPendingMessages;
        this.timeoutMs = timeoutMs;
    }
    
    public long getPendingMessages() {
        return pendingMessages;
    }
    
    public long getMaxPendingMessages() {
        return maxPendingMessages;
    }
    
    public long getTimeoutMs() {
        return timeoutMs;
    }
    
    public boolean isRetryable() {
        return true;
    }
}

