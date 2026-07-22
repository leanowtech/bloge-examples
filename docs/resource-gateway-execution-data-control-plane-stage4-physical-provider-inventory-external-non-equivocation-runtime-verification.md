# Resource Gateway Stage 4 Physical Provider Inventory External Non-Equivocation Runtime Verification

## 1. Increment Boundary

The preceding increment froze the physical-provider-inventory external-first ordering protocol but
left it as an embedding API. This increment closes that product-composition gap for the `test` and
`staging` profiles:

- the physical inventory owns a dedicated external-anchor marker bean, so another product domain's
  trust policy cannot be injected accidentally;
- the default bean reuses the shared strict challenge-bound HTTP/quorum implementation;
- the external wrapper advances the notary quorum before its local database floor;
- staging requires a non-zero Byzantine fault model, managed receipt trust, complete-chain
  bootstrap roots, and three authenticated, workload-identity-bound transports;
- aggregate health and the existing capability projection now describe the installed runtime;
- all three physical transport identities participate in the shared certificate-rotation target
  inventory.

Production remains physically excluded by the existing profile expression. This is a test/staging
authoring-runtime closure, not a claim that an external notary service has been production-certified.

## 2. Composition And Ownership

`TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration` first performs a stateless,
profile-sensitive preflight. It then creates, or accepts from the embedder, exactly one
`TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor`. The dedicated marker is
the ownership boundary. A generic `TestSuiteStabilityExternalSequenceAnchor`, including one governed
for suite stability, test secrets, or recovery fleets, cannot satisfy this dependency.

The default marker delegates to `TestRuntimeConfiguration.buildExternalSequenceAnchor(...)`. That
keeps media types, fresh challenges, receipt verification, authenticated-conflict dominance,
managed trust, complete root-chain recovery, bounded I/O, and certificate rotation identical to the
already-certified shared implementation. This composition adds no second protocol dialect.

The floor is assembled as:

```text
signed deployment + witness candidate
  -> physical-domain external compare-and-append quorum
  -> physical provider-inventory database floor
  -> immutable dynamic authority generation
```

The database floor is initialized explicitly before it is hidden behind the external decorator.
Without that lifecycle handoff, Spring would no longer see the concrete floor's `@PostConstruct` and
the first publication could fail because its table did not exist.

## 3. Profile Policy

The complete configuration root is:

```text
gateway.testing.stability-physical-attempt.provider-inventory.external-anchor
```

The deployable environment prefix is:

```text
RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_EXTERNAL_ANCHOR_*
```

`application-test.yml` and `application-staging.yml` enumerate the full contract. Its four policy
groups are:

| Group | Responsibility |
| --- | --- |
| root properties | trust domain, anchor set, M-of-N keys/endpoints, fault bound, challenge timing |
| `TRANSPORT_*` | private PKIX, SPKI, mTLS and exact notary workload identities |
| `TRUST_*` / `TRUST_TRANSPORT_*` | restart-free managed notary receipt-key publication and transport |
| `BOOTSTRAP_ROOT_*` / `BOOTSTRAP_ROOT_TRANSPORT_*` | signed complete-chain trust-root recovery and transport |

Local `test` may leave the anchor disabled. That preserves explicit migration and component-test
paths, but descriptor/capability truth stays external=false and Byzantine=false. If enabled, there
must be exactly one available, externally durable, challenge-bound anchor. A non-zero configured
fault requirement additionally demands a Byzantine quorum descriptor.

Staging rejects startup unless all of the following hold:

1. external anchor is enabled and required;
2. `minimum-faults >= 1` and `maximum-faults >= 1`;
3. managed receipt trust is enabled and required;
4. complete-chain bootstrap roots are enabled and required;
5. notary, managed-trust, and bootstrap-root transports are enabled and required;
6. all three transports bind exact client and server certificate workload identities;
7. every insecure-loopback escape hatch is false.

Nested typed validators then enforce the exact `3f+1 / 2f+1` quorum, unique authority/endpoint/failure
domains, strict HTTPS, trust publications, key lifecycles, timing bounds, transport credentials, and
workload identities. Staging YAML defaults each `certificate-identity-required` flag to its transport
enable flag, while Java preflight independently requires `certificateIdentityBound()` on all three
links. Consequently an environment override or programmatic property source cannot downgrade the
identity policy. The preflight runs before the physical floor or remote bootstrap; the shared strict
builder remains the final authority for cryptographic and transport semantics.

## 4. Certificate Rotation And Wire Contract

The physical notary, managed receipt-trust, and bootstrap-root links add three stable target IDs to
`ControlPlaneCertificateRotationTargets`, increasing the closed product inventory from 12 to 15.
Runtime descriptor, event-page, configuration, and convergence schemas use the same 15-target bound.
When certificate rotation is enabled, the physical default adapter requires the shared rotation
runtime rather than silently constructing non-rotating transports.

The domain entry point is
[`physical-attempt-provider-inventory-external-anchor-configuration-v1.schema.json`](schemas/resource-gateway-testing/physical-attempt-provider-inventory-external-anchor-configuration-v1.schema.json).
It references the shared strict v2 external-anchor configuration contract instead of copying it.
Both the server and independent test-kit packaging tests pin this entry point.

## 5. Failure And Information Semantics

Startup fails closed for a hidden anchor while the feature is disabled, an absent/duplicate marker,
invalid quorum, unavailable or non-durable anchor, non-challenge-bound adapter, or Byzantine-policy
mismatch. Staging also rejects local-only ordering, static receipt trust, incomplete root recovery,
disabled transport authentication, missing certificate workload identity, and insecure loopback.

The health contributor reads only the anchor's process-local aggregate snapshot. It exposes status,
strength, bounded counters, timing, and transport booleans; it never performs remote I/O or returns
scope, stream, endpoint, authority, key, certificate selector, challenge, receipt, signature, or
fingerprint. Secret references are resolved only while transport contexts are built.

## 6. Verification Evidence

Focused Spring and Schema verification passes 19 tests with zero failures, errors, or skips. The
broader physical-floor, capability, Tool Studio, rotation, and Schema gate passes 63 tests. It proves
both deployable YAML files expose the same complete 82-property key contract, while staging binds
each certificate-identity requirement to transport enablement,
local-only honest downgrade, a custom dedicated marker, default challenge-bound
adapter construction, external decorator installation, aggregate health, hidden/invalid/unsafe
anchor rejection, three independent workload-identity downgrade rejections, strict Schema
entry-point binding, and schema packaging inventory.

The final release gate for this increment is:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

The release gates completed with:

```text
Resource Gateway: 4268 tests, 0 failures, 0 errors, 2 skips; BUILD SUCCESS; 09:55 min
Resource Gateway test-kit: 231 tests, 0 failures, 0 errors, 0 skips; BUILD SUCCESS; 19.135 s
```

The 477 Resource Gateway Surefire XML reports and 25 test-kit reports independently aggregate to the
same totals. The 39,952,965-byte Spring Boot executable JAR contains 55 matching physical-inventory
or rotation-target entries. The 763,374-byte test-kit JAR and 3,803,464-byte shaded CLI JAR both
package the new physical configuration Schema. YAML loading, changed-JSON parsing, and
`git diff --check` also pass. Maven still reports the pre-existing local artifact metadata warning
for `bloge-durable` and `bloge-test`, whose installed POM omits the `bloge-execution-control` version.

## 7. Residual Gap

This increment closes physical-inventory external anti-rollback product wiring, staging downgrade
fences, managed notary receipt-trust consumption, complete-chain bootstrap-root consumption, and
certificate-rotation target registration. It does not close:

- restart-free managed rotation for the deployment and independent witness signing roots that sign
  the provider-inventory publication itself;
- N/N-1 publication/source backfill and dynamic cohort rebalance;
- bounded cancellation/observation/projection evidence retention, legal hold, tombstones, and WORM;
- a certified process/container provider and production-profile composition;
- external notary organizational-independence audit, target-database certification, capacity, SLO,
  alert routing, backup/restore, HA, DR, soak, and chaos evidence.

Relative to the complete industrial testability plan, the estimated substantive gap is now about
12%. It remains outside the allowed +/-8% completion band. The next highest-leverage root fix is
managed deployment/witness signing-root hot rotation, because static publication-signing roots still
make emergency revocation and routine key replacement restart-bound even though the downstream
notary trust chain can now rotate independently.
