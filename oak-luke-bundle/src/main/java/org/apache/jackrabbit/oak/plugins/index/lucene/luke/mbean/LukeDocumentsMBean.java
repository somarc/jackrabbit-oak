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
package org.apache.jackrabbit.oak.plugins.index.lucene.luke.mbean;

import java.io.IOException;

import org.apache.jackrabbit.oak.api.jmx.Description;
import org.apache.jackrabbit.oak.api.jmx.Name;

/**
 * JMX MBean for inspecting documents in the index.
 * 
 * <h2>Purpose</h2>
 * See what actual documents look like in the index.
 * Useful for debugging and understanding index content.
 * 
 * <h2>All Operations are Fast: O(sampleSize)</h2>
 */
public interface LukeDocumentsMBean {
    String TYPE = "LukeDocuments";

    @Description("Samples random documents showing stored field values. " +
            "Answers: 'What does an actual document look like in this index?' " +
            "Fast operation: O(sampleSize).")
    String[] sampleDocuments(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath,
            @Name("sampleSize")
            @Description("Number of documents to sample (recommended: 5-10)")
            int sampleSize,
            @Name("showFields")
            @Description("Comma-separated field names to show (empty = all stored fields)")
            String showFields
    ) throws IOException;

    @Description("Gets stored field values for a specific document by doc ID. " +
            "Fast operation: O(1).")
    String getDocument(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath,
            @Name("docId")
            @Description("Lucene document ID (0-based)")
            int docId
    ) throws IOException;
}

