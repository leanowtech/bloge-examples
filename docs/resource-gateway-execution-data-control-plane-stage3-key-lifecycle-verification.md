# Execution Data Control Plane Stage 3 Evidence Key Lifecycle Verification

> Verification date: 2026-07-16
>
> Scope: atomic evidence verification-key snapshots, lifecycle history, external fingerprint pinning,
> time-aware retirement/revocation, managed-provider v1/v2 compatibility, and independent test-kit verification.
>
> Result: implemented and fail-closed. The follow-on
> [evidence trust transparency increment](resource-gateway-execution-data-control-plane-stage3-evidence-trust-transparency-verification.md)
> now supplies externally authorized pin distribution and bounded consistency checkpoints.
> Independent timestamp authority and real ANEKE conformance remain outside this increment.

## 1. Closed risk

The former `GET /api/integration/evidence-keys/{keyId}` contract could return the public key named by
one evidence seal, but it could not answer four release-critical questions:

1. Was the key active when the evidence was signed, or had it already been retired?
2. Was a later revocation prospective, or did a compromise invalidate older signatures?
3. Did key lookup cross a rotation boundary and combine mutually inconsistent generations?
4. Why should an offline consumer trust a public key obtained from the same producer as the evidence?

A current-state label cannot answer historical questions. Repeated per-key reads are not an atomic
policy. A key set signed only by a key embedded in that same set is self-consistent, but not an
independent trust root. This increment closes those protocol defects instead of hiding them behind a
`signatureStatus=VERIFIED` producer claim.

## 2. Protocol boundary

| Boundary | Contract | Purpose |
|---|---|---|
| KMS/HSM sidecar to Resource Gateway | `resourceGateway.managedEvidenceSigningKeys.v2` | Atomic public-key generation with validity bounds, completeness, and lifecycle events |
| Resource Gateway to governance/test consumers | `toolStudio.resourceGateway.evidenceVerificationKeySet.v1` | Canonical, signed, externally pinnable public policy snapshot |
| Historical compatibility | `resourceGateway.managedEvidenceSigningKeys.v1` | Current-state-only provider snapshot; never promoted to complete history |
| Exact-key compatibility | `toolStudio.resourceGateway.evidenceVerificationKey.v1` | Debug/migration lookup for one key; insufficient for a release decision |

Machine-authoritative schemas:

- [managed evidence keys v2](schemas/tool-studio-resource-gateway/managed-evidence-signing-keys-v2.schema.json)
- [public evidence key set v1](schemas/tool-studio-resource-gateway/evidence-verification-key-set-v1.schema.json)
- [testing control plane v1](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json)

Private keys, private JWK fields, payload values, credentials, free-form provider diagnostics, and
publish decisions are not present in the public snapshot.

## 3. Trust bootstrap

The key-set `snapshotFingerprint` is the SHA-256 fingerprint of canonical material:

```text
{
  schemaVersion,
  provider,
  generatedAt,
  expiresAt,
  activeKeyId,
  policyCompleteness,
  keys[],
  events[]
}
```

The active key signs the fingerprint, and Resource Gateway immediately verifies that signature before
publishing the snapshot. This detects provider/Gateway assembly errors and transport or storage
mutation. It does **not** bootstrap trust by itself because the signing public key is embedded in the
same material.

A release consumer must independently authorize the exact `snapshotFingerprint`. The original
compatibility path reads it from an ANEKE registry revision, protected CI variable, or signed
deployment manifest. The current path verifies an externally M-of-N signed trust publication and a
durable consistency checkpoint. Fetching the fingerprint and keys from the same unsigned HTTP
response and then accepting its self-signature remains explicitly insufficient.

Pin rotation is an organizational transaction:

1. publish and review a new complete snapshot through the trusted channel;
2. update the accepted pin set under change control;
3. verify old evidence while the old key remains `VERIFY_ONLY` and its lifecycle history is retained;
4. remove an old pin only after evidence retention and rollback windows close.

The exact single-pin verifier remains available for compatibility. The follow-on trust-publication
protocol models `ACTIVE/OVERLAP/REVOKED` rollout policy, while signing authority and approval
ownership remain in the governance system rather than Resource Gateway.

## 4. Key and event invariants

The server rejects a snapshot unless all generic invariants hold:

- one to 64 unique Ed25519 X.509 public keys with valid base64/crypto material;
- exactly one `ACTIVE` key, and it equals `activeKeyId`;
- key creation/validity bounds do not postdate snapshot generation;
- zero to 512 events with unique ids and strictly increasing authority sequence;
- every event references a declared key and is recorded no later than `generatedAt`;
- event effective/retroactive times do not predate key creation;
- revocation fields are present only for `REVOKED` or `COMPROMISE_DECLARED`;
- retroactive revocation has an `invalidFrom` no later than its effective time.

For `policyCompleteness=COMPLETE`, stronger history invariants apply:

- every key has exactly one `CREATED` fact bound to `createdAt`;
- state facts cannot precede that creation fact;
- the latest lifecycle state agrees with the exported current state;
- `ACTIVE` ends in `ACTIVATED`;
- `VERIFY_ONLY` was activated and ends in `RETIRED`;
- `DISABLED` ends in `DISABLED`;
- `REVOKED` ends in `REVOKED` or `COMPROMISE_DECLARED`.

The independent test-kit repeats these checks. A signed but internally contradictory snapshot is not
accepted merely because its signature is cryptographically valid.

## 5. Signing-time policy

Evidence validity is evaluated at the evidence attestation's signed time, not only against today's key
label:

| Lifecycle fact | Evidence signed before fact | Evidence signed at/after fact |
|---|---|---|
| `ACTIVATED` | rejected as not active | eligible for cryptographic verification |
| `RETIRED` | remains verifiable | rejected as signed after retirement |
| `DISABLED` | remains verifiable | rejected as signed after disablement |
| prospective `REVOKED` | remains verifiable | rejected from `effectiveAt` |
| retroactive `REVOKED` / `COMPROMISE_DECLARED` | valid only before `invalidFrom` | rejected from `invalidFrom` |

`notBefore` and exclusive `notAfter` are checked in addition to events. A stale snapshot, future
snapshot beyond bounded clock skew, unsupported algorithm, absent key, or incomplete history fails
closed with a stable payload-free reason code.

## 6. Consumer workflow

Fetch the terminal suite bundle and the atomic key policy, then verify against a pin supplied outside
the response:

```java
TestSuiteEvidenceBundle bundle = client.findSuiteEvidenceBundle(suiteRunId);
EvidenceVerificationKeySet keySet = client.findEvidenceVerificationKeySet();
String trustedPin = System.getenv("RESOURCE_GATEWAY_EVIDENCE_KEY_SET_PIN");

TestSuiteEvidenceVerifier.VerificationResult result =
        new TestSuiteEvidenceVerifier().verify(bundle, keySet, trustedPin);

if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

The convenience form performs the same fetch and validation:

```java
TestSuiteEvidenceVerifier.VerificationResult result =
        client.verifySuiteEvidence(suiteRunId, trustedPin);
```

The exact-key overload remains available for migration and local diagnosis. It verifies fingerprints,
closure, and Ed25519 with the supplied key, but it cannot prove atomic rotation history or
prospective/retroactive revocation. It must not be used as the final release gate.

## 7. Failure semantics

| Condition | Outcome / reason |
|---|---|
| No key-set provider snapshot | `KEY_UNAVAILABLE / KEY_SET_UNAVAILABLE` |
| External pin absent, malformed, or different | `POLICY_REJECTED / KEY_SET_PIN_MISMATCH` |
| `CURRENT_STATE_ONLY` provider history | `POLICY_REJECTED / KEY_LIFECYCLE_POLICY_INCOMPLETE` |
| Snapshot expired | `POLICY_REJECTED / KEY_SET_STALE` |
| Snapshot too far in the future | `POLICY_REJECTED / KEY_SET_NOT_YET_VALID` |
| Canonical material or attestation binding changed | `INVALID / KEY_SET_MATERIAL_INVALID` |
| Snapshot signature invalid | `INVALID / KEY_SET_SIGNATURE_INVALID` |
| Key/event state contradiction | `POLICY_REJECTED / KEY_SET_POLICY_INVALID` or `KEY_LIFECYCLE_POLICY_INCOMPLETE` |
| Evidence key absent from pinned set | `KEY_UNAVAILABLE / EVIDENCE_KEY_NOT_IN_PINNED_SET` |
| Evidence signed outside validity interval | `POLICY_REJECTED / EVIDENCE_KEY_NOT_VALID_AT_SIGNING_TIME` |
| Evidence signed before activation | `POLICY_REJECTED / EVIDENCE_KEY_NOT_ACTIVE_AT_SIGNING_TIME` |
| Evidence signed after retirement/disable/revocation | corresponding stable `EVIDENCE_KEY_*_AT_SIGNING_TIME` reason |

The integration endpoint distinguishes authority unavailability from snapshot attestation failure:

- `503 RG.INTEGRATION.EVIDENCE_KEY_SET_PROVIDER_UNAVAILABLE`
- `503 RG.INTEGRATION.EVIDENCE_KEY_SET_ATTESTATION_UNAVAILABLE`

## 8. Compatibility and honest capability reporting

`ManagedVisualEvidenceSigner` accepts provider v1 and v2 during migration. V1 is forcibly projected as
`CURRENT_STATE_ONLY`, even if an adapter attempts to overclaim completeness. V2 may advertise
`COMPLETE` only when its event history passes all invariants.

`/api/integration/capabilities` exposes:

- supported object `evidenceVerificationKeySet`;
- managed key schemas v1 and v2;
- endpoint `GET /api/integration/evidence-keys`;
- `evidenceVerificationKeySet=true` when an atomic source is available;
- `timeAwareEvidenceKeyRevocation=true` only for a currently available complete policy;
- trust publication/bundle/descriptor objects and endpoints;
- trust distribution, authority quorum, consistency log, and rollback/fork detection only while a
  currently usable external authority quorum is configured.

The local in-memory signer has a complete one-key creation/activation history. The demo database
signer downgrades to `CURRENT_STATE_ONLY` if historical keys exist without persisted transition times;
it does not invent a retirement timestamp from key creation time.

## 9. Implementation and verification map

| Responsibility | Implementation |
|---|---|
| Public source/material/attestation contract | `EvidenceVerificationKeySet` |
| Atomic signer lookup | `VisualEvidenceSigner.resolveKeySet()` |
| Managed v1/v2 normalization | `ManagedVisualEvidenceSigner` |
| Sidecar HTTP parsing | `HttpManagedEvidenceSigningProvider` |
| Integration publication | `ToolStudioIntegrationService.evidenceKeySet()` |
| Typed consumer projection | test-kit `EvidenceVerificationKeySet` |
| Pin, policy, crypto, and signing-time checks | `TestSuiteEvidenceVerifier` |
| Convenience client | `ResourceGatewayTestClient` |

Reproduce the focused gates:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=EvidenceVerificationKeySetTest,ManagedVisualEvidenceSignerTest,ManagedEvidenceSigningProtocolSchemaTest,HttpManagedEvidenceSigningProviderTest,ManagedEvidenceSigningApplicationIntegrationTest,ToolStudioIntegrationServiceTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest \
  test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=TestSuiteEvidenceVerifierTest,ResourceGatewayTestClientTest,TestingProtocolTest \
  test
```

The matrix covers canonical fingerprint/attestation, malformed keys, duplicate identity, inconsistent
state history, provider v1 downgrade, zero-downtime rotation overlap, prospective and retroactive
revocation, retirement-time rejection, stale/future/pin failures, material tamper, exact endpoint/path
binding, and a real managed-sidecar Spring Boot HTTP round trip.

The focused server matrix passes 41 tests and the focused test-kit matrix passes 21 tests, both with
zero failures or errors. The independent test-kit `clean verify` passes all 42 tests with JavaDoc,
doclint, library JAR, and shaded CLI packaging. The full Resource Gateway `clean verify` passes 1806
tests with zero failures or errors and 34 conditional skips, then packages the Spring Boot JAR.

## 10. Explicit non-claims and next work

This increment makes key policy portable and independently enforceable. By itself it does not
provide:

- a trust publication log, consistency checkpoint, or split-view detection;
- automatic trusted pin publication, multi-party approval, or compromised-pin recovery;
- independent trusted timestamps for evidence or key events;
- customer KMS/HSM conformance, cross-region retention, restore, or disaster-recovery evidence;
- ANEKE registry/workbook/publish-gate projection;
- encrypted payload attachments or replay authorization;
- semantic branch/rule/fallback/compensation coverage.

The follow-on trust-chain increment has now added a bounded linear hash chain, external M-of-N
publication, durable anti-rollback checkpoint, and compromised-pin recovery without changing the
key-set response. It intentionally does not claim a Merkle public transparency log, independent
witness gossip, or cross-region split-view comparison; those remain future deployment/conformance
work.
