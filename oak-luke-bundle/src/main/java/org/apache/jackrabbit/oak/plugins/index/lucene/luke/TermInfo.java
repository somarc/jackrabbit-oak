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
 * Statistics for a single term in a field.
 * Adapted from LUKE's TermStats for Oak integration.
 */
public class TermInfo implements Comparable<TermInfo> {
    public String field;
    public String term;
    public int docFreq;
    public long totalTermFreq;

    public TermInfo(String field, String term, int docFreq, long totalTermFreq) {
        this.field = field;
        this.term = term;
        this.docFreq = docFreq;
        this.totalTermFreq = totalTermFreq;
    }

    @Override
    public int compareTo(TermInfo other) {
        // Sort by doc frequency descending
        if (docFreq > other.docFreq) {
            return -1;
        } else if (docFreq < other.docFreq) {
            return 1;
        } else {
            return term.compareTo(other.term);
        }
    }

    @Override
    public String toString() {
        if (totalTermFreq > 0) {
            return String.format("%-40s | %-40s | %,10d docs | %,12d occurrences",
                    field, term, docFreq, totalTermFreq);
        } else {
            return String.format("%-40s | %-40s | %,10d docs",
                    field, term, docFreq);
        }
    }
}

