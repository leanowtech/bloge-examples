# Polyglot Protocol Certification

This directory independently certifies that non-Java consumers can verify a
Resource Gateway authoritative-outcome artifact without linking server code,
Spring, the Java Test Kit, or third-party packages.

Run both consumers from the repository root:

```bash
bash resource-gateway-test-kit/scripts/verify-polyglot-protocols.sh
```

The suite requires Node.js 22.18 or newer and Go 1.24 or newer. It performs no
package download and does not require `npm install` or `go get`.

## What It Proves

Both consumers read the server-produced public-only fixture at
`docs/schemas/resource-gateway-mirror/authoritative-outcome-observation-stage1-v1.fixture.json`
and independently enforce:

- exact object fields and frozen protocol versions;
- recursive canonical JSON plus the SHA-256 content address;
- pre-treatment population selection and attribution-window ordering;
- authority watermark, source-record identity, and fact-order closure;
- producer-independent reconciliation and evidence-completeness derivation;
- domain-separated attestation material;
- X.509 SPKI Ed25519 signature, key state, and clock policy;
- fail-closed rejection of unknown fields and representative tampering.

The TypeScript and Go tests assert the same expected observation identity,
fingerprint, reconciliation, key identity, and stable failure reason codes.

## What It Does Not Prove

This is a fixed-vector producer/consumer compatibility gate. Passing it does
not prove that:

- a customer production outcome source is authoritative, current, or complete;
- a selected-population upload is complete or legally admissible;
- transport, KMS, database, regional, HA, or disaster-recovery controls work;
- all Mirror Evidence versions or future protocol versions are certified.

Live admission still requires the customer-governed authority callback and the
runtime controls documented by Resource Gateway. New protocol versions must
add a new fixed fixture and explicit consumer branch; silently accepting an
unknown field or version is a certification failure.

## Layout

- `typescript/` uses the Node.js built-in test runner, crypto, and native type
  stripping.
- `go/` uses only the Go standard library.
- `../scripts/verify-polyglot-protocols.sh` is the CI and release entry point.
