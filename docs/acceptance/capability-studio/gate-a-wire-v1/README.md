# Gate A executable wire design v1

This directory turns the Gate A trust design into executable, reviewable input
before production implementation begins. The normative architecture and lifecycle
remain in `docs/resource-gateway-capability-studio-convergent-acceptance-engine-technical-design.md`.
The JSON Schemas under `docs/schemas/resource-gateway-capability-studio/` are the
wire authority.

## Design packs

| Pack | Purpose | Authority boundary |
|---|---|---|
| `canonicalization/` | Independent Node.js JCS reference, 44-entry Fingerprint Profile and golden vectors | Defines bytes and object-bound digest semantics; never decides Gate A |
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

## Local verification

Create one disposable Python environment for Draft 2020-12 validation:

```bash
python3 -m venv /tmp/gate-a-jsonschema
/tmp/gate-a-jsonschema/bin/pip -q install jsonschema
```

Run all design checks:

```bash
node docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/reference-fingerprint.mjs verify-vectors
node docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/reference-fingerprint.mjs verify-profile
node docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/generate-signed-review-count-guard.mjs
/tmp/gate-a-jsonschema/bin/python docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/validate-fixtures.py
/tmp/gate-a-jsonschema/bin/python docs/acceptance/capability-studio/gate-a-wire-v1/process-results/validate-fixtures.py
/tmp/gate-a-jsonschema/bin/python docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/validate-vectors.py
/tmp/gate-a-jsonschema/bin/python docs/acceptance/capability-studio/gate-a-wire-v1/material-attacks/run-real-material-attacks.py
jq -e . docs/schemas/resource-gateway-capability-studio/*.schema.json >/dev/null
mvn -f resource-gateway-test-kit/pom.xml -Dtest=CapabilityStudioCanonicalizationReferenceTest,CapabilityStudioGateAMaterialAttackReferenceTest test
git diff --check
```

The negative validators match an expected JSON Path and Schema keyword. A generic
"some validation failed" result is not sufficient because an unrelated missing
field can otherwise manufacture a false green negative test.

## D0 stop rule

Design Gate D0 remains open until all commands above pass, the Draw.io trust
topology matches the wire objects, and the test-only Java reference independently
reproduces both canonicalization and all 38 real-material attacks without invoking
the non-Java runner. A final adversarial review must report no open P0 or P1.
Passing D0 authorizes A0 implementation; it does not authorize Gate A, Gate B or
formal `ACCEPTED`.
