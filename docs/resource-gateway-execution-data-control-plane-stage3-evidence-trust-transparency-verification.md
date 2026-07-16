# Execution Data Control Plane Stage 3 Evidence Trust Transparency Verification

> Verification date: 2026-07-16
>
> Scope: externally authorized evidence key-set pins, bounded append-only consistency pages,
> durable consumer checkpoints, M-of-N authority quorum, rollback/fork/split-view detection,
> and permanent compromised-pin recovery.
>
> Result: repository-owned trust distribution and consistency verification are implemented
> fail-closed. Real ANEKE N/N-1 conformance, independent witness deployment, gossip, and
> external HA database drills remain outside this increment.

## 1. Closed risk

The earlier key lifecycle protocol required a consumer to receive the exact key-set fingerprint
through an independent channel. That was cryptographically sound but operationally incomplete:
every CI job, workbook service, and publish gate needed a manually synchronized pin, and a client
could not distinguish a legitimate rotation from rollback or a producer-specific split view.

This increment introduces a versioned trust publication without making Resource Gateway its own
trust root. Security-owned authorities sign publication fingerprints outside Resource Gateway.
The Gateway stores and serves those signed decisions; it never receives authority private keys.

## 2. Protocol and ownership

| Owner | Asset | Responsibility |
| --- | --- | --- |
| Security/governance | Ed25519 authority private keys | Approve pin rotation/revocation outside Resource Gateway |
| Resource Gateway deployment | Authority public keys and M-of-N threshold | Verify publications; never sign them |
| Resource Gateway database | Linear publication log and permanent revoked-pin index | Serialize one successor per log and survive restart |
| ANEKE/CI consumer | `EvidenceTrustPolicy` and durable `EvidenceTrustCheckpoint` | Independently verify pages and refuse rollback/fork/resurrection |
| Evidence signing provider | `evidenceVerificationKeySet.v1` | Publish the exact current public key lifecycle snapshot |

Machine contracts:

- [trust publication v1](schemas/tool-studio-resource-gateway/evidence-key-set-trust-publication-v1.schema.json)
- [trust bundle v1](schemas/tool-studio-resource-gateway/evidence-key-set-trust-bundle-v1.schema.json)
- [trust-store descriptor v1](schemas/tool-studio-resource-gateway/evidence-trust-store-descriptor-v1.schema.json)
- [evidence verification key set v1](schemas/tool-studio-resource-gateway/evidence-verification-key-set-v1.schema.json)

The test-kit packages the trust publication/bundle schemas and validates the complete wire value
before projection. Governance authority keys are deliberately absent from publications, bundles,
and capability descriptors.

## 3. Publication invariants

`EvidenceKeySetTrustPublication.v1` binds:

- `trustDomain + logId`, a contiguous one-based sequence, and the previous publication fingerprint;
- a monotonic `recoveryEpoch`;
- publication and exclusive expiry time;
- exactly one `ACTIVE` key-set snapshot, optional time-bounded `OVERLAP` snapshots, and explicit
  `REVOKED` snapshots;
- distinct detached Ed25519 signatures from externally configured authorities.

The server recomputes canonical material, verifies the configured M-of-N quorum, requires the
`ACTIVE` pin to equal the currently exported key-set fingerprint, and appends under a per-log
database row lock. Sequence gaps, stale predecessor fingerprints, time regression, recovery-epoch
misuse, concurrent successors, and revoked-pin resurrection are rejected. Revoked fingerprints are
also stored in a permanent index, so history compaction cannot make a compromised pin acceptable.

This is a bounded linear hash-chain consistency protocol, not a Merkle transparency tree. It solves
the current rotation frequency and repository scale without returning an unbounded history. A future
high-volume or cross-organization log may replace page proof internals while retaining the protocol
identity/checkpoint boundary.

## 4. Runtime configuration

Trust publication is disabled by default. Start the demo with an externally provisioned public-key
policy by passing Spring properties after `--`:

```bash
./scripts/start-visual-canvas-demo.sh --profile test -- \
  --gateway.integration.evidence-trust.enabled=true \
  --gateway.integration.evidence-trust.trust-domain=corp.example/evidence \
  --gateway.integration.evidence-trust.log-id=resource-gateway/prod \
  --gateway.integration.evidence-trust.signature-threshold=2 \
  '--gateway.integration.evidence-trust.trusted-authorities-json=[{"authorityId":"security-a","publicKeyBase64":"<x509-ed25519-base64>","enabled":true,"revoked":false},{"authorityId":"release-b","publicKeyBase64":"<x509-ed25519-base64>","enabled":true,"revoked":false}]'
```

Optional authority fields are `notBefore` and exclusive `expiresAt` ISO-8601 instants. The
capability descriptor computes usable authority count at observation time. It reports trust features
as false when the configured active authorities cannot satisfy the threshold.

Normal demo lifecycle remains:

```bash
./scripts/visual-canvas-demo.sh status
./scripts/stop-visual-canvas-demo.sh
```

Do not place authority private keys in Spring properties, environment variables, the Gateway
database, or the publication body.

## 5. Publication and read flow

1. Read `GET /api/integration/evidence-keys` and obtain the current `snapshotFingerprint`.
2. Build publication material whose single `ACTIVE` pin is that exact fingerprint.
3. Obtain the configured authority quorum's detached signatures over `publicationFingerprint`
   outside Resource Gateway.
4. Submit the complete value to `POST /api/integration/evidence-keys/trust-publications` with a
   verified credential and `X-Purpose: EVIDENCE_TRUST_ADMIN`.
5. Consumers read
   `GET /api/integration/evidence-keys/trust-bundle?afterSequence=<checkpoint>&limit=<1..256>`.
6. Persist the returned checkpoint after every accepted page. `CATCH_UP_REQUIRED` means the page is
   valid but no release decision may be made yet.

Every bundle includes the full observed head even when the page is empty. Consumers can therefore
recheck head freshness and the current pin without trusting server booleans.

## 6. Independent consumer workflow

```java
EvidenceTrustPolicy policy = loadSecurityOwnedPolicy();
EvidenceTrustCheckpoint checkpoint = checkpointStore.load().orElse(null);

ResourceGatewayTestClient.TrustAnchoredSuiteVerification result =
        client.verifySuiteEvidence(suiteRunId, policy, checkpoint, 64);

checkpointStore.save(result.trust().checkpoint());
if (!result.verified()) {
    throw new IllegalStateException(result.trust().reasonCode());
}
```

The caller must store the entire checkpoint atomically: identity, sequence, publication fingerprint,
recovery epoch, last publication time, and permanent revoked-pin set. Storing only the sequence
destroys split-view, time-rollback, and compromised-pin resurrection detection.

The independent verifier does not reuse server verification code. It repeats JSON Schema validation,
canonical fingerprints, Ed25519 quorum, page continuity, head binding, current pin validity, nested
key-set lifecycle verification, and only then verifies suite evidence.

## 7. Fail-closed semantics

| Failure class | Stable examples |
| --- | --- |
| Bootstrap/configuration | `TRUST_STORE_UNAVAILABLE`, `TRUST_CHECKPOINT_REQUIRED` |
| Identity/cursor | `TRUST_LOG_IDENTITY_MISMATCH`, `TRUST_CURSOR_CHECKPOINT_MISMATCH` |
| Consistency | `TRUST_LOG_SEQUENCE_GAP`, `TRUST_LOG_FORK_DETECTED`, `TRUST_LOG_ROLLBACK_DETECTED`, `TRUST_LOG_SPLIT_VIEW_DETECTED` |
| Authority | `TRUST_AUTHORITY_SIGNATURE_INVALID`, `TRUST_AUTHORITY_QUORUM_NOT_MET` |
| Time/material | `TRUST_PUBLICATION_TIME_INVALID`, `TRUST_PUBLICATION_STALE`, `TRUST_PUBLICATION_MATERIAL_INVALID` |
| Recovery | `TRUST_RECOVERY_EPOCH_INVALID`, `TRUST_REVOKED_PIN_REACTIVATED` |
| Pin/key set | `TRUST_ACTIVE_PIN_MISMATCH` and existing key-set lifecycle reason codes |

Server publication conflicts return bounded `RG.INTEGRATION.EVIDENCE_TRUST_*` problems without
keys, payloads, or arbitrary exception text.

## 8. Verification map

| Concern | Tests |
| --- | --- |
| Canonical material, quorum, identity, time, descriptor honesty | `EvidenceKeySetTrustPublicationTest` |
| Genesis, successor, gap/fork, recovery epoch, permanent revocation | `InMemoryEvidenceKeySetTrustPublicationRepositoryTest` |
| Restart, transaction rollback, concurrent successor fencing | `DatabaseEvidenceKeySetTrustPublicationRepositoryTest` |
| Publish/current-key binding, bounded pages, capability fail-closed | `ToolStudioEvidenceTrustIntegrationTest` |
| Schema field parity and authority-key exclusion | `EvidenceKeySetTrustProtocolSchemaTest` |
| Independent catch-up, rollback, split view, fork, quorum, stale head, resurrection | test-kit `EvidenceKeySetTrustVerifierTest` |
| HTTP request path/query and complete schema projection | test-kit `ResourceGatewayTestClientTest` |

Focused reproduction:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=EvidenceKeySetTrustPublicationTest,InMemoryEvidenceKeySetTrustPublicationRepositoryTest,DatabaseEvidenceKeySetTrustPublicationRepositoryTest,ToolStudioEvidenceTrustIntegrationTest,EvidenceKeySetTrustProtocolSchemaTest \
  test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=EvidenceKeySetTrustVerifierTest,ResourceGatewayTestClientTest,TestingProtocolTest \
  test
```

The strict repository gate passes 1857 Resource Gateway tests with zero failures or errors and 34
conditional skips, including the real browser suite and executable Spring Boot JAR packaging. The
independent test-kit `clean verify` passes 60 tests with zero failures or errors, packages the library
and shaded CLI JARs plus five Tool Studio schemas, and completes JavaDoc/doclint without warnings.

## 9. Honest residual boundary

This increment does not prove that two geographically or organizationally isolated consumers saw
the same head. M-of-N publication signatures make the configured authorities independent witnesses,
but automatic witness gossip, cross-region checkpoint comparison, public log monitoring, and Merkle
inclusion/consistency proofs are not implemented. It also does not include real ANEKE N/N-1
consumer conformance, customer KMS/HSM ceremonies, HA database/failover drills, external timestamp
authority, or automatic checkpoint storage in ANEKE.

The next acceptance step is an ANEKE-owned N/N-1 consumer matrix that persists checkpoints, performs
multi-page catch-up, exercises rollback/split-view fixtures, and consumes the resulting trusted
key-set pin before workbook and publish-gate decisions.
