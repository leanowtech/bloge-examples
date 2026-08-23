# Caller-pinned role black-box fixtures

This directory is caller-owned input material. The validator never derives an expected
response from a candidate process, and it never turns a synthetic role JAR into a production
black-box pass.

## A1.0 boundary

At A1.0 the gate validates this fixture contract and its fail-closed behavior only. The
synthetic JAR self-tests prove that the structural gate and fixture contract reject the right
attacks; they report `STRUCTURAL_PASS BLACK_BOX_PENDING (not publishable)`. A1.7 executes the
actual Candidate, Provider, Verifier and Harness JARs. A2 later executes the actual Admission
Checker. A missing real fixture or role artifact is an explicit preflight failure, never a
generated oracle or an inferred production result.

## Binding manifest

Create exactly `fixture-bindings-v1.json`. In normal use, the caller runs
`protocol-compiler/compile-role-self-test-fixtures.py`; the compiler takes one stable Authority
snapshot plus the actual role JARs and writes the binding manifest, immutable fixture copies and
caller-owned expected receipts without executing any role. Its top-level `messageVersion`,
`fixtureSetId`, requested role set and `bindingFingerprint` are frozen by the Protocol Authority.
`bindings` must contain exactly the roles supplied to the current gate invocation: the four A1
roles for A1.7, or the Admission role for its later A2 invocation. Unknown roles, omitted requested
roles and unrelated future roles all fail closed. A profile-bearing role binding is:

The manifest is validated by the strict `role-black-box-fixture-bindings.v1` Draft 2020-12 Schema
from the Authority's closed schema inventory before semantic binding checks run.

```json
{
  "fixtures": {
    "AUTHORITY_SNAPSHOT": {
      "relativePath": "authority/protocol-authority.json",
      "kind": "FILE",
      "fingerprint": {
        "kind": "RAW_BYTES",
        "algorithm": "SHA-256",
        "value": "sha256:<64 lowercase hex characters>"
      }
    },
    "ROLE_ARTIFACT": {
      "relativePath": "artifacts/INDEPENDENT_VERIFIER.jar",
      "kind": "FILE",
      "fingerprint": {
        "kind": "RAW_BYTES",
        "algorithm": "SHA-256",
        "value": "sha256:<64 lowercase hex characters>"
      }
    },
    "PACKAGED_PROFILE": {
      "relativePath": "profiles/INDEPENDENT_VERIFIER.json",
      "kind": "FILE",
      "fingerprint": {
        "kind": "RAW_BYTES",
        "algorithm": "SHA-256",
        "value": "sha256:<64 lowercase hex characters>"
      }
    }
  },
  "oracle": {
    "relativePath": "oracle/INDEPENDENT_VERIFIER.json",
    "kind": "FILE",
    "fingerprint": {
      "kind": "RAW_BYTES",
      "algorithm": "SHA-256",
      "value": "sha256:<64 lowercase hex characters>"
    }
  }
}
```

Candidate and Provider omit `PACKAGED_PROFILE`; Verifier and Harness require it. The names,
`FILE` kind and commitment kinds are not inferred from file-name suffixes. They are read from
`gate-a-protocol-authority-v1.json`; an unknown, missing or mismatched binding is rejected. The
binding manifest itself carries an Authority-domain aggregate commitment over the manifest with
`bindingFingerprint` set to `null` during calculation. The checked-in
`valid-fixture-bindings-v1.json` is a Schema-level shape fixture with illustrative fingerprints;
it is not accepted as an executable fixture root because the referenced bytes are deliberately
not checked into this directory.

Before a real role starts, the validator stably reads every bound file, copies the verified bytes
into a private execution root and verifies the source again. Commands receive only those private
paths; the oracle is also sealed there. This closes both raw fingerprint drift and post-check path
replacement. Dynamic challenge inputs, run pins and evidence trees belong to the separate A1.7
conformance run; they are intentionally absent from the deterministic role self-test contract.
