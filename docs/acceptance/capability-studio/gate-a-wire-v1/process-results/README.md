# Gate A process/results wire fixtures

This directory is the design-as-code fixture set for the Gate A process boundary. The schemas in `docs/schemas/resource-gateway-capability-studio/` are the wire authority; these files are intentionally small, deterministic examples rather than production evidence.

## What is closed

- `CandidateChallengeResponse v1` contains semantic facts only. It has no exit code, stdout/stderr fingerprint, start/end time, timeout, cancellation, or process state. Those facts exist only in the parent-observed transcript.
- `HarnessProcessTranscript v1` and `ProcessTranscript v1` bind command, invocation, actual CodeSource, exit, timestamps, timeout/cancel flags, exact stdout/stderr references, and a raw `ProcessObservation v1` reference. `ProcessTranscript v1` additionally carries caller-owned `codeSourceObservation.preRead` and `postRead` snapshots. Each snapshot freezes resolved path, file key, owner, group, link count, POSIX mode, size, and the raw bytes read. `ProcessObservation v1` records Java runtime/executable identity, effective classpath, admitted class origins, process-tree quiescence, bounded output capture and leak-scan outcome. These are parent-owned observation facts; a child result cannot manufacture them.
- `ProcessTranscript v1` separates protocol completion from a successful admission decision. `COMPLETED` accepts the frozen protocol exits `0/2/3/4` with `false/false`; `FAILED` is an unexpected non-protocol exit; `TIMED_OUT = 143 + true/false`, `CANCELLED = 130 + false/true`, and `UNAVAILABLE = 255 + false/false`. `startedAt <= endedAt` and the pre-read/post-read CodeSource identity binding are semantic rules checked by the validator.
- `GateACandidateReplayResult v1` freezes three ordered adapters and fourteen ordered `FELT-01..14` obligations. Obligation status is `FAIL | BLOCKED | NOT_RUN`; A0 cannot claim `PASS`. Count fields are bounded structural projections; A1 must recompute them through `A0_SLOT_COUNT_PROJECTION` before trusting them.
- `GateAReplayVerificationResult v1` is a complete nine-slot result. It becomes a closed diagnostic Proof only inside a caller-owned `GateAReplayProofEnvelope v1` that binds the result bytes to the A1 producer transcript and material root. Incomplete material or an A1 crash/timeout remains an outer process attempt and cannot be serialized as a Proof.
- `GateAIndependentVerificationResult v1` is the **only** wire authority for the immutable artifact carried in the `TEST_REPORT` role. `TEST_REPORT` is an artifact role, not a second protocol or alias Schema. The result has exactly five ordered outer A1 runs and twelve ordered TCK projections: nine `NORMAL_CHILD` entries bind distinct candidate child processes, while three trust-plane entries bind the corresponding digest/Registry/TCK bootstrap-rejection outer processes. These three aliases are intentional and fixed by Test ID; every other command/request/response/transcript or observation reuse is rejected. Provider namespace collision is its only mandatory guard and is not a thirteenth test. Review-count consistency is evaluated later by A2 after signed review material exists.
- `GateAIndependentProofEnvelope v1` is the caller-owned closure around the exact `TEST_REPORT` stdout, Harness transcript, Harness run tree, and expected/observed Harness CodeSource. The report never references its own producer transcript. A Harness that prints only bootstrap `READY` while writing a side-file report is rejected even when both documents are individually Schema-valid.
- `ProviderMaterializationObservation v1` binds the actual deterministic Provider JAR, its closed ZIP inventory, Service descriptor/class bytes, `CREATE_NEW` destination, source pre/materialized/source post fingerprints, and executable parent receipts for destination create/open-read/delete. The receipts freeze destination identity before/after the stable read, raw bytes, link count, no-follow mode, and post-delete absence; D0 records the parent observation in a private fixture scratch directory because no real `/work` mount exists. Replay Results must reference this parent-owned observation.
- Every `run-material/runs/<runId>/` is a capability-scoped single-writer closure: caller owns pre-launch `command.json` and post-exit `stdout`, `stderr`, `response.json`, `process-observation.json`, `process-transcript.json`, and the final `material-root.tree`; A1 owns only `producer/invocation.json`, optional `producer/provider-materialization.json`, and `producer/children/**`. Harness runs use `producer/invocation.json` even when they have no Provider or children. Early digest/Registry/TCK bootstrap runs short-circuit before Provider admission and therefore must not contain `producer/provider-materialization.json`.
- `GateAAdmissionVerificationResult v1` freezes five requirement, four artifact, twelve test, two guard, and one trusted-review slots. Each slot uses `PASS | FAIL | MISSING | UNAVAILABLE`; `PASS/FAIL` requires its evidence ref and `MISSING/UNAVAILABLE` requires null.
- `GateAAdmissionProofEnvelope v1` is the caller-owned final A2 closure. It references the raw A2 result and its parent `ProcessTranscript`, and the semantic validator recomputes both referenced files' raw SHA-256 fingerprints before accepting the closure. It carries both Challenge/Admission Pin fingerprints, expected and observed A2 `CodeSource`, and binds the completed protocol outcome to `PASS/0`, `OPEN/4`, `FAIL/2`, or `UNAVAILABLE/3`; its time order is `transcript.startedAt <= transcript.endedAt <= envelope.createdAt`. Only `PASS/0` grants Gate B permission. A crash, timeout, cancellation, unavailable parent process, missing result, or CodeSource drift produces no envelope; it remains an attempt/transcript diagnostic.
- `AbnormalAttempt v1` closes each of the six Authority abnormal transitions (`CHILD_CRASH`, `CHILD_TIMEOUT`, `CHILD_CANCELLED`, `STDOUT_TRUNCATED`, `PROCESS_TREE_RESIDUE`, and `CODESOURCE_OBSERVATION_UNAVAILABLE`) around the existing ProcessObservation/ProcessTranscript bytes. It fixes the first-error reason and A1 exit, requires stop-later-slots, stream drain and process-tree reap, preserves only formed attempt material, and structurally forbids a semantic Result and later material. The validator checks the physical attempt directory as well as every declared field.
- A2 also emits eighteen ordered `semanticGuardResults` from the frozen Guard Catalog. Each result binds a root-cause Guard ID to its fixed admission target, closed reason, source fact IDs, concrete observation refs, and collector/derivation revisions. This is a diagnostic projection, not a second denominator; A2 must evaluate every Guard and verify that each result agrees with its target slot.
- All fingerprints in companion protocols are typed (`RAW_BYTES`, `CANONICAL_DOCUMENT`, `TREE_COMMITMENT`, or `AGGREGATE_COMMITMENT`). Relative references use the ASCII path grammar frozen by Gate A.

## Fixture inventory

Valid examples are prefixed `valid-`. Negative examples are prefixed `invalid-` and must be rejected by the named schema.

| Fixture | Schema exercised |
|---|---|
| `valid-candidate-challenge-request.json` | candidate request |
| `valid-candidate-challenge-response.json` | typed candidate response |
| `valid-candidate-challenge-response-legacy.json` | legacy candidate response with provider identity |
| `valid-challenge-sandbox-profile.json` | sandbox profile |
| `valid-process-command.json` | process command |
| `valid-process-observation.json` | parent runtime, process-tree and output-capture observation |
| `valid-process-observation-{a1,harness,a2}.json` | application-only outer launcher observations |
| `valid-a1-invocation.json` | A1 invocation |
| `valid-a1-bootstrap-response.json` | A1 bootstrap response |
| `valid-process-transcript*.json` | completed, cancelled, unavailable, A1 timeout, and A2 parent-observed transcripts with TOCTOU snapshots |
| `valid-abnormal-attempt-*.json` | six Authority abnormal-transition attempts with no Result or later material |
| `valid-harness-invocation.json` | caller-owned Harness invocation |
| `valid-harness-process-transcript.json` | caller-observed Harness transcript |
| `valid-replay-profile.json` | Replay profile |
| `valid-harness-profile.json` | Harness profile |
| `valid-admission-profile.json` | Admission profile |
| `valid-tck.json` | twelve-test TCK definition |
| `valid-role-registry.json` | role registry |
| `valid-candidate-replay-result.json` | A0 result |
| `valid-replay-verification-result*.json` | complete nine-test A1 `VERIFIED`, `INVALID`, and `UNAVAILABLE` results |
| `valid-replay-proof-envelope*.json` | caller-owned producer closure around complete `VERIFIED` and child-failure `UNAVAILABLE` A1 results |
| `valid-admission-proof-envelope.json` | caller-owned completed A2 admission proof closure |
| `valid-independent-verification-result.json` | five outer + nine child + three trust-plane outer projections used as the `TEST_REPORT` artifact |
| `valid-independent-proof-envelope.json` | caller-owned Harness process and TEST_REPORT closure |
| `valid-provider-materialization-observation.json` | actual Provider JAR materialization and cleanup observation |
| `valid-admission-verification-result*.json` | A2 `PASS`, `OPEN`, `FAIL`, and `UNAVAILABLE` admission decisions |

Every negative fixture is derived from the named valid fixture by one declared mutation region. `negative-fixture-expectations.json` externalizes the base fixture, Schema, mutation path, and either the one expected Schema keyword/path or one semantic error code. The validator rejects mapping drift, unrelated mutations, multiple validation errors, and errors at a different path. Covered negatives include candidate process self-reporting, missing transcript material, missing TOCTOU evidence, process discriminant/exit drift, reversed time, A2 conclusion drift, A2 envelope around a timeout, TCK order drift, wrong mechanism/terminal/exit, A0 adapter/obligation drift, A2 `PASS` with a missing slot, Provider guard acceptance, declaration-only Provider materialization without an open/read receipt, and review-count guard intrusion into `TEST_REPORT`.

`process-material-binding-attack-vectors.json` covers the material-aware checks that JSON
Schema cannot express. It rejects cross-run observation substitution, observation digest or
Sandbox Profile drift, caller/launch-role drift, transcript/Classpath CodeSource drift,
outer/child transcript reuse, and child request/response/transcript stitching. The validator
uses exact observation/profile bytes and normalized run roots, not child self-reporting. It
also proves request/response challenge ID equality and nine-ID uniqueness within one run;
replaying the same pinned challenge in a different create-new run is intentionally allowed.

`run-material-attack-vectors.json` adds executable Protocol Authority launch attacks for outer
arguments and child physical request-role binding. The validator authenticates the compiled
`LAUNCH_CONTRACT` projection against its authority source and compilation manifest, then
expands each `launchKind` template exactly. It also rejects a Provider destination whose
`CREATE_NEW` identity differs from either no-follow read snapshot, even after document
fingerprints are recomputed. The compiled `REPLAY_VECTOR_REGISTRY` projection is authenticated
against the same source, manifest and Challenge Pin; normal child request/result semantics and
allowed material are derived from its nine vectors, while the three trust-plane projections
remain TCK-fixed.

`process-launcher-contamination-vectors.json` proves the empty-launcher contract for
`CLASSPATH`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, javaagent, extra
classpath injection, application CodeSource drift and admitted-class-origin drift. The matrix
runs against real positive observations for A1, Harness, A2 and candidate children before a
Result or Envelope may exist.

`generate-run-material-fixtures.mjs` materializes two replay producer roots, one isolated parent-
unavailable attack root, five independent A1 roots, two Harness roots and one report root from the
caller-owned Challenge Pin. It also writes an actual closed Challenge Input tree and deterministic
Provider JAR. It writes
canonical exact caller/producer command/request/response/observation/transcript bytes, one A0
result only for `HONEST_INCOMPLETE_ACCEPTED`, scoped tree manifests, aggregates and top-level
proof fixtures. The three trust-plane bootstrap runs deliberately omit Provider materialization.
The independent NORMAL run is caller-sealed with its own `material-root.tree`; its complete Replay
Proof Envelope is fixed at `reports/A1-REPORT-GENERATED-001/derived/replay-proof-envelope.json`
and committed into the independent report material root for A2 `HARNESS_PROOF_COMPLETENESS`.
The generator never invents a second artifact authority: candidate, Verifier, Harness,
Provider, SPI, TCK, profiles and Schema Set bindings are derived from the Pin or their actual
manifest bytes.

`validate_run_material.py` distrusts every copied digest in the report. It opens all referenced
files from a held anchor directory FD through component-wise `openat`/`O_NOFOLLOW` traversal;
there is no `lstat`-then-path-open window. Every physical material root is inventoried before
verification and again after verification, recording directory/file identity, kind, metadata and
the closed entry set; additions, replacements, hard links, symlinks and special files are rejected.
Every file is size-checked before reading and every `read` is capped by the remaining per-file and
tree-total byte budget derived from the Protocol Authority's `fixtureLimits`. The validator then
recomputes raw/document/tree/aggregate commitments, verifies stdout as exact
`JCS(Response) || LF`, binds ProcessObservation to command/transcript/classpath/CodeSource,
checks Provider response origin, verifies the role-aware 12-test projection and accepts the
same nine pinned challenge IDs across two different create-new replay runs. The twenty vectors in
`run-material-attack-vectors.json` prove that digest rebound, cross-run stitching, observation
substitution, Provider-origin drift, ref reuse, hard-link aliasing, launch argument/request-role
stitching, Provider destination identity replacement, Protocol source/projection rebound, file-size,
entry-count and tree-total budget enforcement, parent-directory symlink/replacement detection,
post-inventory entry drift, and replay recipe/input-set rebound. A Replay Proof Envelope with its
`messageVersion` deleted after recomputing its self fingerprint remains rejected after self
fingerprints are recomputed. The derived Replay Proof Envelope is read only from its fixed path,
strictly Schema-validated and passed through the same full result/transcript/tree/terminal-exit
closure as top-level Replay proofs. Every typed run-material document is also validated against
its exact `messageVersion` Schema before semantic closure is attempted.

The budget vectors deliberately use temporary roots with reduced limits so the self-test exercises
the same Authority-derived code paths without allocating the production ceilings.

Schema does not enumerate every possible count as nested `if/then` branches. That would duplicate verifier code in a language unsuited to arithmetic and still fail to close fingerprints, refs, time, or process identity. Structurally valid count-drift attacks and their named verifier outcomes live in `../semantic-guards/`.

## Local validation

From the repository root:

```bash
jq -e . docs/schemas/resource-gateway-capability-studio/capability-studio-gate-a-*.schema.json >/dev/null
jq -e . docs/acceptance/capability-studio/gate-a-wire-v1/process-results/*.json >/dev/null
node docs/acceptance/capability-studio/gate-a-wire-v1/process-results/generate-run-material-fixtures.mjs
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/process-results/validate-fixtures.py
```

Expected final line:

```text
Gate A fixtures valid: 45 positive, 48 negative
```

> **注意**：这 45/48 是 fixture corpus 覆盖数，不是 formal acceptance 分母。
> TCK 固定分母仍为 9 candidate-path + 3 trust-plane = 12；
> 正式 acceptance 计划仍为 `formalPassCount=0 / formalExpectedCount=27`。
> `validate-fixtures.py` 验证 fixture corpus 合规性，不决定 formal pass/fail。

Before the final line, the validator also reports `replay=2`, `independent=1`,
`cross-run-replay=PASS`, `harness-bootstrap=REJECTED`, `early-bootstrap-provider=REJECTED`,
`parent-unavailable=REJECTED`, `abnormal-transitions=6`, and twenty matched real-material attacks.
It resolves the non-business common vocabulary locally, checks every Schema with Draft 2020-12,
discovers every `valid-*` fixture without a hardcoded positive-file allowlist, and performs
process time, A1/A2 cross-document and exact-byte closure checks.

## Fixture Regeneration Workflow

When fixtures need regeneration (e.g., after schema changes or fingerprint profile updates), use this workflow:

```bash
# 1. Regenerate all fixtures deterministically
node docs/acceptance/capability-studio/gate-a-wire-v1/process-results/generate-run-material-fixtures.mjs

# 2. Validate the generated fixtures
python3 docs/acceptance/capability-studio/gate-a-wire-v1/process-results/validate-fixtures.py
python3 docs/acceptance/capability-studio/gate-a-wire-v1/process-results/validate_run_material.py
python3 docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/validate-fixtures.py

# 3. Run Java canonicalization reference tests
mvn -f resource-gateway-test-kit/pom.xml test -Dtest=CapabilityStudioCanonicalizationReferenceTest

# 4. Verify canonicalization reference vectors
node docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/reference-fingerprint.mjs verify-profile
node docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/reference-fingerprint.mjs verify-vectors
```

### One-Command Workflow

From the repository root:

```bash
# Complete fixture regeneration and validation
node docs/acceptance/capability-studio/gate-a-wire-v1/process-results/generate-run-material-fixtures.mjs && \
python3 docs/acceptance/capability-studio/gate-a-wire-v1/process-results/validate-fixtures.py && \
python3 docs/acceptance/capability-studio/gate-a-wire-v1/process-results/validate_run_material.py && \
python3 docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/validate-fixtures.py && \
mvn -f resource-gateway-test-kit/pom.xml test -Dtest=CapabilityStudioCanonicalizationReferenceTest && \
node docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/reference-fingerprint.mjs verify-profile && \
node docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/reference-fingerprint.mjs verify-vectors
```

### Fingerprint Cascade Prevention

The generator ensures deterministic fingerprints by:
1. Computing fingerprints from canonical document forms (JCS/RFC 8785)
2. Recalculating fingerprints for negative fixtures after mutations
3. Updating cross-references when fixture content changes

Negative fixtures that mutate document content include `derivedMutationPaths` in
`negative-fixture-expectations.json` to declare which fingerprint fields should change.
