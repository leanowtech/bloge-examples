# Stage 2 Governed Replay Payload Verification

## Scope

This increment establishes the governed F4 data source before activating `FixtureRule.REPLAY`.
It does not treat arbitrary fixture JSON as historical truth.

Implemented contracts:

- exact `bloge-replay:<id>@<revision>#<fingerprint>` references with no `latest` form;
- dedicated `TEST_REPLAY` workload purpose and test/staging profile boundary;
- capture from one exact successful node attempt in signed visual run evidence;
- detached payload lifecycle signature, tenant/environment, clearance, and group checks;
- optimistic source run and payload fingerprint checks;
- server-side second sanitization and fail-closed truncation rejection;
- destination classification that cannot downgrade the governed source;
- destination retention capped by both source expiry and server maximum;
- isolated JDBC value storage with `EXPIRED` payload-free tombstones;
- bounded scheduled expiry sweep and read-time expiry convergence;
- schema, capability, endpoint, and configuration documentation.

The captured value is only the selected sanitized node output. Source input, credentials,
side-effect outcomes, and side-effect journal records do not cross into the replay vault.

## Trust Classes

`ReplayPayloadDescriptor.certificationEligible` is true only for a signed immutable publication
run. Successful stored/transient draft captures remain useful but explicitly carry
`SOURCE_NOT_IMMUTABLE_PUBLICATION_RUN` and `SOURCE_NOT_CERTIFIABLE` gaps. A later execution may not
upgrade a non-certifiable payload merely because its fixture is stored.

## Failure Semantics

| Condition | Result |
| --- | --- |
| wrong purpose or production identity | forbidden before payload access |
| cross-tenant/environment source | not found |
| stale run/payload fingerprint | conflict |
| invalid evidence or lifecycle signature | conflict plus security event |
| missing/failed attempt | conflict |
| classification downgrade or insufficient clearance/group | bad request/forbidden |
| source or capture truncation | conflict |
| expiry beyond source/server policy | bad request |
| expired stored value | gone; no fallback and no payload in tombstone |
| same id/revision with different content | immutable revision conflict |

## Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestRuntimeProfileIsolationTest,ReplayPayloadRefTest,\
DatabaseReplayPayloadRepositoryTest,TestReplayPayloadServiceTest,TestExecutionControllerTest,\
TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,\
TestRuntimeApplicationIntegrationTest test
```

Result: 26 tests, 0 failures, 0 errors.

The focused matrix proves canonical reference rejection, immutable/scoped persistence, JSON-null
round-trip, scheduled/read-time expiry convergence, signed source capture, repeat sanitization,
exact-attempt selection, purpose/scope/group/clearance/fingerprint/classification rejection,
truncation rejection, HTTP purpose routing, schema parity, capability truthfulness, and real Spring
profile assembly including its visual-run repository dependency.

Full project gate:

```bash
mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify
```

Result: build success; 1771 tests, 0 failures, 0 errors, 0 skipped.

The nine new public replay types also pass isolated `javadoc -Xdoclint:all` with zero warnings.
The repository-wide Javadoc goal remains outside this increment because existing unrelated source
files still carry historical doclint debt.

## Deliberate Boundary

Capability probe reports:

- `governedTestReplayPayloadCapture=true`;
- `testReplayBehavior=false`.

`SafetyPreflight` continues to reject `kind=REPLAY` until the next increment freezes all referenced
payloads into the effective plan, executes without repository access, emits `REPLAYED` fidelity,
and blocks certification when any replay dependency is non-certifiable.
