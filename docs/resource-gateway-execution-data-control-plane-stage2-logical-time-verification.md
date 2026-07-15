# Execution Data Control Plane Stage 2 Logical-Time Verification

> Verification date: 2026-07-15
>
> Scope: run-scoped logical clock and node-boundary `DELAY` / `TIMEOUT`

## 1. Claim

Resource Gateway can now execute time-dependent graph tests without wall-clock waiting. A fixture
bundle that declares `logicalClock` receives a fresh `AdvancingLogicalTimeSource`; BLOGE propagates
that source through `OperatorContext`, retry backoff, loop delay, and the isolated test engine.

`DELAY` advances logical time and returns a schema-gated fixture value. `TIMEOUT` advances logical
time and throws BLOGE's `OperatorTimeoutException`, preserving the graph's real retry and fallback
policies. Evidence keeps real audit timestamps and separately records logical origin, current time,
elapsed milliseconds, node control mode, normalized timeout status, and fixture consumption.

## 2. Safety Invariants

1. Logical time exists only inside one short-lived test engine; production engines and adjacent
   test runs never share it.
2. `DELAY` and `TIMEOUT` are rejected unless `fixtureBundle.logicalClock` is present.
3. `after` must be positive and no greater than 365 days.
4. Time controls are node-boundary only; `TIMEOUT` cannot carry a return/protocol payload.
5. `randomSeed`, `STREAM`, `REPLAY`, sequence, and replay references remain fail closed.
6. An unrecovered timeout emits top-level `TIMED_OUT`, node `TIMEOUT`, and a stable error code.
7. A recovered timeout emits a successful graph result while bounded fixture consumption proves the
   number of attempts that actually occurred.

## 3. Executable Proofs

| Proof | Expected observation |
| --- | --- |
| 30-day `DELAY` under a two-second test deadline | run passes; logical elapsed is exactly 30 days |
| single custom-code `TIMEOUT` | terminal `TIMED_OUT`; node `TIMEOUT`; declared error code retained |
| two 3-second timeouts + one 2-second retry backoff + fallback | run passes; fixture uses = 2; fallback output returned; logical elapsed = 8 seconds |
| 100 concurrent one-second sleeps | no lost advance; final logical elapsed = 100 seconds |
| missing clock, misplaced `after`, random seed | control plan rejected before graph execution |
| test-kit delay/timeout builder | complete v1 wire payload and 365-day client-side bound |

Focused server verification:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=AdvancingLogicalTimeSourceTest,ExecutionControlCompilerTest,TestRunServiceTest test
```

Result: 30 tests, 0 failures, 0 errors, 0 skipped.

Independent client verification:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

Result: 11 tests, 0 failures, 0 errors, 0 skipped; JAR built with the canonical schema.

Full Resource Gateway gate:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result: 1699 tests, 0 failures, 0 errors, 34 conditional skips; real-Chrome authoring
regression and executable Spring Boot JAR packaging both passed.

## 4. Deliberate Non-Claims

- This proves deterministic logical-time advancement, not deterministic ordering between
  concurrently scheduled branches.
- Injected `TIMEOUT` proves retry/fallback and business failure handling, not wall-clock watchdog
  precision or interruption of a genuinely blocked operator thread.
- Real deadline, scheduler, cancellation, and thread-release behavior remains a BLOGE engine and
  sandbox conformance responsibility.
- `STREAM`, replay vaults, random/UUID/function services, attempt selectors, and nested invocation
  addressing are not activated by this increment.

These boundaries prevent a fast logical-time test from being misrepresented as production timing
evidence.
