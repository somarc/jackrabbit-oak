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

