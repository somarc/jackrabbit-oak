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
package org.apache.jackrabbit.oak.segment.consensus.server.lifecycle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple registry for lifecycle-managed components.
 */
public class ComponentRegistry {

    private final Map<String, Object> components = new ConcurrentHashMap<>();

    public void register(String name, Object component) {
        if (name != null && component != null) {
            components.put(name, component);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String name, Class<T> type) {
        Object component = components.get(name);
        if (component == null) {
            return null;
        }
        if (type.isInstance(component)) {
            return (T) component;
        }
        return null;
    }
}
