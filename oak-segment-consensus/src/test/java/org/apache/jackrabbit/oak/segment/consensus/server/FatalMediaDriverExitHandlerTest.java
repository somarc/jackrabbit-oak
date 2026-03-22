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
package org.apache.jackrabbit.oak.segment.consensus.server;

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FatalMediaDriverExitHandlerTest {

    @Test
    public void handleFatalDriverErrorExitsAndSchedulesForceHalt() {
        List<String> events = new ArrayList<>();
        FatalMediaDriverExitHandler handler = new FatalMediaDriverExitHandler(new FatalMediaDriverExitHandler.ExitRuntime() {
            @Override
            public void exit(int statusCode) {
                events.add("exit:" + statusCode);
            }

            @Override
            public void startThread(String name, Runnable runnable) {
                events.add("thread:" + name);
                runnable.run();
            }

            @Override
            public void sleep(long millis) {
                events.add("sleep:" + millis);
            }

            @Override
            public void halt(int statusCode) {
                events.add("halt:" + statusCode);
            }
        });

        ListAppender<ILoggingEvent> appender = TestLogAppenderSupport.attach(FatalMediaDriverExitHandler.class);
        try {
            handler.handleFatalDriverError();

            assertEquals("exit:1", events.get(0));
            assertEquals("thread:force-exit-thread", events.get(1));
            assertEquals("sleep:5000", events.get(2));
            assertEquals("halt:1", events.get(3));
            assertTrue(TestLogAppenderSupport.contains(appender, "FATAL MediaDriver error"));
            assertTrue(TestLogAppenderSupport.contains(appender, "forcing halt"));
        } finally {
            TestLogAppenderSupport.detach(FatalMediaDriverExitHandler.class, appender);
        }
    }
}
