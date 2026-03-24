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

import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AeronIngressEndpointPlannerTest {

    @Test
    public void planPrefersValidatorSubnetForSelfIngressAndClientEgress() {
        AeronIngressEndpointPlanner planner = new AeronIngressEndpointPlanner(
            Arrays.asList("self", "peer-1"),
            "self",
            hostname -> {
                if ("peer-1".equals(hostname)) {
                    return "172.20.1.11";
                }
                return "192.168.10.9";
            },
            () -> Arrays.asList(
                new AeronClusterAddressResolver.CandidateAddress("en9", "172.30.0.4"),
                new AeronClusterAddressResolver.CandidateAddress("en0", "172.20.1.7")
            )
        );

        AeronIngressEndpointPlanner.Plan plan = planner.plan();

        assertEquals(
            "0=172.20.1.7:" + AeronClusterLauncher.calculatePort(0, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET)
                + ",1=172.20.1.11:" + AeronClusterLauncher.calculatePort(1, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET),
            plan.ingressEndpoints
        );
        assertEquals("172.20.1.7", plan.clientIp);
    }

    @Test
    public void planFallsBackToHostnameWhenResolutionFails() {
        AeronIngressEndpointPlanner planner = new AeronIngressEndpointPlanner(
            Arrays.asList("self", "peer-1"),
            "client-host",
            hostname -> {
                if ("self".equals(hostname)) {
                    return "192.168.10.9";
                }
                throw new UnknownHostException(hostname);
            },
            Collections::emptyList
        );

        AeronIngressEndpointPlanner.Plan plan = planner.plan();

        assertEquals(
            "0=192.168.10.9:" + AeronClusterLauncher.calculatePort(0, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET)
                + ",1=peer-1:" + AeronClusterLauncher.calculatePort(1, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET),
            plan.ingressEndpoints
        );
        assertEquals("client-host", plan.clientIp);
    }

    @Test
    public void planBuildsIngressEndpointsInNodeOrder() {
        AeronIngressEndpointPlanner planner = new AeronIngressEndpointPlanner(
            Arrays.asList("node-a", "node-b", "node-c"),
            "client-host",
            hostname -> {
                if ("node-a".equals(hostname)) {
                    return "10.0.0.1";
                }
                if ("node-b".equals(hostname)) {
                    return "10.0.0.2";
                }
                if ("node-c".equals(hostname)) {
                    return "10.0.0.3";
                }
                return "10.0.0.9";
            },
            Collections::emptyList
        );

        AeronIngressEndpointPlanner.Plan plan = planner.plan();

        assertEquals(
            "0=10.0.0.1:" + AeronClusterLauncher.calculatePort(0, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET)
                + ",1=10.0.0.2:" + AeronClusterLauncher.calculatePort(1, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET)
                + ",2=10.0.0.3:" + AeronClusterLauncher.calculatePort(2, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET),
            plan.ingressEndpoints
        );
        assertEquals("10.0.0.9", plan.clientIp);
    }

    @Test
    public void planUsesProvidedClusterBasePort() {
        AeronIngressEndpointPlanner planner = new AeronIngressEndpointPlanner(
            Arrays.asList("node-a", "node-b"),
            "client-host",
            9400,
            hostname -> "10.0.0." + ("node-a".equals(hostname) ? "1" : "2"),
            Collections::emptyList
        );

        AeronIngressEndpointPlanner.Plan plan = planner.plan();

        assertEquals("0=10.0.0.1:9402,1=10.0.0.2:9502", plan.ingressEndpoints);
        assertEquals("10.0.0.2", plan.clientIp);
    }

    @Test
    public void fromUrlsExtractsHostnamesBeforePlanning() {
        AeronIngressEndpointPlanner planner = AeronIngressEndpointPlanner.fromUrls(
            "http://self-host:8080",
            List.of("http://peer-1:8081", "http://peer-2:8082"),
            hostname -> {
                if ("self-host".equals(hostname)) {
                    return "10.0.0.10";
                }
                if ("peer-1".equals(hostname)) {
                    return "172.18.0.11";
                }
                if ("peer-2".equals(hostname)) {
                    return "172.18.0.12";
                }
                return "10.0.0.99";
            },
            () -> Arrays.asList(
                new AeronClusterAddressResolver.CandidateAddress("en0", "172.18.0.10")
            )
        );

        AeronIngressEndpointPlanner.Plan plan = planner.plan();

        assertEquals(
            "0=172.18.0.10:" + AeronClusterLauncher.calculatePort(0, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET)
                + ",1=172.18.0.11:" + AeronClusterLauncher.calculatePort(1, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET)
                + ",2=172.18.0.12:" + AeronClusterLauncher.calculatePort(2, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET),
            plan.ingressEndpoints
        );
        assertEquals("172.18.0.10", plan.clientIp);
    }
}
