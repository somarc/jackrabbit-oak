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
package org.apache.jackrabbit.oak.segment.agentic.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool for querying OSGi bundle information.
 */
public class OSGiBundleTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(OSGiBundleTool.class);
    
    @Override
    public String getName() {
        return "osgi-bundles";
    }
    
    @Override
    public String getDescription() {
        return "Query OSGi bundle information - list bundles, check bundle states, find bundles by name";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        return lower.contains("bundle") || lower.contains("osgi") || 
               lower.contains("installed") || lower.contains("active") ||
               lower.contains("component") || lower.contains("service");
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            // Get BundleContext using reflection
            Object bundleContext = getBundleContext();
            if (bundleContext == null) {
                return ToolResult.failure("OSGi framework not available - cannot query bundles");
            }
            
            // Get bundles
            Method getBundlesMethod = bundleContext.getClass().getMethod("getBundles");
            Object[] bundles = (Object[]) getBundlesMethod.invoke(bundleContext);
            
            if (bundles == null || bundles.length == 0) {
                return ToolResult.success("No bundles found", "osgi-bundles");
            }
            
            // Parse query to determine what to show
            String lower = query.toLowerCase();
            boolean showAll = lower.contains("all") || lower.contains("list");
            boolean showActive = lower.contains("active");
            boolean showInstalled = lower.contains("installed");
            boolean filterByName = false;
            String filterName = null;
            
            // Extract bundle name filter if present
            for (String word : lower.split("\\s+")) {
                if (word.contains("bundle") && word.length() > 6) {
                    // Try to extract name after "bundle"
                    int idx = lower.indexOf("bundle");
                    if (idx >= 0 && idx + 6 < lower.length()) {
                        String after = lower.substring(idx + 6).trim();
                        if (!after.isEmpty() && !after.equals("s")) {
                            filterName = after.split("\\s+")[0];
                            filterByName = true;
                            break;
                        }
                    }
                }
            }
            
            StringBuilder result = new StringBuilder();
            result.append("OSGi Bundles (").append(bundles.length).append(" total):\n\n");
            
            int count = 0;
            for (Object bundle : bundles) {
                try {
                    // Get bundle state
                    Method getStateMethod = bundle.getClass().getMethod("getState");
                    int state = (Integer) getStateMethod.invoke(bundle);
                    
                    // State constants: ACTIVE=32, INSTALLED=2, RESOLVED=4, STARTING=8, STOPPING=16
                    String stateName = getStateName(state);
                    
                    // Filter by state if requested
                    if (showActive && state != 32) continue;
                    if (showInstalled && state != 2) continue;
                    
                    // Get bundle info
                    Method getSymbolicNameMethod = bundle.getClass().getMethod("getSymbolicName");
                    Method getVersionMethod = bundle.getClass().getMethod("getVersion");
                    Method getBundleIdMethod = bundle.getClass().getMethod("getBundleId");
                    
                    String symbolicName = (String) getSymbolicNameMethod.invoke(bundle);
                    Object version = getVersionMethod.invoke(bundle);
                    long bundleId = ((Number) getBundleIdMethod.invoke(bundle)).longValue();
                    
                    // Filter by name if requested
                    if (filterByName && symbolicName != null && 
                        !symbolicName.toLowerCase().contains(filterName.toLowerCase())) {
                        continue;
                    }
                    
                    count++;
                    result.append(String.format("ID: %d | %s | %s\n", 
                        bundleId, stateName, symbolicName));
                    result.append(String.format("  Version: %s\n", version));
                    
                    // Get bundle location if available
                    try {
                        Method getLocationMethod = bundle.getClass().getMethod("getLocation");
                        String location = (String) getLocationMethod.invoke(bundle);
                        if (location != null && location.length() > 0) {
                            result.append(String.format("  Location: %s\n", 
                                location.length() > 80 ? location.substring(0, 80) + "..." : location));
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                    
                    result.append("\n");
                    
                    // Limit output
                    if (count >= 50 && !showAll) {
                        result.append("... (showing first 50 bundles, use 'all bundles' to see more)\n");
                        break;
                    }
                } catch (Exception e) {
                    log.debug("Error processing bundle", e);
                }
            }
            
            if (count == 0) {
                result.append("No bundles match the query criteria.");
            } else {
                result.append(String.format("\nTotal shown: %d", count));
            }
            
            return ToolResult.success(result.toString(), "osgi-bundles");
        } catch (Exception e) {
            log.error("Error querying OSGi bundles", e);
            return ToolResult.failure("Error querying OSGi bundles: " + e.getMessage());
        }
    }
    
    private String getStateName(int state) {
        switch (state) {
            case 1: return "UNINSTALLED";
            case 2: return "INSTALLED";
            case 4: return "RESOLVED";
            case 8: return "STARTING";
            case 16: return "STOPPING";
            case 32: return "ACTIVE";
            default: return "UNKNOWN(" + state + ")";
        }
    }
    
    /**
     * Get BundleContext using reflection.
     * Works by finding any OSGi bundle and getting its BundleContext.
     */
    private Object getBundleContext() {
        try {
            // Try FrameworkUtil.getBundle approach
            Class<?> frameworkUtilClass = Class.forName("org.osgi.framework.FrameworkUtil");
            Method getBundleMethod = frameworkUtilClass.getMethod("getBundle", Class.class);
            Object bundle = getBundleMethod.invoke(null, getClass());
            
            if (bundle != null) {
                Method getBundleContextMethod = bundle.getClass().getMethod("getBundleContext");
                return getBundleContextMethod.invoke(bundle);
            }
        } catch (Exception e) {
            log.debug("FrameworkUtil.getBundle approach failed", e);
        }
        
        // Fallback: try to get from system property or environment
        // This is a last resort and may not work
        return null;
    }
}

