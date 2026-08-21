# Gate A trust/build wire fixtures v1

This directory is a small, deterministic fixture pack for the Gate A trust boundary.
Every JSON document is UTF-8 JSON without a BOM and uses the companion Draft 2020-12
schemas under `docs/schemas/resource-gateway-capability-studio/`.

## Valid documents

The `valid-*.json` documents provide one valid sample for each of the 13 companion
schemas. They intentionally use synthetic fingerprints; a real runner must replace
them with caller-computed raw bytes or canonical fingerprints before admission.

## Negative documents

The `negative-*.json` documents are expected to fail structural validation:

* `negative-challenge-kind-mismatch.json`: a tree commitment is supplied where raw bytes are required.
* `negative-admission-observation-kind.json`: an aggregate commitment is supplied for a raw GateResult.
* `negative-review-count-drift.json`: the arrays imply zero open findings but the projection says one P0.
* `negative-envelope-algorithm.json`: `none` is never an accepted signature algorithm.
* `negative-policy-unknown-check.json`: required checks are a closed ordered set.
* `negative-revocation-duplicate-key.json`: revocation sets are unique.
* `negative-build-role-profile.json`: role-specific profile nullability is enforced.
* `negative-source-path.json`: paths reject `..`, backslashes and non-ASCII characters.
* `negative-provider-descriptor.json`: the provider service descriptor is fixed.

Each negative document starts from its complete valid peer and changes one field.
The local validator checks that exactly one expected JSON Path and JSON Schema
keyword rejects it, so a missing unrelated required field cannot create a false
green result.

Run from the repository root:

```bash
/tmp/gate-a-jsonschema/bin/python \
  docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/validate-fixtures.py
```

Schema validation is necessary but not sufficient. A Gate A verifier must additionally
recompute document fingerprints, sort and commit leaf entries, compare raw bytes from
the actual CodeSource JAR, enforce Review Body count formulas, verify Ed25519 against
the external policy and revocation snapshot, and enforce cross-document pin equality.

`signed-review-count-guard/` closes the most important semantic negative. Its
Review Body, policy, revocation snapshot and Envelope all pass structural Schema,
and its Ed25519 signature is valid, but one open P0 finding is reported as zero.
Run `node generate-signed-review-count-guard.mjs` to regenerate and verify it.
