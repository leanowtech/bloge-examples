# Resource Gateway Test Kit

`bloge-resource-gateway-test-kit` lets Java and JUnit 5 suites drive the
Resource Gateway testing control plane without depending on its Spring Boot
implementation. The JAR packages the authoritative v1 JSON Schema and provides:

- a bounded JDK HTTP client for graph/operator target discovery, fixture and immutable-suite
  registries, built-in graph-catalog materialization, graph/operator execution, suite execution,
  and persisted child/aggregate-run lookup;
- a fail-closed `FixtureBundleBuilder` for output-level and transport-level
  protocol fixtures;
- a dependency-closed `TestSuiteBuilder` with exact target and fixture references;
- runtime validation against the packaged Draft 2020-12 schema plus request/response identity binding;
- payload-safe typed child/suite-run summaries and JUnit 5 assertions;
- occurrence-addressable node, retry-attempt, and edge summaries without payload fields;
- payload-free JUnit XML with deterministic CI exit codes;
- an executable `-cli.jar` that fails closed on suite, coverage, or promotion-policy failure.

## Build

From the repository root:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml install
```

The module is intentionally independent of `resource-gateway-examples`. The
server and client can therefore build and release separately against the
versioned wire schema.

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

TestRun run = client.execute(fixture.storedExecution(
        stored.fingerprint(),
        Map.of("applicantId", "app-42", "amount", 100_000),
        ResourceGatewayTestClient.Verbosity.STANDARD,
        Map.of("suiteRef", "loan-policy", "caseRef", "approved")));

TestRunAssertions.assertPassed(run);
TestRunAssertions.assertCertifiable(run);
TestRunAssertions.assertFixturesSatisfied(run);
TestRunAssertions.assertNoRealInvocations(run);

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

JUnitXmlReportWriter.writeSuite(
        Path.of("target/surefire-reports/resource-gateway-suite.xml"),
        suiteRun,
        true);
```

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

`TestSuiteRun` links each case to its child `runId`, exact fixture revision, evidence class,
assertion counters, and stable diagnostic code. It intentionally excludes child inputs, outputs,
and free-form diagnostics from its reportable projection. `promotionEligible()` means only that the
run satisfies the suite's policy and may be submitted to a later gate; it does not mean signed,
certified, approved, or published.

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

The command returns:

- `0` only when suite status is `PASSED`, coverage is `SATISFIED`, every case passed, and promotion
  status is `ELIGIBLE`;
- `1` when governed evidence was obtained but the suite gate failed;
- `2` when configuration, transport, protocol validation, report generation, or a non-terminal
  `RUNNING` checkpoint prevents a trustworthy gate verdict.

`--allow-non-eligible` disables only the promotion-eligibility requirement; execution, all cases,
and coverage must still pass. The CLI never accepts a token argument, never generates an idempotency
key implicitly, and writes a one-test infrastructure failure report when execution fails before
governed terminal suite evidence is available. Unknown options and positional arguments are reported
without echoing their values.

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

## Security Defaults

- A fresh bearer token is requested from the provider for each HTTP call.
- Every operation sends an explicit least-privilege `X-Purpose` and correlation
  id.
- Redirects are disabled by the default client.
- Request and response bodies default to a 16 MiB hard limit.
- Exceptions and JUnit XML omit credentials, request bodies, node input/output,
  and problem `details`; use the run/correlation id for authorized diagnosis.
- Unknown response protocol versions fail immediately.
- Suite requests and responses are validated against the exact packaged JSON Schema; returned suite
  id, revision, fingerprint, run id, and `clientRequestId` are rebound to the originating request.
- Suite execution requires an exact positive revision, full lowercase SHA-256 fingerprint, and
  explicit `clientRequestId` before any network call.

The packaged schema is available at `TestingProtocol.SCHEMA_RESOURCE`; `clean verify` also fails on
public JavaDoc warnings so the client contract cannot silently lose parameter semantics. Full
server endpoint, identity, and profile requirements are documented in
[`docs/resource-gateway-testing-control-plane-api.md`](../docs/resource-gateway-testing-control-plane-api.md).
