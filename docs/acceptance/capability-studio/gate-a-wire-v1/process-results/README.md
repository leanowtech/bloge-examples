# Gate A process/results wire fixtures

This directory is the design-as-code fixture set for the Gate A process boundary. The schemas in `docs/schemas/resource-gateway-capability-studio/` are the wire authority; these files are intentionally small, deterministic examples rather than production evidence.

## What is closed

- `CandidateChallengeResponse v1` contains semantic facts only. It has no exit code, stdout/stderr fingerprint, start/end time, timeout, cancellation, or process state. Those facts exist only in the parent-observed transcript.
- `HarnessProcessTranscript v1` and `ProcessTranscript v1` bind command, invocation, actual CodeSource, exit, timestamps, timeout/cancel flags, and exact stdout/stderr references. `ProcessTranscript v1` additionally carries caller-owned `codeSourceObservation.preRead` and `postRead` snapshots. Each snapshot freezes resolved path, file key, owner, group, link count, POSIX mode, size, and the raw bytes read. The two snapshots are the TOCTOU evidence boundary; a child result cannot manufacture them.
- `ProcessTranscript v1` uses a closed process-state table: `COMPLETED = exit 0 + false/false`, `FAILED = exit 1..255 + false/false`, `TIMED_OUT = exit 143 + true/false`, `CANCELLED = exit 130 + false/true`, and `UNAVAILABLE = exit 255 + false/false`. `143` is retained for timeout compatibility; `255` means that no usable process result was available. `startedAt <= endedAt` is a semantic, not a lexical, rule and is checked by the validator.
- `GateACandidateReplayResult v1` freezes three ordered adapters and fourteen ordered `FELT-01..14` obligations. Obligation status is `FAIL | BLOCKED | NOT_RUN`; A0 cannot claim `PASS`. Count fields are bounded structural projections; A1 must recompute them through `A0_SLOT_COUNT_PROJECTION` before trusting them.
- `GateAReplayVerificationResult v1` is a complete nine-slot result. It becomes a closed diagnostic Proof only inside a caller-owned `GateAReplayProofEnvelope v1` that binds the result bytes to the A1 producer transcript and material root. Incomplete material or an A1 crash/timeout remains an outer process attempt and cannot be serialized as a Proof.
- `GateAIndependentVerificationResult v1` is the **only** wire authority for the immutable artifact carried in the `TEST_REPORT` role. `TEST_REPORT` is an artifact role, not a second protocol or alias Schema. The result has exactly five ordered outer A1 runs and twelve ordered child runs. Provider namespace collision is its only mandatory guard and is not a thirteenth child test. Review-count consistency is evaluated later by A2 after signed review material exists.
- `GateAAdmissionVerificationResult v1` freezes five requirement, four artifact, twelve test, two guard, and one trusted-review slots. Each slot uses `PASS | FAIL | MISSING | UNAVAILABLE`; `PASS/FAIL` requires its evidence ref and `MISSING/UNAVAILABLE` requires null.
- `GateAAdmissionProofEnvelope v1` is the caller-owned final A2 closure. It references the raw A2 result and its parent `ProcessTranscript`, carries both Challenge/Admission Pin fingerprints, expected and observed A2 `CodeSource`, and binds the completed process outcome to the observed admission conclusion. Only `COMPLETED + exit 0` can be enveloped. An A2 crash, timeout, cancellation, unavailable parent process, missing result, or CodeSource drift produces no envelope; it remains an attempt/transcript diagnostic.
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
| `valid-a1-invocation.json` | A1 invocation |
| `valid-a1-bootstrap-response.json` | A1 bootstrap response |
| `valid-process-transcript*.json` | completed, cancelled, unavailable, A1 timeout, and A2 parent-observed transcripts with TOCTOU snapshots |
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
| `valid-independent-verification-result.json` | five outer + twelve child Proof used as the `TEST_REPORT` artifact |
| `valid-admission-verification-result*.json` | A2 `PASS`, `OPEN`, `FAIL`, and `UNAVAILABLE` admission decisions |

Every negative fixture is derived from the named valid fixture by one declared mutation region. `negative-fixture-expectations.json` externalizes the base fixture, Schema, mutation path, and either the one expected Schema keyword/path or one semantic error code. The validator rejects mapping drift, unrelated mutations, multiple validation errors, and errors at a different path. Covered negatives include candidate process self-reporting, missing transcript material, missing TOCTOU evidence, process discriminant/exit drift, reversed time, A2 conclusion drift, A2 envelope around a timeout, TCK order drift, wrong mechanism/terminal/exit, A0 adapter/obligation drift, A2 `PASS` with a missing slot, Provider guard acceptance, and review-count guard intrusion into `TEST_REPORT`.

The report's `resultFingerprint` values are syntactically valid placeholders. A runtime verifier must recompute them with the domain-separated JCS algorithm before treating any fixture as evidence.

Schema does not enumerate every possible count as nested `if/then` branches. That would duplicate verifier code in a language unsuited to arithmetic and still fail to close fingerprints, refs, time, or process identity. Structurally valid count-drift attacks and their named verifier outcomes live in `../semantic-guards/`.

## Local validation

From the repository root:

```bash
jq -e . docs/schemas/resource-gateway-capability-studio/capability-studio-gate-a-*.schema.json >/dev/null
jq -e . docs/acceptance/capability-studio/gate-a-wire-v1/process-results/*.json >/dev/null
python3 -m venv /tmp/gate-a-jsonschema
/tmp/gate-a-jsonschema/bin/pip -q install jsonschema
/tmp/gate-a-jsonschema/bin/python docs/acceptance/capability-studio/gate-a-wire-v1/process-results/validate-fixtures.py
```

Expected final line:

```text
Gate A fixtures valid: 33 positive, 20 negative
```

The validator resolves the non-business common vocabulary locally, checks every Schema with Draft 2020-12, accepts every `valid-*` fixture, performs process time and A2 cross-document closure checks, and prints the exact matched keyword/path or semantic code for every `invalid-*` fixture.
