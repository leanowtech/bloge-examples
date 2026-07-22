# Resource Gateway Mirror Protocol Schemas

This directory is the wire-contract authority for the Resource Gateway capability-mirror protocol.
Every schema is strict (`additionalProperties: false`), versioned independently, and paired with a
Java protocol model and field-closure test in `resource-gateway-examples`.

| Schema | Java model | Purpose |
|---|---|---|
| `artifact-provenance-v1.schema.json` | `ArtifactProvenance` | Trust level, source lineage, confidence, approval, expiry, and revocation |
| `effect-contract-v1.schema.json` | `EffectContract` | Conservative transitive read/write/effect and risk summary |
| `capability-contract-v1.schema.json` | `CapabilityContract` | Input/output/error/effect/idempotency/security/SLO contract |
| `capability-snapshot-v1.schema.json` | `CapabilitySnapshot` | Immutable Resource/Operator/Graph projection consumed by mirror planning |

## Invariants

- Every executable reference carries a positive revision and canonical `sha256:<hex>` fingerprint.
- `UNKNOWN` effects remain critical, require an unresolved reason, and cannot collapse to read-only.
- Recorded and inferred provenance requires exact source references.
- Statistical confidence is not legal for owner-declared artifacts.
- An external capability cannot have child capability dependencies.
- A composed capability must freeze at least one exact dependency.
- A snapshot fingerprint covers the complete normalized object with only its own fingerprint field blanked.

The Stage 0 schema presence does not make mirror execution available. Capability discovery must keep
runtime feature flags disabled until projection, plan compilation, external-leaf interception, isolation,
and evidence paths have all passed their own release gates.
