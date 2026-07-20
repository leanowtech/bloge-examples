# Stage 2 Governed Replay Payload And Execution Verification

## Scope

This increment establishes the governed F4 data source and activates `FixtureRule.REPLAY` without
treating arbitrary fixture JSON as historical truth.

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
- pre-plan exact dependency-closure resolution with a 1,000-ref and 16 MiB run budget;
- run-scoped canonical payload freezing with no repository access from planner or runtime;
- payload-free `bloge.effectiveExecutionPlan.v2` dependency identity and source lineage;
- fresh value materialization per invocation, BLOGE output-schema validation, and zero real calls;
- `MOCKED`/`REPLAYED` node and attempt evidence plus whole-run certification downgrade;
- schema, capability, endpoint, and configuration documentation.

The captured value is only the selected sanitized node output. Source input, credentials,
side-effect outcomes, and side-effect journal records do not cross into the replay vault.

## Storage Integrity Hardening (2026-07-20)

The replay vault now treats persistence as a hostile adapter boundary rather than relying only on
the service's available-value check. Values are canonicalized and detached, create receipts and
complete lookup keys are verified, descriptor JSON is bound to every indexed projection, and a
payload-free record commitment protects scope, provenance, and lifecycle state after expiry removes
the value. Expiry replaces the commitment in the same compare-and-set update that erases
`payload_json`; legacy available rows are revalidated and legacy tombstones receive an explicit
value-free migration baseline before reads are served.

Implementation, migration limits, attack cases, and current verification evidence are recorded in
[Replay vault storage integrity verification](resource-gateway-execution-data-control-plane-stage2-replay-storage-integrity-verification.md).

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
| missing, expired, or purged stored value | fail before scheduling; no fallback and no payload in tombstone |
| descriptor/value integrity mismatch | conflict plus security event before scheduling |
| replay closure differs from fixture refs | control-plan rejection |
| replay output violates operator schema | execution failure; real operator remains uncalled |
| more than 1,000 refs or 16 MiB frozen JSON | bounded request rejection |
| same id/revision with different content | immutable revision conflict |

## Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestRuntimeProfileIsolationTest,ReplayPayloadRefTest,\
DatabaseReplayPayloadRepositoryTest,TestReplayPayloadServiceTest,TestExecutionControllerTest,\
TestExecutionApiServiceTest,TestSuiteExecutionServiceTest,ExecutionControlCompilerTest,\
TestRunServiceTest,TestingControlProtocolSchemaTest,TestingDomainProtocolTest,\
TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest test
```

Result: 94 tests, 0 failures, 0 errors, 0 skipped.

The focused matrix proves canonical reference rejection, immutable/scoped persistence, JSON-null
round-trip, scheduled/read-time expiry convergence, signed source capture, repeat sanitization,
exact-attempt selection, purpose/scope/group/clearance/fingerprint/classification rejection,
truncation rejection, exact fixture closure, payload-free control-plan lineage, schema-gated replay
execution without a real operator call, graph/operator/suite purpose routing, schema parity,
capability truthfulness, and real Spring profile assembly including its visual-run repository
dependency.

Full project gate:

```bash
mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify
```

Result: build success; 1781 tests, 0 failures, 0 errors, 0 skipped.

The newly introduced `ResolvedReplayPayloads` runtime type also passes isolated
`javadoc -Xdoclint:all` with zero warnings. A wider pass over changed public types still reports
historical documentation-completeness warnings; the repository-wide Javadoc cleanup remains
outside this increment.

## Execution Boundary

Capability probe reports:

- `governedTestReplayPayloadCapture=true`;
- `testReplayBehavior=true`.

Only the authorized API boundary may resolve replay storage. Fixture registration validates the
dependency closure, and each execution resolves it again to recheck lifecycle and authorization.
`SafetyPreflight` requires exact closure equality and rejects payload-bearing, transport-boundary,
or fallback-to-real REPLAY rules. `TestDoubleFactory` consumes only the frozen run object, emits
`REPLAYED`, and never calls the real binding. STREAM behavior remains reserved.
