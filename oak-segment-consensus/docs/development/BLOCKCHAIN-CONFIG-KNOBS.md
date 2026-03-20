# Blockchain Config Knobs and Gears

Operator map for blockchain runtime tuning as exposed to `oak-chain-dashboard-eds`.

## Goal

Show exactly:
- where each blockchain setting is configured
- which surface wins (precedence)
- which API endpoint exposes effective values

## Control Surfaces

### 1) OSGi ConfigAdmin (highest for configured keys)

PID / file:
- `org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfigTuningService`
- `OSGI-INF/config/org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfigTuningService.cfg`

Keys:
- `mode`
- `contract_address`
- `rpc_url`
- `gas_price_gwei`
- `gas_write_standard`
- `gas_write_express`
- `gas_write_priority`

Behavior:
- String keys: empty value means no OSGi override.
- Numeric keys: `<= 0` means no OSGi override.

### 2) Environment variables

- `OAK_BLOCKCHAIN_MODE`
- `OAK_BLOCKCHAIN_CONTRACT_ADDRESS`
- `OAK_BLOCKCHAIN_RPC_URL`
- `OAK_BLOCKCHAIN_GAS_PRICE_GWEI`
- `OAK_BLOCKCHAIN_GAS_WRITE_STANDARD`
- `OAK_BLOCKCHAIN_GAS_WRITE_EXPRESS`
- `OAK_BLOCKCHAIN_GAS_WRITE_PRIORITY`

### 3) JVM system properties

- `oak.blockchain.mode`
- `oak.blockchain.contractAddress`
- `oak.blockchain.rpcUrl`
- `oak.blockchain.gasPriceGwei`
- `oak.blockchain.gas.write.standard`
- `oak.blockchain.gas.write.express`
- `oak.blockchain.gas.write.priority`

### 4) Built-in defaults

- mode: `mock`
- gas price: `3 gwei`
- gas units: `74534` for standard/express/priority (current measured write baseline)

## Precedence

For blockchain tuning keys:
1. OSGi override (when key is set in OSGi)
2. Environment variable
3. System property (`-D`)
4. Default

## API Introspection Surfaces

### `/v1/blockchain/config`

Primary dashboard-facing blockchain snapshot:
- `mode`, `network`, `chainId`, `contractAddress`, `rpcUrl`
- `configSource` (`osgi-config-admin` or `env-or-system-properties`)
- `gasModel` (gas price + gas units)
- `tiers.*` with computed:
  - `baseFeeWei`
  - `gasUnits`
  - `gasPriceGwei`
  - `estimatedGasFeeWei`
  - `estimatedTotalWei`
  - `estimatedCost`

### `/v1/config/osgi`

Effective OSGi/system runtime components map:
- `components.blockchainTuning.*`

### `/v1/config/osgi/sources`

Component source map:
- `sources.blockchainTuning`

### `/v1/config/osgi/schema`

Typed schema / defaults / risk / backing property:
- `blockchainTuning.*` entries

### `/v1/config/osgi/coverage` and `/v1/config/osgi/delta`

Coverage and drift tracking include `blockchainTuning.*` keys.

## Knob Mapping Table

| Functional knob | OSGi key | System property | Env var | API fields |
|---|---|---|---|---|
| Mode | `mode` | `oak.blockchain.mode` | `OAK_BLOCKCHAIN_MODE` | `/v1/blockchain/config.mode`, `/v1/config/osgi.components.blockchainTuning.mode` |
| Contract | `contract_address` | `oak.blockchain.contractAddress` | `OAK_BLOCKCHAIN_CONTRACT_ADDRESS` | `/v1/blockchain/config.contractAddress` |
| RPC endpoint | `rpc_url` | `oak.blockchain.rpcUrl` | `OAK_BLOCKCHAIN_RPC_URL` | `/v1/blockchain/config.rpcUrl`, `rpc_url_configured` |
| Gas price | `gas_price_gwei` | `oak.blockchain.gasPriceGwei` | `OAK_BLOCKCHAIN_GAS_PRICE_GWEI` | `/v1/blockchain/config.gasModel.gasPriceGwei` |
| Write gas (standard) | `gas_write_standard` | `oak.blockchain.gas.write.standard` | `OAK_BLOCKCHAIN_GAS_WRITE_STANDARD` | `/v1/blockchain/config.gasModel.writeGasUnitsStandard` |
| Write gas (express) | `gas_write_express` | `oak.blockchain.gas.write.express` | `OAK_BLOCKCHAIN_GAS_WRITE_EXPRESS` | `/v1/blockchain/config.gasModel.writeGasUnitsExpress` |
| Write gas (priority) | `gas_write_priority` | `oak.blockchain.gas.write.priority` | `OAK_BLOCKCHAIN_GAS_WRITE_PRIORITY` | `/v1/blockchain/config.gasModel.writeGasUnitsPriority` |

## Operator Verification Commands

```bash
# Effective blockchain config for dashboard consumption
curl -s http://localhost:8090/v1/blockchain/config | jq '.'

# OSGi effective component values
curl -s http://localhost:8090/v1/config/osgi | jq '.components.blockchainTuning'

# Source provenance
curl -s http://localhost:8090/v1/config/osgi/sources | jq '.sources.blockchainTuning'

# Schema metadata for blockchain tuning keys
curl -s http://localhost:8090/v1/config/osgi/schema | jq '.schema[] | select(.key|startswith("blockchainTuning."))'
```

## Dashboard Payload Contract

Canonical shape for `oak-chain-dashboard-eds` consumption (`GET /v1/blockchain/config`):

```json
{
  "mode": "sepolia",
  "network": "Sepolia Testnet",
  "chainId": 11155111,
  "contractAddress": "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
  "rpcUrl": "https://sepolia.infura.io/v3/***",
  "requiresMetaMask": true,
  "useTestnet": true,
  "displayName": "✅ SEPOLIA TESTNET",
  "badgeColor": "#10b981",
  "configSource": "osgi-config-admin",
  "gasModel": {
    "source": "measured-sepolia-baseline",
    "gasPriceGwei": 3,
    "writeGasUnitsStandard": 74534,
    "writeGasUnitsExpress": 74534,
    "writeGasUnitsPriority": 74534
  },
  "tiers": {
    "STANDARD": {
      "tier": 0,
      "maxDelay": "13 min",
      "baseFeeWei": "5000000000000000",
      "gasUnits": 74534,
      "gasPriceGwei": 3,
      "estimatedGasFeeWei": "223602000000000",
      "estimatedTotalWei": "5223602000000000",
      "estimatedCost": "~0.005224 ETH"
    },
    "EXPRESS": {
      "tier": 1,
      "maxDelay": "6.5 min",
      "baseFeeWei": "10000000000000000",
      "gasUnits": 74534,
      "gasPriceGwei": 3,
      "estimatedGasFeeWei": "223602000000000",
      "estimatedTotalWei": "10223602000000000",
      "estimatedCost": "~0.010224 ETH"
    },
    "PRIORITY": {
      "tier": 2,
      "maxDelay": "45 sec",
      "baseFeeWei": "20000000000000000",
      "gasUnits": 74534,
      "gasPriceGwei": 3,
      "estimatedGasFeeWei": "223602000000000",
      "estimatedTotalWei": "20223602000000000",
      "estimatedCost": "~0.020224 ETH"
    }
  }
}
```

Notes:
- `configSource` is `osgi-config-admin` when any blockchain OSGi tuning key is actively overriding.
- Numeric pricing fields should be treated as authoritative for calculations; `estimatedCost` is display-oriented.
