# Resource Gateway Test Kit

`bloge-resource-gateway-test-kit` lets Java and JUnit 5 suites drive the
Resource Gateway testing control plane without depending on its Spring Boot
implementation. The JAR packages the authoritative v1 JSON Schema and provides:

- a bounded JDK HTTP client for graph/operator target discovery, fixture and immutable-suite
  registries, deterministic property planning/materialization/execution, pure-DSL mutation
  planning/V5 materialization/V6 execution, built-in graph-catalog materialization,
  graph/operator execution, suite execution, and persisted child/aggregate-run lookup;
- a fail-closed `FixtureBundleBuilder` for output-level and transport-level protocol fixtures,
  including one-based attempt/occurrence selectors;
- a dependency-closed `TestSuiteBuilder` with exact target/fixture references and typed semantic
  branch, decision, retry, fallback, timeout, and compensation requirements;
- runtime validation against the packaged Draft 2020-12 schema plus request/response identity binding;
- packaged validation and version constants for the payload-free
  `bloge.executionServiceStateSnapshot.v1` durable-resume building block;
- payload-safe typed child/suite-run summaries and JUnit 5 assertions;
- typed v2 child-evidence integrity manifests with v1 migration compatibility;
- signed suite checkpoint/terminal attestations, payload-free evidence-bundle export, verification
  key lookup, and dependency-light offline Ed25519 verification, including schema-admission v3
  evidence with a signed empty business-child closure, bounded-property v4 evidence, and mutation
  v5 score/child-closure re-derivation;
- bounded suite-stability execution/query, typed stability-evidence re-derivation, exact source-run
  closure verification, and offline Ed25519 verification against a caller-owned key-set pin;
- challenge-bound request-index replica proof collection plus an offline exact-inventory rollout
  gate that rejects missing, duplicate, unexpected, stale, mixed-scope/artifact/protocol/mode, or
  cryptographically invalid cohorts against an externally pinned key set;
- occurrence-addressable node, retry-attempt, and edge summaries without payload fields;
- payload-free JUnit XML with deterministic CI exit codes and per-mutant or per-stability-case rows;
- an executable `-cli.jar` that fails closed on suite, coverage, promotion, immutable mutation
  score-policy, stability, or pinned-trust failure.

## Build

From the repository root:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml install
```

The module is intentionally independent of `resource-gateway-examples`. The
server and client can therefore build and release separately against the
versioned wire schema.

Resource Gateway now exposes authenticated durable create/query/claim/heartbeat/recovery endpoints
backed by BLOGE suspend state. This test-kit deliberately does not yet expose that broad control
surface as a typed Java client; it packages the checkpoint schema and only the narrow request-index
rollout proof needed by the fleet gate. Direct durable-control consumers should use the authoritative
testing API/schema until a generation-matched typed client is added.

## Use

Start Resource Gateway with its `test` or `staging` profile, then discover the
current composite target fingerprint before constructing fixtures:

```java
ResourceGatewayTestClient client = ResourceGatewayTestClient
        .builder(URI.create("http://localhost:8080"))
        .bearerToken(() -> System.getenv("RESOURCE_GATEWAY_TEST_TOKEN"))
        .build();

GraphTargetDescriptor target = client.describeGraphTarget("loanDecisionPolicy");

FixtureBundleBuilder fixture = FixtureBundleBuilder
        .graph(target.graphId(), target.fingerprint())
        .id("loan-approved")
        .revision(1)
        .rule("credit-provider")
            .resource("credit-provider.primary")
            .protocolResponse(
                    "{\"code\":0,\"data\":{\"score\":780}}",
                    200,
                    Map.of("Content-Type", "application/json"))
            .requiredUses(1, 1)
            .add()
        .assertOutput("/approved", "EQUALS", true);

FixtureBundleRevision stored = client.registerFixture(
        "loan-approved", fixture.registrationRequest());

var execution = fixture.storedExecution(
        stored.fingerprint(),
        Map.of("applicantId", "app-42", "amount", 100_000),
        ResourceGatewayTestClient.Verbosity.STANDARD,
        Map.of("suiteRef", "loan-policy", "caseRef", "approved"));
TestRun baselineRun = client.execute(execution);
TestRun run = client.execute(execution);

TestRunAssertions.assertPassed(run);
TestRunAssertions.assertCertifiable(run);
TestRunAssertions.assertFixturesSatisfied(run);
TestRunAssertions.assertNoRealInvocations(run);

// Compare a repeated run with a frozen baseline without coupling to run ids or durations.
TestRunAssertions.assertSameSemanticResult(baselineRun, run);
String semanticResult = run.semanticResultFingerprint();

TestRun.NodeTrace occurrence = run.nodeTraces().getFirst();
String site = occurrence.invocationSiteId();
int graphOccurrence = occurrence.graphOccurrence();
List<TestRun.AttemptTrace> attempts = occurrence.attempts();
List<TestRun.EdgeTrace> edges = run.edgeTraces();

JUnitXmlReportWriter.write(
        Path.of("target/surefire-reports/resource-gateway-contracts.xml"),
        "loan-policy",
        List.of(run));
```

Plan pure-DSL graph mutations without receiving mutated source, then freeze and execute the exact
reviewed plan against a governed oracle:

```java
JsonNode mutationPlan = client.planGraphMutationCases("loanDecisionPolicy", 16);
if (!mutationPlan.path("status").asText().equals("GENERATED")) {
    mutationPlan.path("gaps").forEach(System.out::println);
}

ObjectNode materializationRequest = new ObjectMapper().createObjectNode();
materializationRequest.put("schemaVersion",
        "bloge.testMutationSuiteMaterializationRequest.v1");
materializationRequest.put("suiteId", "loan-decision-mutations");
materializationRequest.put("classification", "INTERNAL");
materializationRequest.put("expectedTargetFingerprint",
        mutationPlan.path("target").path("fingerprint").asText());
materializationRequest.put("expectedSourceFingerprint",
        mutationPlan.path("sourceFingerprint").asText());
materializationRequest.put("expectedGraphArtifactFingerprint",
        mutationPlan.path("graphArtifactFingerprint").asText());
materializationRequest.put("expectedPlanFingerprint",
        mutationPlan.path("planFingerprint").asText());
materializationRequest.put("maxMutants", 16);
materializationRequest.put("acceptPlanningGaps",
        mutationPlan.path("status").asText().equals("PARTIAL"));
materializationRequest.putObject("oracleSuiteRef")
        .put("suiteId", "loan-decision-regression")
        .put("revision", 7)
        .put("fingerprint", "sha256:<exact-oracle-suite-fingerprint>");
materializationRequest.putObject("scorePolicy")
        .put("minimumScoreBasisPoints", 8000)
        .put("maximumInconclusiveMutants", 0)
        .put("requireNoSurvivors", false)
        .put("excludeEquivalentMutants", false);
JsonNode materialized = client.materializeGraphMutationSuite(
        "loanDecisionPolicy", materializationRequest);
JsonNode mutationSuite = materialized.path("suiteRef");

TestSuiteRun mutationRun = client.executeMutationSuite(
        mutationSuite.path("suiteId").asText(),
        mutationSuite.path("revision").asLong(),
        mutationSuite.path("fingerprint").asText(),
        "mutation-ci-1842",
        ResourceGatewayTestClient.MutationStrategy.STOP_AFTER_KILL,
        Map.of("pipeline", "release-candidate", "buildId", "1842"));

TestSuiteRunAssertions.assertMutationSatisfied(mutationRun);
TestSuiteRun.MutationScore mutationScore = mutationRun.requireMutationScore();
List<TestSuiteRun.MutantResult> mutants = mutationRun.mutantResults();
```

The client validates the plan, materialization, V6 response, V5 evidence, and V5 attestation against
its packaged authoritative Schema and binds every response back to the caller's exact graph, plan,
oracle, suite, and idempotency identities. It independently re-derives baseline status, mutant
classification, killing cases, denominator, score, and policy verdict. Planning by itself remains an
authoring asset; only the terminal generation-matched bundle is evidence. Verify that bundle against
an independently pinned key set before a governance gate consumes it.

Run an exact synchronous operator binding with the same governed fixture and evidence protocol:

```java
OperatorTargetDescriptor operator = client.describeOperatorTarget("customer.normalize");

FixtureBundleBuilder operatorFixture = FixtureBundleBuilder
        .operator(operator.operatorRef(), operator.fingerprint())
        .id("normalize-contract")
        .rule("real-binding")
            .operator(operator.operatorRef())
            .spy()
            .requiredUses(1, 1)
            .add()
        .assertOutput("/normalized", "EQUALS", "ADA");

FixtureBundleRevision operatorRevision = client.registerFixture(
        "normalize-contract", operatorFixture.registrationRequest());
TestRun operatorRun = client.executeOperator(operator.operatorRef(),
        operatorFixture.storedOperatorExecution(operatorRevision.fingerprint(),
                Map.of("name", "Ada"),
                ResourceGatewayTestClient.Verbosity.STANDARD,
                Map.of("suiteRef", "normalization", "caseRef", "uppercase")));

TestRunAssertions.assertPassed(operatorRun);
TestRunAssertions.assertCertifiable(operatorRun);
```

Build and execute one immutable suite without hand-writing the suite protocol:

```java
TestSuiteBuilder suite = TestSuiteBuilder.operator(operator)
        .id("normalization-regression")
        .revision(1)
        .addCase("uppercase", TestSuiteBuilder.CaseType.GOLDEN,
                Map.of("name", "Ada"), operatorRevision)
        .requireCaseTypes(TestSuiteBuilder.CaseType.GOLDEN)
        .metadata(Map.of("owner", "customer-platform"));

TestSuiteRevision storedSuite = client.registerSuite(
        "normalization-regression", suite.registrationRequest());

TestSuiteRun suiteRun = client.executeSuite(
        storedSuite.suiteId(),
        storedSuite.revision(),
        storedSuite.fingerprint(),
        "pipeline-982-job-4",
        ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL,
        Map.of("source", "junit"));

TestSuiteRunAssertions.assertPassed(suiteRun);
TestSuiteRunAssertions.assertAllCasesPassed(suiteRun);
TestSuiteRunAssertions.assertCoverageSatisfied(suiteRun);
TestSuiteRunAssertions.assertPromotionEligible(suiteRun);

TestSuiteEvidenceVerifier.VerificationResult verification =
        client.verifySuiteEvidence(suiteRun.suiteRunId());
if (!verification.verified()) {
    throw new IllegalStateException(verification.reasonCode());
}

JUnitXmlReportWriter.writeSuite(
        Path.of("target/surefire-reports/resource-gateway-suite.xml"),
        suiteRun,
        true);
```

Run the exact immutable suite repeatedly and require independently verified stability before a
release gate consumes the result:

```java
TestSuiteStabilityRun stability = client.executeSuiteStability(
        storedSuite.suiteId(),
        storedSuite.revision(),
        storedSuite.fingerprint(),
        "stability-ci-982",
        5,
        Map.of("source", "nightly"));

String trustedPin = System.getenv("RESOURCE_GATEWAY_TRUSTED_KEY_SET_FINGERPRINT");
TestSuiteStabilityEvidenceVerifier.VerificationResult stabilityVerification =
        client.verifySuiteStability(stability.stabilityRunId(), trustedPin);

TestSuiteStabilityAssertions.assertReleaseEligible(stability, stabilityVerification);
JUnitXmlReportWriter.writeStability(
        Path.of("target/surefire-reports/resource-gateway-stability.xml"),
        stability,
        stabilityVerification);
```

For an exact probability claim, precommit the model and let the policy derive the minimum horizon:

```java
TestSuiteStabilityStatisticalPolicy policy =
        TestSuiteStabilityStatisticalPolicy.exactBinomial(9_500, 1_000);
TestSuiteStabilityRun statistical = client.executeStatisticalSuiteStability(
        storedSuite.suiteId(), storedSuite.revision(), storedSuite.fingerprint(),
        "stability-ci-983", policy.minimumRequiredAttempts(), policy,
        Map.of("source", "nightly"));

TestSuiteStabilityEvidenceVerifier.VerificationResult verified =
        client.verifySuiteStability(statistical.stabilityRunId(), trustedPin);
TestSuiteStabilityAssertions.assertStatisticalReleaseEligible(statistical, verified);
JUnitXmlReportWriter.writeStability(
        Path.of("target/surefire-reports/resource-gateway-statistical-stability.xml"),
        statistical, verified, true);
```

Deterministic stability always runs `COLLECT_ALL` exactly 3..20 times. Statistical request v2 uses a
precommitted 3..1000 horizon, exact integer arithmetic, and a 10,000 attempt-by-case work bound. A
case is compared by
`evidenceStatus + semanticResultFingerprint`, not only by pass/fail status. The aggregate is
`STABLE`, `FLAKY`, `CONSISTENT_FAILURE`, or `INCONCLUSIVE`; plan drift, reused source/child run ids,
missing evidence, an invalid signature, or an incomplete source closure can never be promoted to
stable. Stability response v2 also binds each source suite's promotion status and reasons. A result
may therefore be `STABLE + BLOCKED`: its behavior is invariant, but at least one source run was not
certifiable. Signed v1 responses remain verifiable for audit, while
`sourcePromotionClosureAvailable()` is false and every release assertion fails closed. A `FLAKY`
result carries a quarantine recommendation, but the API does not mutate suite state or bypass a
business failure. V3 confidence is a conditional zero-instability-event bound, not a correctness
proof, non-zero-event confidence interval, adaptive stopping policy, or historical flake-rate trend.

Execute a reviewed `bloge.testSuite.v3` reference returned by the server's boundary-suite
materialization API without confusing schema admission with business execution:

```java
TestSuiteRun admissionRun = client.executeSuite(
        "loan-decision-schema-boundaries",
        materializedRevision,
        materializedFingerprint,
        "schema-admission-ci-1042",
        ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL,
        Map.of("source", "contract-regression"));

assert admissionRun.evaluationMode() == TestSuiteRun.EvaluationMode.SCHEMA_ADMISSION;
TestSuiteRunAssertions.assertAdmissionPassed(admissionRun);
assert !admissionRun.passed();             // no business graph/operator execution occurred
assert !admissionRun.promotionEligible();  // admission evidence never authorizes publication

TestSuiteRun.AdmissionCoverage coverage = admissionRun.requireAdmissionCoverage();
List<TestSuiteRun.AdmissionCaseResult> observations = admissionRun.admissionResults();

TestSuiteEvidenceBundle admissionBundle =
        client.findSuiteEvidenceBundle(admissionRun.suiteRunId());
String trustedPin = System.getenv("RESOURCE_GATEWAY_EVIDENCE_KEY_SET_PIN");
TestSuiteEvidenceVerifier.VerificationResult admissionVerification =
        client.verifySuiteEvidence(admissionRun.suiteRunId(), trustedPin);

JUnitXmlReportWriter.writeSuite(
        Path.of("target/surefire-reports/schema-admission.xml"),
        admissionRun,
        false); // promotion is deliberately blocked for admission-only evidence
```

Admission success means every stored case still belongs to the exact reviewed boundary plan and the
shared validator produced the expected outcome/codes. It does not mean the operator, DAG, assertions,
or structural/semantic coverage passed. v4 response, v3 evidence, v3 attestation, and v3 bundle are a
single generation; the schema validator and offline verifier reject mixed generations. The v3
attestation must have an empty `childEvidenceRefs` list.

Plan, freeze, execute, and independently verify a bounded property suite:

```java
JsonNode propertyPlan = client.planGraphPropertyCases(
        "loanDecisionPolicy", 918273645L, 8, 3);

ObjectNode materializationRequest = JsonNodeFactory.instance.objectNode();
materializationRequest.put("schemaVersion",
        TestingProtocol.TEST_PROPERTY_SUITE_MATERIALIZATION_REQUEST_V1);
materializationRequest.put("suiteId", "loan-decision-properties");
materializationRequest.put("classification", "INTERNAL");
materializationRequest.put("expectedTargetFingerprint",
        propertyPlan.path("target").path("fingerprint").asText());
materializationRequest.put("expectedInputSchemaFingerprint",
        propertyPlan.path("inputSchemaFingerprint").asText());
materializationRequest.put("expectedPlanFingerprint",
        propertyPlan.path("planFingerprint").asText());
materializationRequest.put("seed", 918273645L);
materializationRequest.put("trials", 8);
materializationRequest.put("maxShrinkSteps", 3);
ObjectNode propertyFixture = materializationRequest.putObject("fixtureRef");
propertyFixture.put("fixtureBundleId", stored.fixtureBundleId());
propertyFixture.put("revision", stored.revision());
propertyFixture.put("fingerprint", stored.fingerprint());
materializationRequest.put("acceptGenerationGaps", false);

JsonNode materialized = client.materializeGraphPropertySuite(
        "loanDecisionPolicy", materializationRequest);
JsonNode propertySuiteRef = materialized.path("suiteRef");
assert propertySuiteRef.path("schemaVersion").asText()
        .equals(TestingProtocol.TEST_SUITE_V4);

TestSuiteRun propertyRun = client.executeSuite(
        propertySuiteRef.path("suiteId").asText(),
        propertySuiteRef.path("revision").asLong(),
        propertySuiteRef.path("fingerprint").asText(),
        "property-ci-1842",
        ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL,
        Map.of("source", "property-regression"));

assert propertyRun.evaluationMode() == TestSuiteRun.EvaluationMode.PROPERTY_EXECUTION;
TestSuiteRunAssertions.assertPropertySatisfied(propertyRun);

TestSuiteEvidenceVerifier.VerificationResult propertyVerification =
        client.verifySuiteEvidence(propertyRun.suiteRunId());
if (!propertyVerification.verified()) {
    throw new IllegalStateException(propertyVerification.reasonCode());
}
```

The service regenerates the plan and freezes its complete root/shrink closure; there is no case
selection. The fixture must already belong to the same exact target and contain assertions. The
ordinary `TestSuiteBuilder` intentionally remains limited to V1/V2 business intents, while
`PROPERTY` is reserved for server-materialized V4. Check the capability probe before execution;
`propertySuiteExecution=true` means the isolated testing runtime can emit the complete V5/V4/V4/V4
response, evidence, attestation, and portable-bundle generation.

Property execution is bounded sampling, never an exhaustive proof. `COLLECT_ALL` runs every frozen
root and shrink candidate. `FAIL_FAST` completes the shrink path belonging to the first failing root,
then skips later roots. When a counterexample is expected, use
`TestSuiteRunAssertions.assertCounterexampleFound(propertyRun)` to obtain a payload-free reference
containing case id, input fingerprint, deterministic complexity, and
`minimalityScope=PRECOMPUTED_SHRINK_PATH`. The reference always says `globallyMinimal=false` because
the runtime proves only the smallest observed failure on the reviewed path. Abandoned checkpoints
are terminalized as incomplete evidence; the server never regenerates or reruns missing inputs during
reconciliation.

Calling any semantic requirement method emits `bloge.testSuite.v2`; builders without these methods
remain on v1:

```java
TestSuiteBuilder semanticSuite = TestSuiteBuilder.graph(target)
        .id("loan-semantic-regression")
        .addCase("prime", TestSuiteBuilder.CaseType.GOLDEN,
                Map.of("applicantId", "prime"), stored)
        .requireBranchTransferred("approve-branch",
                "/root/decision#PRIMARY", "/root/approve#PRIMARY")
        .requireDecisionRule("prime-rule", "/root/decision#PRIMARY", "/rule", "PRIME")
        .requireRetry("bureau-retry", "/root/bureau#PRIMARY", 2)
        .requireTimeout("bureau-timeout", "/root/bureau#PRIMARY", "UPSTREAM_TIMEOUT");

TestSuiteRevision storedSemanticSuite = client.registerSuite(
        "loan-semantic-regression", semanticSuite.registrationRequest());
TestSuiteRun semanticRun = client.executeSuite(
        storedSemanticSuite.suiteId(), storedSemanticSuite.revision(),
        storedSemanticSuite.fingerprint(), "pipeline-semantic-1",
        ResourceGatewayTestClient.SuiteStrategy.COLLECT_ALL, Map.of());
TestSuiteRun.SemanticCoverage semanticCoverage = semanticRun.requireSemanticCoverage();

SemanticCorrectnessWorkbook workbook = client.findSemanticCorrectnessWorkbook(
        storedSemanticSuite.suiteId(), storedSemanticSuite.revision());
workbook.requireGateReady();

// Build this value from the exact workbook manifest/evidence projection plus ANEKE's policy result.
JsonNode gateV3 = objectMapper.readTree(gateResultJson);
GovernanceGateReceipt receipt = client.submitGovernanceGateResult(gateV3);
```

The semantic workbook call uses `WORKBOOK_SYNC` and validates the independent Tool Studio schema
before projecting any field. It accepts only an exact `bloge.testSuite.v2` revision and exposes
payload-free case identities, typed requirements, signed verdict references, truncation/trust state,
and portable evidence endpoints. `READY` is a producer-side seed status, not a publish decision:
retrieve every evidence bundle used by the gate and verify it with the independently distributed
key-set pin shown below. Structural v1 is rejected rather than interpreted as empty semantic coverage.
`submitGovernanceGateResult` validates `governance-gate-result-v3.schema.json` before sending and
validates the acknowledged payload again, using the least-privilege `GOVERNANCE_GATE_FEEDBACK`
purpose. It also rejects an acknowledgement whose immutable gate id or result fingerprint differs
from the submitted decision. This is an independent protocol consumer, not a substitute for the
real ANEKE N/N-1 release matrix.

Migrate the seven built-in graph suites into the same immutable registry without parsing raw maps:

```java
TestSuiteCatalogMaterialization catalog =
        client.materializeBuiltInGraphContractCatalog();

for (TestSuiteCatalogMaterialization.SuiteAsset asset : catalog.suites()) {
    TestSuiteCatalogMaterialization.ExactSuiteRef ref = asset.suiteRef();
    System.out.println(asset.sourceSuiteId() + " -> " + ref.exactRef());
}
```

The operation is idempotent for unchanged graph dependencies and source cases. Its payload-free exact
references can be supplied directly to `executeSuite` or to the CI command below; target, descriptor,
case, intent, assertion, or policy changes produce a new immutable revision instead of overwriting
history.

For business suites, `TestSuiteRun` links each case to its child `runId`, exact fixture revision,
evidence class, assertion counters, and stable diagnostic code. Schema-admission suites instead carry
typed admission observations and deliberately blank child fields. Both projections exclude inputs,
outputs, and free-form diagnostics. Structural v2, semantic v3, and admission v4 responses expose a
generation-matched signed `CHECKPOINT` or `TERMINAL` attestation; v1 responses remain readable but
explicitly unsigned. Semantic-aware consumers call `requireSemanticCoverage()` so historical v1 fails as
`SEMANTIC_COVERAGE_UNAVAILABLE` rather than appearing empty and satisfied.
`promotionEligible()` means only that the run satisfies the suite's policy and may be submitted to a
later gate; it does not mean certified, approved, or published.

For release-grade verification, export the portable terminal bundle and one atomic key lifecycle
snapshot, then compare it with a fingerprint obtained through an independent governance channel:

```java
TestSuiteEvidenceBundle bundle = client.findSuiteEvidenceBundle(suiteRun.suiteRunId());
EvidenceVerificationKeySet keySet = client.findEvidenceVerificationKeySet();
String trustedPin = System.getenv("RESOURCE_GATEWAY_EVIDENCE_KEY_SET_PIN");
TestSuiteEvidenceVerifier.VerificationResult verification =
        new TestSuiteEvidenceVerifier().verify(bundle, keySet, trustedPin);

// Convenience form: fetch the same bundle and key set, then apply the supplied pin.
TestSuiteEvidenceVerifier.VerificationResult sameResult =
        client.verifySuiteEvidence(suiteRun.suiteRunId(), trustedPin);
```

The verifier independently recomputes the aggregate, bundle, and signature-material fingerprints,
checks the ordered child run closure, validates the signed snapshot against the external pin, and
replays activation, retirement, disablement, prospective revocation, or retroactive compromise at
the evidence signing time before verifying Ed25519. It reports only bounded reason codes. A
`CURRENT_STATE_ONLY` snapshot fails closed for release use. Reading the pin from the same HTTP
response does not create trust; use an ANEKE registry revision, protected CI configuration, or an
equivalent independent channel.

The older `findEvidenceVerificationKey` and `verify(bundle, key)` path remains useful for migration
and local diagnosis, but a single current-state key cannot prove atomic rotation or historical
revocation. The bundle uses `payloadPolicy=OMITTED`; child input/output values remain in governed
server storage. It is not a replay payload package, publish decision, or complete ANEKE workbook;
the semantic workbook projection contains references and verdicts, while this bundle supplies the
portable material that must be independently verified. See the
[key lifecycle verification record](../docs/resource-gateway-execution-data-control-plane-stage3-key-lifecycle-verification.md).

Gate a request-index format transition without trusting one load-balanced sample. The deployment
platform must provide a directly routable URI for every exact serving instance and independently
trusted policy values:

```java
Map<String, URI> servingInventory = deploymentPlatform.exactServingInstances();
String challenge = deploymentPlatform.newGateChallenge();

List<WorkerQuarantineRequestIndexReplicaProof> proofs = new ArrayList<>();
for (Map.Entry<String, URI> instance : servingInventory.entrySet()) {
    ResourceGatewayTestClient instanceClient = ResourceGatewayTestClient
            .builder(instance.getValue())
            .bearerToken(() -> System.getenv("RESOURCE_GATEWAY_MAINTENANCE_TOKEN"))
            .build();
    proofs.add(instanceClient.requestWorkerQuarantineRequestIndexReplicaProof(
            challenge,
            WorkerQuarantineRequestIndexReplicaProof.Mode.DUAL_READ_KEYED_WRITE));
}

EvidenceVerificationKeySet keySet = controlClient.findEvidenceVerificationKeySet();
WorkerQuarantineRequestIndexFleetPolicy policy =
        WorkerQuarantineRequestIndexFleetPolicy.strict(
                challenge,
                deploymentPlatform.deploymentScopeFingerprint(),
                WorkerQuarantineRequestIndexReplicaProof.Mode.DUAL_READ_KEYED_WRITE,
                deploymentPlatform.artifactFingerprint(),
                deploymentPlatform.resourceGatewayProtocolVersion(),
                servingInventory.keySet(),
                independentlyPinnedKeySetFingerprint);

WorkerQuarantineRequestIndexFleetGateVerifier.VerificationResult gate =
        new WorkerQuarantineRequestIndexFleetGateVerifier().verify(proofs, policy, keySet);
if (!gate.verified()) {
    throw new IllegalStateException(gate.reasonCode());
}
```

The verifier first requires exact set equality for `instanceId` and unique process-start UUIDs,
then validates cohort observation spread, challenge, scope, artifact, protocol, immediate predecessor
mode, DB-clock inventory, exclusive expiry, canonical material fingerprint, current active-key policy,
and every Ed25519 signature. It never discovers fleet membership. An omitted, unregistered,
partitioned, shadow, or N-1 process remains the deployment platform's responsibility; the test-kit
only proves that the complete independently supplied inventory produced one coherent valid cohort.
Run one gate per identity-derived region scope. Cross-region simultaneity remains a higher-level
release policy.

## CI Command

`clean package` produces both the library JAR and a dependency-contained
`bloge-resource-gateway-test-kit-1.0.0-cli.jar`. Credentials are accepted only through the
environment, while the exact suite identity and caller-owned idempotency key are explicit:

```bash
export RESOURCE_GATEWAY_TOKEN='<short-lived workload token>'

java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --base-uri http://localhost:8080 \
  --suite-id normalization-regression \
  --revision 1 \
  --fingerprint 'sha256:<64 lowercase hex characters>' \
  --client-request-id "${CI_PIPELINE_ID}-${CI_JOB_ID}" \
  --strategy COLLECT_ALL \
  --report target/test-results/resource-gateway-suite.xml
```

V5 mutation suites must opt into the dedicated endpoint and its per-mutant strategy:

```bash
java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --base-uri http://localhost:8080 \
  --suite-id loan-decision-mutations \
  --revision 5 \
  --fingerprint 'sha256:<64 lowercase hex characters>' \
  --client-request-id "${CI_PIPELINE_ID}-${CI_JOB_ID}-mutation" \
  --mode MUTATION \
  --strategy STOP_AFTER_KILL \
  --report target/test-results/resource-gateway-mutation.xml
```

Suite stability is a third, strategy-free mode. It requires an externally managed key-set
fingerprint rather than trusting a key set merely because the same server returned it:

```bash
export RESOURCE_GATEWAY_TRUSTED_KEY_SET_FINGERPRINT='sha256:<externally-pinned-key-set-fingerprint>'

java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --base-uri http://localhost:8080 \
  --suite-id normalization-regression \
  --revision 1 \
  --fingerprint 'sha256:<64 lowercase hex characters>' \
  --client-request-id "${CI_PIPELINE_ID}-${CI_JOB_ID}-stability" \
  --mode STABILITY \
  --attempts 5 \
  --report target/test-results/resource-gateway-stability.xml
```

Add both statistical coordinates to select request v2 and the v3 confidence gate. When
`--attempts` is omitted, the CLI uses the exact minimum horizon; this example derives 29 attempts:

```bash
java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --base-uri http://localhost:8080 \
  --suite-id normalization-regression \
  --revision 1 \
  --fingerprint 'sha256:<64 lowercase hex characters>' \
  --client-request-id "${CI_PIPELINE_ID}-${CI_JOB_ID}-statistical-stability" \
  --mode STABILITY \
  --confidence-bps 9500 \
  --max-instability-rate-bps 1000 \
  --report target/test-results/resource-gateway-statistical-stability.xml
```

`STANDARD` is the default mode and accepts `COLLECT_ALL` or `FAIL_FAST`. `MUTATION` accepts
`COLLECT_ALL` or `STOP_AFTER_KILL`; the latter stops only the current mutant after a signed assertion
kill and still visits every later mutant. Deterministic `STABILITY` accepts 3..20 attempts;
statistical mode is selected by the paired confidence/rate options and accepts a sufficient 3..1000
horizon. Both reject `--strategy` and `--allow-non-eligible`, and require
`--trusted-key-set-fingerprint` or
`RESOURCE_GATEWAY_TRUSTED_KEY_SET_FINGERPRINT`. A mode-incompatible option is rejected before any
network request.

The command returns:

- `0` only when the selected evaluation mode's typed verdict passes and, by default, promotion status
  is `ELIGIBLE`; mutation mode additionally requires a passing baseline and independently re-derived
  `SATISFIED` score policy, while stability mode requires `STABLE`, promotion eligibility, exact
  v2+ source-promotion closure, exact source evidence closure, and a signature rooted in the
  externally pinned key set; a configured statistical policy additionally requires independently
  re-derived v3 `SATISFIED` confidence;
- `1` when governed terminal evidence was obtained but its quality, promotion, or trust gate failed;
- `2` when configuration, transport, protocol validation, report generation, or a non-terminal
  `RUNNING` checkpoint prevents a trustworthy gate verdict.

`--allow-non-eligible` disables only the promotion-eligibility requirement; the mode-specific typed
verdict must still pass. Mutation JUnit XML includes one payload-free row per baseline case and mutant,
but individual survivors are informational because the immutable aggregate score policy owns the gate
verdict. Stability JUnit XML includes one payload-free row per stability case, one pinned-trust
attestation row, and one aggregate gate row. The CLI never accepts a token argument, never generates an idempotency key implicitly, and
writes a one-test infrastructure failure report when execution fails before governed terminal suite
evidence is available. Unknown options and positional arguments are reported without echoing values.

`EXECUTABLE_UNIT` does not by itself imply certification. The server also requires a frozen
implementation closure, runtime state, and v2 composability manifest. Stateless operators satisfy
only the state-freezing condition; they do not qualify automatically. Configured operators implement
`OperatorRuntimeBindingSnapshotProvider`, while non-resource certifiable operators implement
`OperatorComposabilityManifestProvider` with a self-contained declaration and fingerprinted
conformance suite. An undeclared, unformalized stateful, or opaque binding still runs when
the plan can control it, but its evidence remains `EXPLORATORY`. `HttpResourceOperator` requires
transport-level resource fixtures so its mapping and response protocol execute for real.

Use inline fixtures only for exploratory authoring. Registered immutable
fixtures are required for certifiable evidence. Resource fixtures that need to
prove response protocol and payload extraction behavior should use
`protocolResponse`; `returnValue` is an output-level double and cannot by itself
earn certifiable evidence for a resource site.

The typed summaries retain `invocationSiteId`, `graphPath`, `correlationKey`,
site `occurrence`, containing `graphOccurrence`, retry attempts, and edge
endpoints. They intentionally omit node/attempt/edge payload values; use
`rawResponse()` only in an explicitly authorized diagnostic path when sanitized
payload inspection is required. Producers that predate occurrence coordinates
remain readable and project zero coordinates plus empty attempt/edge lists.

Current `bloge.testRunEvidence.v2` also carries `semanticResultFingerprint`. It identifies stable
business outcomes across equivalent deterministic runs while complete evidence fingerprints remain
unique. Historical evidence v1 remains readable but has no semantic identity;
`assertSameSemanticResult` fails closed when the baseline fingerprint is absent. `STANDARD` and
`SUMMARY` expose this value as signed full-evidence lineage, not as independently recomputable proof.

For timeout, retry, fallback, or time-dependent business rules, declare one
run-scoped logical clock and use `delay` or `timeout`:

```java
FixtureBundleBuilder timeoutFixture = FixtureBundleBuilder
        .graph(target.graphId(), target.fingerprint())
        .id("loan-provider-timeout")
        .logicalClock(Instant.parse("2026-07-15T09:00:00Z"))
        .rule("provider-timeout")
            .node("fetchCreditScore")
            .timeout(Duration.ofSeconds(3),
                    "CREDIT_BUREAU_TIMEOUT",
                    "credit bureau did not answer")
            .requiredUses(2, 2)
            .add();
```

`requiredUses(2, 2)` proves that a graph configured for one retry consumed the
timeout twice. `delay(after, value)` advances the same logical clock and then
returns a fixed schema-gated value. Both controls are node-boundary controls,
require `logicalClock`, reject durations over 365 days, and consume no wall time.
They verify retry/fallback and time-dependent business semantics, not real
watchdog timing or thread interruption.

Use separate rules when each retry attempt or nested graph re-entry needs different behavior:

```java
FixtureBundleBuilder scriptedRetry = FixtureBundleBuilder
        .graph(target.graphId(), target.fingerprint())
        .id("scripted-retry")
        .logicalClock(Instant.parse("2026-07-15T09:00:00Z"))
        .rule("first-attempt-times-out")
            .node("fetchCreditScore")
            .attempts(1)
            .timeout(Duration.ofSeconds(3))
            .add()
        .rule("second-attempt-recovers")
            .node("fetchCreditScore")
            .attempts(2)
            .returnValue(Map.of("score", 780))
            .add();
```

`attempts(...)` and `occurrences(...)` canonicalize their arguments as sorted one-based sets.
Attempts count delegate calls within one occurrence; occurrences count repeated bindings for one
site and correlation key. The dimensions are ANDed when both are present. Overlapping rules at the
same precedence are rejected before execution, and a coordinate with no matching rule follows the
declared unmatched policy, which defaults to fail closed.

## Security Defaults

- A fresh bearer token is requested from the provider for each HTTP call.
- Every operation sends an explicit least-privilege `X-Purpose` and correlation
  id.
- Redirects are disabled by the default client.
- Request and response bodies default to a 16 MiB hard limit.
- Exceptions and JUnit XML omit credentials, request bodies, node input/output,
  and problem `details`; use the run/correlation id for authorized diagnosis.
- Unknown response protocol versions fail immediately.
- Current v2 child runs require a structurally consistent versioned integrity manifest. Structural
  suite response v2 and semantic response v3 require generation-matched signed checkpoint or
  terminal attestations. Historical v1 suite responses are accepted only as unsigned migration data
  and cannot be exported as trusted terminal bundles.
- Release-grade offline verification pins the signed atomic key-set fingerprint and reconstructs
  ACTIVE, retirement, disable, and prospective/retroactive revocation at evidence signing time.
  Exact-key lookup remains a migration/diagnostic path. Missing keys, pin mismatch, stale policy,
  invalid signatures, and malformed material fail closed without echoing evidence payloads.
- Suite requests and responses are validated against the exact packaged JSON Schema; returned suite
  id, revision, fingerprint, run id, and `clientRequestId` are rebound to the originating request.
- Suite execution requires an exact positive revision, full lowercase SHA-256 fingerprint, and
  explicit `clientRequestId` before any network call.

The packaged schema is available at `TestingProtocol.SCHEMA_RESOURCE`; `clean verify` also fails on
public JavaDoc warnings so the client contract cannot silently lose parameter semantics. Full
server endpoint, identity, and profile requirements are documented in
[`docs/resource-gateway-testing-control-plane-api.md`](../docs/resource-gateway-testing-control-plane-api.md).
