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
import java.util.Map;

/**
 * Tool for querying OSGi service information.
 */
public class OSGiServiceTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(OSGiServiceTool.class);
    
    @Override
    public String getName() {
        return "osgi-services";
    }
    
    @Override
    public String getDescription() {
        return "Query OSGi service registry - list services, find services by interface name, check service properties";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        return lower.contains("service") || lower.contains("osgi") ||
               (lower.contains("registered") && lower.contains("osgi"));
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            Object bundleContext = getBundleContext();
            if (bundleContext == null) {
                return ToolResult.failure("OSGi framework not available - cannot query services");
            }
            
            // Parse query
            String lower = query.toLowerCase();
            String filterInterface = null;
            
            // Try to extract interface name from query
            // Look for patterns like "service Servlet" or "services of type X"
            if (lower.contains("service") || lower.contains("servlet")) {
                String[] words = lower.split("\\s+");
                for (int i = 0; i < words.length - 1; i++) {
                    if (words[i].equals("service") || words[i].equals("services")) {
                        if (i + 1 < words.length && words[i + 1].length() > 2) {
                            filterInterface = words[i + 1];
                            // Capitalize first letter (Java convention)
                            filterInterface = filterInterface.substring(0, 1).toUpperCase() + 
                                            filterInterface.substring(1);
                            break;
                        }
                    }
                }
            }
            
            // Get all service references
            Method getAllServiceReferencesMethod = bundleContext.getClass()
                .getMethod("getAllServiceReferences", String.class, String.class);
            Object[] serviceRefs = (Object[]) getAllServiceReferencesMethod.invoke(
                bundleContext, filterInterface, null);
            
            if (serviceRefs == null || serviceRefs.length == 0) {
                return ToolResult.success(
                    filterInterface != null ? 
                        "No services found for interface: " + filterInterface :
                        "No services found in OSGi registry",
                    "osgi-services");
            }
            
            StringBuilder result = new StringBuilder();
            result.append("OSGi Services (").append(serviceRefs.length).append(" total");
            if (filterInterface != null) {
                result.append(" for interface: ").append(filterInterface);
            }
            result.append("):\n\n");
            
            int count = 0;
            for (Object serviceRef : serviceRefs) {
                try {
                    // Get service interface names
                    Method getPropertyMethod = serviceRef.getClass().getMethod("getProperty", String.class);
                    String[] interfaces = (String[]) getPropertyMethod.invoke(serviceRef, "objectClass");
                    
                    if (interfaces == null || interfaces.length == 0) {
                        continue;
                    }
                    
                    count++;
                    result.append(String.format("%d. Service Interfaces:\n", count));
                    for (String iface : interfaces) {
                        result.append("   - ").append(iface).append("\n");
                    }
                    
                    // Get bundle ID
                    try {
                        Method getBundleMethod = serviceRef.getClass().getMethod("getBundle");
                        Object bundle = getBundleMethod.invoke(serviceRef);
                        if (bundle != null) {
                            Method getBundleIdMethod = bundle.getClass().getMethod("getBundleId");
                            long bundleId = ((Number) getBundleIdMethod.invoke(bundle)).longValue();
                            result.append("   Bundle ID: ").append(bundleId).append("\n");
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                    
                    result.append("\n");
                    
                    // Limit output
                    if (count >= 30) {
                        result.append("... (showing first 30 services)\n");
                        break;
                    }
                } catch (Exception e) {
                    log.debug("Error processing service reference", e);
                }
            }
            
            if (count == 0) {
                result.append("No services match the query criteria.");
            } else {
                result.append(String.format("\nTotal shown: %d", count));
            }
            
            return ToolResult.success(result.toString(), "osgi-services");
        } catch (Exception e) {
            log.error("Error querying OSGi services", e);
            return ToolResult.failure("Error querying OSGi services: " + e.getMessage());
        }
    }
    
    private Object getBundleContext() {
        try {
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
        return null;
    }
}

