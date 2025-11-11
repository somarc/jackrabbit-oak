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

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Detects conflicts between two DAG HEADs.
 * Like Git's conflict detection, checks if the same paths were modified.
 * 
 * <p>Two writes conflict if they modify the same path (node or property).
 * Non-conflicting writes can proceed in parallel and be merged later.
 */
public class ConflictDetector {
    
    private static final Logger log = LoggerFactory.getLogger(ConflictDetector.class);
    
    /**
     * Check if two HEADs have conflicting changes.
     * 
     * @param commonAncestor The common ancestor HEAD
     * @param head1 First HEAD to compare
     * @param head2 Second HEAD to compare
     * @return true if conflicts detected, false if can auto-merge
     */
    public static boolean hasConflicts(NodeState commonAncestor, NodeState head1, NodeState head2) {
        Set<String> modifiedPaths1 = getModifiedPaths(commonAncestor, head1, "");
        Set<String> modifiedPaths2 = getModifiedPaths(commonAncestor, head2, "");
        
        // Check for overlapping modifications
        Set<String> conflicts = new HashSet<>(modifiedPaths1);
        conflicts.retainAll(modifiedPaths2);
        
        if (!conflicts.isEmpty()) {
            log.warn("Conflict detected on paths: {}", conflicts);
            return true;
        }
        
        return false;
    }
    
    /**
     * Get all paths modified between ancestor and current state.
     * This is a simplified version - production would need more sophisticated diff.
     */
    private static Set<String> getModifiedPaths(NodeState ancestor, NodeState current, String path) {
        Set<String> modified = new HashSet<>();
        
        // Compare properties
        for (PropertyState prop : current.getProperties()) {
            String propPath = path + "/" + prop.getName();
            PropertyState ancestorProp = ancestor.getProperty(prop.getName());
            
            if (ancestorProp == null || !prop.equals(ancestorProp)) {
                modified.add(propPath);
            }
        }
        
        // Compare child nodes (recursive)
        for (String childName : current.getChildNodeNames()) {
            String childPath = path + "/" + childName;
            NodeState ancestorChild = ancestor.getChildNode(childName);
            NodeState currentChild = current.getChildNode(childName);
            
            if (!ancestorChild.exists()) {
                // New node added
                modified.add(childPath);
            } else if (!currentChild.equals(ancestorChild)) {
                // Node modified - recurse
                modified.addAll(getModifiedPaths(ancestorChild, currentChild, childPath));
            }
        }
        
        // Check for deleted nodes
        for (String childName : ancestor.getChildNodeNames()) {
            if (!current.getChildNode(childName).exists()) {
                modified.add(path + "/" + childName + " (deleted)");
            }
        }
        
        return modified;
    }
    
    /**
     * Find the lowest common ancestor of two HEADs in the DAG.
     * This is like Git's merge-base calculation.
     * 
     * @param head1 First HEAD
     * @param head2 Second HEAD
     * @return The common ancestor, or null if no common ancestor
     */
    public static DagHead findCommonAncestor(DagHead head1, DagHead head2) {
        // Simplified: if one is ancestor of the other, return it
        if (head1.isAncestorOf(head2)) {
            return head1;
        }
        if (head2.isAncestorOf(head1)) {
            return head2;
        }
        
        // In production, would traverse the DAG backwards from both HEADs
        // to find lowest common ancestor (LCA)
        // For now, return null indicating we need a full merge
        return null;
    }
    
    /**
     * Determine if merge can be fast-forward (like Git fast-forward).
     * 
     * @param source HEAD being merged from
     * @param target HEAD being merged into
     * @return true if can fast-forward (target is ancestor of source)
     */
    public static boolean canFastForward(DagHead source, DagHead target) {
        return target.isAncestorOf(source);
    }
}

