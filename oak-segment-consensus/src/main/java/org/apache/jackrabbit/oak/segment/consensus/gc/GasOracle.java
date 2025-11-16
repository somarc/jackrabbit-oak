package org.apache.jackrabbit.oak.segment.consensus.gc;

import java.math.BigDecimal;

/**
 * Interface for fetching current gas prices from Ethereum network.
 * 
 * <p>Future enhancement: Integrate with Ethereum gas oracle (e.g., Chainlink, ETH Gas Station)
 * to dynamically adjust GC cost estimates based on current network conditions.
 * 
 * <p>Example implementations:
 * <ul>
 *   <li>MockGasOracle: Returns fixed price for testing</li>
 *   <li>ChainlinkGasOracle: Fetches from Chainlink price feeds</li>
 *   <li>EthGasStationOracle: Fetches from ETH Gas Station API</li>
 * </ul>
 */
public interface GasOracle {
    
    /**
     * Get current USDC cost per MB for GC operations.
     * 
     * <p>This should factor in:
     * <ul>
     *   <li>Current Ethereum gas price</li>
     *   <li>USDC/ETH exchange rate</li>
     *   <li>GC operation complexity (segments per MB)</li>
     * </ul>
     * 
     * @return USDC cost per MB
     * @throws GasOracleException if price fetch fails
     */
    BigDecimal getUsdcPerMB() throws GasOracleException;
    
    /**
     * Get human-readable description of the oracle source.
     * 
     * @return Description (e.g., "Chainlink Price Feed", "Mock Oracle")
     */
    String getSource();
    
    /**
     * Exception thrown when gas oracle fails to fetch price.
     */
    class GasOracleException extends Exception {
        public GasOracleException(String message) {
            super(message);
        }
        
        public GasOracleException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

