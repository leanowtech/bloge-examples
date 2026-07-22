# Stage 4 Physical Provider Inventory Managed Trust-Root Product Composition Verification

## Decision

Resource Gateway test/staging composition can now run the physical-attempt provider inventory with
restart-free, atomically published deployment and witness runtime keys. The Spring composition owns
the managed root source, its durable floor and health independently from the inventory consumer.
Static keys remain an explicit test migration mode; managed and static material cannot be combined.

Staging is fail closed. It requires managed roots, an external Byzantine sequence authority and all
previously defined authenticated external-anchor transports. Production still excludes the entire
physical-attempt runtime by profile, so this increment does not claim production certification.

## Product Composition

| Order | Component | Ownership and invariant |
| --- | --- | --- |
| 1 | Runtime preflight | Rejects profile downgrade before remote bootstrap. |
| 2 | Managed-root preflight | Strictly parses bootstrap keys and policies; rejects duplicate/unknown/trailing JSON, overlapping authorities and a shared root/inventory URI. |
| 3 | Root sequence floor | Creates and initializes the database floor; when an external anchor exists, wraps it with external-first ordering. |
| 4 | Dynamic root authority | Bootstraps one signed atomic dual-key publication and owns refresh, hard-age expiry and unknown-key refresh. |
| 5 | Inventory publication floor | Preserves its independent publication/witness sequence domain and optional external-first ordering. |
| 6 | Dynamic inventory consumer | Receives the root authority as a direct Spring dependency, so it cannot observe an uninitialized root source. |
| 7 | Cohort, health and runtime capability | Admit only the exact signed fleet and expose aggregate strength/readiness facts. |

Spring destroys dependants in reverse dependency order. The inventory consumer therefore closes
before the managed root authority; an in-flight consumer cannot outlive its key source during
shutdown.

## External-First Root Floor

`ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor` maps each
accepted atomic root generation to the physical-domain stream kind
`SERVING_INVENTORY_TRUST_ROOT`. Its stream id is a fixed-size SHA-256 identity over a dedicated
namespace and the root-set id. The raw root-set identity is not disclosed to the external log.

The commit order is strict:

1. Validate that the local floor is durable and the external authority is available, externally
   durable and challenge-bound.
2. Compare-and-append the exact sequence, material fingerprint and predecessor to the external
   authority.
3. Advance the local database floor only after the external acknowledgement.
4. On uncertain local failure, retry the same external head and then repair the local floor.

An external failure never advances local state. Byzantine truth comes from the external authority
descriptor; the wrapper does not infer it from configuration.

## Configuration Modes

| Mode | Static inventory keys | `trust-roots.enabled` | Intended use |
| --- | --- | --- | --- |
| Disabled | Runtime disabled | `false` | Default; no physical inventory beans exist. |
| Static migration | Both deployment/witness thresholds and key arrays populated | `false` | Isolated `test` migration and component verification only. |
| Managed test | All four static fields are `0`/`[]` | `true` | Restart-free root rotation in a controlled test environment. |
| Managed staging | All four static fields are `0`/`[]` | `true`, `required=true` | Required staging posture together with external Byzantine anchoring and strict transports. |

The managed contract is exposed identically by `application-test.yml` and
`application-staging.yml` through the
`RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_TRUST_ROOT_*` environment variables:

- activation: `ENABLED`, `REQUIRED`
- identity and policy: `SET_ID`, `POLICY_FINGERPRINTS`
- deployment bootstrap: `DEPLOYMENT_DOMAIN`, `DEPLOYMENT_THRESHOLD`, `DEPLOYMENT_KEYS_JSON`
- witness bootstrap: `WITNESS_DOMAIN`, `WITNESS_THRESHOLD`, `WITNESS_KEYS_JSON`
- source: `PUBLICATION_URI`, `REFRESH_SECONDS`, `REQUEST_TIMEOUT_MS`,
  `UNKNOWN_KEY_REFRESH_SECONDS`, `MAXIMUM_AGE_SECONDS`
- test-only escape hatch: `ALLOW_INSECURE_LOOPBACK`

The publication URI must use HTTPS except when the explicit loopback escape hatch is enabled in a
test profile. Redirects, weak or changing ETags, wrong media/protocol headers, oversized bodies,
malformed JSON, stale snapshots and signature/policy mismatches fail closed.

## Staging Downgrade Matrix

Staging refuses startup if any of the following is true:

- managed roots are disabled, not required or allow insecure loopback;
- inventory source allows insecure loopback;
- the external sequence anchor is disabled, optional, non-Byzantine or allows loopback;
- managed receipt trust or complete-chain bootstrap roots are disabled, optional or allow loopback;
- any of the three external-anchor transports is disabled, optional or lacks exact certificate
  workload identity binding;
- static inventory key material is present with managed roots;
- deployment and witness bootstrap authorities overlap, root domains coincide, or root and
  inventory publications share one URI.

`test` may deliberately retain the static or local-floor migration posture. Capability and health
report that weaker posture honestly; they do not promote it to managed/externally anchored
readiness.

## Health and Diagnosis

The root health contributor reports aggregate lifecycle, sequence and floor-strength facts only.
It never emits publication URI, ETag, authority id, key id, trust domain or fingerprint. Operators
should distinguish these classes:

| Signal | Meaning | Action |
| --- | --- | --- |
| Root health `UP` and inventory health `UP` | Current roots and inventory are fresh and the exact cohort converged. | No action. |
| Root health down, inventory previously healthy | Root source expired, rotated incompatibly or violated sequence/signature policy. | Repair root publication/transport; do not inject static keys as a staging bypass. |
| Root health up, inventory health down | Root trust is usable but inventory publication, adapter binding or cohort convergence failed. | Diagnose inventory and cohort independently. |
| Root floor durable but not external | Valid test migration posture only. | Enable and certify the external authority before staging. |
| External but not Byzantine | Durable ordering without the configured fault-tolerance claim. | Restore a descriptor satisfying the staging fault bound. |

## Verification Scope

Focused tests cover:

- external-before-local ordering and exact physical stream mapping;
- external failure, local repair retry, unsafe anchor and non-durable local floor rejection;
- real Spring bootstrap and reverse-order shutdown of roots and inventory;
- managed-root Actuator health and inventory capability projection;
- profile metadata completeness against the managed-root record;
- staging root and transport downgrade rejection;
- static/managed material exclusivity and independent publication endpoints.

The combined floor, root authority, inventory consumer, Spring composition, runtime capability and
Tool Studio projection gate passed 58/58 tests.

## Project Gates

```text
Resource Gateway clean verify     4302 tests, 0 failures, 0 errors, 2 skips
Surefire independent aggregate     481 XML, 4302 tests, 0 failures, 0 errors, 2 skips
Independent test-kit clean verify  231 tests, 0 failures, 0 errors, 0 skips
Test-kit independent aggregate      25 XML,  231 tests, 0 failures, 0 errors, 0 skips
Strict JavaDoc                       0 warnings, 0 errors
```

The Resource Gateway build completed in 10 minutes 1 second and repackaged the Spring Boot
executable JAR. The test-kit build completed in 20.748 seconds and copied all 75 testing-protocol
and five Tool Studio protocol schemas into its ordinary and shaded artifacts.

## Remaining Production Boundary

This composition closes the Resource Gateway test/staging product path. It does not certify the
organization operating the root publisher or notary, HSM/KMS custody, publisher HA and
anti-equivocation, N/N-1 rollout/backfill, disaster recovery, cross-replica certificate activation,
retention/legal-hold behavior, real process/container adapters, production fleet sizing or chaos
SLOs. Those are deployment and certification gates, not reasons to weaken the protocol implemented
here.
