# Gate A trust/build wire fixtures v1

This directory is a small, deterministic fixture pack for the Gate A trust boundary.
Every JSON document is UTF-8 JSON without a BOM and uses the companion Draft 2020-12
schemas under `docs/schemas/resource-gateway-capability-studio/`.

## Valid documents

The validator checks 18 positive JSON documents. This count is by document path, not by
unique schema id: it includes the 13 ordinary trust/build samples, the role fixture
binding, and the four signed-review count-guard documents. Compiled protocol projections,
schema inventories, and other generated artifacts are not counted as positive documents.
Most samples intentionally use synthetic fingerprints. The Schema
Schema Set sample is different: `schema-set-inventory-v1.json` is a compiled output,
not a second authority. `generate-schema-set-manifest.mjs` reads Protocol Authority's
exact `schemaInventoryPolicy.gateASchemas` list (currently 48 names, including
`abnormal-attempt`, protocol projection/manifest, provider-probe, canonical challenge/oracle,
and role black-box fixture bindings)
plus its four required reviewer wire schemas, derives each `schemaId` and `entryPath`,
pins actual repository bytes, computes the ordered aggregate, and updates Build Identity
and Challenge Pin bindings. Missing, extra, duplicate, reorder, source/entry-path, or
compiled-inventory drift is rejected; an unlisted schema is not silently admitted.

## Negative documents

The `negative-*.json` documents are expected to fail structural or named semantic validation:

* `negative-challenge-kind-mismatch.json`: a tree commitment is supplied where raw bytes are required.
* `negative-admission-observation-kind.json`: an aggregate commitment is supplied for a raw GateResult.
* `negative-review-count-drift.json`: the arrays imply zero open findings but the projection says one P0.
* `negative-envelope-algorithm.json`: `none` is never an accepted signature algorithm.
* `negative-policy-unknown-check.json`: required checks are a closed ordered set.
* `negative-revocation-duplicate-key.json`: revocation sets are unique.
* `negative-build-role-profile.json`: role-specific profile nullability is enforced.
* `negative-source-path.json`: paths reject `..`, backslashes and non-ASCII characters.
* `negative-provider-descriptor.json`: the provider service descriptor is fixed.
* `negative-schema-set-missing.json`: one required Gate A v1 schema is absent.
* `negative-schema-set-extra.json`: an unknown schema is inserted.
* `negative-schema-set-duplicate.json`: a schema id/path is reused with different bytes.

Each negative document starts from its complete valid peer and changes one mutation
region. The local validator checks either the exact JSON Path/Schema keyword or the
single expected Schema Set semantic code, so an unrelated failure cannot create a
false green result.

Run from the repository root:

```bash
uv run --with jsonschema python \
  docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/validate-fixtures.py
```

Regenerate the closed Schema Set after an intentional Gate A v1 schema change:

```bash
node docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/generate-schema-set-manifest.mjs
```

The validator treats that manifest as the closed-world allowlist and compares every
listed entry to the actual `docs/schemas` bytes; missing, extra, duplicate, reorder,
raw digest, aggregate, Build Identity, or Challenge Pin drift fails closed. Every source
schema is also parsed with duplicate-member rejection, meta-validated as Draft 2020-12,
checked for a unique `$id`, and checked for closed `$ref` resolution. The Challenge Pin
also binds the exact raw bytes of `protocol-compiler/gate-a-protocol-authority-v1.json`.

`packaged-schema-set-attack-vectors.json` goes one boundary further. The validator builds
a deterministic real JAR in a private temporary directory, reads the manifest and all 52
Schema entries back through ZIP APIs, and rejects wrong manifest paths, missing/extra/drifted
packaged bytes, Build Identity or Challenge Pin self-fingerprint drift, and Sandbox Profile
pin drift. Its ZIP self-tests also reject duplicate names, traversal, normalization
collisions, and malformed archives. The temporary JAR is a D0 self-test only; it is never
evidence that an A1/A2 production role artifact was packaged correctly.

## Packaged role JARs

The no-argument command above is synthetic fixture-contract and structural validation only.
It is deliberately not a production black-box pass. A1.7 runs exactly the four actual A1
roles: `IMPLEMENTATION_CANDIDATE`, `TCK_PROVIDER`, `INDEPENDENT_VERIFIER`, and
`CONFORMANCE_HARNESS`. `GATE_ADMISSION_CHECKER` is an A2 role and is intentionally excluded
from `run-a1-release-gate.py`; its actual JAR and black-box fixture are admitted by the later
A2 gate. The five Authority role names and compatibility selectors are accepted by the direct
validator: `IMPLEMENTATION_CANDIDATE` (`candidate`), `TCK_PROVIDER` (`provider`/`tck`),
`INDEPENDENT_VERIFIER` (`verifier`/`A1`), `CONFORMANCE_HARNESS` (`harness`), and
`GATE_ADMISSION_CHECKER` (`a2`/`GATE_VERIFIER`).

The runtime binding manifest contains exactly the roles requested by that invocation. A1.7
therefore binds four roles and A2 binds Admission separately. Before A1.7 admission, caller-side
reference code must compile the private fixture root without executing a tested role; the release
gate accepts that root only through its required `--runtime-fixture-root` argument.

```bash
uv run --with jsonschema python \
  docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/validate-fixtures.py \
  --role-jar candidate=path/to/candidate.jar \
  --role-jar provider=path/to/provider.jar \
  --role-jar verifier=path/to/verifier.jar \
  --role-jar harness=path/to/harness.jar \
  --execute-role-canonicalization \
  --execute-role-black-box \
  --runtime-fixture-root docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/role-black-box-fixture
```

`--role-jar` is repeatable and each canonical role is validated according to its Authority
`launchMode`: classpath main for Candidate, the Authority's `--probe-provider` CLI for TCK
Provider, and executable JAR contracts for Verifier and Harness in A1. Candidate and Provider
do not acquire profile/registry/schema-set requirements that the Authority does not assign.
Local black-box execution uses one bounded process runner with `env={}`, a private working
root, isolated process groups, selector-based nonblocking stdout/stderr capture, one absolute
deadline, bounded TERM/KILL grace, and quiescence checks. A detached `setsid` child that keeps
a pipe open is reported as `UNAVAILABLE` once the deadline is reached; no unbounded pipe
close, thread join, or process wait is permitted. The production hermetic launcher and cgroup
remain the final enforcement boundary.
Every supplied path is traversed component by component with `openat` and `O_NOFOLLOW`,
checked by fstat-read-fstat (`nlink=1`, not group/other writable, stable raw SHA), and every
read pass applies its remaining byte budget immediately. Only verified bytes are copied with
`CREATE_NEW` into a private execution directory. POM properties, dependency coordinates and
scopes, compile/runtime allowlists, forbidden project dependencies, exact projections,
compiled-manifest rebinds, and role-specific resources are then closed from those bytes.
For shaded roles, the synthetic self-test fixture is generated from the Authority's complete
`runtimeDependencyLockIds` closure. Each nested JAR is read from the caller-owned Maven local
repository and must match the Authority's external raw digest; the role dependency manifest is
then checked against both the manifest bytes and that independent lock. A nested dependency and
its internal manifest can therefore be changed together and still fail with
`ROLE_JAR_DEPENDENCY_EXTERNAL_RAW_DRIFT`.

The TCK Provider is deliberately different: its dependency manifest contains a `provided` ABI
record for the Authority-pinned Candidate classifier, but no Candidate or runtime dependency JAR
is embedded. The validator binds the Candidate GAV, Candidate artifact raw digest, and Candidate
SPI class raw digest to the Challenge Pin. The nested Provider observation is also closed to one
service descriptor and exactly one implementation class, which is the deterministic local
equivalent of a unique ClassLoader/ServiceLoader observation for this synthetic fixture.
The verifier role's nested Provider is independently closed from its immutable outer-entry
bytes: Authority-derived ZIP capacities, CRC, one shared ZIP entry canonicalizer, the exact
three-entry allowlist, pinned descriptor/class identity, and the existing resource-manifest
raw fingerprint binding all apply. The canonicalizer rejects empty/dot/dotdot segments,
absolute/backslash paths, duplicate/canonical collisions, symlink/FIFO/device/socket entries,
and other non-regular, non-directory modes. Synthetic roles remain `BLACK_BOX_PENDING` and
are not publishable.

Structural success is printed as `STRUCTURAL_PASS`. For applicable roles,
`--execute-role-canonicalization` passes the caller-pinned challenge to Authority's exact
self-test command and compares stdout bytes with Authority's oracle bytes; exit, stderr,
and timeout are strict. Provider is intentionally excluded from this requirement.
`--execute-role-black-box` expands each Authority `blackBoxCommand` from caller fixture
bindings, checks strict JSON `messageVersion`, empty stderr, exact exit, and exact oracle
stdout. Without black-box execution, a structurally valid JAR is explicitly
`BLACK_BOX_PENDING` and the command fails as not publishable. `role-jar-attack-vectors.json`
includes 37 exact single-code attacks: the original coordinate/dependency/projection/
compilation-manifest/canonical-output/role/main/class/unknown-entry and nested Provider
cases, plus seven outer-JAR and seven nested-Provider lexical/type attacks, an adaptive
external-lock attack, and a thin-provider embedded-ABI attack. Synthetic structure
PASS is never publishable.

The bounded runner has seven deterministic self-test cases, including an ordinary descendant
cleanup case and a detached `setsid` pipe-holder case. These tests establish bounded capture,
path integrity, and structural trust behavior only. They do not claim same-UID oracle
isolation: a tested role and the validator still share the current process user, so production
oracle secrecy requires the future isolated launcher/authority-bundle boundary.

Schema validation is necessary but not sufficient. A Gate A verifier must additionally
recompute document fingerprints, sort and commit leaf entries, compare raw bytes from
the actual CodeSource JAR, enforce Review Body count formulas, verify Ed25519 against
the external policy and revocation snapshot, and enforce cross-document pin equality.

`signed-review-count-guard/` closes the most important semantic negative. Its
Review Body, policy, revocation snapshot and Envelope all pass structural Schema,
and its Ed25519 signature is valid, but one open P0 finding is reported as zero.
Run `node generate-signed-review-count-guard.mjs` to regenerate and verify it.
