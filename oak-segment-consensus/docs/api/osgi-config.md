# OSGi Config Introspection API

Read-only control-plane endpoints for inspecting the effective OSGi-governed
runtime configuration in `oak-segment-consensus`.

## Purpose

These endpoints exist so operators can answer five concrete questions without
opening a generic Felix console:

- What values is the validator actually using right now?
- Which knobs are intentionally exposed as supported runtime controls?
- Where did the current values come from?
- Which values differ from defaults?
- Which known tunables still are not exposed through the contract?

The surface is read-only. It is intended for validators and tooling, not for
bundle lifecycle management.

## Authentication

If `OAK_VALIDATOR_AUTH_TOKEN` or `oak.validator.auth.token` is configured, send
the raw token value in the `Authorization` header:

```bash
curl -H "Authorization: $OAK_VALIDATOR_AUTH_TOKEN" \
  http://localhost:8090/v1/config/osgi
```

Do not prefix the token with `Bearer`. The current validator auth path compares
the header value directly.

If no token is configured, the validator is in development mode and these
endpoints remain open.

## Endpoints

### `GET /v1/config/osgi`

Returns effective runtime values grouped by component.

Use this first when you want to know what the validator is actually running
with after OSGi overrides, system properties, environment variables, and
defaults have been resolved.

Response shape:

```json
{
  "contractVersion": "config.osgi.v1",
  "generatedAtMs": 1760000000000,
  "components": {
    "nodeRuntimeTuning": {},
    "tlsTuning": {},
    "blockchainTuning": {}
  }
}
```

### `GET /v1/config/osgi/schema`

Returns config metadata for the exposed control surface.

Each schema entry describes:

- `key`
- `type`
- `default`
- `reloadMode`
- `risk`
- `description`
- `source`

Use this when building dashboards, docs, or validation tooling against the
supported knob set.

### `GET /v1/config/osgi/sources`

Returns the effective source for each configuration group.

This is the endpoint to use when you need to prove whether a value came from
OSGi, system properties, environment variables, or defaults.

Response shape:

```json
{
  "contractVersion": "config.osgi.sources.v1",
  "generatedAtMs": 1760000000000,
  "sources": {
    "nodeRuntimeTuning": "osgi",
    "tokenAuthTuning": "system-properties-or-env"
  }
}
```

### `GET /v1/config/osgi/coverage`

Returns how much of the known tunable surface is exposed through the read-only
contract.

Use this for roadmap and cleanup work. A non-empty `missing` list is a direct
pointer to knobs that still need to be surfaced.

Response shape:

```json
{
  "contractVersion": "config.osgi.coverage.v1",
  "generatedAtMs": 1760000000000,
  "summary": {
    "knownTunables": 0,
    "exposedTunables": 0,
    "missingTunables": 0,
    "extraExposedTunables": 0,
    "coveragePercent": 100.0
  },
  "missing": [],
  "extra": []
}
```

### `GET /v1/config/osgi/delta`

Returns a drift report comparing current values to documented defaults.

Use this when reviewing production overrides or debugging unexpected runtime
behavior. The response separates changed and unchanged keys and includes risk
and reload metadata.

Response shape:

```json
{
  "contractVersion": "config.osgi.delta.v1",
  "generatedAtMs": 1760000000000,
  "summary": {
    "totalKeys": 0,
    "changedKeys": 0,
    "unchangedKeys": 0
  },
  "changed": [],
  "unchanged": []
}
```

## Recommended Operator Flow

1. Call `/v1/config/osgi` to inspect effective runtime values.
2. Call `/v1/config/osgi/sources` to verify provenance.
3. Call `/v1/config/osgi/schema` to understand type/default/risk expectations.
4. Call `/v1/config/osgi/delta` to review non-default behavior.
5. Call `/v1/config/osgi/coverage` during cleanup work to find remaining gaps.

## Related Docs

- [`../../CONFIGURATION.md`](../../CONFIGURATION.md)
- [`../development/BLOCKCHAIN-CONFIG-KNOBS.md`](../development/BLOCKCHAIN-CONFIG-KNOBS.md)
- [`README.md`](README.md)
