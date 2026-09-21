/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.mount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Circuit breaker for remote service calls.
 * <p>
 * Implements the circuit breaker pattern to prevent cascading failures
 * when a remote cluster is unavailable. States:
 * <ul>
 *   <li><b>CLOSED</b> - Normal operation, requests pass through</li>
 *   <li><b>OPEN</b> - Failing fast, requests immediately rejected</li>
 *   <li><b>HALF_OPEN</b> - Testing if service recovered</li>
 * </ul>
 * <p>
 * Configuration:
 * <ul>
 *   <li>failureThreshold: Number of failures before opening (default: 5)</li>
 *   <li>resetTimeoutMs: Time before trying again (default: 60s)</li>
 *   <li>successThreshold: Successes needed to close (default: 3)</li>
 * </ul>
 */
public class CircuitBreaker {
    
    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreaker.class);
    
    /**
     * Circuit breaker states.
     */
    public enum State {
        CLOSED,     // Normal operation
        OPEN,       // Failing fast
        HALF_OPEN   // Testing recovery
    }
    
    private final String name;
    private final int failureThreshold;
    private final long resetTimeoutMs;
    private final int successThreshold;
    
    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong openedAt = new AtomicLong(0);
    
    // Metrics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);
    
    /**
     * Create a circuit breaker with default settings.
     *
     * @param name Name for logging
     */
    public CircuitBreaker(String name) {
        this(name, 5, 60_000, 3);
    }
    
    /**
     * Create a circuit breaker with custom settings.
     *
     * @param name Name for logging
     * @param failureThreshold Failures before opening
     * @param resetTimeoutMs Time before half-open (ms)
     * @param successThreshold Successes to close from half-open
     */
    public CircuitBreaker(String name, int failureThreshold, long resetTimeoutMs, int successThreshold) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
        this.successThreshold = successThreshold;
        
        LOG.info("CircuitBreaker[{}] initialized: threshold={}, timeout={}ms, successThreshold={}",
            name, failureThreshold, resetTimeoutMs, successThreshold);
    }
    
    /**
     * Check if a request should be allowed.
     *
     * @return true if request can proceed, false if circuit is open
     */
    public boolean allowRequest() {
        totalRequests.incrementAndGet();
        
        switch (state) {
            case CLOSED:
                return true;
                
            case OPEN:
                // Check if timeout has elapsed
                if (System.currentTimeMillis() - openedAt.get() >= resetTimeoutMs) {
                    transitionTo(State.HALF_OPEN);
                    return true;
                }
                totalRejected.incrementAndGet();
                return false;
                
            case HALF_OPEN:
                // Allow limited requests to test recovery
                return true;
                
            default:
                return true;
        }
    }
    
    /**
     * Record a successful request.
     */
    public void recordSuccess() {
        switch (state) {
            case CLOSED:
                failureCount.set(0);
                break;
                
            case HALF_OPEN:
                int successes = successCount.incrementAndGet();
                if (successes >= successThreshold) {
                    transitionTo(State.CLOSED);
                }
                break;
                
            case OPEN:
                // Shouldn't happen, but reset anyway
                break;
        }
    }
    
    /**
     * Record a failed request.
     *
     * @param error The error that occurred
     */
    public void recordFailure(Throwable error) {
        totalFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        switch (state) {
            case CLOSED:
                int failures = failureCount.incrementAndGet();
                if (failures >= failureThreshold) {
                    transitionTo(State.OPEN);
                }
                break;
                
            case HALF_OPEN:
                // Single failure in half-open goes back to open
                transitionTo(State.OPEN);
                break;
                
            case OPEN:
                // Already open, nothing to do
                break;
        }
        
        LOG.debug("CircuitBreaker[{}] failure recorded: {} (state={}, count={})",
            name, error.getMessage(), state, failureCount.get());
    }
    
    /**
     * Transition to a new state.
     */
    private synchronized void transitionTo(State newState) {
        if (state == newState) {
            return;
        }
        
        State oldState = state;
        state = newState;
        
        switch (newState) {
            case OPEN:
                openedAt.set(System.currentTimeMillis());
                LOG.warn("🔴 CircuitBreaker[{}] OPENED after {} failures", name, failureCount.get());
                break;
                
            case HALF_OPEN:
                successCount.set(0);
                LOG.info("🟡 CircuitBreaker[{}] HALF-OPEN, testing recovery...", name);
                break;
                
            case CLOSED:
                failureCount.set(0);
                successCount.set(0);
                LOG.info("🟢 CircuitBreaker[{}] CLOSED, service recovered", name);
                break;
        }
    }
    
    /**
     * Get current state.
     *
     * @return Current state
     */
    public State getState() {
        return state;
    }
    
    /**
     * Check if circuit is open (failing fast).
     *
     * @return true if open
     */
    public boolean isOpen() {
        return state == State.OPEN;
    }
    
    /**
     * Check if circuit is closed (normal operation).
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return state == State.CLOSED;
    }
    
    /**
     * Force the circuit to open.
     */
    public void forceOpen() {
        transitionTo(State.OPEN);
    }
    
    /**
     * Force the circuit to close.
     */
    public void forceClose() {
        transitionTo(State.CLOSED);
    }
    
    /**
     * Reset the circuit breaker to initial state.
     */
    public void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime.set(0);
        openedAt.set(0);
        LOG.info("CircuitBreaker[{}] reset", name);
    }
    
    /**
     * Get the circuit breaker name.
     *
     * @return Name
     */
    public String getName() {
        return name;
    }
    
    /**
     * Get current failure count.
     *
     * @return Failure count
     */
    public int getFailureCount() {
        return failureCount.get();
    }
    
    /**
     * Get total requests.
     *
     * @return Total request count
     */
    public long getTotalRequests() {
        return totalRequests.get();
    }
    
    /**
     * Get total failures.
     *
     * @return Total failure count
     */
    public long getTotalFailures() {
        return totalFailures.get();
    }
    
    /**
     * Get total rejected requests.
     *
     * @return Total rejected count
     */
    public long getTotalRejected() {
        return totalRejected.get();
    }
    
    /**
     * Get time since circuit opened (if open).
     *
     * @return Milliseconds since opened, or 0 if not open
     */
    public long getTimeSinceOpened() {
        if (state != State.OPEN) {
            return 0;
        }
        return System.currentTimeMillis() - openedAt.get();
    }
    
    @Override
    public String toString() {
        return String.format(
            "CircuitBreaker[%s]{state=%s, failures=%d/%d, requests=%d, rejected=%d}",
            name, state, failureCount.get(), failureThreshold, totalRequests.get(), totalRejected.get()
        );
    }
}
