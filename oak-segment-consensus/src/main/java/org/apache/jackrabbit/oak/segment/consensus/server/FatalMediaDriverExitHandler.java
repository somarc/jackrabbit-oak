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

final class FatalMediaDriverExitHandler {

    interface ExitRuntime {
        void exit(int statusCode);
        void startThread(String name, Runnable runnable);
        void sleep(long millis) throws InterruptedException;
        void halt(int statusCode);
    }

    private final ExitRuntime exitRuntime;

    FatalMediaDriverExitHandler() {
        this(new ExitRuntime() {
            @Override
            public void exit(int statusCode) {
                System.exit(statusCode);
            }

            @Override
            public void startThread(String name, Runnable runnable) {
                new Thread(runnable, name).start();
            }

            @Override
            public void sleep(long millis) throws InterruptedException {
                Thread.sleep(millis);
            }

            @Override
            public void halt(int statusCode) {
                Runtime.getRuntime().halt(statusCode);
            }
        });
    }

    FatalMediaDriverExitHandler(ExitRuntime exitRuntime) {
        this.exitRuntime = exitRuntime;
    }

    void handleFatalDriverError() {
        System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.err.println("🚨 FATAL MediaDriver error - exiting JVM for restart");
        System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        exitRuntime.exit(1);

        exitRuntime.startThread("force-exit-thread", () -> {
            try {
                exitRuntime.sleep(5000);
                System.err.println("⚠️  JVM still running after System.exit() - forcing halt");
                exitRuntime.halt(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
