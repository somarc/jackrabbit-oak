/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.economics;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import org.apache.jackrabbit.oak.segment.consensus.contracts.PropagationPaymentClient;
import org.apache.jackrabbit.oak.segment.consensus.registry.ClusterRegistration;
import org.apache.jackrabbit.oak.segment.consensus.registry.ShardRegistry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PropagationCostCalculatorTest {

    @Test
    public void testEstimateArchivalCostUsesClusterCountAndFormatting() {
        ShardRegistry shardRegistry = mock(ShardRegistry.class);
        when(shardRegistry.getActiveClusters()).thenReturn(Arrays.asList(
            mock(ClusterRegistration.class),
            mock(ClusterRegistration.class),
            mock(ClusterRegistration.class)
        ));

        PropagationCostCalculator calculator = new PropagationCostCalculator(shardRegistry);
        PropagationCostCalculator.CostEstimate estimate = calculator.estimateArchivalCost(1_048_576L);

        assertEquals(PropagationCostCalculator.StorageMode.ARCHIVAL, estimate.mode);
        assertEquals(3, estimate.clusterCount);
        assertEquals(
            PropagationPaymentClient.estimateCostLocally(
                1_048_576L,
                3,
                PropagationPaymentClient.STORAGE_MODE_ARCHIVAL
            ),
            estimate.totalCostWei
        );
        assertEquals(BigInteger.ZERO, estimate.deleteFeeWei);
        assertEquals("1.00 MB", estimate.getHumanReadableSize());
        assertTrue(estimate.toString().contains("Archival (Permanent)"));
    }

    @Test
    public void testEstimateEphemeralAndRecommendationsReflectLifecycle() {
        ShardRegistry shardRegistry = mock(ShardRegistry.class);
        when(shardRegistry.getActiveClusters()).thenReturn(Arrays.asList(
            mock(ClusterRegistration.class),
            mock(ClusterRegistration.class),
            mock(ClusterRegistration.class),
            mock(ClusterRegistration.class),
            mock(ClusterRegistration.class)
        ));

        PropagationCostCalculator calculator = new PropagationCostCalculator(shardRegistry);
        PropagationCostCalculator.CostEstimate ephemeral = calculator.estimateEphemeralPrepaidCost(1_000_000L);
        PropagationCostCalculator.CostComparison comparison = calculator.compareCosts(1_000_000L);

        assertEquals(PropagationCostCalculator.StorageMode.EPHEMERAL_PREPAID, ephemeral.mode);
        assertEquals(
            PropagationPaymentClient.BASE_DELETE_FEE.multiply(BigInteger.valueOf(6)),
            ephemeral.deleteFeeWei
        );
        assertEquals(ephemeral.totalCostWei, comparison.ephemeralPrepaid.totalCostWei);
        assertEquals(PropagationCostCalculator.StorageMode.ARCHIVAL, comparison.getCheapestPermanent().mode);
        assertEquals(PropagationCostCalculator.StorageMode.EPHEMERAL_PREPAID, comparison.getCheapestTemporary().mode);
        assertTrue(comparison.getEphemeralPremiumPercent().compareTo(BigDecimal.ZERO) > 0);

        PropagationCostCalculator.StorageRecommendation permanent = calculator.recommend(1_000_000L, 0);
        PropagationCostCalculator.StorageRecommendation temporary = calculator.recommend(1_000_000L, 30);
        PropagationCostCalculator.StorageRecommendation longRetention = calculator.recommend(1_000_000L, 90);

        assertEquals(PropagationCostCalculator.StorageMode.ARCHIVAL, permanent.recommendedMode);
        assertTrue(permanent.reasoning.contains("permanently"));
        assertEquals(PropagationCostCalculator.StorageMode.EPHEMERAL_PREPAID, temporary.recommendedMode);
        assertTrue(temporary.reasoning.contains("30 days"));
        assertEquals(PropagationCostCalculator.StorageMode.ARCHIVAL, longRetention.recommendedMode);
        assertTrue(longRetention.reasoning.contains("90 days"));
    }

    @Test
    public void testEstimateOnDemandDeleteFallsBackToSingleClusterAndUsesUpdatedEthPrice() {
        ShardRegistry shardRegistry = mock(ShardRegistry.class);
        when(shardRegistry.getActiveClusters()).thenThrow(new RuntimeException("boom"));

        PropagationCostCalculator calculator = new PropagationCostCalculator(shardRegistry);
        calculator.setEthPriceUsd(new BigDecimal("2000"));

        PropagationCostCalculator.CostEstimate estimate = calculator.estimateOnDemandDeleteCost();

        assertEquals(PropagationCostCalculator.StorageMode.DELETE_ONDEMAND, estimate.mode);
        assertEquals(1, estimate.clusterCount);
        assertEquals(PropagationPaymentClient.BASE_DELETE_FEE, estimate.totalCostWei);
        assertEquals(PropagationPaymentClient.BASE_DELETE_FEE, estimate.deleteFeeWei);
        assertEquals(new BigDecimal("0.001000000000000000"), estimate.getTotalCostEth());
        assertEquals(new BigDecimal("2.00"), estimate.getDeleteFeeUsd());
    }

    @Test
    public void testCostComparisonAndRecommendationToStringIncludeUsefulDetails() {
        ShardRegistry shardRegistry = mock(ShardRegistry.class);
        when(shardRegistry.getActiveClusters()).thenReturn(Collections.singletonList(mock(ClusterRegistration.class)));

        PropagationCostCalculator calculator = new PropagationCostCalculator(shardRegistry);
        PropagationCostCalculator.CostComparison comparison = calculator.compareCosts(512L);
        PropagationCostCalculator.StorageRecommendation recommendation = calculator.recommend(512L, 7);

        assertTrue(comparison.toString().contains("Cost Comparison"));
        assertTrue(comparison.toString().contains("Ephemeral premium"));
        assertTrue(recommendation.toString().contains("Recommendation:"));
        assertTrue(recommendation.toString().contains("Reason:"));
    }
}
