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
package org.apache.jackrabbit.oak.segment.http.server.util;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.jackrabbit.oak.segment.consensus.mount.MountReadFailures;
import org.apache.jackrabbit.oak.segment.consensus.mount.RemoteMounts;
import org.junit.After;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ApiErrorUtilTest {

    @After
    public void endRequest() {
        MountReadFailures.end();
    }

    @Test
    public void testA500CausedByARemoteMountIsAnswered503() throws Exception {
        RemoteMounts mounts = mock(RemoteMounts.class);
        when(mounts.reportReadFailures(anyList())).thenReturn(true);
        MountReadFailures.begin(mounts);
        MountReadFailures.record("http://b:8090", MountReadFailures.Kind.MISSING, "segment gone");
        StringWriter body = new StringWriter();
        HttpServletResponse response = response(body);

        ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Segment not found");

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(response).setHeader("Retry-After", "1");
        assertTrue(body.toString(), body.toString().contains("\"code\":\"mount_stale\""));
    }

    @Test
    public void testOtherErrorsAreUnchanged() throws Exception {
        RemoteMounts mounts = mock(RemoteMounts.class);
        when(mounts.reportReadFailures(anyList())).thenReturn(true);
        MountReadFailures.begin(mounts);
        MountReadFailures.record("http://b:8090", MountReadFailures.Kind.MISSING, "segment gone");
        HttpServletResponse badRequest = response(new StringWriter());
        ApiErrorUtil.sendJsonError(badRequest, HttpServletResponse.SC_BAD_REQUEST, "bad input");
        verify(badRequest).setStatus(HttpServletResponse.SC_BAD_REQUEST);

        MountReadFailures.end();
        StringWriter body = new StringWriter();
        HttpServletResponse local = response(body);
        ApiErrorUtil.sendJsonError(local, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "local failure");
        verify(local).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        verify(local, never()).setHeader(anyString(), anyString());
        assertTrue(body.toString().contains("local failure"));
    }

    private static HttpServletResponse response(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }
}
