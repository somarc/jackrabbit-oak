# ADR-001: Operations Readiness Gate and Operator Opus

**Status:** Accepted  
**Date:** 2026-02-07  
**Owner:** oak-segment-consensus maintainers

## Context

Load testing has exposed real-world limits under sustained traffic:
- sustained `429` pressure at ingress
- large verified/finalized gaps during overload windows
- heavy operational dependence on runtime tuning and observability
- excessive log volume risk under backpressure/rate-limit conditions

The module also needs complete runtime tunability and operator guidance without relying on ad-hoc script-only controls.

## Decision

We will treat operations readiness as a formal gate with this sequence:

1. OSGi-first runtime controls for performance, backpressure, persistence, and log-volume knobs.
2. Evidence-based load testing (ultra, multi-node ingress, rejoin/drain, persistence sweeps) with explicit pass/fail criteria.
3. A comprehensive operator manual ("Operator Opus") published in `oak-chain-docs` and aligned with `oak-magnum-oakus` quality.

## Required Outcomes

### 1) OSGi-First Controls

- Every high-impact runtime knob must be configurable via OSGi Config Admin file.
- System-property fallback remains for non-OSGi/dev modes only.
- Current priority set includes:
  - queue/finalization/backpressure knobs
  - proposal persistence toggles and flush controls
  - rate-limiter throughput controls
  - rate-limiter log-volume controls

### 2) Load-Test Evidence Gates

For each tuning/profile change, collect:
- request outcomes (`2xx`, `429`, `5xx`)
- queue/backpressure/durability/replication signals
- verified vs finalized progression and drain-down behavior
- jstack series snapshots during peak and drain phases

### 3) Operator Opus

Publish a single operator-grade guide covering:
- startup/shutdown/recovery runbooks
- limit signatures and interpretation
- safe tuning playbooks
- failure modes and mitigations
- observability/dashboard signal semantics
- incident response and postmortem workflow

## Consequences

### Positive
- Reduces production risk from hidden script defaults.
- Turns load-test pain into repeatable engineering evidence.
- Creates a reliable handoff artifact for operators and SREs.

### Tradeoffs
- More configuration surface to validate.
- Requires discipline to keep docs synchronized with code.

## Non-Goals

- This ADR does not prescribe specific numeric defaults for all environments.
- This ADR does not replace component-level ADRs for individual subsystem designs.

## Implementation Plan Linkage

- Phase 1: complete OSGi coverage for runtime knobs.
- Phase 2: execute sweep matrix and capture evidence packs.
- Phase 3: publish Operator Opus and make it release-gating documentation.
