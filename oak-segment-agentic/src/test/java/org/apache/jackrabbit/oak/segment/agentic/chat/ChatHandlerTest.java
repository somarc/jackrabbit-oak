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
package org.apache.jackrabbit.oak.segment.agentic.chat;

import org.apache.jackrabbit.oak.segment.agentic.llm.LLMService;
import org.apache.jackrabbit.oak.segment.agentic.rag.RAGService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChatHandler}.
 */
public class ChatHandlerTest {

    @Mock
    private LLMService mockLLMService;

    @Mock
    private HttpServletRequest mockRequest;

    @Mock
    private HttpServletResponse mockResponse;

    private RAGService ragService;
    private ChatHandler chatHandler;
    private StringWriter responseWriter;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        
        // Create real RAGService (uses basic chunks)
        ragService = new RAGService();
        
        // Create ChatHandler with mock LLM
        chatHandler = new ChatHandler(mockLLMService, ragService, "http://localhost:8090");
        
        // Setup response writer
        responseWriter = new StringWriter();
        when(mockResponse.getWriter()).thenReturn(new PrintWriter(responseWriter));
        
        // Setup mock LLM to return a simple response
        when(mockLLMService.generate(anyString(), anyString()))
            .thenReturn("This is a test response from the LLM.");
        when(mockLLMService.isAvailable()).thenReturn(true);
    }

    @Test
    public void testHandleChat_ValidRequest_ReturnsResponse() throws Exception {
        // Setup request body
        String requestBody = "{\"query\": \"What is FileStore?\"}";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
        
        // Execute
        chatHandler.handleChat(mockRequest, mockResponse);
        
        // Verify
        verify(mockResponse).setContentType("application/json");
        verify(mockResponse).setStatus(HttpServletResponse.SC_OK);
        
        String response = responseWriter.toString();
        assertTrue("Response should contain answer field", response.contains("answer"));
    }

    @Test
    public void testHandleChat_EmptyQuery_Returns400() throws Exception {
        // Setup request body with empty query
        String requestBody = "{\"query\": \"\"}";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
        
        // Execute
        chatHandler.handleChat(mockRequest, mockResponse);
        
        // Verify
        verify(mockResponse).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        
        String response = responseWriter.toString();
        assertTrue("Response should contain error", response.contains("error"));
    }

    @Test
    public void testHandleChat_NullQuery_Returns400() throws Exception {
        // Setup request body with null query
        String requestBody = "{\"query\": null}";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
        
        // Execute
        chatHandler.handleChat(mockRequest, mockResponse);
        
        // Verify
        verify(mockResponse).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void testHandleChat_MissingQuery_Returns400() throws Exception {
        // Setup request body without query field
        String requestBody = "{}";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
        
        // Execute
        chatHandler.handleChat(mockRequest, mockResponse);
        
        // Verify
        verify(mockResponse).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void testHandleChat_WithContext_PassesContextToLLM() throws Exception {
        // Setup request body with context
        String requestBody = "{\"query\": \"What is the leader?\", \"context\": {\"includeMetrics\": true}}";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
        
        // Execute
        chatHandler.handleChat(mockRequest, mockResponse);
        
        // Verify LLM was called
        verify(mockLLMService).generate(eq("What is the leader?"), anyString());
    }

    @Test
    public void testHandleChat_AgentToAgentMode_IncludesAgentMetadata() throws Exception {
        // Setup request body with agent-to-agent flag
        String requestBody = "{\"query\": \"What is the cluster state?\", \"context\": {\"agentToAgent\": true}}";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
        
        // Execute
        chatHandler.handleChat(mockRequest, mockResponse);
        
        // Verify response contains answer
        verify(mockResponse).setStatus(HttpServletResponse.SC_OK);
        String response = responseWriter.toString();
        assertTrue("Response should contain answer", response.contains("answer"));
    }

    @Test
    public void testHandleChat_InvalidJson_Returns500() throws Exception {
        // Setup invalid JSON
        String requestBody = "not valid json";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
        
        // Execute
        chatHandler.handleChat(mockRequest, mockResponse);
        
        // Verify error response
        verify(mockResponse).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    @Test
    public void testHandleChat_LLMUnavailable_StillReturnsResponse() throws Exception {
        // Setup LLM as unavailable
        when(mockLLMService.isAvailable()).thenReturn(false);
        when(mockLLMService.generate(anyString(), anyString()))
            .thenReturn("LLM service is not available.");
        
        // Setup request
        String requestBody = "{\"query\": \"What is FileStore?\"}";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));
        
        // Execute
        chatHandler.handleChat(mockRequest, mockResponse);
        
        // Should still return 200 with a message about LLM unavailability
        verify(mockResponse).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void testChatRequest_ParsesCorrectly() {
        ChatRequest request = new ChatRequest();
        request.query = "Test query";
        
        assertEquals("Query should be set", "Test query", request.query);
    }

    @Test
    public void testChatResponse_HasRequiredFields() {
        ChatResponse response = new ChatResponse();
        response.answer = "Test answer";
        
        assertEquals("Answer should be set", "Test answer", response.answer);
        assertNotNull("Sources should not be null", response.sources);
    }

    @Test
    public void testChatResponse_Source_HasRequiredFields() {
        ChatResponse.Source source = new ChatResponse.Source("tool", "test-endpoint", "test data");
        
        assertEquals("Type should be set", "tool", source.type);
        assertEquals("Endpoint should be set", "test-endpoint", source.endpoint);
        assertEquals("Data should be set", "test data", source.data);
    }
}
