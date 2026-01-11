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
package org.apache.jackrabbit.oak.segment.agentic.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversation memory for maintaining chat context across turns.
 * 
 * <p>Stores conversation history per session, allowing the LLM to
 * reference previous exchanges for better context understanding.
 * 
 * <p>Features:
 * <ul>
 *   <li>Session-based isolation</li>
 *   <li>Automatic trimming to prevent context overflow</li>
 *   <li>TTL-based cleanup for stale sessions</li>
 * </ul>
 * 
 * @since 1.89
 */
public class ConversationMemory {
    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);
    
    /** Maximum number of turns to keep per session */
    private static final int MAX_TURNS = 10;
    
    /** Maximum age of a session before cleanup (30 minutes) */
    private static final long SESSION_TTL_MS = 30 * 60 * 1000;
    
    /** Map from session ID to conversation history */
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    
    /**
     * Add a conversation turn to a session.
     * 
     * @param sessionId Session identifier
     * @param query User's query
     * @param response Assistant's response
     */
    public void addTurn(String sessionId, String query, String response) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        
        ConversationSession session = sessions.computeIfAbsent(sessionId, 
            k -> new ConversationSession());
        
        session.addTurn(query, response);
        
        log.debug("Added turn to session {}: {} turns", sessionId, session.getTurnCount());
    }
    
    /**
     * Get conversation context for LLM prompt.
     * 
     * @param sessionId Session identifier
     * @return Formatted conversation history, or empty string if no history
     */
    public String getContextForLLM(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "";
        }
        
        ConversationSession session = sessions.get(sessionId);
        if (session == null || session.isEmpty()) {
            return "";
        }
        
        return session.formatForLLM();
    }
    
    /**
     * Get the number of turns in a session.
     * 
     * @param sessionId Session identifier
     * @return Number of turns, or 0 if session doesn't exist
     */
    public int getTurnCount(String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        return session != null ? session.getTurnCount() : 0;
    }
    
    /**
     * Clear a specific session.
     * 
     * @param sessionId Session identifier
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        log.debug("Cleared session: {}", sessionId);
    }
    
    /**
     * Clean up stale sessions.
     * Should be called periodically.
     */
    public void cleanupStaleSessions() {
        long now = System.currentTimeMillis();
        int removed = 0;
        
        for (Map.Entry<String, ConversationSession> entry : sessions.entrySet()) {
            if (now - entry.getValue().getLastActivityTime() > SESSION_TTL_MS) {
                sessions.remove(entry.getKey());
                removed++;
            }
        }
        
        if (removed > 0) {
            log.info("Cleaned up {} stale conversation sessions", removed);
        }
    }
    
    /**
     * Get total number of active sessions.
     * 
     * @return Number of sessions
     */
    public int getSessionCount() {
        return sessions.size();
    }
    
    /**
     * Represents a single conversation session.
     */
    private static class ConversationSession {
        private final List<ChatTurn> turns = new ArrayList<>();
        private volatile long lastActivityTime;
        
        public ConversationSession() {
            this.lastActivityTime = System.currentTimeMillis();
        }
        
        public synchronized void addTurn(String query, String response) {
            turns.add(new ChatTurn(query, response, System.currentTimeMillis()));
            lastActivityTime = System.currentTimeMillis();
            
            // Trim to max turns
            while (turns.size() > MAX_TURNS) {
                turns.remove(0);
            }
        }
        
        public synchronized int getTurnCount() {
            return turns.size();
        }
        
        public synchronized boolean isEmpty() {
            return turns.isEmpty();
        }
        
        public long getLastActivityTime() {
            return lastActivityTime;
        }
        
        public synchronized String formatForLLM() {
            if (turns.isEmpty()) {
                return "";
            }
            
            StringBuilder context = new StringBuilder();
            context.append("Previous conversation:\n");
            context.append("─".repeat(40)).append("\n");
            
            for (ChatTurn turn : turns) {
                context.append("User: ").append(turn.query).append("\n");
                // Truncate long responses
                String response = turn.response;
                if (response.length() > 500) {
                    response = response.substring(0, 500) + "...";
                }
                context.append("Assistant: ").append(response).append("\n\n");
            }
            
            context.append("─".repeat(40)).append("\n");
            return context.toString();
        }
    }
    
    /**
     * Represents a single conversation turn.
     */
    private static class ChatTurn {
        final String query;
        final String response;
        final long timestamp;
        
        ChatTurn(String query, String response, long timestamp) {
            this.query = query;
            this.response = response;
            this.timestamp = timestamp;
        }
    }
}
