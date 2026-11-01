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

import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.blob.data.DataStoreException;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IpfsOpsHandlerTest {

    private final StringWriter body = new StringWriter();

    @Test
    public void testHealthReportsTheDataStoreStatus() throws Exception {
        IPFSDataStore ipfs = mock(IPFSDataStore.class);
        when(ipfs.checkHealth()).thenReturn(Map.of("status", "UP", "encryption", Map.of("enabled", true)));
        HttpServletResponse response = response();

        new IpfsOpsHandler(context(new DataStoreBlobStore(ipfs))).handleHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertTrue(body.toString().contains("\"enabled\":true"));

        when(ipfs.checkHealth()).thenReturn(Map.of("status", "DOWN"));
        HttpServletResponse down = response();
        new IpfsOpsHandler(context(new DataStoreBlobStore(ipfs))).handleHealth(down);
        verify(down).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    public void testVerifyPassesTheLimitAndRejectsBadOnes() throws Exception {
        IPFSDataStore ipfs = mock(IPFSDataStore.class);
        when(ipfs.verifyRetention(1000)).thenReturn(Map.of("problems", 0));
        when(ipfs.verifyRetention(5)).thenReturn(Map.of("problems", 2));
        IpfsOpsHandler handler = new IpfsOpsHandler(context(new DataStoreBlobStore(ipfs)));

        HttpServletResponse defaults = response();
        handler.handleVerify(request(null), defaults);
        verify(defaults).setStatus(HttpServletResponse.SC_OK);
        assertTrue(body.toString().contains("\"problems\":0"));

        HttpServletResponse limited = response();
        handler.handleVerify(request("5"), limited);
        assertTrue(body.toString().contains("\"problems\":2"));

        for (String limit : new String[] {"0", "100001", "lots"}) {
            HttpServletResponse bad = response();
            handler.handleVerify(request(limit), bad);
            verify(bad).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }

        when(ipfs.verifyRetention(7)).thenThrow(new DataStoreException("Kubo unreachable"));
        HttpServletResponse failed = response();
        handler.handleVerify(request("7"), failed);
        verify(failed).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(body.toString().contains("ipfs_verify_failed"));
    }

    @Test
    public void testOtherBlobStoresAreNotFound() throws Exception {
        HttpServletResponse response = response();

        new IpfsOpsHandler(context(mock(BlobStore.class))).handleHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        assertTrue(body.toString().contains("ipfs_not_configured"));
    }

    private static ServerContext context(BlobStore blobStore) {
        ServerContext context = new ServerContext(mock(FileStore.class), mock(NodeStore.class), Paths.get("/tmp/store"),
            "http://localhost:8090");
        context.blobStore = blobStore;
        return context;
    }

    private static HttpServletRequest request(String limit) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("limit")).thenReturn(limit);
        return request;
    }

    private HttpServletResponse response() throws Exception {
        body.getBuffer().setLength(0);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));
        return response;
    }
}
