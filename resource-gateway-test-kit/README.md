# Resource Gateway Test Kit

`bloge-resource-gateway-test-kit` lets Java and JUnit 5 suites drive the
Resource Gateway testing control plane without depending on its Spring Boot
implementation. The JAR packages the authoritative v1 JSON Schema and provides:

- a bounded JDK HTTP client for target discovery, fixture registry, single/batch
  execution, and persisted-run lookup;
- a fail-closed `FixtureBundleBuilder` for output-level and transport-level
  protocol fixtures;
- payload-safe typed run summaries and JUnit 5 assertions;
- occurrence-addressable node, retry-attempt, and edge summaries without payload fields;
- payload-free JUnit XML with deterministic CI exit codes.

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

The packaged schema is available at `TestingProtocol.SCHEMA_RESOURCE`. Full
server endpoint, identity, and profile requirements are documented in
[`docs/resource-gateway-testing-control-plane-api.md`](../docs/resource-gateway-testing-control-plane-api.md).
