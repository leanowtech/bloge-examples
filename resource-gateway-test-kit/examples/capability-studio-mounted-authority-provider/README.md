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

Legacy stores keep the STORE, LIFECYCLE, and LEASE v2 domains for the ordinary formal CLI. New
full-evidence roots use explicit v4 domains and persist an immutable transition witness with every
lease receipt. The v4 state uses a non-circular state-core commitment, witness-material commitment,
final state commitment, and checkpoint commitment; parsing replays the complete transition chain.
Both versions bind the Provider artifact/version, Authority and Target Admission material,
immutable store descriptor, revocation registry/lifecycle material, and fixed behavior versions.
Absolute and real mount paths are deliberately excluded: copying identical read-only bundles to
another mount while reusing the same store descriptor produces the same component material and
formal outer. Paths are still validated and used to enforce local filesystem safety. Lifecycle and
lease material bind the post-run `AuthorityBinding` because their callbacks authorize requests
against the complete formal outer.

The ordinary `CapabilityStudioStageAcceptanceCli` remains compatible with an exact v2 store. Full
evidence has no v2 fallback: `formalEvidenceAuthorityBinding()` is unavailable unless the mounted
store is v4 and can recover the original transaction witness. Do not alter a v2 store in place.
Create a new private state root, declare and authenticate its material, and independently issue new
descriptor and formal-outer pins before using the full-evidence flow.

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

### Observe existing state and produce full evidence

`MountedCapabilityStudioDeploymentStateObservationCli` opens an existing v4 store only. Under the
same fixed JVM lock stripe and a shared OS descriptor lock, with one bounded monotonic deadline for
both locks, it validates the exact four-file root
closure (`descriptor`, `state`, `checkpoint`, and revocation head), performs two complete metadata
inventories and strict file reads, and writes one fresh canonical observation outside the state
root. It never initializes, repairs, forces, chmods, creates, deletes, or moves store objects. A
generation-one state with a generation-zero checkpoint is therefore unavailable and remains
unchanged. Shared locks or required POSIX/Unix identity metadata that cannot be proven also fail
unavailable.

```bash
java -cp "<provider-and-test-kit-classpath>" \
  com.leanowtech.bloge.gateway.testkit.mounted.MountedCapabilityStudioDeploymentStateObservationCli \
  --phase BEFORE \
  --evidence-transaction-id 'sha256:<stable evidence transaction>' \
  --state-root /absolute/path/to/private-lease-state \
  --expected-store-descriptor-fingerprint 'sha256:<out-of-band descriptor pin>' \
  --output /absolute/private/evidence/deployment-state-before-v1.json
```

The observer makes no explicit state write and verifies that namespace, content, mode, UID, and
mtime remain stable. A normal file read may still update atime; deployments requiring atime
stability need a read-only or `noatime` mount. External BEFORE/AFTER observations do not prove which
concurrent lease caused a transition. The v4 full-evidence coordinator holds the store's exclusive
transaction lock while it persists BEFORE, commits or recovers the exact lease, captures AFTER, and
persists the committed journal. The transcript verifier cross-checks the observations' governed
pre/post commitments, generation, fencing, checkpoint/head, request, receipt, and witness
coordinates. Observation-only raw fields remain independent cross-checks; a generation difference
alone is never accepted as attribution.

### Provision, execute or recover, and verify

Provision the evidence publication parent before running formal evidence. Provisioning creates the
fixed bootstrap, stable lock, and declaration only. Deployment automation must authenticate the
returned publication fingerprint out of band:

```bash
java -cp "<test-kit-cli-jar>" \
  com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidencePublicationProvisioningCli \
  --publication-parent /absolute/private/execution-lease-evidence \
  --publication-nonce 'sha256:<deployment-generated 64 lowercase hex>'
```

Exit `0` emits one `PROVISIONED` line. Invalid arguments, pins, or structure return `INVALID` with
exit `2`; unavailable permissions, I/O, or metadata capability return
`NOT_PROVISIONED outcome=BLOCKED` with exit `3`. Record the emitted `publicationFingerprint` in the
deployment trust configuration. One provisioned parent admits exactly one transaction: the
declaration binds `execution-lease-transcript-v1.json` and a nonce-derived transaction identity.
An exact retry reuses that output; a new business transaction requires a new private parent, a new
provisioning run, and a new independently authenticated publication pin. Execution opens those
provisioned objects existing-only:

```bash
BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT='sha256:<formal outer>' \
BLOGE_EXPECTED_CAPABILITY_STUDIO_EVIDENCE_PUBLICATION_FINGERPRINT='sha256:<publication pin>' \
java \
  -Dbloge.capabilityStudio.authorityBundleRoot=/absolute/path/to/authority-bundle \
  -Dbloge.capabilityStudio.targetAdmissionBundleRoot=/absolute/path/to/target-admission-bundle \
  -Dbloge.capabilityStudio.executionLeaseStateRoot=/absolute/path/to/private-lease-state \
  -cp "<test-kit-cli-jar>:<provider-jar>" \
  com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidenceCli \
  /absolute/path/to/stage-acceptance-result-v2.json \
  /absolute/private/execution-lease-evidence/execution-lease-transcript-v1.json
```

Initial durable publication reports `evidencePublicationStatus=COMMITTED`; an exact retry reports
`RECOVERED` and returns the same durable receipt and witness. Governance rejection stays
`REJECTED`; malformed or conflicting evidence is `INVALID`/exit `2`; permission, lock, I/O,
metadata, Provider, or store outage is `BLOCKED`/exit `3`. Provider reasons, paths, credentials, and
payload are not copied into the closed CLI line.

Verify the durable wrapper without Provider discovery or writes:

```bash
java -cp "<test-kit-cli-jar>" \
  com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli \
  --transcript /absolute/private/execution-lease-evidence/execution-lease-transcript-v1.json \
  --expected-stage-result-raw-fingerprint 'sha256:<Stage Result raw pin>' \
  --expected-formal-outer-fingerprint 'sha256:<formal outer pin>' \
  --expected-publication-fingerprint 'sha256:<publication pin>'
```

Success is one `VERIFIED status=VERIFIED verificationScope=DURABLE_WRAPPER` line with exit `0`.
Structural or coordinate conflict is `INVALID`/exit `2`; an unavailable filesystem or metadata
dependency is `NOT_VERIFIED outcome=BLOCKED`/exit `3`.

`CapabilityStudioExecutionLeaseEvidenceCli` uses that v4 companion capability along the unchanged
formal acceptance path. Before any journal write, the Provider proves the evidence parent and state
root physically disjoint using stable ancestor identities under the wrapper-then-store lock order.
Pending recovery loads a separate binding. Its `recovery()` callback opens the existing closure
under an exclusive transaction lock but never initializes, repairs, forces, chmods, or mutates
store material. If and only if that lookup reports an unavailable recognized one-generation writer
intermediate, `interruptedRecovery()` may run the fixed writer reconciliation without current
admission and without creating a lease. Missing dependencies and stale intermediates are
unavailable; malformed material and unknown closure entries are invalid. A stable v2 transaction
directory retains its exact owner, generation-numbered BEFORE journals, immutable ABSENT closures,
committed transcript source, an inner commit manifest binding request, BEFORE, transcript, receipt,
witness, generation, and previous attempt closure, plus an outer `final-commit-v1.json` binding the
manifest raw/canonical fingerprints, owner, and final transcript. Only the outer layer closes the
durable Bundle. Publication uses deterministic parts,
file force, `0400`, hard-link installation, parent force, source unlink, and a second parent force;
source-only, target-only, and same-inode BOTH states recover, while distinct or unknown objects are
preserved and blocked. An exact final is verified from the Stage raw digest and independently pinned
outer before Provider discovery or Stage/lifecycle freshness checks. A pending committed lease uses
the exact historical transition evidence and never creates another lease. `ABSENT` closes the old
attempt and starts a new generation with current Stage/lifecycle/admission checks; `CONFLICT` fails
`INVALID`, and storage/lock/I/O unavailability fails `BLOCKED`. A post-commit evidence failure
does not roll back the lease or emit `ACCEPTED`; stdout failure recovers the same final transcript.

`CapabilityStudioExecutionLeaseTranscriptVerifyCli` checks transcript schema and semantic
self-consistency only. It is not FELT-08/FELT-14 evidence. Use
`CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli` with the out-of-band Stage raw, formal
outer, and publication pins to verify the immutable owner claim, attempt chain, retained source,
commit manifest, final transcript, receipt, and transition witness. The Bundle verifier is
read-only and does not create a publication lock or issue a durability receipt.
The shipped eight-argument runner does not yet orchestrate before/run/after or publish this
transcript. The current full regression and ninth independent P0/P1 rereview are green, so the
component mechanism is `DEVELOPMENT_VERIFIED`. FELT-01 remains `PARTIAL` and FELT-14 remains
`NOT_RUN`; this result does not change `formalPassCount=0/27`.

`Clock.systemUTC()` is the reference time source. It is not independently authenticated by this
module; the deployment authenticates the Provider artifact and complete formal outer fingerprint.
The module creates no keys or signatures, performs no network access, and writes no business
payload. Filesystem durability, host time integrity, mount immutability, and backup/restore policy
remain deployment responsibilities.

The reference store has a fixed aggregate closure ceiling of 32 MiB and retains at most 1,024
immutable leases; each evidence transaction retains at most 1,024 attempts. Exhaustion is
unavailable. The implementation does not automatically delete, compact, archive, or reuse history.
Increasing capacity requires an explicit protocol/configuration revision and a new state root with
new descriptor, component, publication, and formal-outer pins.

Regular managed files require the exact protocol link count. Directories require a stable positive
link count instead of Linux's conventional value two because APFS commonly reports `nlink=1` for
directories; regular-file hard-link checks remain strict. Production sources expose no crash JVM
property, environment switch, or `Runtime.halt` path. The test-only build deterministically creates
a source- and class-pinned shadow overlay with 17 abrupt-termination checkpoints that cover the 14
semantic windows frozen by FELT-10. Its strict manifest records the exact point-to-window mapping.
Before use, packaged tests require the Maven-resolved ordinary and shaded Test Kit JARs to expose the
same build identity, independently recompute their Evidence CLI class digests, and match the current
source and shadow source digest. The two-process exact-retry test releases the first JVM only after
the second JVM has durably recorded an actual publication `tryLock()` miss. A completion ACK is
published only after the main marker's parent force returns; the parent waits for the ACK and rereads
the marker. A deterministic pause between marker installation and parent force proves that path
visibility alone cannot advance the test. A production-only JVM then verifies recovery. Only the
harness can activate test hooks; these checks do not establish FELT `14/14`.

This local reference assumes a trustworthy same-host kernel, UID ownership, crash-consistent local
filesystem with working force/atomic operations, protected out-of-band pins, and honest deployment
automation. A privileged actor or same-UID attacker capable of coherently replacing all trusted
store/publication objects, a false durability implementation, compromised host clock, and missing
external Candidate/Environment/Owner Authority remain outside its trust claim.

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
