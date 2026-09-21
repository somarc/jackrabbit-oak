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
package org.apache.jackrabbit.oak.segment.consensus.gc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks GC debt for a single entity (wallet address).
 * 
 * <p>Account Tax Model: Entities accumulate debt when deleting content.
 * Debt is converted from pending to executed when periodic GC runs.
 * Writes are blocked when executed debt exceeds the limit.</p>
 * 
 * <p>Debt Flow:</p>
 * <pre>
 * Delete content → totalDebt += cost (pending)
 * Periodic GC runs → pendingDebt → executedDebt
 * If executedDebt > limit → writesBlocked = true
 * Pay debt → executedDebt -= payment, writesBlocked = false
 * </pre>
 */
public class EntityGCAccount {
    
    /** Ethereum wallet address of entity */
    public final String walletAddress;
    
    /** Total GC debt owed (pending + executed) */
    public BigDecimal totalDebt;
    
    /** Debt from executed GC (due now) - this blocks writes */
    public BigDecimal executedDebt;
    
    /** Maximum debt before writes are blocked */
    public BigDecimal debtLimit;
    
    /** Are writes currently blocked for this entity? */
    public boolean writesBlocked;
    
    /** History of delete operations that incurred debt */
    public final List<DeleteOperation> deletes;
    
    /** History of GC executions that converted debt */
    public final List<GCExecution> gcExecutions;
    
    /** History of debt payments */
    public final List<DebtPayment> payments;
    
    /** Timestamp of last delete operation */
    public long lastDeleteTime;
    
    /** Timestamp of last payment */
    public long lastPaymentTime;
    
    /** Timestamp when debt became "due" (after last GC execution) */
    public long debtDueTime;
    
    /**
     * Create new GC account for entity.
     * 
     * @param walletAddress Ethereum wallet address
     */
    public EntityGCAccount(String walletAddress) {
        this.walletAddress = walletAddress;
        this.totalDebt = BigDecimal.ZERO;
        this.executedDebt = BigDecimal.ZERO;
        this.debtLimit = new BigDecimal("100.00");  // $100 default limit
        this.writesBlocked = false;
        this.deletes = new ArrayList<>();
        this.gcExecutions = new ArrayList<>();
        this.payments = new ArrayList<>();
        this.lastDeleteTime = 0;
        this.lastPaymentTime = 0;
        this.debtDueTime = 0;
    }
    
    /**
     * Get pending debt (not yet executed).
     * 
     * @return pending debt amount
     */
    public BigDecimal getPendingDebt() {
        return totalDebt.subtract(executedDebt);
    }
    
    /**
     * Check if writes should be blocked for this entity.
     * 
     * @return true if executed debt exceeds limit
     */
    public boolean shouldBlockWrites() {
        return executedDebt.compareTo(debtLimit) >= 0;
    }
    
    /**
     * Add debt from a delete operation.
     * 
     * @param path content path deleted
     * @param sizeMB size in MB
     * @param cost debt amount in USDC
     */
    public void addDebt(String path, long sizeMB, BigDecimal cost) {
        totalDebt = totalDebt.add(cost);
        lastDeleteTime = System.currentTimeMillis();
        
        DeleteOperation delete = new DeleteOperation(path, sizeMB, cost, lastDeleteTime);
        deletes.add(delete);
    }
    
    /**
     * Convert pending debt to executed debt (after GC runs).
     * 
     * @param amount amount to convert
     */
    public void convertPendingToExecuted(BigDecimal amount) {
        // Move from pending to executed
        executedDebt = executedDebt.add(amount);
        debtDueTime = System.currentTimeMillis();
        
        // Check if should block writes
        writesBlocked = shouldBlockWrites();
    }
    
    /**
     * Record a debt payment.
     * 
     * @param amount payment amount
     * @param txHash Ethereum transaction hash (optional)
     */
    public void recordPayment(BigDecimal amount, String txHash) {
        executedDebt = executedDebt.subtract(amount);
        if (executedDebt.compareTo(BigDecimal.ZERO) < 0) {
            executedDebt = BigDecimal.ZERO;
        }
        
        // Unblock writes if debt now under limit
        writesBlocked = shouldBlockWrites();
        
        lastPaymentTime = System.currentTimeMillis();
        DebtPayment payment = new DebtPayment(txHash, amount, lastPaymentTime);
        payments.add(payment);
    }
    
    /**
     * Record a GC execution that affected this entity.
     * 
     * @param proposalId GC proposal ID
     * @param cost cost attributed to this entity
     * @param reclaimedMB space reclaimed
     */
    public void recordGCExecution(String proposalId, BigDecimal cost, long reclaimedMB) {
        GCExecution execution = new GCExecution(proposalId, cost, reclaimedMB, System.currentTimeMillis());
        gcExecutions.add(execution);
    }
    
    /**
     * Delete operation record.
     */
    public static class DeleteOperation {
        public final String path;
        public final long sizeMB;
        public final BigDecimal cost;
        public final long timestamp;
        
        public DeleteOperation(String path, long sizeMB, BigDecimal cost, long timestamp) {
            this.path = path;
            this.sizeMB = sizeMB;
            this.cost = cost;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * GC execution record.
     */
    public static class GCExecution {
        public final String proposalId;
        public final BigDecimal cost;
        public final long reclaimedMB;
        public final long timestamp;
        
        public GCExecution(String proposalId, BigDecimal cost, long reclaimedMB, long timestamp) {
            this.proposalId = proposalId;
            this.cost = cost;
            this.reclaimedMB = reclaimedMB;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * Debt payment record.
     */
    public static class DebtPayment {
        public final String txHash;
        public final BigDecimal amount;
        public final long timestamp;
        
        public DebtPayment(String txHash, BigDecimal amount, long timestamp) {
            this.txHash = txHash;
            this.amount = amount;
            this.timestamp = timestamp;
        }
    }
}

