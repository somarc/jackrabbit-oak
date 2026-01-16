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
package org.apache.jackrabbit.oak.segment.consensus.gc;

import java.math.BigDecimal;

/**
 * Mock gas oracle for testing and POC.
 * 
 * <p>Returns a fixed USDC rate configured via system property or constructor.
 * Used when real Ethereum gas oracle integration is not yet implemented.
 */
public class MockGasOracle implements GasOracle {
    
    private final BigDecimal fixedUsdcPerMB;
    
    /**
     * Create mock oracle with fixed rate from system property.
     * 
     * <p>System property: `gc.usdc.per.mb` (default: 0.10)
     */
    public MockGasOracle() {
        String usdcRateStr = System.getProperty("gc.usdc.per.mb", "0.10");
        this.fixedUsdcPerMB = new BigDecimal(usdcRateStr);
    }
    
    /**
     * Create mock oracle with fixed rate.
     * 
     * @param fixedUsdcPerMB Fixed USDC cost per MB
     */
    public MockGasOracle(BigDecimal fixedUsdcPerMB) {
        this.fixedUsdcPerMB = fixedUsdcPerMB;
    }
    
    @Override
    public BigDecimal getUsdcPerMB() throws GasOracleException {
        return fixedUsdcPerMB;
    }
    
    @Override
    public String getSource() {
        return "Mock Oracle (fixed: $" + fixedUsdcPerMB + " per MB)";
    }
}

