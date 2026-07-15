# Execution Data Control Plane Stage 3 Semantic Coverage Verification

## 1. Result

Resource Gateway now turns orchestration semantics into typed, signed, fail-closed suite evidence.
The implementation follows [ADR-003](adr/ADR-003-semantic-coverage-protocol-versioning.md): existing
v1 suite and aggregate records are unchanged, while semantic policy and verdicts use independent
v2 canonical records and an independent attestation domain.

This increment proves seven semantic fact kinds:

| Requirement | Trusted evidence source | Satisfied when |
|---|---|---|
| `BRANCH_TRANSFERRED` | addressed edge trace | exact source/destination edge is `TRANSFERRED` |
| `BRANCH_SKIPPED` | addressed edge trace | exact conditional edge is `SKIPPED` |
| `DECISION_RULE` | sanitized node output | JSON Pointer resolves to the declared scalar |
| `RETRY` | ordered attempt trace | one occurrence reaches `minimumAttempts` |
| `FALLBACK` | node result plus attempts | node succeeds after the final delegate attempt fails/times out |
| `TIMEOUT` | node/attempt status | site times out and optional error code matches |
| `COMPENSATION` | compensation node trace | exact `#COMPENSATION` site is invoked |

Only `CERTIFIABLE` child evidence can satisfy a requirement. Author declarations, exploratory
traces, suite metadata, and aggregate self-reporting cannot create observed facts.

## 2. Version Matrix

| Artifact | Structural generation | Semantic generation |
|---|---|---|
| immutable suite | `bloge.testSuite.v1` | `bloge.testSuite.v2` |
| aggregate evidence | `bloge.testSuiteRunEvidence.v1` | `bloge.testSuiteRunEvidence.v2` |
| aggregate attestation | `bloge.testSuiteRunAttestation.v1` | `bloge.testSuiteRunAttestation.v2` |
| execution response | `bloge.testSuiteExecutionResponse.v2` | `bloge.testSuiteExecutionResponse.v3` |
| portable bundle | `bloge.testSuiteEvidenceBundle.v1` | `bloge.testSuiteEvidenceBundle.v2` |

`TestSuiteProtocolCodec` and `TestSuiteRunEvidenceProtocolCodec` dispatch by the existing
`schemaVersion` and fingerprint the concrete generation. Persistence, response, bundle, and
attestation constructors reject mixed generations. A v1 value is never converted to v2 before
fingerprinting or verification.

## 3. Verdict Semantics

| State | Meaning | Promotion effect |
|---|---|---|
| `NOT_EVALUATED` | running checkpoint or historical v1 | v1 follows structural policy; v2 terminal cannot remain here |
| `SATISFIED` | every requirement has trusted observations | semantic gate passes |
| `UNSATISFIED` | complete certifiable evidence exists but a fact is absent | blocked with `SEMANTIC_COVERAGE_UNSATISFIED` |
| `INCOMPLETE` | execution, coordinates, sanitized path, or trust class cannot prove the fact | blocked with `SEMANTIC_COVERAGE_INCOMPLETE` |

The distinction is deliberate. A missing decision result is a business coverage failure; a result
removed by sanitization is an evidence-availability failure. Neither can become a false green.
Abandoned v2 checkpoints retain observed facts, mark every unresolved requirement unavailable, and
are re-signed as v2 `EVIDENCE_INCOMPLETE` terminal evidence.

## 4. Consumer Contract

The capability probe advertises all supported generations and `typedSemanticCoverageV2=true`.
The independent test-kit:

- upgrades `TestSuiteBuilder` to v2 when any semantic requirement is added;
- keeps suites without semantic requirements byte-compatible with v1;
- validates v1/v2 and response/bundle variants with the packaged authoritative JSON Schema;
- exposes `TestSuiteRun.semanticCoverage()` and fail-closed `requireSemanticCoverage()`;
- returns `SEMANTIC_COVERAGE_UNAVAILABLE` when a semantic-aware gate receives v1 evidence;
- verifies that bundle, aggregate, and attestation generations match before signature verification.

## 5. Verification Matrix

The focused gate covers:

- v1 concrete JSON round-trip and historical fingerprint algorithm preservation;
- v2 codec round-trip, unsupported version rejection, and class/version mismatch rejection;
- all seven satisfied semantic fact kinds;
- trusted missing facts versus sanitized/exploratory unavailable facts;
- registry validation and immutable v2 persistence;
- JDBC suite and suite-run v2 restart round-trip;
- v2 attestation signing, tamper rejection, and v1-domain downgrade rejection;
- end-to-end v2 suite execution producing response v3 and portable bundle v2;
- schema/capability synchronization and independent test-kit generation mismatch rejection.

Focused result: Resource Gateway 52 tests and independent test-kit 46 tests, all with zero failures
and zero errors. The final Resource Gateway `clean verify` gate ran 1822 tests with zero failures,
zero errors, and 34 conditional skips; the Spring Boot JAR and both test-kit JARs were packaged,
and public JavaDoc/doclint passed.

## 6. Deliberate Non-Claims

Semantic coverage does not prove source-code line coverage, mutation score, production equivalence,
or owner approval. It also does not solve trusted pin distribution, transparency proof, ANEKE
projection, stream/suspend semantics, arbitrary private binding conformance, or customer KMS/IAM
deployment certification. Those remain explicit later-stage or deployment gates.
