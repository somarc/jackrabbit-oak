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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfigTuningConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.FileStoreFlushTuningConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.GcEconomicsTuningConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.HttpTlsTuningConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.MetatypeCatalog;
import org.apache.jackrabbit.oak.segment.consensus.config.NodeRuntimeTuningConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimePropertyOverrideRegistry;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeUiTuningConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.TokenAuthTuningConfig;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueTuningConfig;
import org.apache.jackrabbit.oak.segment.consensus.server.lifecycle.ValidatorConfig;
import org.apache.jackrabbit.oak.segment.http.server.RateLimiterTuningConfig;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;

/**
 * Read-only, Felix-console-style rendering of the validator configuration.
 *
 * <p>Joins the declared OSGi contract (generated metatype) with the effective
 * runtime values served by {@link OsgiConfigApiHandler}, so declared-but-unread
 * attributes and runtime values without a declaration are visible side by side.</p>
 */
final class ConfigConsoleView {

    static final String OSGI_CONFIG_ADMIN_SOURCE = "osgi-config-admin";

    /** Runtime component id of {@code /v1/config/osgi} → the OCD that declares it. */
    static final Map<String, Class<?>> COMPONENT_OCDS;
    static {
        Map<String, Class<?>> map = new LinkedHashMap<>();
        map.put("nodeRuntimeTuning", NodeRuntimeTuningConfig.class);
        map.put("aeronClusterTuning", AeronClusterConfig.class);
        map.put("proposalQueueTuning", ProposalQueueTuningConfig.class);
        map.put("blockchainTuning", BlockchainConfigTuningConfig.class);
        map.put("rateLimiterTuning", RateLimiterTuningConfig.class);
        map.put("tlsTuning", HttpTlsTuningConfig.class);
        map.put("tokenAuthTuning", TokenAuthTuningConfig.class);
        map.put("fileStoreFlushTuning", FileStoreFlushTuningConfig.class);
        map.put("gcEconomicsTuning", GcEconomicsTuningConfig.class);
        map.put("runtimeUiTuning", RuntimeUiTuningConfig.class);
        COMPONENT_OCDS = Collections.unmodifiableMap(map);
    }

    /** Declared configurations that no runtime component reads in the standalone validator. */
    static final List<Class<?>> UNWIRED_OCDS = Collections.singletonList(ValidatorConfig.class);

    private static final Pattern SECRET_NAME =
        Pattern.compile("(?i)(password|passphrase|secret|token|private|connection[._]?string)");
    private static final String[] RUNTIME_KEY_SUFFIXES = {"", "_configured", "_count"};

    enum Binding { BOUND, DECLARED_ONLY, RUNTIME_ONLY }

    static final class Row {
        String label;
        String osgiId;
        String runtimeKey;
        Object value;
        Object defaultValue;
        String alias;
        List<String> setVia = Collections.emptyList();
        String reloadMode;
        String risk;
        String description;
        boolean changed;
        Binding binding;
    }

    static final class Config {
        String name;
        String pid;
        String description;
        String componentId;
        String source;
        final List<Row> rows = new ArrayList<>();

        long count(Binding binding) {
            return rows.stream().filter(r -> r.binding == binding).count();
        }

        long changed() {
            return rows.stream().filter(r -> r.changed).count();
        }
    }

    private final OsgiConfigApiHandler api;

    ConfigConsoleView(OsgiConfigApiHandler api) {
        this.api = api;
    }

    List<Config> buildConfigs() {
        Map<String, Object> components = api.buildComponents();
        Map<String, Object> sources = api.buildSourcesMap();
        Map<String, Map<String, Object>> schema = new LinkedHashMap<>();
        for (Map<String, Object> entry : api.buildSchema()) {
            schema.put(String.valueOf(entry.get("key")), entry);
        }

        List<Config> configs = new ArrayList<>();
        for (Map.Entry<String, Class<?>> entry : COMPONENT_OCDS.entrySet()) {
            String componentId = entry.getKey();
            configs.add(buildConfig(MetatypeCatalog.load(entry.getValue()), componentId,
                String.valueOf(sources.get(componentId)), asMap(components.get(componentId)), schema));
        }
        for (Class<?> ocd : UNWIRED_OCDS) {
            configs.add(buildConfig(MetatypeCatalog.load(ocd), null, "not wired", Collections.emptyMap(), schema));
        }
        return configs;
    }

    static Config buildConfig(MetatypeCatalog.Definition definition,
                              String componentId,
                              String source,
                              Map<String, Object> values,
                              Map<String, Map<String, Object>> schema) {
        Config config = new Config();
        config.componentId = componentId;
        config.source = source;
        config.name = definition != null ? definition.name : componentId;
        config.pid = definition != null ? definition.pid : "";
        config.description = definition != null ? definition.description : "";

        Set<String> unclaimed = new LinkedHashSet<>(values.keySet());
        if (definition != null) {
            for (MetatypeCatalog.Attribute attribute : definition.attributes) {
                String runtimeKey = matchRuntimeKey(attribute.id, unclaimed);
                if (runtimeKey == null) {
                    runtimeKey = matchByAlias(attribute.id, componentId, unclaimed, schema);
                }
                Row row = new Row();
                row.label = attribute.name;
                row.osgiId = attribute.id;
                row.description = attribute.description;
                row.defaultValue = attribute.defaultValue;
                if (runtimeKey != null) {
                    unclaimed.remove(runtimeKey);
                    bindRuntime(row, componentId, runtimeKey, values.get(runtimeKey), schema);
                } else {
                    row.binding = Binding.DECLARED_ONLY;
                }
                config.rows.add(row);
            }
        }
        for (String runtimeKey : unclaimed) {
            Row row = new Row();
            row.label = humanize(runtimeKey);
            bindRuntime(row, componentId, runtimeKey, values.get(runtimeKey), schema);
            row.binding = Binding.RUNTIME_ONLY;
            config.rows.add(row);
        }
        return config;
    }

    private static void bindRuntime(Row row, String componentId, String runtimeKey, Object value,
                                    Map<String, Map<String, Object>> schema) {
        Map<String, Object> meta = schema.getOrDefault(componentId + "." + runtimeKey, Collections.emptyMap());
        row.binding = Binding.BOUND;
        row.runtimeKey = componentId + "." + runtimeKey;
        row.value = value;
        if (meta.containsKey("default")) {
            row.defaultValue = meta.get("default");
            row.changed = !OsgiConfigApiHandler.looselyEqual(value, meta.get("default"));
        }
        row.alias = meta.get("systemPropertyAlias") != null ? String.valueOf(meta.get("systemPropertyAlias")) : null;
        row.setVia = setVia(row.alias);
        row.reloadMode = meta.get("reloadMode") != null ? String.valueOf(meta.get("reloadMode")) : null;
        row.risk = meta.get("risk") != null ? String.valueOf(meta.get("risk")) : null;
        if (row.description == null || row.description.isEmpty()) {
            row.description = meta.get("description") != null ? String.valueOf(meta.get("description")) : "";
        }
    }

    static String matchRuntimeKey(String osgiId, Set<String> runtimeKeys) {
        String snake = toSnakeCase(osgiId);
        for (String suffix : RUNTIME_KEY_SUFFIXES) {
            if (runtimeKeys.contains(snake + suffix)) {
                return snake + suffix;
            }
        }
        return null;
    }

    /** Compound attribute ids (camelCase or dotted) may be bridged to a differently named runtime key. */
    static String matchByAlias(String osgiId, String componentId, Set<String> runtimeKeys,
                               Map<String, Map<String, Object>> schema) {
        if (osgiId.equals(osgiId.toLowerCase(Locale.ROOT)) && osgiId.indexOf('.') < 0) {
            return null;
        }
        for (String runtimeKey : runtimeKeys) {
            Object alias = schema.getOrDefault(componentId + "." + runtimeKey, Collections.emptyMap())
                .get("systemPropertyAlias");
            if (alias != null && String.valueOf(alias).split("\\|")[0].endsWith("." + osgiId)) {
                return runtimeKey;
            }
        }
        return null;
    }

    static String toSnakeCase(String id) {
        return id.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replace('.', '_').toLowerCase(Locale.ROOT);
    }

    /** Which configuration layers currently carry a value for the alias ({@code sysprop|ENV}). */
    static List<String> setVia(String alias) {
        if (alias == null || alias.isEmpty()) {
            return Collections.emptyList();
        }
        if (alias.startsWith("osgi:")) {
            return Collections.singletonList("OSGi only");
        }
        String[] names = alias.split("\\|");
        List<String> layers = new ArrayList<>();
        if (RuntimePropertyOverrideRegistry.get(names[0]) != null) {
            layers.add("OSGi");
        }
        if (System.getProperty(names[0]) != null) {
            layers.add("-D");
        }
        if (names.length > 1 && System.getenv(names[1]) != null) {
            layers.add("env");
        }
        return layers;
    }

    Map<String, String> tokens() {
        List<Config> configs = buildConfigs();
        boolean configAdminBound = configs.stream().anyMatch(c -> OSGI_CONFIG_ADMIN_SOURCE.equals(c.source));
        long wired = configs.stream().filter(c -> c.componentId != null).count();
        long declared = 0, bound = 0, changed = 0;
        for (Config config : configs) {
            declared += config.count(Binding.BOUND) + config.count(Binding.DECLARED_ONLY);
            bound += config.count(Binding.BOUND);
            changed += config.changed();
        }

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < configs.size(); i++) {
            renderConfig(rows, configs.get(i), i);
        }

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("{{CONFIG_ADMIN_STATLINE}}", configAdminBound
            ? "Configuration Admin Service is running."
            : "Configuration Admin Service is not running. This standalone validator resolves values from -D system "
                + "properties, environment variables and code defaults; the OSGi declarations document the contract.");
        tokens.put("{{CONFIG_ADMIN_STATE}}", configAdminBound ? "running" : "missing");
        tokens.put("{{CONFIG_STATLINE}}", "Configuration information: " + configs.size() + " configurations in total - "
            + wired + " bound, " + (configs.size() - wired) + " unbound. " + declared + " declared properties, "
            + bound + " read at runtime, " + changed + " changed from default.");
        tokens.put("{{CONFIG_ROWS}}", rows.toString());
        return tokens;
    }

    private static void renderConfig(StringBuilder html, Config config, int index) {
        long drift = config.count(Binding.DECLARED_ONLY) + config.count(Binding.RUNTIME_ONLY);
        String zebra = index % 2 == 0 ? "even" : "odd";
        html.append("<tr class=\"config-row ").append(zebra).append("\">")
            .append("<td class=\"col_Name\"><span class=\"toggle\" aria-hidden=\"true\"></span>")
            .append("<span class=\"bName\">").append(esc(config.name)).append("</span> ")
            .append("<span class=\"symName\">").append(esc(config.pid)).append("</span></td>")
            .append("<td>").append(esc(config.source)).append("</td>")
            .append("<td class=\"num\">").append(config.rows.size()).append("</td>")
            .append("<td class=\"num").append(config.changed() > 0 ? " hl" : "").append("\">")
            .append(config.changed()).append("</td>")
            .append("<td class=\"num").append(drift > 0 ? " drift" : "").append("\">").append(drift).append("</td>")
            .append("<td>").append(config.componentId == null ? "Unbound" : "Bound").append("</td></tr>");

        html.append("<tr class=\"detail-row ").append(zebra).append("\" hidden><td colspan=\"6\"><div class=\"editor\">");
        if (!config.description.isEmpty()) {
            html.append("<p class=\"configDescription\">").append(esc(config.description)).append("</p>");
        }
        html.append("<table class=\"editorTable\"><tbody>");
        for (Row row : config.rows) {
            renderRow(html, row);
        }
        html.append("<tr><th colspan=\"2\">Configuration Information</th></tr>")
            .append(infoRow("Persistent Identity (PID)", "<code>" + esc(config.pid) + "</code>"))
            .append(infoRow("Configuration Binding", config.componentId == null
                ? "Unbound: no runtime component reads this configuration"
                : "Runtime component <code>" + esc(config.componentId) + "</code>"))
            .append(infoRow("Value Source", esc(config.source)))
            .append("</tbody></table></div></td></tr>");
    }

    private static String infoRow(String label, String valueHtml) {
        return "<tr><td class=\"minWidthCell\">" + label + "</td><td class=\"paddedCell\">" + valueHtml + "</td></tr>";
    }

    private static void renderRow(StringBuilder html, Row row) {
        html.append("<tr class=\"prop ").append(row.binding.name().toLowerCase(Locale.ROOT).replace('_', '-'))
            .append(row.changed ? " changed" : "").append("\"><td class=\"minWidthCell\">").append(esc(row.label))
            .append("</td><td class=\"paddedCell\">");
        if (row.binding == Binding.DECLARED_ONLY) {
            html.append("<span class=\"ro-field na\">not read at runtime</span>");
        } else {
            html.append(renderValue(row, row.value));
        }
        if (row.changed) {
            html.append("<span class=\"flag changed\">changed from default</span>");
        } else if (row.binding == Binding.RUNTIME_ONLY) {
            html.append("<span class=\"flag drift\">not declared in metatype</span>");
        }
        html.append("<div class=\"topPaddedText\">").append(esc(nullToEmpty(row.description)));
        if (row.osgiId != null) {
            html.append(" <span class=\"pid\">").append(esc(row.osgiId)).append("</span>");
        }
        html.append("</div><div class=\"meta\">");
        List<String> meta = new ArrayList<>();
        if (row.defaultValue != null) {
            meta.add("Default: " + renderDefault(row));
        }
        if (row.alias != null && !row.alias.startsWith("osgi:")) {
            String[] names = row.alias.split("\\|");
            meta.add("Read from: <code>-D" + esc(names[0]) + "</code>"
                + (names.length > 1 ? " <code>$" + esc(names[1]) + "</code>" : ""));
        }
        if (row.binding != Binding.DECLARED_ONLY) {
            meta.add("Set via: " + (row.setVia.isEmpty()
                ? (row.changed ? "derived" : "default") : esc(String.join(", ", row.setVia))));
        }
        if (row.reloadMode != null) {
            meta.add(esc(row.reloadMode));
        }
        if (row.risk != null) {
            meta.add("<span class=\"risk-" + esc(row.risk) + "\">" + esc(row.risk) + "</span>");
        }
        if (row.runtimeKey != null) {
            meta.add("Key: <code>" + esc(row.runtimeKey) + "</code>");
        }
        html.append(String.join(" &middot; ", meta)).append("</div></td></tr>");
    }

    private static String renderDefault(Row row) {
        String text = String.valueOf(row.defaultValue);
        if (text.isEmpty()) {
            return "<em>empty</em>";
        }
        return isSecret(row) ? "<em>redacted</em>" : "<code>" + esc(text) + "</code>";
    }

    static String renderValue(Row row, Object value) {
        if (value == null) {
            return "<span class=\"ro-field empty\">&nbsp;</span>";
        }
        if (value instanceof Boolean) {
            boolean on = (Boolean) value;
            return "<label class=\"ro-check bool-" + on + "\"><input type=\"checkbox\" disabled" + (on ? " checked" : "")
                + "> " + on + "</label>";
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return "<span class=\"ro-field empty\">&nbsp;</span>";
        }
        if (isSecret(row)) {
            return "<span class=\"ro-field redacted\" title=\"redacted\">&bull;&bull;&bull;&bull;&bull;&bull;&bull;&bull;</span>";
        }
        return "<span class=\"ro-field\">" + esc(text) + "</span>";
    }

    static boolean isSecret(Row row) {
        return SECRET_NAME.matcher(nullToEmpty(row.osgiId) + " " + nullToEmpty(row.runtimeKey)
            + " " + nullToEmpty(row.alias)).find();
    }

    private static String humanize(String key) {
        String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String esc(String value) {
        return FormatUtils.escapeHtml(value);
    }
}
