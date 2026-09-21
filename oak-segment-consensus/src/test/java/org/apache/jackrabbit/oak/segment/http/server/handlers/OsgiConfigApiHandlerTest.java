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

import org.junit.After;
import org.junit.Test;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OsgiConfigApiHandlerTest {

    private static final String PROP_CONSENSUS_ENABLED = "consensus.enabled";
    private static final String PROP_VERIFIER_THREADS = "oak.proposal.verifier.threads";
    private static final String PROP_BROWSER_UI_ENABLED = "oak.http.browser.ui.enabled";
    private static final String PROP_EXTERNAL_DASHBOARD_URL = "oak.dashboard.external.url";

    private final OsgiConfigApiHandler handler = new OsgiConfigApiHandler();

    @After
    public void tearDown() {
        System.clearProperty(PROP_CONSENSUS_ENABLED);
        System.clearProperty(PROP_VERIFIER_THREADS);
        System.clearProperty(PROP_BROWSER_UI_ENABLED);
        System.clearProperty(PROP_EXTERNAL_DASHBOARD_URL);
    }

    @Test
    public void testHandleEffectiveConfigIncludesNodeRuntimeAndUiTunings() throws Exception {
        System.setProperty(PROP_CONSENSUS_ENABLED, "true");
        System.setProperty(PROP_BROWSER_UI_ENABLED, "false");

        ResponseCapture capture = captureResponse();
        handler.handleEffectiveConfig(capture.response);

        verify(capture.response).setStatus(HttpServletResponse.SC_OK);
        verify(capture.response).setContentType("application/json; charset=UTF-8");
        String json = capture.body.toString();
        assertTrue(json.contains("\"contractVersion\":\"config.osgi.v1\""));
        assertTrue(json.contains("\"components\":"));
        assertTrue(json.contains("\"nodeRuntimeTuning\":"));
        assertTrue(json.contains("\"consensus_enabled\":true"));
        assertTrue(json.contains("\"runtimeUiTuning\":"));
        assertTrue(json.contains("\"browser_ui_enabled\":false"));
    }

    @Test
    public void testHandleConfigSourcesIncludesExpectedSourceFamilies() throws Exception {
        ResponseCapture capture = captureResponse();
        handler.handleConfigSources(capture.response);

        verify(capture.response).setStatus(HttpServletResponse.SC_OK);
        verify(capture.response).setContentType("application/json; charset=UTF-8");
        String json = capture.body.toString();
        assertTrue(json.contains("\"contractVersion\":\"config.osgi.sources.v1\""));
        assertTrue(json.contains("\"sources\":"));
        assertTrue(json.contains("\"nodeRuntimeTuning\":\"system-properties-or-env\""));
        assertTrue(json.contains("\"runtimeUiTuning\":\"system-properties\""));
        assertTrue(json.contains("\"aeronClusterTuning\":"));
    }

    @Test
    public void testHandleConfigSchemaIncludesNewRuntimeKnobs() throws Exception {
        ResponseCapture capture = captureResponse();
        handler.handleConfigSchema(capture.response);

        verify(capture.response).setStatus(HttpServletResponse.SC_OK);
        verify(capture.response).setContentType("application/json; charset=UTF-8");
        String json = capture.body.toString();
        assertTrue(json.contains("\"contractVersion\":\"config.osgi.schema.v1\""));
        assertTrue(json.contains("\"schema\":"));
        assertTrue(json.contains("\"key\":\"nodeRuntimeTuning.consensus_enabled\""));
        assertTrue(json.contains("\"key\":\"nodeRuntimeTuning.mock_epoch_duration_seconds\""));
        assertTrue(json.contains("\"key\":\"runtimeUiTuning.browser_ui_enabled\""));
        assertTrue(json.contains("\"systemPropertyAlias\":\"consensus.enabled\""));
    }

    @Test
    public void testHandleCoverageReportsCompleteSchemaCoverage() throws Exception {
        ResponseCapture capture = captureResponse();
        handler.handleCoverage(capture.response);

        verify(capture.response).setStatus(HttpServletResponse.SC_OK);
        verify(capture.response).setContentType("application/json; charset=UTF-8");
        String json = capture.body.toString();
        assertTrue(json.contains("\"contractVersion\":\"config.osgi.coverage.v1\""));
        assertTrue(json.contains("\"summary\":"));
        assertTrue(json.contains("\"missingTunables\":0"));
        assertTrue(json.contains("\"extraExposedTunables\":0"));
        assertTrue(json.contains("\"coveragePercent\":100.0"));
    }

    @Test
    public void testHandleDeltaReportsChangedOverridesAndJustification() throws Exception {
        System.setProperty(PROP_CONSENSUS_ENABLED, "true");
        System.setProperty(PROP_VERIFIER_THREADS, "4");
        System.setProperty(PROP_BROWSER_UI_ENABLED, "false");
        System.setProperty(PROP_EXTERNAL_DASHBOARD_URL, "https://dashboard.example");

        ResponseCapture capture = captureResponse();
        handler.handleDelta(capture.response);

        verify(capture.response).setStatus(HttpServletResponse.SC_OK);
        verify(capture.response).setContentType("application/json; charset=UTF-8");
        String json = capture.body.toString();
        assertTrue(json.contains("\"contractVersion\":\"config.osgi.delta.v1\""));
        assertTrue(json.contains("\"changed\":"));
        assertTrue(json.contains("\"key\":\"nodeRuntimeTuning.consensus_enabled\""));
        assertTrue(json.contains("\"current\":true"));
        assertTrue(json.contains("\"key\":\"proposalQueueTuning.verifier_threads\""));
        assertTrue(json.contains("\"current\":4"));
        assertTrue(json.contains("\"justification\":\"Raised to reduce verifier queue pressure and mempool buildup during sustained load.\""));
        assertTrue(json.contains("\"key\":\"runtimeUiTuning.browser_ui_enabled\""));
        assertTrue(json.contains("\"key\":\"runtimeUiTuning.external_dashboard_url_configured\""));
    }

    private static ResponseCapture captureResponse() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return new ResponseCapture(response, body);
    }

    private static final class ResponseCapture {
        private final HttpServletResponse response;
        private final StringWriter body;

        private ResponseCapture(HttpServletResponse response, StringWriter body) {
            this.response = response;
            this.body = body;
        }
    }
}
