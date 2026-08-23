# Gate A real material attack pack

This pack is the executable security boundary for the 18 semantic Guards in
`gate-a-wire-v1`. It is deliberately separate from
`semantic-guards/semantic-guard-vectors-v1.json`: those vectors exercise the
normalised reducer contract and are not security evidence.

Each manifest case is processed as:

```text
production fixture/schema
        -> temporary material root
        -> one explicit mutation
        -> schema validation of every related JSON document
        -> collector re-reads bytes/path/ZIP/tree/process/signature
        -> semantic result {guardId,status,admissionTarget,conclusion,reason,exit}
```

The material root is deleted after each case. ZIP timestamps, bytes, JSON
serialization and the fixture Ed25519 key are deterministic. The signed review
fixtures use a fixture-only public key; no operational credential is present.

Run from the repository root:

```bash
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/material-attacks/run-real-material-attacks.py
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/material-attacks/run-real-material-attacks.py --json
```

The runner requires Python standard-library modules, the installed `jsonschema`
package, Node.js and OpenSSL. Node.js runs the independent canonicalization
reference and deterministically re-signs the review-count fixture; OpenSSL
performs Ed25519 verification. No operational credential is used.

The fixed order is the Guard Catalog order and is also frozen by
`capability-studio-gate-a-material-attack-manifest-v1.schema.json`. The first
18 cases are explicitly classified `PRIMARY_GUARD_ATTACK`, with exactly one
 real-material attack per Guard. The pack contains exactly 18 primary attacks
and 25 supplemental attacks (43 total). Supplemental cases are explicitly classified
`SUPPLEMENTAL_ATTACK` and cover raw/reference closure, Proof Envelope ref and
digest binding, terminal/exit mapping, a non-Guard A2 requirement slot, filesystem
tree file and directory symlink attacks, and Reviewer key, authority, revocation,
policy, check/finding, ordering and pinned-freshness attacks.
A final reviewer trio covers candidate binding drift, body/envelope `reviewedAt`
drift, and a revocation snapshot issued after review; each is re-signed with
valid Ed25519 material and must uniquely hit `REVIEW_SIGNATURE_AUTHORITY`.
A missing Replay Proof Envelope is reported as `FAIL / A1_REPLAY_PROOF_MISSING /
2`; an outer A1 replay process marked `FAILED` is reported as
`UNAVAILABLE / A1_OUTER_TRANSCRIPT_CRASHED / 3`.

Before mutation, each case requires its applicable Guard baseline to be
`PASS`. After mutation, the runner probes all 18 Guards: the expected Guard
must be the only hit; a Guard without the material needed for its contract is
reported internally as `NOT_APPLICABLE`, never as a normalized security
observation. For the signed-count case, the existing signed fixture is first
re-materialized as a valid signed count=1 baseline; the runner then performs
the count=0 logical mutation and re-signs it, so both signature and count
baseline checks are real PASS checks.
