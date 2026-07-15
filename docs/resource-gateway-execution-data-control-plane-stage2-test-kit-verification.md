# Execution Data Control Plane Stage 2 Test-Kit Verification

> Verified: 2026-07-15
>
> Scope: independent Resource Gateway Java/JUnit client adapter

## Delivered Boundary

The test kit is a standalone Maven library, not a dependency on the Resource Gateway Spring Boot
application. It consumes only `bloge.testing.v1` HTTP contracts and packages the authoritative
testing-control-plane JSON Schema. This keeps server startup, packaging, and release independent
while allowing CI and business repositories to pin a client version.

Implemented surfaces:

| Surface | Verification |
| --- | --- |
| target discovery | encoded graph ids, `TEST_EXECUTION` purpose, typed fingerprint/readiness projection |
| immutable fixture registry | write/read purposes, revision query, typed stored revision |
| single/batch/query execution | protocol guards, result cardinality, ten-state typed run projection |
| occurrence evidence | payload-free node/site/correlation/occurrence, retry-attempt, and edge endpoint projection; legacy zero-coordinate compatibility |
| fixture authoring | schema-complete selector/match/behavior/consumption/schema-check fields, strict fail-closed defaults, inline/stored request exclusivity |
| F2 resource fixture | transport-boundary raw body, status, and headers instead of self-reported resource output |
| JUnit integration | certifiable/pass/consumption/hermetic assertions, payload-free XML, deterministic exit code |
| client hardening | per-call token provider, explicit purpose/correlation id, bounded bodies, protocol mismatch failure, immutable raw projections, problem-detail/credential/payload omission |

## Reproduce

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
mvn -f resource-gateway-examples/pom.xml clean verify
```

Test-kit result at this increment: 12 tests, 0 failures, 0 errors, 0 skipped. The server regression
build completed with 1709 tests, 0 failures, 0 errors, 2 conditional skips, and a packaged Spring
Boot JAR.

## Explicit Non-Claims

This increment does not complete Stage 2. It does not add public operator execution,
streaming/suspendable control and evidence, or physical runtime deployment isolation. The current
JUnit XML is intentionally an evidence index, not an evidence dump; authorized users diagnose
payload differences through the persisted run API. Synchronous nested/foreach/loop/compensation
addressing, temporal `DELAY/TIMEOUT`, and seven-graph dogfooding are now implemented server-side.
