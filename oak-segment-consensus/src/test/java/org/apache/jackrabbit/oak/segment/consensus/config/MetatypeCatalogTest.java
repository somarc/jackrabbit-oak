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
package org.apache.jackrabbit.oak.segment.consensus.config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MetatypeCatalogTest {

    @Test
    public void loadsGeneratedDescriptorWithDesignatedPid() {
        MetatypeCatalog.Definition definition = MetatypeCatalog.load(RuntimeUiTuningConfig.class);

        assertEquals("org.apache.jackrabbit.oak.segment.consensus.config.RuntimeUiTuningService", definition.pid);
        assertEquals(RuntimeUiTuningConfig.class.getName(), definition.ocdId);
        assertEquals("Oak Runtime UI Tuning", definition.name);
        assertEquals(2, definition.attributes.size());
        MetatypeCatalog.Attribute first = definition.attributes.get(0);
        assertEquals("browser.ui.enabled", first.id);
        assertEquals("Browser UI Enabled Override", first.name);
        assertEquals("String", first.type);
        assertEquals("", first.defaultValue);
        assertTrue(first.description.contains("oak.http.browser.ui.enabled"));
    }

    @Test
    public void returnsNullWhenNoDescriptorIsGenerated() {
        assertNull(MetatypeCatalog.load(String.class));
    }

    @Test
    public void fallsBackToOcdIdWithoutDesignate() throws Exception {
        MetatypeCatalog.Definition definition = MetatypeCatalog.parse(stream(
            "<MetaData><OCD id=\"ocd.id\" name=\"N\" description=\"D\">"
                + "<AD id=\"a.b\" type=\"Integer\" name=\"A\" description=\"x\" default=\"7\"/></OCD></MetaData>"));

        assertEquals("ocd.id", definition.pid);
        assertEquals("7", definition.attributes.get(0).defaultValue);
    }

    @Test
    public void rejectsDoctypeToPreventEntityExpansion() {
        assertThrows(Exception.class, () -> MetatypeCatalog.parse(stream(
            "<!DOCTYPE m [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><MetaData><OCD id=\"&x;\"/></MetaData>")));
    }

    @Test
    public void rejectsDescriptorWithoutOcd() {
        assertThrows(IllegalStateException.class, () -> MetatypeCatalog.parse(stream("<MetaData/>")));
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
