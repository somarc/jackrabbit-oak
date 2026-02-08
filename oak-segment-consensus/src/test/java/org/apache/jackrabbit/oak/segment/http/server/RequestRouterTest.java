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
package org.apache.jackrabbit.oak.segment.http.server;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.eclipse.jetty.server.Request;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RequestRouterTest {

    @Test
    public void testApiBrowserDisabledWhenHeadlessToggleIsFalse() throws Exception {
        String previous = System.getProperty("oak.http.browser.ui.enabled");
        String previousRateLimit = System.getProperty("rate.limit.enabled");
        try {
            System.setProperty("oak.http.browser.ui.enabled", "false");
            System.setProperty("rate.limit.enabled", "false");

            ServerContext context = new ServerContext(
                mock(FileStore.class),
                mock(NodeStore.class),
                Paths.get("/tmp/store"),
                "http://localhost:8090"
            );
            RequestRouter router = new RequestRouter(context);

            Request baseRequest = mock(Request.class);
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);

            StringWriter body = new StringWriter();
            when(response.getWriter()).thenReturn(new PrintWriter(body));
            when(request.getRequestURI()).thenReturn("/api-browser");
            when(request.getMethod()).thenReturn("GET");
            when(request.getRemoteAddr()).thenReturn("127.0.0.1");

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_GONE);
            assertTrue(body.toString().contains("Browser UI routes are disabled"));
        } finally {
            if (previous == null) {
                System.clearProperty("oak.http.browser.ui.enabled");
            } else {
                System.setProperty("oak.http.browser.ui.enabled", previous);
            }
            if (previousRateLimit == null) {
                System.clearProperty("rate.limit.enabled");
            } else {
                System.setProperty("rate.limit.enabled", previousRateLimit);
            }
        }
    }
}
