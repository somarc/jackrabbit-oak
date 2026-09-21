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

import java.util.List;
import java.util.stream.Collectors;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

final class TestLogAppenderSupport {

    private TestLogAppenderSupport() {
    }

    static ListAppender<ILoggingEvent> attach(Class<?> loggerClass) {
        return attach(loggerClass.getName());
    }

    static ListAppender<ILoggingEvent> attachRoot() {
        return attach(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    }

    static ListAppender<ILoggingEvent> attach(String loggerName) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        context.getLogger(loggerName).addAppender(appender);
        return appender;
    }

    static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        detach(loggerClass.getName(), appender);
    }

    static void detachRoot(ListAppender<ILoggingEvent> appender) {
        detach(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME, appender);
    }

    static void detach(String loggerName, ListAppender<ILoggingEvent> appender) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(loggerName).detachAppender(appender);
        appender.stop();
    }

    static boolean contains(ListAppender<ILoggingEvent> appender, String fragment) {
        return messages(appender).stream().anyMatch(message -> message.contains(fragment));
    }

    static List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }
}
