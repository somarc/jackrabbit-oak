/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.plugins.index.lucene.luke;

/**
 * Represents term count statistics for a single field.
 * Adapted from LUKE's FieldTermCount for Oak integration.
 */
public class FieldTermCount implements Comparable<FieldTermCount> {
    public String fieldName;
    public long termCount;
    public double percentage;

    public FieldTermCount(String fieldName, long termCount) {
        this.fieldName = fieldName;
        this.termCount = termCount;
        this.percentage = 0.0;
    }

    @Override
    public int compareTo(FieldTermCount other) {
        // Sort by term count descending (largest first)
        if (termCount > other.termCount) {
            return -1;
        } else if (termCount < other.termCount) {
            return 1;
        } else {
            return fieldName.compareTo(other.fieldName);
        }
    }

    @Override
    public String toString() {
        return String.format("%-60s | %,12d terms | %6.2f%%",
                fieldName, termCount, percentage);
    }
}

