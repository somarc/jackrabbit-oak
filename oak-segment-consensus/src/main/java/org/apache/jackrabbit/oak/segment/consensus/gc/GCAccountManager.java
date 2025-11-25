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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages GC accounts for all entities (wallet addresses).
 * 
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Track GC debt per entity</li>
 *   <li>Enforce write blocking when debt exceeds limit</li>
 *   <li>Convert pending debt to executed debt after GC</li>
 *   <li>Record payments and clear debt</li>
 * </ul>
 * 
 * <p>Thread-safe: Uses ConcurrentHashMap for account storage.</p>
 */
public class GCAccountManager {
    
    private static final Logger log = LoggerFactory.getLogger(GCAccountManager.class);
    
    /** Base GC cost per MB */
    public static final BigDecimal GC_COST_PER_MB = new BigDecimal("0.10");
    
    /** In-memory storage of accounts (wallet address -> account) */
    private final Map<String, EntityGCAccount> accounts = new ConcurrentHashMap<>();
    
    /**
     * Get or create account for wallet address.
     * 
     * @param walletAddress Ethereum wallet address
     * @return account for this entity
     */
    public EntityGCAccount getAccount(String walletAddress) {
        return accounts.computeIfAbsent(walletAddress, EntityGCAccount::new);
    }
    
    /**
     * Add GC debt for entity after delete operation.
     * 
     * @param walletAddress Ethereum wallet address
     * @param path content path deleted
     * @param sizeMB size of deleted content in MB
     * @return debt amount added
     */
    public BigDecimal addDebt(String walletAddress, String path, long sizeMB) {
        BigDecimal cost = BigDecimal.valueOf(sizeMB).multiply(GC_COST_PER_MB);
        
        EntityGCAccount account = getAccount(walletAddress);
        account.addDebt(path, sizeMB, cost);
        
        log.info("💸 Added GC debt: wallet={}, path={}, size={}MB, cost=${}", 
            walletAddress, path, sizeMB, cost);
        
        return cost;
    }
    
    /**
     * Check if entity can write (debt under limit).
     * 
     * @param walletAddress Ethereum wallet address
     * @return true if writes allowed, false if blocked
     */
    public boolean canWrite(String walletAddress) {
        EntityGCAccount account = getAccount(walletAddress);
        return !account.shouldBlockWrites();
    }
    
    /**
     * Get all accounts (for dashboard, reporting).
     * 
     * @return collection of all accounts
     */
    public Collection<EntityGCAccount> getAllAccounts() {
        return accounts.values();
    }
    
    /**
     * Get accounts with pending debt (for GC cost attribution).
     * 
     * @return list of accounts with pending debt
     */
    public List<EntityGCAccount> getAccountsWithPendingDebt() {
        return accounts.values().stream()
            .filter(a -> a.getPendingDebt().compareTo(BigDecimal.ZERO) > 0)
            .collect(Collectors.toList());
    }
    
    /**
     * Get accounts with executed debt (for payment tracking).
     * 
     * @return list of accounts with executed debt
     */
    public List<EntityGCAccount> getAccountsWithExecutedDebt() {
        return accounts.values().stream()
            .filter(a -> a.executedDebt.compareTo(BigDecimal.ZERO) > 0)
            .collect(Collectors.toList());
    }
    
    /**
     * Get blocked accounts (for monitoring).
     * 
     * @return list of accounts with writes blocked
     */
    public List<EntityGCAccount> getBlockedAccounts() {
        return accounts.values().stream()
            .filter(a -> a.writesBlocked)
            .collect(Collectors.toList());
    }
    
    /**
     * Convert pending debt to executed debt for all accounts.
     * Called after periodic GC runs.
     */
    public void convertAllPendingToExecuted() {
        int converted = 0;
        int blocked = 0;
        
        for (EntityGCAccount account : accounts.values()) {
            BigDecimal pending = account.getPendingDebt();
            if (pending.compareTo(BigDecimal.ZERO) > 0) {
                account.convertPendingToExecuted(pending);
                converted++;
                
                if (account.writesBlocked) {
                    blocked++;
                    log.warn("🔒 BLOCKED writes for {}: debt ${} exceeds limit ${}",
                        account.walletAddress, account.executedDebt, account.debtLimit);
                }
            }
        }
        
        log.info("✅ Converted pending debt for {} accounts ({} now blocked)", converted, blocked);
    }
    
    /**
     * Record payment for entity (manual for MVP, will integrate with smart contract).
     * 
     * @param walletAddress Ethereum wallet address
     * @param amount payment amount
     * @param txHash Ethereum transaction hash (optional)
     */
    public void recordPayment(String walletAddress, BigDecimal amount, String txHash) {
        EntityGCAccount account = getAccount(walletAddress);
        BigDecimal beforeDebt = account.executedDebt;
        
        account.recordPayment(amount, txHash);
        
        log.info("💰 Payment recorded: wallet={}, amount=${}, before=${}, after=${}, unblocked={}",
            walletAddress, amount, beforeDebt, account.executedDebt, !account.writesBlocked);
    }
    
    /**
     * Set debt limit for entity (for testing/admin).
     * 
     * @param walletAddress Ethereum wallet address
     * @param limit new debt limit
     */
    public void setDebtLimit(String walletAddress, BigDecimal limit) {
        EntityGCAccount account = getAccount(walletAddress);
        account.debtLimit = limit;
        
        // Re-check if should block writes with new limit
        account.writesBlocked = account.shouldBlockWrites();
        
        log.info("⚙️  Set debt limit: wallet={}, limit=${}, blocked={}",
            walletAddress, limit, account.writesBlocked);
    }
    
    /**
     * Get total network debt across all entities.
     * 
     * @return sum of all debt
     */
    public BigDecimal getTotalNetworkDebt() {
        return accounts.values().stream()
            .map(a -> a.totalDebt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * Get total executed debt across all entities.
     * 
     * @return sum of all executed debt
     */
    public BigDecimal getTotalExecutedDebt() {
        return accounts.values().stream()
            .map(a -> a.executedDebt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * Reset all accounts (for testing).
     */
    public void reset() {
        accounts.clear();
        log.warn("⚠️  All GC accounts reset");
    }
}

