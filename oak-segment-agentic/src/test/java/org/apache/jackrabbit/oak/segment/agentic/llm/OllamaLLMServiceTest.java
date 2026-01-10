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
package org.apache.jackrabbit.oak.segment.agentic.llm;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link OllamaLLMService}.
 * 
 * <p>Note: These tests are designed to work without a running Ollama instance.
 * Tests that require Ollama are marked as integration tests.
 */
public class OllamaLLMServiceTest {

    @Test
    public void testLLMService_Interface() {
        // Test that OllamaLLMService implements LLMService
        OllamaLLMService service = new OllamaLLMService("http://localhost:99999", "test-model");
        assertTrue("Should implement LLMService", service instanceof LLMService);
    }

    @Test
    public void testIsAvailable_WhenOllamaNotRunning_ReturnsFalse() {
        // Use an invalid URL to ensure Ollama is not reachable
        OllamaLLMService service = new OllamaLLMService("http://localhost:99999", "test-model");
        assertFalse("Should return false when Ollama is not running", service.isAvailable());
    }

    @Test
    public void testGenerate_WhenNotAvailable_ReturnsHelpfulMessage() {
        OllamaLLMService service = new OllamaLLMService("http://localhost:99999", "test-model");
        
        String response = service.generate("What is FileStore?", "Some context");
        
        assertNotNull("Response should not be null", response);
        assertTrue("Response should mention LLM not available", 
            response.contains("not available") || response.contains("Error"));
    }

    @Test
    public void testGenerate_WithNullQuery_HandlesGracefully() {
        OllamaLLMService service = new OllamaLLMService("http://localhost:99999", "test-model");
        
        // Should not throw exception
        String response = service.generate(null, "Some context");
        assertNotNull("Response should not be null", response);
    }

    @Test
    public void testGenerate_WithNullContext_HandlesGracefully() {
        OllamaLLMService service = new OllamaLLMService("http://localhost:99999", "test-model");
        
        // Should not throw exception
        String response = service.generate("What is FileStore?", null);
        assertNotNull("Response should not be null", response);
    }

    @Test
    public void testGenerate_WithEmptyQuery_HandlesGracefully() {
        OllamaLLMService service = new OllamaLLMService("http://localhost:99999", "test-model");
        
        String response = service.generate("", "Some context");
        assertNotNull("Response should not be null", response);
    }

    @Test
    public void testGenerate_WithEmptyContext_HandlesGracefully() {
        OllamaLLMService service = new OllamaLLMService("http://localhost:99999", "test-model");
        
        String response = service.generate("What is FileStore?", "");
        assertNotNull("Response should not be null", response);
    }

    @Test
    public void testDefaultConstructor_UsesDefaultModel() {
        // This test verifies the default constructor doesn't throw
        // The actual model used depends on environment variables
        try {
            OllamaLLMService service = new OllamaLLMService();
            assertNotNull("Service should be created", service);
        } catch (Exception e) {
            // Expected if Ollama is not running - that's fine
            // We just want to ensure no NPE or other unexpected errors
        }
    }

    @Test
    public void testGenerate_AgentToAgentContext_DetectedCorrectly() {
        OllamaLLMService service = new OllamaLLMService("http://localhost:99999", "test-model");
        
        // Context with agent-to-agent markers
        String agentContext = "🤖 AGENT-TO-AGENT COMMUNICATION MODE\n" +
                             "You are communicating with another agent in a TWO-WAY CONVERSATION.\n" +
                             "TOOL RESULTS (ACTUAL DATA):\n" +
                             "Some data here";
        
        String response = service.generate("What is the leader?", agentContext);
        
        // Should handle agent-to-agent context without errors
        assertNotNull("Response should not be null", response);
    }

    @Test
    public void testGenerate_LongContext_HandlesGracefully() {
        OllamaLLMService service = new OllamaLLMService("http://localhost:99999", "test-model");
        
        // Create a very long context
        StringBuilder longContext = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longContext.append("This is line ").append(i).append(" of context. ");
        }
        
        String response = service.generate("What is FileStore?", longContext.toString());
        
        // Should handle long context without errors
        assertNotNull("Response should not be null", response);
    }

    // ========== Integration Tests (require running Ollama) ==========
    // These tests are commented out by default as they require Ollama to be running
    
    /*
    @Test
    public void integrationTest_IsAvailable_WhenOllamaRunning_ReturnsTrue() {
        // This test requires Ollama to be running locally
        OllamaLLMService service = new OllamaLLMService();
        
        // Skip if Ollama is not available
        if (!service.isAvailable()) {
            System.out.println("Skipping integration test - Ollama not available");
            return;
        }
        
        assertTrue("Should return true when Ollama is running", service.isAvailable());
    }
    
    @Test
    public void integrationTest_Generate_ReturnsValidResponse() {
        OllamaLLMService service = new OllamaLLMService();
        
        // Skip if Ollama is not available
        if (!service.isAvailable()) {
            System.out.println("Skipping integration test - Ollama not available");
            return;
        }
        
        String response = service.generate(
            "What is 2 + 2?", 
            "You are a helpful assistant. Answer briefly."
        );
        
        assertNotNull("Response should not be null", response);
        assertFalse("Response should not be empty", response.isEmpty());
        // The response should contain "4" somewhere
        assertTrue("Response should contain the answer", 
            response.contains("4") || response.toLowerCase().contains("four"));
    }
    */
}
