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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.segment.consensus.config.MetatypeCatalog;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeUiTuningConfig;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConfigConsoleViewTest {

    private static final String UI_PROPERTY = "oak.http.browser.ui.enabled";

    @After
    public void tearDown() {
        System.clearProperty(UI_PROPERTY);
    }

    @Test
    public void everyMappedConfigurationHasMetatypeAndRuntimeComponent() {
        Map<String, Object> components = new OsgiConfigApiHandler().buildComponents();

        assertEquals(components.keySet(), ConfigConsoleView.COMPONENT_OCDS.keySet());
        for (Class<?> ocd : ConfigConsoleView.COMPONENT_OCDS.values()) {
            assertNotNull(ocd.getName(), MetatypeCatalog.load(ocd));
        }
        for (Class<?> ocd : ConfigConsoleView.UNWIRED_OCDS) {
            assertNotNull(ocd.getName(), MetatypeCatalog.load(ocd));
        }
    }

    @Test
    public void matchesOsgiAttributesToRuntimeKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>(Arrays.asList(
            "node_id", "self_url_configured", "peer_urls_count", "max_message_batch"));

        assertEquals("node_id", ConfigConsoleView.matchRuntimeKey("nodeId", keys));
        assertEquals("self_url_configured", ConfigConsoleView.matchRuntimeKey("selfUrl", keys));
        assertEquals("peer_urls_count", ConfigConsoleView.matchRuntimeKey("peerUrls", keys));
        assertEquals("max_message_batch", ConfigConsoleView.matchRuntimeKey("max.message.batch", keys));
        assertNull(ConfigConsoleView.matchRuntimeKey("basePort", keys));
    }

    @Test
    public void matchesCompoundAttributeIdsThroughSchemaAlias() {
        Map<String, Map<String, Object>> schema = new LinkedHashMap<>();
        schema.put("aeron.cluster_base_port", meta(9000, "aeron.cluster.basePort"));
        schema.put("aeron.warn_logging_enabled", meta(true, "rate.limit.warn.logging.enabled"));
        LinkedHashSet<String> keys = new LinkedHashSet<>(Arrays.asList("cluster_base_port", "warn_logging_enabled"));

        assertEquals("cluster_base_port", ConfigConsoleView.matchByAlias("basePort", "aeron", keys, schema));
        assertNull(ConfigConsoleView.matchByAlias("enabled", "aeron", keys, schema));
    }

    @Test
    public void joinsDeclaredAttributesWithRuntimeValuesAndFlagsDrift() {
        System.setProperty(UI_PROPERTY, "false");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("browser_ui_enabled", false);
        values.put("undeclared_value", 7);
        Map<String, Map<String, Object>> schema = new LinkedHashMap<>();
        schema.put("runtimeUiTuning.browser_ui_enabled", meta(true, UI_PROPERTY));

        ConfigConsoleView.Config config = ConfigConsoleView.buildConfig(
            MetatypeCatalog.load(RuntimeUiTuningConfig.class), "runtimeUiTuning", "system-properties", values, schema);

        assertEquals("Oak Runtime UI Tuning", config.name);
        assertEquals(3, config.rows.size());
        ConfigConsoleView.Row bound = config.rows.get(0);
        assertEquals(ConfigConsoleView.Binding.BOUND, bound.binding);
        assertEquals("browser.ui.enabled", bound.osgiId);
        assertEquals("runtimeUiTuning.browser_ui_enabled", bound.runtimeKey);
        assertTrue(bound.changed);
        assertEquals(Collections.singletonList("-D"), bound.setVia);
        assertEquals(ConfigConsoleView.Binding.DECLARED_ONLY, config.rows.get(1).binding);
        assertEquals("external.dashboard.url", config.rows.get(1).osgiId);
        ConfigConsoleView.Row runtimeOnly = config.rows.get(2);
        assertEquals(ConfigConsoleView.Binding.RUNTIME_ONLY, runtimeOnly.binding);
        assertEquals("Undeclared value", runtimeOnly.label);
        assertEquals(1, config.changed());
    }

    @Test
    public void setViaReportsOsgiOnlyAndUnsetLayers() {
        assertEquals(Collections.singletonList("OSGi only"), ConfigConsoleView.setVia("osgi:AeronClusterConfig.selfUrl"));
        assertTrue(ConfigConsoleView.setVia("oak.unset.property|OAK_UNSET_PROPERTY_FOR_TEST").isEmpty());
        assertTrue(ConfigConsoleView.setVia(null).isEmpty());
    }

    @Test
    public void rendersValuesEscapedAndRedactsSecrets() {
        ConfigConsoleView.Row plain = row("plain.value", "tuning.plain_value");
        ConfigConsoleView.Row secret = row("auth.token", "tokenAuthTuning.auth_token");

        assertEquals("<code>&lt;b&gt;</code>", ConfigConsoleView.renderValue(plain, "<b>"));
        assertTrue(ConfigConsoleView.renderValue(secret, "s3cr3t").contains("redacted"));
        assertFalse(ConfigConsoleView.renderValue(secret, "s3cr3t").contains("s3cr3t"));
        assertTrue(ConfigConsoleView.renderValue(secret, true).contains("bool-true"));
        assertTrue(ConfigConsoleView.renderValue(plain, "").contains("empty"));
        assertTrue(ConfigConsoleView.renderValue(plain, null).contains("—"));
    }

    @Test
    public void tokensSummariseConfigurationsAndRuntimeModel() {
        Map<String, String> tokens = new ConfigConsoleView(new OsgiConfigApiHandler()).tokens();
        List<ConfigConsoleView.Config> configs = new ConfigConsoleView(new OsgiConfigApiHandler()).buildConfigs();

        assertEquals("inactive", tokens.get("{{CONFIG_ADMIN_STATE}}"));
        assertTrue(tokens.get("{{CONFIG_STATS}}").contains(">" + configs.size() + "</span>"));
        assertTrue(tokens.get("{{CONFIG_LIST}}").contains("Oak Proposal Queue Tuning"));
        assertTrue(tokens.get("{{CONFIG_LIST}}").contains("none — declared only"));
    }

    private static Map<String, Object> meta(Object defaultValue, String alias) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("default", defaultValue);
        meta.put("systemPropertyAlias", alias);
        meta.put("reloadMode", "startup-only");
        meta.put("risk", "safe");
        return meta;
    }

    private static ConfigConsoleView.Row row(String osgiId, String runtimeKey) {
        ConfigConsoleView.Row row = new ConfigConsoleView.Row();
        row.osgiId = osgiId;
        row.runtimeKey = runtimeKey;
        return row;
    }
}
