# Resource Gateway Stage 3 Semantic Gate Basis Verification

## 1. Scope

Round 22 verifies the repository-owned chain from an exact semantic workbook seed to an immutable
ANEKE governance decision. It does not certify ANEKE's policy implementation or a customer
deployment.

Implemented protocol:

- `toolStudio.resourceGateway.gateResult.v3`;
- `governance-gate-result-v3.schema.json`;
- v1/v2/v3 capability negotiation;
- v2 JSON-shape and golden-fingerprint compatibility;
- independent test-kit request and acknowledgement schema validation.

## 2. Closed Failure Modes

| Failure mode | Root cause | Enforced invariant |
|---|---|---|
| Gate says semantic checks passed but names no semantic fact | Semantic workbook was only implied by free-form check text | v3 carries exact semantic workbook refs; `PASSED` requires them and requires `SEMANTIC_CORRECTNESS` |
| New suite run makes an old decision look stale | Revalidation queried a mutable latest-history projection | v3 stores the complete ordered projected evidence closure plus bounded manifest facts and reconstructs from exact run ids |
| Same graph name points at different topology | Name was treated as target identity | exact GraphDraft is lowered and compiled; its composite graph/resource target fingerprint must equal the suite target |
| Operator suite belongs to another graph or old binding | `operatorRef` alone omitted membership and runtime closure | operator must occur in the draft and current implementation/binding/schema/composability/resource fingerprint must match |
| Operator-only tests certify a DAG | Unit evidence was mistaken for graph orchestration evidence | semantic `PASSED` requires at least one gate-ready `GRAPH` suite |
| Check result is detached from workbook inputs | `refs` were not reconciled | `SEMANTIC_CORRECTNESS.refs` must equal the exact set of semantic bundle fingerprints |
| Verification outage looks like stale or current | Freshness had no third state | source drift is `STALE`; verification authority/store outage is `UNVERIFIABLE` |
| Protocol upgrade silently changes v2 identity | Record evolution changes Jackson property material | v2 omits the v3 field and is locked to a pre-change golden fingerprint |

## 3. Reconstruction Contract

Each `SemanticWorkbookRef` contains:

```text
suiteId + revision + suiteFingerprint
target.kind + target.id + target.fingerprint
bundleFingerprint + projectionStatus
candidateEvidenceCount + unavailableEvidenceCount + evidenceTruncated
ordered [suiteRunId + evidenceFingerprint]
```

The verifier resolves the exact suite in tenant/environment/clearance scope, requires
`bloge.testSuite.v2`, rechecks its immutable fingerprint and target, reads every exact run, requires
terminal `bloge.testSuiteRunEvidence.v2` and terminal `bloge.testSuiteRunAttestation.v2`, verifies the
signature, rebuilds the payload-free projection in the recorded order, derives the manifest, and
compares the original bundle fingerprint. Evidence lists are bounded to 100; candidate cardinality
is bounded to 101 and must obey the truncation equation used by the export endpoint.

`PASSED` additionally requires every referenced bundle to be gate-ready, at least one exact graph
target, and exact semantic check refs. v3 `BLOCKED/WARNING/UNKNOWN` may legitimately carry no semantic
workbook when missing evidence is itself the reason for the decision.

## 4. Compatibility

- v1 remains readable and cannot submit `PASSED`.
- v2 remains accepted for the historical structural workbook basis.
- v2 rejects a smuggled `semanticWorkbooks` field.
- v2 serialized basis omits the new field.
- the v2 golden fixture remains
  `sha256:dde9ff6ea32baa0a9510c789efa01e01ad987d812bf5477e8909d3101a007735`.
- v3 is the default/latest generation and is advertised after v1 and v2.

## 5. Verification Commands

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dmaven.repo.local=$PWD/target/codex-m2 \
  -Dtest=CorrectnessWorkbookProtocolSchemaTest,SemanticCorrectnessWorkbookProjectionServiceTest,CompiledSemanticGateTargetVerifierTest,CorrectnessWorkbookGateIntegrationTest test

mvn -f resource-gateway-examples/pom.xml \
  -Dmaven.repo.local=$PWD/target/codex-m2 \
  -Dtest='com.leanowtech.bloge.gateway.integration.*Test' test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dmaven.repo.local=$PWD/target/codex-m2 clean verify

mvn -f resource-gateway-examples/pom.xml \
  -Dmaven.repo.local=$PWD/target/codex-m2 clean verify
```

Observed verification results:

- semantic gate/projector/target/schema: 23 tests, 0 failures, 0 errors;
- integration package: 138 tests, 0 failures, 0 errors;
- Resource Gateway `clean verify`: 1842 tests, 0 failures, 0 errors, 34 conditional skips,
  real-browser regression and executable JAR packaging successful;
- independent test-kit `clean verify`: 53 tests, 0 failures, 0 errors, public JavaDoc/doclint,
  library/CLI JAR packaging, and all three authoritative schemas successful;
- frontend TypeScript `tsc --noEmit`: successful. The local Node 26 npm/Vite/Vitest entrypoints hung
  before framework initialization, so this round does not replace the previously recorded 150-test
  frontend and production-build evidence.

## 6. Residual Limits

This increment does not prove:

- real ANEKE producer/consumer N/N-1 compatibility;
- ANEKE workbook policy or final publish decision correctness;
- trusted pin distribution or transparency-log inclusion;
- evidence validity after configured retention has legitimately removed the exact run;
- production use of the isolated test runtime;
- streaming/suspendable graph certification, deterministic random/UUID/functions, durable resume,
  physical runtime/network isolation, or cross-failure-domain recovery.

The audited repository implementation score is `97.520%` with a `2.480%` gap. This number is not a
customer production certification.
