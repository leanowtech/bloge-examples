# Mounted Capability Studio authority provider

This standalone module is the reference `ServiceLoader` provider for formal Capability Studio
stage acceptance. It snapshots one read-only post-run Authority Bundle, one read-only Target
Admission Bundle, and a deployment-owned durable execution-lease state directory. The legacy
four-component `authorityBinding()` remains backed only by the Authority Bundle; the formal CLI
uses the precomputed `formalTargetBoundAuthorityBinding()` snapshot.

## Build order

Install the Test Kit first, then build this module:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean install
mvn -f resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/pom.xml clean verify
```

The module depends on `com.leanowtech.bloge:bloge-resource-gateway-test-kit:1.0.0` and targets
Java 25. The service descriptor is packaged in the resulting JAR without relocation or merging.

## Deployment contract

The post-run authority-material API requires only the existing authority property:

```text
-Dbloge.capabilityStudio.authorityBundleRoot=/absolute/path/to/authority-bundle
```

Formal v2 admission additionally requires both of these properties. Supplying only one is invalid;
supplying neither leaves the phase-2 API operational and makes the formal accessor unavailable:

```text
-Dbloge.capabilityStudio.targetAdmissionBundleRoot=/absolute/path/to/target-admission-bundle
-Dbloge.capabilityStudio.executionLeaseStateRoot=/absolute/path/to/private-lease-state
```

The Authority Bundle is loaded during construction. The Target Admission Bundle and formal state
are loaded exactly once, lazily, as one immutable formal snapshot. The formal properties must
already be absolute and normalized; there are no defaults or fallbacks. The state root must be an
existing real private directory writable by the Provider process. State updates use one immutable
store descriptor/inter-process lock, bounded closed state and checkpoint documents, forced files
and directories, atomic moves, and a checkpoint-bound local revocation head. External deployment
automation is responsible for advancing that reference head; normal acceptance callbacks never
update it. Exact lease retries recover the original receipt; a changed request under the same lease
is rejected. Deletion or independent rollback of state, checkpoint, or revocation-head files fails
unavailable. Coherent rollback of every trusted local store file by an attacker with the same UID
and host storage identity remains a reference implementation limitation.

The STORE, LIFECYCLE, and LEASE component declarations use explicit v2 domains. Their material
binds the Provider artifact/version, Authority and Target Admission material, immutable store
descriptor, revocation registry/lifecycle material, and fixed behavior versions. Absolute and real
mount paths are deliberately excluded: copying identical read-only bundles to another mount while
reusing the same store descriptor produces the same component material and formal outer. Paths are
still validated and used to enforce local filesystem safety. In particular, both LIFECYCLE v2 and
LEASE v2 bind the post-run `AuthorityBinding` material because their callbacks authorize requests
against the complete formal outer.

Reference stores initialized by the earlier v1 store configuration are incompatible with these v2
component declarations. Do not alter an old store in place. Create a new private state root, declare
its material, and independently issue new descriptor and formal-outer pins before admitting work.

### Declare formal material

`MountedCapabilityStudioFormalMaterialCli` reads only the three JVM properties above and accepts no
arguments. It assembles one formal snapshot and may initialize a new state root at genesis. It does
not read a Stage Result, invoke post-run verification, or commit a lease:

```bash
java \
  -Dbloge.capabilityStudio.authorityBundleRoot=/absolute/path/to/authority-bundle \
  -Dbloge.capabilityStudio.targetAdmissionBundleRoot=/absolute/path/to/target-admission-bundle \
  -Dbloge.capabilityStudio.executionLeaseStateRoot=/absolute/path/to/new-private-lease-state \
  -cp "<provider-and-test-kit-classpath>" \
  com.leanowtech.bloge.gateway.testkit.mounted.MountedCapabilityStudioFormalMaterialCli
```

Exit `0` emits exactly one payload-free `DECLARED` line containing, in order,
`authorityMaterialFingerprint`, `formalOuterFingerprint`,
`targetAdmissionMaterialFingerprint`, `deploymentAdmissionAuthorityMaterialFingerprint`,
`trustedClockMaterialFingerprint`, `admissionLifecycleAuthorityMaterialFingerprint`,
`executionLeaseAuthorityMaterialFingerprint`, and `storeDescriptorFingerprint`, followed by the
closed declaration reason code. Exit `2` emits only a closed `INVALID` or `BLOCKED` line. The
declaration is offline material for deployment-controlled pin issuance. It is not an acceptance
result, transcript, or evidence manifest and must never be treated as formal admission evidence.
On an already consistent store, declaration does not change generation, fencing, leases,
descriptor, checkpoint, or revocation-head bytes. Store preparation may perform only the existing
protocol's uniquely recognized one-generation crash repair by advancing a stale checkpoint to its
already durable successor state or revocation head. That repair records no new business action and
does not consume a lease.

### Advance the revocation head

Deployment automation advances the local reference head only through
`MountedCapabilityStudioRevocationHeadCli`. Prepare a strict
`resource-gateway.capability-studio.revocation-head-update.v1` document matching the packaged
`schemas/capability-studio-revocation-head-update-v1.schema.json`. Its
`predecessorHeadFingerprint` must equal the current head, its revision must be exactly the next
revision, and `headFingerprint` is SHA-256 over the fixed canonical document with that self field
set to `null`.

Pin the immutable store descriptor independently of the writable state directory, pin the exact
raw update bytes, then invoke the CLI with no additional arguments:

```bash
BLOGE_EXPECTED_EXECUTION_LEASE_STORE_DESCRIPTOR_FINGERPRINT="sha256:<64 lowercase hex>" \
BLOGE_EXPECTED_REVOCATION_HEAD_INPUT_SHA256="sha256:<64 lowercase hex>" \
java -cp "<provider-and-test-kit-classpath>" \
  com.leanowtech.bloge.gateway.testkit.mounted.MountedCapabilityStudioRevocationHeadCli \
  --state-root /absolute/path/to/private-lease-state \
  --head-input /absolute/path/to/revocation-head-update.json
```

Exit `0` emits one `UPDATED` line. An exact retry emits `ALREADY_CURRENT` with the same immutable,
update-specific receipt fingerprint. Exit `3` is a stale, rollback, predecessor, revision, or time
rejection. Exit `2` covers malformed arguments/input/pins and unavailable storage or durability.
Output is payload-free and never includes paths or registry material. The deployment must protect
the descriptor pin, author update documents from an independently current revocation source, and
retain the same crash-consistent filesystem assumptions as lease commits.

`Clock.systemUTC()` is the reference time source. It is not independently authenticated by this
module; the deployment authenticates the Provider artifact and complete formal outer fingerprint.
The module creates no keys or signatures, performs no network access, and writes no business
payload. Filesystem durability, host time integrity, mount immutability, and backup/restore policy
remain deployment responsibilities.

Missing, malformed, invalid, and unavailable configuration fails closed with stable payload-free
codes. Paths, authority material, lease identifiers, receipts, and callback reasons are excluded
from Provider errors and `toString()` output.

## Current gate integration

After installing the Test Kit and building this provider, use the existing deployment gate. Supply
the provider JAR and every immutable dependency it needs on the provider classpath:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean install
mvn -f resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/pom.xml clean package

JAVA_TOOL_OPTIONS="-Dbloge.capabilityStudio.authorityBundleRoot=/absolute/path/to/authority-bundle -Dbloge.capabilityStudio.targetAdmissionBundleRoot=/absolute/path/to/target-admission-bundle -Dbloge.capabilityStudio.executionLeaseStateRoot=/absolute/path/to/private-lease-state" \
BLOGE_EXPECTED_TEST_KIT_JAR_SHA256="<64 lowercase hex>" \
BLOGE_EXPECTED_STAGE_RESULT_SHA256="<64 lowercase hex>" \
BLOGE_EXPECTED_PROVIDER_CLASSPATH_SHA256S="<64 lowercase hex>" \
BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT="sha256:<out-of-band-bundle-fingerprint>" \
BLOGE_EXPECTED_FORMAL_AUTHORITY_BINDING_FINGERPRINT="sha256:<out-of-band-formal-outer-fingerprint>" \
JAVA_BIN="$(command -v java)" \
resource-gateway-test-kit/scripts/verify-capability-studio-stage-acceptance.sh \
  --test-kit-jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --provider-classpath \
    "resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/target/bloge-capability-studio-mounted-authority-provider-1.0.0.jar" \
  --stage-result <stage-acceptance-result-v2.json> \
  --conformance-output <provider-conformance-report.json>
```

`BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT` pins the post-run four-component
`AuthorityBinding` used by Provider Conformance.
`BLOGE_EXPECTED_FORMAL_AUTHORITY_BINDING_FINGERPRINT` separately pins the formal outer used by the
formal child. The runner scopes each pin to its own child and never compares them for equality.

A successful local provider conformance result is not a formal `ACCEPTED` result by itself. The
current deployment runner requires the
three ordered artifact pins `BLOGE_EXPECTED_TEST_KIT_JAR_SHA256`,
`BLOGE_EXPECTED_STAGE_RESULT_SHA256`, and `BLOGE_EXPECTED_PROVIDER_CLASSPATH_SHA256S`,
plus `BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT`, an exact out-of-band authority-material pin
supplied by the deployment Authority, and the independently issued formal-outer pin.
`JAVA_TOOL_OPTIONS` root injection remains deployment-controlled and outside the runner's existing
eight arguments. The runner does not snapshot either mounted bundle or produce a formal-v2 Evidence
manifest; it validates the child transcript and configured pins only. Formal acceptance additionally
requires
real externally signed evidence, organizational Owner approvals, target-environment attestation,
and deployment-level egress enforcement evidence. Those authorities are intentionally outside
this repository. The runner reads all artifact and binding pin variables and fails before Java when
they are
missing, malformed, out of order, or inconsistent with the source and snapshot artifacts.
