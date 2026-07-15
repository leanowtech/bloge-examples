# Execution Data Control Plane Stage 3: ANEKE Semantic Workbook Verification

## 1. Scope

This record verifies the payload-free ANEKE projection for one exact semantic testing-control-plane
suite revision. It does not replace the historical draft-oriented `CorrectnessWorkbookBundle.v1`,
does not make Resource Gateway the governance authority, and does not claim a publish decision.

The protocol chain under test is:

```text
bloge.testSuite.v2
  + retained terminal bloge.testSuiteRunEvidence.v2
  + bloge.testSuiteRunAttestation.v2 (TERMINAL, cryptographically verified)
  -> toolStudio.resourceGateway.semanticCorrectnessWorkbookBundle.v1
  -> independent test-kit schema projection
  -> portable evidence-bundle verification with an out-of-band key-set pin
```

## 2. Implemented Contract

| Surface | Contract |
|---|---|
| Endpoint | `GET /api/integration/test-suites/{suiteId}/revisions/{revision}/semantic-correctness-workbook` |
| Identity | verified tenant/environment scope plus `X-Purpose: WORKBOOK_SYNC` |
| Runtime isolation | bean exists only in `test` or `staging`; production fails unavailable |
| Source suite | exact immutable `bloge.testSuite.v2`; v1 is rejected |
| Evidence | newest-first retained terminal v2 aggregates, maximum 100 projected rows |
| Integrity | exact suite ref/target/generation plus v2 terminal attestation verification |
| Payload policy | `OMITTED` |
| Consumer | independent `resource-gateway-test-kit` Tool Studio schema validator and typed projection |

The authoritative schema is
[`semantic-correctness-workbook-bundle-v1.schema.json`](schemas/tool-studio-resource-gateway/semantic-correctness-workbook-bundle-v1.schema.json).
The test-kit packages this schema in its normal and shaded JARs instead of importing server DTOs.

## 3. Information Boundary

The projection includes suite/case/fixture identities, structural and typed semantic policies,
aggregate status, assertion counters, semantic verdict, promotion verdict, terminal completion time,
verification-key identity, ordered child evidence closure, and a URL-encoded portable bundle endpoint.

It deliberately omits:

- case input and fixture payload values;
- child request/output and replay attachments;
- free-text diagnostics;
- suite metadata values, while committing them through `metadataFingerprint`;
- signing bytes and embedded trust roots.

The manifest fingerprint also commits candidate count, unavailable-verification count, and truncation.
This prevents a bounded projection from being presented as complete history.

## 4. Fail-Closed Matrix

| Condition | Result |
|---|---|
| no retained terminal candidate | `NO_TERMINAL_EVIDENCE`, `gateReady=false` |
| verification authority unavailable | candidate omitted, `VERIFICATION_UNAVAILABLE`, `gateReady=false` |
| verified evidence but no `PASSED + SATISFIED + ELIGIBLE` result | `NO_ELIGIBLE_EVIDENCE`, `gateReady=false` |
| at least one eligible result and no unavailable candidate | `READY`, `gateReady=true` |
| structural suite v1 | request rejected; never treated as empty semantics |
| suite/evidence/attestation mixed generation | request rejected |
| invalid or unsigned terminal evidence | entire projection rejected |
| tenant/environment, exact suite ref, target, terminal scope, or fingerprint mismatch | entire projection rejected |
| repository adapter lacks exact terminal-history support | retryable service unavailable, not “no evidence” |
| more than 100 retained candidates | newest 100 projected; count and `evidenceTruncated=true` are fingerprinted |

## 5. Consumer Verification

`ResourceGatewayTestClient.findSemanticCorrectnessWorkbook(...)` uses `WORKBOOK_SYNC`, validates the
integration envelope, then validates the payload against the independently packaged Tool Studio
schema. `SemanticCorrectnessWorkbook.requireGateReady()` fails with a bounded state code. It does not
verify signatures by inference from the producer's status.

For every evidence row consumed by a release gate, the consumer must call
`findSuiteEvidenceBundle(...)` and verify the v2 bundle with `TestSuiteEvidenceVerifier` against an
independently distributed key-set fingerprint. Tests exercise structural response/bundle v1 and
semantic response v3/bundle v2 through the same public client.

## 6. Reproducible Gates

Focused server gate:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ToolStudioIntegrationServiceTest,SemanticCorrectnessWorkbookProjectionServiceTest,TestabilityCapabilitiesTest,ToolStudioIntegrationControllerTest,TestRuntimePersistenceTest \
  test
```

Result: 40 tests, 0 failures, 0 errors. This includes payload omission, exact generation checks,
verification-unavailable separation, URL-safe evidence links, the 101-candidate truncation edge,
malformed adapter fingerprints, stable integration-envelope binding, and 409/503 failure translation.

Independent consumer gate:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Result: 51 tests, 0 failures, 0 errors. The library JAR, shaded CLI JAR, and public JavaDoc/doclint
gate succeeded. Both JARs contain only the required testing-control-plane and semantic-workbook
schemas.

## 7. Remaining Boundaries

This increment closes the repository-side semantic seed and same-generation independent consumer
path. It intentionally leaves these controls open:

1. `GovernanceGateResult.v2` does not yet bind a semantic workbook fingerprint as a first-class
   decision-basis reference. A later protocol generation must add this; free-text required checks are
   insufficient.
2. The test-kit is independently built but remains a same-repository consumer. Real ANEKE N/N-1
   producer/consumer release combinations and downgrade negotiation are not yet certified.
3. Trusted pin distribution, transparency/witness proof, trusted timestamping, and customer KMS/HSM
   conformance remain deployment controls.
4. The seed is bounded to 100 newest candidates and is not a suite-history/trend API.
5. The source testing runtime is intentionally absent from production; an enterprise topology must
   expose governed evidence through an approved test/staging integration boundary.

Therefore `READY` means “Resource Gateway found a verified eligible semantic evidence seed”. It must
never be rendered or stored as “ANEKE approved” or “published”.

## 8. Final Gate Record

The first shared-`~/.m2` rerun exposed an environmental race: another same-version BLOGE build could
replace `0.8.9-RC2` artifacts with an older API shape after a successful run. The final gate therefore
used a workspace-isolated Maven repository. Third-party cache entries were reused read-only, while
the current `tmp/bloge` reactor installed its BLOGE artifacts into the isolated repository before the
Resource Gateway build.

```bash
mvn -Dmaven.repo.local=$PWD/target/codex-m2 \
  -f resource-gateway-examples/pom.xml clean verify
```

Final result: 1832 tests, 0 failures, 0 errors, 34 conditional skips; real-browser regression and
Spring Boot JAR packaging succeeded in 1 minute 39 seconds. `git diff --check`, schema JSON parsing,
demo-script `bash -n`, and normal/shaded JAR schema-content checks also passed.
