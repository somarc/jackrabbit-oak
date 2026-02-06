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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Tracks explicit transaction boundaries with persistence, timeout expiry,
 * idempotency, and replay-safe semantics.
 */
final class TransactionLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(TransactionLifecycleManager.class);

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final int DEFAULT_MAX_TERMINAL_ENTRIES = 10_000;

    private final Path stateFile;
    private final LongSupplier nowMs;
    private final int maxTerminalEntries;

    private final Map<String, TxRecord> active = new LinkedHashMap<>();
    private final LinkedHashMap<String, TxRecord> terminal = new LinkedHashMap<>();

    TransactionLifecycleManager(Path directory) {
        this(directory, System::currentTimeMillis, DEFAULT_MAX_TERMINAL_ENTRIES);
    }

    TransactionLifecycleManager(Path directory, LongSupplier nowMs, int maxTerminalEntries) {
        this.stateFile = directory.resolve("transaction-lifecycle.bin");
        this.nowMs = nowMs;
        this.maxTerminalEntries = Math.max(100, maxTerminalEntries);
        try {
            Files.createDirectories(directory);
        } catch (Exception e) {
            log.warn("Failed to create transaction lifecycle directory {}: {}", directory, e.getMessage());
        }
        load();
    }

    synchronized TransitionResult onStart(String transactionId, String correlationId, long timeoutMs, String initiatorWallet) {
        if (isBlank(transactionId)) {
            return TransitionResult.rejected("missing transactionId");
        }
        long now = nowMs.getAsLong();
        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;

        TxRecord activeRecord = active.get(transactionId);
        if (activeRecord != null) {
            if (activeRecord.status == TxStatus.STARTED) {
                return TransitionResult.idempotent(activeRecord.copy());
            }
            return TransitionResult.rejected("transaction is not startable in active map");
        }

        TxRecord terminalRecord = terminal.get(transactionId);
        if (terminalRecord != null) {
            return TransitionResult.rejected("transaction already terminal: " + terminalRecord.status);
        }

        TxRecord record = new TxRecord();
        record.transactionId = transactionId;
        record.correlationId = correlationId;
        record.initiatorWallet = initiatorWallet;
        record.status = TxStatus.STARTED;
        record.startedAtMs = now;
        record.timeoutMs = effectiveTimeout;
        record.deadlineMs = now + effectiveTimeout;
        active.put(transactionId, record);
        persist();
        return TransitionResult.applied(record.copy());
    }

    synchronized TransitionResult canStart(String transactionId) {
        if (isBlank(transactionId)) {
            return TransitionResult.rejected("missing transactionId");
        }
        TxRecord activeRecord = active.get(transactionId);
        if (activeRecord != null) {
            return activeRecord.status == TxStatus.STARTED
                ? TransitionResult.idempotent(activeRecord.copy())
                : TransitionResult.rejected("transaction is not startable in active map");
        }
        TxRecord terminalRecord = terminal.get(transactionId);
        if (terminalRecord != null) {
            return TransitionResult.rejected("transaction already terminal: " + terminalRecord.status);
        }
        return TransitionResult.applied(null);
    }

    synchronized TransitionResult onCommit(String transactionId, String correlationId) {
        if (isBlank(transactionId)) {
            return TransitionResult.rejected("missing transactionId");
        }
        expireInternal(nowMs.getAsLong());

        TxRecord activeRecord = active.remove(transactionId);
        if (activeRecord != null) {
            if (activeRecord.status != TxStatus.STARTED) {
                return TransitionResult.rejected("active transaction not in STARTED state");
            }
            activeRecord.status = TxStatus.COMMITTED;
            activeRecord.completedAtMs = nowMs.getAsLong();
            if (isBlank(activeRecord.correlationId)) {
                activeRecord.correlationId = correlationId;
            }
            addTerminal(activeRecord);
            persist();
            return TransitionResult.applied(activeRecord.copy());
        }

        TxRecord terminalRecord = terminal.get(transactionId);
        if (terminalRecord == null) {
            return TransitionResult.rejected("unknown transaction");
        }
        if (terminalRecord.status == TxStatus.COMMITTED) {
            return TransitionResult.idempotent(terminalRecord.copy());
        }
        return TransitionResult.rejected("cannot commit terminal transaction: " + terminalRecord.status);
    }

    synchronized TransitionResult canCommit(String transactionId) {
        if (isBlank(transactionId)) {
            return TransitionResult.rejected("missing transactionId");
        }
        expireInternal(nowMs.getAsLong());
        TxRecord activeRecord = active.get(transactionId);
        if (activeRecord != null && activeRecord.status == TxStatus.STARTED) {
            return TransitionResult.applied(activeRecord.copy());
        }
        TxRecord terminalRecord = terminal.get(transactionId);
        if (terminalRecord == null) {
            return TransitionResult.rejected("unknown transaction");
        }
        if (terminalRecord.status == TxStatus.COMMITTED) {
            return TransitionResult.idempotent(terminalRecord.copy());
        }
        return TransitionResult.rejected("cannot commit terminal transaction: " + terminalRecord.status);
    }

    synchronized TransitionResult onAbort(String transactionId, String correlationId, String reason) {
        if (isBlank(transactionId)) {
            return TransitionResult.rejected("missing transactionId");
        }
        expireInternal(nowMs.getAsLong());

        TxRecord activeRecord = active.remove(transactionId);
        if (activeRecord != null) {
            activeRecord.status = TxStatus.ABORTED;
            activeRecord.completedAtMs = nowMs.getAsLong();
            activeRecord.abortReason = reason;
            if (isBlank(activeRecord.correlationId)) {
                activeRecord.correlationId = correlationId;
            }
            addTerminal(activeRecord);
            persist();
            return TransitionResult.applied(activeRecord.copy());
        }

        TxRecord terminalRecord = terminal.get(transactionId);
        if (terminalRecord == null) {
            TxRecord syntheticAbort = new TxRecord();
            syntheticAbort.transactionId = transactionId;
            syntheticAbort.correlationId = correlationId;
            syntheticAbort.status = TxStatus.ABORTED;
            syntheticAbort.startedAtMs = nowMs.getAsLong();
            syntheticAbort.completedAtMs = syntheticAbort.startedAtMs;
            syntheticAbort.deadlineMs = syntheticAbort.startedAtMs;
            syntheticAbort.timeoutMs = 0L;
            syntheticAbort.abortReason = reason;
            addTerminal(syntheticAbort);
            persist();
            return TransitionResult.applied(syntheticAbort.copy());
        }

        if (terminalRecord.status == TxStatus.ABORTED || terminalRecord.status == TxStatus.TIMED_OUT) {
            return TransitionResult.idempotent(terminalRecord.copy());
        }
        return TransitionResult.rejected("cannot abort terminal transaction: " + terminalRecord.status);
    }

    synchronized TransitionResult canAbort(String transactionId) {
        if (isBlank(transactionId)) {
            return TransitionResult.rejected("missing transactionId");
        }
        expireInternal(nowMs.getAsLong());
        TxRecord activeRecord = active.get(transactionId);
        if (activeRecord != null) {
            return TransitionResult.applied(activeRecord.copy());
        }
        TxRecord terminalRecord = terminal.get(transactionId);
        if (terminalRecord == null) {
            return TransitionResult.applied(null);
        }
        if (terminalRecord.status == TxStatus.ABORTED || terminalRecord.status == TxStatus.TIMED_OUT) {
            return TransitionResult.idempotent(terminalRecord.copy());
        }
        return TransitionResult.rejected("cannot abort terminal transaction: " + terminalRecord.status);
    }

    synchronized List<TxRecord> expireTimedOut() {
        List<TxRecord> expired = expireInternal(nowMs.getAsLong());
        if (!expired.isEmpty()) {
            persist();
        }
        return expired;
    }

    synchronized Optional<TxRecord> get(String transactionId) {
        TxRecord activeRecord = active.get(transactionId);
        if (activeRecord != null) {
            return Optional.of(activeRecord.copy());
        }
        TxRecord terminalRecord = terminal.get(transactionId);
        return terminalRecord != null ? Optional.of(terminalRecord.copy()) : Optional.empty();
    }

    synchronized Map<String, Object> stats() {
        long committed = 0;
        long aborted = 0;
        long timedOut = 0;
        for (TxRecord record : terminal.values()) {
            if (record.status == TxStatus.COMMITTED) {
                committed++;
            } else if (record.status == TxStatus.ABORTED) {
                aborted++;
            } else if (record.status == TxStatus.TIMED_OUT) {
                timedOut++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("active", active.size());
        stats.put("terminal", terminal.size());
        stats.put("committed", committed);
        stats.put("aborted", aborted);
        stats.put("timedOut", timedOut);
        return stats;
    }

    private List<TxRecord> expireInternal(long now) {
        if (active.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> timedOutIds = new ArrayList<>();
        for (Map.Entry<String, TxRecord> entry : active.entrySet()) {
            TxRecord record = entry.getValue();
            if (record.status == TxStatus.STARTED && record.deadlineMs > 0 && now >= record.deadlineMs) {
                timedOutIds.add(entry.getKey());
            }
        }
        if (timedOutIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<TxRecord> expired = new ArrayList<>(timedOutIds.size());
        for (String transactionId : timedOutIds) {
            TxRecord record = active.remove(transactionId);
            if (record == null) {
                continue;
            }
            record.status = TxStatus.TIMED_OUT;
            record.completedAtMs = now;
            record.abortReason = "timeout";
            addTerminal(record);
            expired.add(record.copy());
        }
        return expired;
    }

    private void addTerminal(TxRecord record) {
        terminal.put(record.transactionId, record);
        while (terminal.size() > maxTerminalEntries) {
            String eldest = terminal.keySet().iterator().next();
            terminal.remove(eldest);
        }
    }

    private void load() {
        if (!Files.exists(stateFile)) {
            return;
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(stateFile))) {
            Object object = in.readObject();
            if (!(object instanceof PersistedState)) {
                return;
            }
            PersistedState state = (PersistedState) object;
            if (state.active != null) {
                active.clear();
                active.putAll(state.active);
            }
            if (state.terminal != null) {
                terminal.clear();
                terminal.putAll(state.terminal);
            }
        } catch (Exception e) {
            log.warn("Failed to load transaction lifecycle state: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            PersistedState state = new PersistedState();
            state.active = new LinkedHashMap<>(active);
            state.terminal = new LinkedHashMap<>(terminal);

            Path tempFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tempFile))) {
                out.writeObject(state);
            }
            Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("Failed to persist transaction lifecycle state: {}", e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    enum TxStatus {
        STARTED,
        COMMITTED,
        ABORTED,
        TIMED_OUT
    }

    static final class TransitionResult {
        private final boolean applied;
        private final boolean idempotent;
        private final String reason;
        private final TxRecord record;

        private TransitionResult(boolean applied, boolean idempotent, String reason, TxRecord record) {
            this.applied = applied;
            this.idempotent = idempotent;
            this.reason = reason;
            this.record = record;
        }

        static TransitionResult applied(TxRecord record) {
            return new TransitionResult(true, false, null, record);
        }

        static TransitionResult idempotent(TxRecord record) {
            return new TransitionResult(false, true, null, record);
        }

        static TransitionResult rejected(String reason) {
            return new TransitionResult(false, false, reason, null);
        }

        boolean isApplied() {
            return applied;
        }

        boolean isIdempotent() {
            return idempotent;
        }

        String getReason() {
            return reason;
        }

        TxRecord getRecord() {
            return record;
        }
    }

    static final class TxRecord implements Serializable {
        private static final long serialVersionUID = 1L;

        String transactionId;
        String correlationId;
        String initiatorWallet;
        TxStatus status;
        long startedAtMs;
        long timeoutMs;
        long deadlineMs;
        long completedAtMs;
        String abortReason;

        TxRecord copy() {
            TxRecord copy = new TxRecord();
            copy.transactionId = transactionId;
            copy.correlationId = correlationId;
            copy.initiatorWallet = initiatorWallet;
            copy.status = status;
            copy.startedAtMs = startedAtMs;
            copy.timeoutMs = timeoutMs;
            copy.deadlineMs = deadlineMs;
            copy.completedAtMs = completedAtMs;
            copy.abortReason = abortReason;
            return copy;
        }
    }

    private static final class PersistedState implements Serializable {
        private static final long serialVersionUID = 1L;
        private Map<String, TxRecord> active;
        private Map<String, TxRecord> terminal;
    }
}
