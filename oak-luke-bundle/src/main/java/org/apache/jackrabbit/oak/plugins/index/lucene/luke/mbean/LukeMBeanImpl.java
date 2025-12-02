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

import javax.management.openmbean.TabularData;

import org.apache.jackrabbit.oak.plugins.index.lucene.luke.LukeIndexStatsMBeanImplSimple;

/**
 * Single implementation class that delegates to LukeIndexStatsMBeanImplSimple
 * and implements all the grouped MBean interfaces.
 * 
 * This allows us to register multiple MBeans (one per interface) that all
 * delegate to the same underlying implementation.
 */
public class LukeMBeanImpl implements 
        LukeDiscoveryMBean, 
        LukeBasicStatsMBean, 
        LukeFieldAnalysisMBean,
        LukeTermAnalysisMBean,
        LukeDocumentsMBean,
        LukeInsightsMBean {
    
    private final LukeIndexStatsMBeanImplSimple delegate;
    
    public LukeMBeanImpl(LukeIndexStatsMBeanImplSimple delegate) {
        this.delegate = delegate;
    }
    
    // ============================================================
    // LukeDiscoveryMBean
    // ============================================================
    
    @Override
    public TabularData getLocalIndexDirectories() {
        return delegate.getLocalIndexDirectories();
    }
    
    @Override
    public String getLocalIndexPath(String indexPath) {
        return delegate.getLocalIndexPath(indexPath);
    }
    
    @Override
    public String validateLocalIndex(String indexPath) throws IOException {
        return delegate.validateLocalIndex(indexPath);
    }
    
    // ============================================================
    // LukeBasicStatsMBean
    // ============================================================
    
    @Override
    public String getLukeBasicStats(String indexPath) throws IOException {
        return delegate.getLukeBasicStats(indexPath);
    }
    
    @Override
    public String getIndexHealthReport(String indexPath) throws IOException {
        return delegate.getIndexHealthReport(indexPath);
    }
    
    @Override
    public String[] getSegmentInfo(String indexPath) throws IOException {
        return delegate.getSegmentInfo(indexPath);
    }
    
    @Override
    public String getIndexCompositionStats(String indexPath) throws IOException {
        return delegate.getIndexCompositionStats(indexPath);
    }
    
    @Override
    public String getFulltextStats(String indexPath) throws IOException {
        return delegate.getFulltextStats(indexPath);
    }
    
    // ============================================================
    // LukeFieldAnalysisMBean
    // ============================================================
    
    @Override
    public String[] getLukeFieldInfo(String indexPath, int maxFields) throws IOException {
        return delegate.getLukeFieldInfo(indexPath, maxFields);
    }
    
    @Override
    public String[] getFieldSizeAnalysis(String indexPath, int maxFields) throws IOException {
        return delegate.getFieldSizeAnalysis(indexPath, maxFields);
    }
    
    @Override
    public String[] getFieldFlags(String indexPath) throws IOException {
        return delegate.getFieldFlags(indexPath);
    }
    
    @Override
    public String[] getFieldCardinality(String indexPath, int maxFields) throws IOException {
        return delegate.getFieldCardinality(indexPath, maxFields);
    }
    
    // ============================================================
    // LukeTermAnalysisMBean
    // ============================================================
    
    @Override
    public String[] getLukeTermStats(String indexPath, String fieldName, int maxTerms) throws IOException {
        return delegate.getLukeTermStats(indexPath, fieldName, maxTerms);
    }
    
    @Override
    public String[] getTopTermsByDocFreq(String indexPath, String fieldName, int maxTerms) throws IOException {
        return delegate.getTopTermsByDocFreq(indexPath, fieldName, maxTerms);
    }
    
    @Override
    public String[] getTermDistribution(String indexPath, String fieldName, int buckets) throws IOException {
        return delegate.getTermDistribution(indexPath, fieldName, buckets);
    }
    
    // ============================================================
    // LukeDocumentsMBean
    // ============================================================
    
    @Override
    public String[] sampleDocuments(String indexPath, int sampleSize, String showFields) throws IOException {
        return delegate.sampleDocuments(indexPath, sampleSize, showFields);
    }
    
    @Override
    public String getDocument(String indexPath, int docId) throws IOException {
        return delegate.getDocument(indexPath, docId);
    }
    
    // ============================================================
    // LukeInsightsMBean
    // ============================================================
    
    @Override
    public String getIndexInsights(String indexPath, int maxFields) throws IOException {
        return delegate.getIndexInsights(indexPath, maxFields);
    }
    
    @Override
    public String getContentDistribution(String indexPath, int depth) throws IOException {
        return delegate.getContentDistribution(indexPath, depth);
    }
    
    @Override
    public String[] detectDuplicateValues(String indexPath, String fieldName, int maxResults) throws IOException {
        return delegate.detectDuplicateValues(indexPath, fieldName, maxResults);
    }
}

