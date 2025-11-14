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
 * Tool for querying OSGi component information.
 * Note: Component information is typically accessed via SCR (Service Component Runtime).
 */
public class OSGiComponentTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(OSGiComponentTool.class);
    
    @Override
    public String getName() {
        return "osgi-components";
    }
    
    @Override
    public String getDescription() {
        return "Query OSGi component information - list components, check component states, find components by name";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        return lower.contains("component") || 
               (lower.contains("osgi") && (lower.contains("state") || lower.contains("active")));
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            Object bundleContext = getBundleContext();
            if (bundleContext == null) {
                return ToolResult.failure("OSGi framework not available - cannot query components");
            }
            
            // Components are typically accessed via Service Component Runtime (SCR)
            // Try to get SCR service
            Object scrService = getSCRService(bundleContext);
            if (scrService == null) {
                // Fallback: use services that implement component interfaces
                return queryComponentsViaServices(bundleContext, query);
            }
            
            // Query SCR for components
            return queryComponentsViaSCR(scrService, query);
        } catch (Exception e) {
            log.error("Error querying OSGi components", e);
            return ToolResult.failure("Error querying OSGi components: " + e.getMessage());
        }
    }
    
    private ToolResult queryComponentsViaSCR(Object scrService, String query) {
        // SCR API is complex - for now, return a message
        return ToolResult.success(
            "OSGi Component Runtime (SCR) is available. " +
            "Component queries are best done via the Felix Web Console at /system/console/components",
            "osgi-components");
    }
    
    private ToolResult queryComponentsViaServices(Object bundleContext, String query) {
        try {
            // Get services that might be components
            // Components typically register as services
            Method getAllServiceReferencesMethod = bundleContext.getClass()
                .getMethod("getAllServiceReferences", String.class, String.class);
            Object[] serviceRefs = (Object[]) getAllServiceReferencesMethod.invoke(
                bundleContext, null, null);
            
            if (serviceRefs == null || serviceRefs.length == 0) {
                return ToolResult.success("No services/components found", "osgi-components");
            }
            
            StringBuilder result = new StringBuilder();
            result.append("OSGi Components (via service registry, ").append(serviceRefs.length).append(" found):\n\n");
            result.append("Note: Components are typically registered as OSGi services.\n");
            result.append("For detailed component information, use the Felix Web Console at /system/console/components\n\n");
            
            // Show a sample of services that are likely components
            int count = 0;
            for (Object serviceRef : serviceRefs) {
                try {
                    Method getPropertyMethod = serviceRef.getClass().getMethod("getProperty", String.class);
                    String[] interfaces = (String[]) getPropertyMethod.invoke(serviceRef, "objectClass");
                    
                    if (interfaces == null || interfaces.length == 0) {
                        continue;
                    }
                    
                    // Filter to show interesting ones
                    boolean isInteresting = false;
                    for (String iface : interfaces) {
                        if (iface.contains("Servlet") || iface.contains("Component") || 
                            iface.contains("Service") || iface.contains("Handler")) {
                            isInteresting = true;
                            break;
                        }
                    }
                    
                    if (!isInteresting && count > 10) {
                        continue;
                    }
                    
                    count++;
                    result.append(String.format("%d. ", count));
                    for (int i = 0; i < Math.min(interfaces.length, 2); i++) {
                        if (i > 0) result.append(", ");
                        result.append(interfaces[i]);
                    }
                    if (interfaces.length > 2) {
                        result.append(" (+").append(interfaces.length - 2).append(" more)");
                    }
                    result.append("\n");
                    
                    if (count >= 20) {
                        result.append("\n... (showing first 20, use Felix Web Console for complete list)\n");
                        break;
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
            
            return ToolResult.success(result.toString(), "osgi-components");
        } catch (Exception e) {
            return ToolResult.failure("Error querying components: " + e.getMessage());
        }
    }
    
    private Object getSCRService(Object bundleContext) {
        try {
            // Try to get SCR service
            Method getServiceReferenceMethod = bundleContext.getClass()
                .getMethod("getServiceReference", String.class);
            Object serviceRef = getServiceReferenceMethod.invoke(
                bundleContext, "org.apache.felix.scr.Component");
            
            if (serviceRef != null) {
                Method getServiceMethod = bundleContext.getClass()
                    .getMethod("getService", java.lang.reflect.ParameterizedType.class);
                return getServiceMethod.invoke(bundleContext, serviceRef);
            }
        } catch (Exception e) {
            log.debug("SCR service not available", e);
        }
        return null;
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

