# ADR 077 Contract Review Follow-Up

ADR 077 moves Oak runtime scheduling away from epoch-derived payment lanes and toward adaptive release based on local capacity. Oak backend now treats `priority` as an explicit compatibility policy surface instead of the core scheduler model.

That creates a required follow-up outside this repository change set:

- Review validator payment contracts and emitted event semantics against the new Oak runtime policy.
- Decide whether on-chain payment proofs must expose an authoritative entitlement or capability marker beyond raw amount.
- Reconcile mixed payment units currently observed across mock and event-driven bridges before Oak enforces tier-specific proof validation.
- Decide whether validator-hosted binary upload should remain a contract/tier entitlement, move to a dedicated capability, or be retired.
- Review SDK and API surfaces that still present `standard` / `express` / `priority` as scheduler semantics rather than compatibility metadata.

This note is intentionally scoped as a follow-up. No smart contract, SDK, or API contract refactor is performed here.
