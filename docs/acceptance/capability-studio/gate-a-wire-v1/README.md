# Gate A executable wire design v1

This directory turns the Gate A trust design into executable, reviewable input
before production implementation begins. The normative architecture and lifecycle
remain in `docs/resource-gateway-capability-studio-convergent-acceptance-engine-technical-design.md`.
The JSON Schemas under `docs/schemas/resource-gateway-capability-studio/` are the
wire-shape authority. Cross-role commands, ownership, ordering, packaging and delivery
contracts come only from the caller-pinned `protocol-compiler/gate-a-protocol-authority-v1.json`.

## Design packs

| Pack | Purpose | Authority boundary |
|---|---|---|
| `protocol-compiler/` | One caller-pinned source compiles role, launch, vector, check-state, material, canonicalization, authority and delivery contracts | Build-time data compiler only; it never generates role decision code |
| `canonicalization/` | Independent Node.js JCS reference, 46-entry Fingerprint Profile and golden vectors | Defines bytes and object-bound digest semantics; never decides Gate A |
| `trust-build/` | Pins, Build Identity, Reviewer Authority, structural negatives and signed semantic attack | Caller and reviewer trust inputs; never derived from GateResult |
| `process-results/` | Candidate/A1/Harness/A2 process and result contracts | Separates child semantic response, caller observation, closed proof and admission decision |
| `semantic-guards/` | Named cross-material rules and structurally valid attack vectors | Assigns each non-Schema rule to one owner and one fixed admission slot |
| `material-attacks/` | 18 primary Guard attacks plus 20 closure and signed-governance supplemental attacks over real bytes, paths, ZIPs, trees and process material | Security evidence; normalized reducer booleans are explicitly excluded |

## Attempt and proof

Gate A does not use one generic `result` object for every phase:

1. Candidate and bootstrap responses report semantic facts only.
2. Parent-owned transcripts report exit, time, timeout, cancellation and output bytes.
3. A replay result or TEST_REPORT exists only after its fixed material slots close.
4. A2 uses fixed check slots to distinguish `PASS`, `OPEN`, `FAIL` and `UNAVAILABLE`; the caller then binds the A2 result and parent transcript in an Admission Proof Envelope.

A failed Harness run does not create a partial TEST_REPORT. The caller-owned
Harness transcript is the failure evidence, and A2 fails closed.

## Role self-test boundary

Every role command in the Authority is a deterministic `ROLE_SELF_TEST`. Its strict
`capability-studio-gate-a-role-self-test-receipt-v1.schema.json` receipt contains only
facts independently derivable from one stable Authority snapshot, actual role JAR bytes,
packaged profile bytes where applicable, and the role contract. It contains no timestamp,
process observation, stdout/stderr reference, run/material root or parent fact. The caller
compiles exact oracle bytes without starting the tested role.

Dynamic Verifier/Harness conformance is a separate caller-owned A1.7 command. It must take
explicit `--authority`, `--challenge-pin`, `--challenge-input-root` and `--output-root`
arguments, validate the exact pin bytes and its `expectedProtocolAuthorityRawFingerprint`
against the same Authority snapshot, and hand that explicit root to the runtime material
validator. The Challenge Pin is not hashed into the Authority itself.

The seven delivery slices form a strict machine DAG. Each slice declares owner, modules,
prerequisites, allowed-path closure, implementation roles, exact required/handoff/output
artifacts, and acceptance commands. A1.7 derives its four-role set from that Authority
metadata and never admits `GATE_ADMISSION_CHECKER`.

## Local verification

Run the complete design gate from the repository root. Generators are explicit and
must be idempotent; `run-protocol-gate.py --check` is read-only and compiles twice in
independent temporary directories before comparing bytes:

```bash
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/run-protocol-gate.py --check
node docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/generate-canonicalization-challenge.mjs
node docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/reference-fingerprint.mjs verify-vectors
node docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/reference-fingerprint.mjs verify-profile
node docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/generate-schema-set-manifest.mjs
node docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/generate-signed-review-count-guard.mjs
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/validate-fixtures.py
node docs/acceptance/capability-studio/gate-a-wire-v1/process-results/generate-run-material-fixtures.mjs
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/process-results/validate-fixtures.py
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/process-results/validate_run_material.py
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/validate-vectors.py
uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/material-attacks/run-real-material-attacks.py
jq -e . docs/schemas/resource-gateway-capability-studio/*.schema.json >/dev/null
mvn -f resource-gateway-test-kit/pom.xml clean verify
git diff --check
```

The negative validators match an expected JSON Path and Schema keyword. A generic
"some validation failed" result is not sufficient because an unrelated missing
field can otherwise manufacture a false green negative test.

## Execution boundary

The Authority's `/opt/jdk/bin/java` is the canonical Java 25 path inside the
hermetic A1.7 CI/release image. It is intentionally not rewritten to a developer
workstation's `java`. Local A1.0 commands verify the executable design; a production
Proof is valid only in the pinned image with the observed runtime, executable and
CodeSource identities. Changing that image or runtime requires an Authority revision
and regeneration of every projection, pin and run material.

## A1.0 stop rule

The previously signed D0 result remains valid, but it does not authorize A1. A1.0
remains open until all commands above pass, generators reproduce identical bytes,
the Java reference independently reproduces canonicalization and all 38
real-material attacks, and two fresh adversarial reviews both report no open P0 or
P1. Passing A1.0 authorizes only the A1.1 implementation slice; it does not
authorize Gate A, Gate B or formal `ACCEPTED`.
