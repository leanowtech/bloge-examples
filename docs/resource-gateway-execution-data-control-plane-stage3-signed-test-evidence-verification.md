# Execution Data Control Plane Stage 3: Signed Test Evidence Verification

## Scope

This is the first Stage 3 increment. It makes complete sanitized graph/operator child-run evidence
tamper-evident and prevents an immutable-suite aggregate from trusting an unsigned or altered child.
It adds:

- `bloge.testEvidenceIntegrity.v1` and signed `bloge.testExecutionResponse.v2`;
- canonical SHA-256 fingerprints over complete sanitized `TestRunEvidence`;
- detached signatures through the existing local Ed25519 or managed KMS/HSM signing authority;
- immediate verification before persistence and verification again before a stored run is returned;
- explicit SUMMARY/STANDARD/FULL projection fingerprints and independent-verification semantics;
- a suite aggregation gate requiring independently verifiable FULL child evidence;
- v1/v2 migration support and a typed integrity projection in the standalone test-kit.

## Trust Boundary

The signing order is fixed:

```text
runtime evidence
  -> server-side sanitizer
  -> canonical complete TestRunEvidence bytes
  -> sha256 evidenceFingerprint
  -> canonical domain envelope {integrity schema, evidence fingerprint, signedAt}
  -> sha256 signature-material fingerprint
  -> detached signature over the domain-separated material fingerprint
  -> immediate signature verification
  -> persistence of evidence + integrity manifest
```

No raw unsanitized payload is signed or persisted by this path. A persisted record is verified in
FULL form before response projection. SUMMARY and STANDARD preserve the complete-evidence signature
as lineage, but their `projectionFingerprint` identifies different evidence bytes and
`independentlyVerifiable` is therefore false.

## Safety Invariants

1. A new `CERTIFIABLE` record cannot cross the JDBC repository boundary without a VERIFIED FULL
   integrity manifest.
2. Signature creation is followed by verification before persistence; a signer returning invalid
   material is treated as failed, not trusted.
3. Signer failure changes the run to `EVIDENCE_INCOMPLETE + EXPLORATORY` and records only a bounded
   stable diagnostic.
4. Protocol identity and signing time are included in the signed canonical envelope; changing either
   invalidates the signature.
5. A stored run is never returned when evidence, fingerprint, signature, schema version, FULL
   projection claim, or independent-verification claim is inconsistent.
6. Verifier/key unavailability is separated from tampering: unavailable returns 503; invalid or
   unsigned persisted material returns 409 and emits a security audit event.
7. Suite aggregation verifies complete child evidence before consuming status, evidence class,
   assertions, or coverage observations. Invalid children block promotion.
8. The response schema advertises v1 and v2 separately. The server emits v2; the test-kit accepts
   historical v1 as explicitly unsigned migration material.
9. `signatureStatus=VERIFIED` is not sufficient for an external trust decision. A consumer must
   resolve `keyId`, enforce key policy, reproduce canonical evidence bytes, and verify Ed25519.

## Failure Matrix

| Failure | Child result/read result | Suite consequence |
|---|---|---|
| signer unavailable while finalizing | `EVIDENCE_INCOMPLETE`, `EXPLORATORY`, `VERIFICATION_UNAVAILABLE` | child cannot promote |
| signature fails immediate self-check | same fail-closed result with `TEST_EVIDENCE_SIGNATURE_INVALID` | child cannot promote |
| stored evidence or signature altered | `409 RG.TEST.EVIDENCE_INTEGRITY_INVALID` plus audit | aggregate uses `RG.TEST.SUITE_CHILD_EVIDENCE_INTEGRITY_INVALID` |
| verification key/provider unavailable | `503 RG.TEST.EVIDENCE_VERIFICATION_UNAVAILABLE` | no trusted aggregate verdict |
| SUMMARY/STANDARD response | signed lineage, `independentlyVerifiable=false` | never accepted as suite child proof |
| unsigned historical v1 response | accepted by migration client as unsigned | never accepted by the server suite gate |

## Verification Matrix

`TestEvidenceIntegrityServiceTest` proves signing, immediate verification, evidence tamper detection,
projection non-equivalence, signer outage, malformed detached signatures, and signed-time tampering.

`TestExecutionApiServiceTest` proves sanitized evidence is signed before persistence, stored FULL
evidence is independently verifiable, response projection is explicit, altered persisted material is
audited and rejected, and signing failure cannot retain certifiable status.

`TestRuntimePersistenceTest` proves integrity round-trip and rejects unsigned certifiable records.

`TestSuiteExecutionServiceTest` proves unsigned or tampered child evidence makes the aggregate
`EVIDENCE_INCOMPLETE` and blocks promotion.

`TestRuntimeApplicationIntegrationTest` boots the real Spring application, checks capability
advertisement, executes an immutable suite, retrieves its child as FULL v2 evidence, and observes a
VERIFIED independently-verifiable manifest.

The standalone test-kit tests prove v1/v2 negotiation, canonical manifest shape validation,
inconsistent independent-verification claim rejection, typed projection, JAR packaging, and public
JavaDoc enforcement.

## Commands

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestEvidenceIntegrityServiceTest,TestExecutionApiServiceTest,\
TestOperatorExecutionApiServiceTest,TestSuiteExecutionServiceTest,\
TestRuntimePersistenceTest,TestRuntimeApplicationIntegrationTest,\
TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest test

mvn -f resource-gateway-test-kit/pom.xml clean verify

mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify
```

## Measured Result

Verified on 2026-07-16:

- focused Resource Gateway evidence, API, persistence, suite, capability, and real-Spring matrix:
  57 tests, 0 failures, 0 errors, 0 skipped;
- signer and isolated-profile regression matrix after making signer discovery optional and fail
  closed: 9 tests, 0 failures, 0 errors, 0 skipped;
- standalone test-kit `clean verify`: 33 tests, 0 failures, 0 errors, 0 skipped; library JAR,
  dependency-bundled CLI JAR, packaged schema, and public JavaDoc gate succeeded;
- final Resource Gateway `-Pfrontend clean verify`: 1791 tests, 0 failures, 0 errors, 0 skipped;
  TypeScript/Vite production build, real-browser regression, and executable Spring Boot JAR packaging
  succeeded.

## Explicit Non-Claims

- `bloge.testSuiteRunEvidence.v1` is not yet signed. This increment protects every child used by
  aggregation but does not create one portable aggregate certification bundle.
- The test-kit validates and exposes the integrity manifest; it does not yet fetch verification
  keys or perform offline Ed25519 verification.
- Signature integrity proves evidence was not altered after the trusted signer saw it. It does not
  prove fixture fidelity, complete semantic coverage, operator honesty, production equivalence, or
  ANEKE approval.
- Local persistent signing and managed KMS/HSM share this protocol, but deployment-specific key
  rotation, revocation, archival retention, regional availability, and break-glass policy still
  require operational conformance evidence.
- Streaming/suspendable execution, physical test-runtime isolation, semantic coverage signing, and
  deterministic random/UUID/function services remain separate roadmap items.

## Next Increment: Aggregate Attestation

The next Stage 3 slice must close the consumer trust chain rather than merely add another signature
field:

1. Sign a domain-separated canonical `TestSuiteRunEvidence` envelope containing suite id/revision and
   fingerprint, execution-request fingerprint, ordered child run ids and child evidence fingerprints,
   target/fixture/plan closure, coverage verdict, promotion verdict, and completion time.
2. Export a portable evidence-bundle manifest that is payload-free by default. Authorized sanitized
   payloads remain separately encrypted references with classification, retention, and tombstone
   facts; they are never silently embedded in the governance bundle.
3. Publish a versioned verification-key set with provider, algorithm, lifecycle state, validity
   interval, rotation overlap, revocation, and archival policy. Key-provider unavailability must be
   distinguishable from unknown, revoked, expired, and cryptographically invalid evidence.
4. Add an offline test-kit verifier that reproduces canonical bytes and returns typed `VERIFIED`,
   `INVALID`, `KEY_UNAVAILABLE`, or `POLICY_REJECTED` results. Parsing a server-reported
   `signatureStatus=VERIFIED` is not verification.
5. Prove tamper detection for child ordering, child replacement, suite revision, aggregate coverage,
   promotion verdict, signing time, and bundle manifest. Also prove key rotation overlap, revocation,
   provider outage, retry idempotency, and cross-tenant bundle rejection.

Until these conditions pass, ANEKE may consume child evidence for diagnosis but must not treat the
suite aggregate as a portable release attestation.
