# Execution Data Control Plane Stage 2: Suite Consumer Adapters Verification

## Scope

This increment makes the immutable TestSuite registry and runner directly consumable from Java,
JUnit 5, and a conventional CI process. It adds:

- `TestSuiteBuilder`, which binds a discovered graph/operator fingerprint to exact stored fixture
  revisions and emits `bloge.testSuiteRegistrationRequest.v1`;
- `ResourceGatewayTestClient` methods for suite register, exact revision lookup, idempotent execution,
  and aggregate-run lookup;
- payload-safe `TestSuiteRevision` and `TestSuiteRun` projections;
- `TestSuiteRunAssertions` for aggregate execution, child cases, structural coverage, and promotion
  eligibility;
- suite-aware JUnit XML with one testcase per governed case and one fail-closed aggregate gate;
- `ResourceGatewaySuiteCli` and a dependency-contained `-cli.jar` for CI.

The adapters validate complete suite values at runtime against the canonical packaged
testing-control-plane v1 schema. They do not copy server domain or persistence classes into the
client artifact.

## Safety Invariants

1. Suite execution accepts only an exact positive revision and full lowercase SHA-256 fingerprint.
2. `clientRequestId` is mandatory and never generated implicitly by the client or CLI.
3. The CLI accepts a bearer credential only from `RESOURCE_GATEWAY_TOKEN`, never a process argument.
4. Registration uses `TEST_SUITE_WRITE`, lookup uses `TEST_SUITE_READ`, and execution/query use
   `TEST_EXECUTION`.
5. Unknown response versions, malformed aggregate states, inexact fingerprints, invalid counters,
   contradictory passing child evidence, non-machine reason codes, and passing cases without linked
   child evidence fail closed.
6. Returned suite id/revision/fingerprint, `clientRequestId`, and suite-run id must match the exact
   request identity before assertions or reporters can consume them.
7. Built-in assertions and JUnit XML never emit child input/output, free-form case diagnostics,
   aggregate diagnostics, request bodies, credentials, or problem details.
8. Unknown CLI positional/option values are never echoed because they may contain accidentally
   supplied credentials or business payloads.
9. A JUnit suite report has one case per governed case plus one aggregate gate. A non-passing case
   and the blocked aggregate remain two visible failures rather than being collapsed.
10. The aggregate gate requires `PASSED`, `SATISFIED` coverage, and every case passed. It additionally
   requires `ELIGIBLE` unless the caller explicitly chooses `--allow-non-eligible`.
11. CLI exit `1` means terminal governed evidence was obtained and failed policy; exit `2` means no
    trustworthy terminal gate result was available, including a valid `RUNNING` checkpoint.
12. `ELIGIBLE` is described only as input to a later gate, never as certification or publication.
13. Maven `verify` runs doclint and fails on JavaDoc warnings for the public test-kit contract.

## Verification Matrix

`ResourceGatewayTestClientTest` proves exact URI encoding, purposes, request body identity, typed
revision/run projection, query behavior, and pre-network rejection of short fingerprints and blank
idempotency keys. It also rejects responses bound to a different caller intent.

`TestSuiteBuilderTest` proves dependency-closed fixture references, deterministic case-type/tag and
edge ordering, conservative case/certification defaults, and rejection of duplicate cases or
impossible coverage requirements, unrepresented required case types, and scalar graph contexts.

`TestSuiteRunAssertionsTest` proves independent execution, case, coverage, and eligibility assertions,
including payload-free failure messages, authoritative schema rejection, and cause-chain redaction.

`JUnitXmlReportWriterTest` proves XML escaping, case/gate cardinality, deterministic failure counts,
stable reason codes, and exclusion of payload-bearing diagnostics and metadata.

`ResourceGatewaySuiteCliTest` uses a real JDK HTTP server and proves credential/purpose headers,
eligible exit `0`, blocked exit `1`, configuration exit `2`, JUnit artifact generation, and absence
of tokens or diagnostics from process output. It additionally proves `RUNNING` exits `2` and
unexpected positional values are never echoed.

## Commands

Focused suite consumer verification:

```bash
mvn -f resource-gateway-test-kit/pom.xml -q \
  -Dtest=TestSuiteBuilderTest,TestSuiteRunAssertionsTest,ResourceGatewayTestClientTest,\
JUnitXmlReportWriterTest,ResourceGatewaySuiteCliTest test
```

Full standalone artifact verification:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Executable artifact smoke check:

```bash
java -jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar
```

The smoke command intentionally exits `2` without required settings; reaching the bounded
configuration diagnostic proves the shaded artifact, manifest entry point, and runtime dependencies
are loadable.

## Measured Result

Measured on 2026-07-15 with Java 25 and Maven 3.9:

- focused suite-consumer verification: 21 tests, 0 failures, 0 errors, 0 skipped;
- standalone test-kit `clean verify`: 29 tests, 0 failures, 0 errors, 0 skipped;
- Resource Gateway `clean verify`: 1748 tests, 0 failures, 0 errors, 34 conditional skips;
- both the library JAR and dependency-contained CLI JAR were built with the canonical schema;
- doclint completed with zero JavaDoc warnings and is enforced by Maven `verify`;
- direct `java -jar` startup reached the bounded configuration diagnostic and exited `2`, proving
  the manifest and shaded runtime dependencies are loadable without a service-side classpath.

## Explicit Non-Claims

- The Canvas does not yet publish its multi-row operator table as one immutable suite revision.
- The CLI does not poll or reconcile abandoned `RUNNING` suite checkpoints; it emits an
  infrastructure JUnit failure and exits `2`.
- No suite list/history/trend API, shard coordinator, quota scheduler, or multi-region owner exists.
- JUnit XML is payload-free evidence projection, not a signed evidence bundle.
- Promotion eligibility is not ANEKE approval, certification, signature, or publication.
