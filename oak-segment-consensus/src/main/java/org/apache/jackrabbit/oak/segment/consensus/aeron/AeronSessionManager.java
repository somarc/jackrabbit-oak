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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks Aeron client sessions and handles reconnect triggers.
 */
@Component(service = AeronSessionManager.class)
public class AeronSessionManager {

    private static final Logger log = LoggerFactory.getLogger(AeronSessionManager.class);

    private final Runnable heartbeatCallback;
    private final java.util.function.Consumer<String> reconnectCallback;

    public AeronSessionManager(Runnable heartbeatCallback,
                               java.util.function.Consumer<String> reconnectCallback) {
        this.heartbeatCallback = heartbeatCallback;
        this.reconnectCallback = reconnectCallback;
    }

    public void onSessionOpen(ClientSession session, long timestamp) {
        log.info("Client session opened: {} (timestamp: {})", session.id(), timestamp);
        if (heartbeatCallback != null) {
            heartbeatCallback.run();
        }
    }

    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        log.info("Client session closed: {} (reason: {}, timestamp: {})", session.id(), closeReason, timestamp);
        if (heartbeatCallback != null) {
            heartbeatCallback.run();
        }
        if (closeReason == CloseReason.TIMEOUT && reconnectCallback != null) {
            reconnectCallback.accept("session_timeout");
        }
    }
}
