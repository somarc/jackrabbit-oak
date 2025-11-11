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
package org.apache.jackrabbit.oak.segment.consensus.dag;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a HEAD in the distributed DAG.
 * Like Git branches, multiple HEADs can exist simultaneously.
 * 
 * <p>In a distributed Oak network, each validator may have a different HEAD
 * representing their current state. HEADs are periodically merged to achieve
 * consensus on a canonical state.
 */
public class DagHead {
    
    private String recordId;           // The Oak RecordId (segment:offset)
    private String validatorUrl;       // Which validator owns this HEAD
    private long timestamp;            // When this HEAD was created
    private List<String> parentIds;    // Parent HEADs (for merge commits)
    private String commitMessage;      // Description of this HEAD
    private int depth;                 // Distance from genesis (for ordering)
    
    public DagHead() {
        this.parentIds = new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    public DagHead(String recordId, String validatorUrl) {
        this();
        this.recordId = recordId;
        this.validatorUrl = validatorUrl;
    }
    
    /**
     * Check if this is a merge HEAD (multiple parents).
     */
    public boolean isMerge() {
        return parentIds.size() > 1;
    }
    
    /**
     * Check if this HEAD is an ancestor of another.
     * Used for fast-forward detection.
     */
    public boolean isAncestorOf(DagHead other) {
        // Simplified: compare depths
        // In production, would traverse the DAG
        return this.depth < other.depth && other.parentIds.contains(this.recordId);
    }
    
    // Getters and setters
    
    public String getRecordId() {
        return recordId;
    }
    
    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }
    
    public String getValidatorUrl() {
        return validatorUrl;
    }
    
    public void setValidatorUrl(String validatorUrl) {
        this.validatorUrl = validatorUrl;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public List<String> getParentIds() {
        return parentIds;
    }
    
    public void setParentIds(List<String> parentIds) {
        this.parentIds = parentIds;
    }
    
    public void addParent(String parentId) {
        if (!this.parentIds.contains(parentId)) {
            this.parentIds.add(parentId);
        }
    }
    
    public String getCommitMessage() {
        return commitMessage;
    }
    
    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }
    
    public int getDepth() {
        return depth;
    }
    
    public void setDepth(int depth) {
        this.depth = depth;
    }
    
    @Override
    public String toString() {
        String shortId = recordId != null && recordId.length() > 8 ? recordId.substring(0, 8) : recordId;
        if (isMerge()) {
            return String.format("DagHead{%s, MERGE[%d parents], depth=%d, validator=%s}",
                shortId, parentIds.size(), depth, validatorUrl);
        } else {
            return String.format("DagHead{%s, depth=%d, validator=%s}",
                shortId, depth, validatorUrl);
        }
    }
}

