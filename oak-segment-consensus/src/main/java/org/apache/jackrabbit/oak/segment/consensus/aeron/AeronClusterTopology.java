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

import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;

import java.util.List;

final class AeronClusterTopology {

    static final String PORT_BASE_PROPERTY = "aeron.cluster.basePort";
    private static final int DEFAULT_PORT_BASE = 9000;
    private static final int PORTS_PER_NODE = 100;
    private static final int ARCHIVE_CONTROL_PORT_OFFSET = 1;
    static final int CLIENT_FACING_PORT_OFFSET = 2;
    private static final int MEMBER_FACING_PORT_OFFSET = 3;
    private static final int LOG_PORT_OFFSET = 4;
    private static final int TRANSFER_PORT_OFFSET = 5;
    private static final int LOG_CONTROL_PORT_OFFSET = 6;

    private AeronClusterTopology() {
    }

    static int getPortBase() {
        return Integer.getInteger(PORT_BASE_PROPERTY, DEFAULT_PORT_BASE);
    }

    static int calculatePort(int nodeId, int offset) {
        return calculatePort(getPortBase(), nodeId, offset);
    }

    static int calculatePort(int portBase, int nodeId, int offset) {
        return portBase + (nodeId * PORTS_PER_NODE) + offset;
    }

    static String archiveControlChannel(int nodeId, String ipAddress, int clusterTermLengthBytes) {
        return archiveControlChannel(getPortBase(), nodeId, ipAddress, clusterTermLengthBytes);
    }

    static String archiveControlChannel(int portBase, int nodeId, String ipAddress, int clusterTermLengthBytes) {
        return udpChannel(portBase, nodeId, ipAddress, ARCHIVE_CONTROL_PORT_OFFSET, clusterTermLengthBytes);
    }

    static String consensusLogChannel(int nodeId, String ipAddress, int clusterTermLengthBytes) {
        return consensusLogChannel(getPortBase(), nodeId, ipAddress, clusterTermLengthBytes);
    }

    static String consensusLogChannel(int portBase, int nodeId, String ipAddress, int clusterTermLengthBytes) {
        int port = calculatePort(portBase, nodeId, LOG_CONTROL_PORT_OFFSET);
        return new ChannelUriStringBuilder()
            .media("udp")
            .termLength(clusterTermLengthBytes)
            .controlMode(CommonContext.MDC_CONTROL_MODE_MANUAL)
            .controlEndpoint(ipAddress + ":" + port)
            .build();
    }

    static String replicationChannel(String ipAddress) {
        return new ChannelUriStringBuilder()
            .media("udp")
            .endpoint(ipAddress + ":0")
            .build();
    }

    static String clusterMembers(List<String> addresses) {
        return clusterMembers(getPortBase(), addresses);
    }

    static String clusterMembers(int portBase, List<String> addresses) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addresses.size(); i++) {
            String address = addresses.get(i);
            sb.append(i);
            sb.append(',').append(address).append(':').append(calculatePort(portBase, i, CLIENT_FACING_PORT_OFFSET));
            sb.append(',').append(address).append(':').append(calculatePort(portBase, i, MEMBER_FACING_PORT_OFFSET));
            sb.append(',').append(address).append(':').append(calculatePort(portBase, i, LOG_PORT_OFFSET));
            sb.append(',').append(address).append(':').append(calculatePort(portBase, i, TRANSFER_PORT_OFFSET));
            sb.append(',').append(address).append(':').append(calculatePort(portBase, i, ARCHIVE_CONTROL_PORT_OFFSET));
            sb.append('|');
        }
        return sb.toString();
    }

    private static String udpChannel(int portBase, int nodeId, String ipAddress, int portOffset, int clusterTermLengthBytes) {
        int port = calculatePort(portBase, nodeId, portOffset);
        return new ChannelUriStringBuilder()
            .media("udp")
            .termLength(clusterTermLengthBytes)
            .endpoint(ipAddress + ":" + port)
            .build();
    }
}
