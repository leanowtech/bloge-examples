# Stage 4 Worker Quarantine Request Index Protection Verification

## Purpose

Worker-quarantine retention removes detailed command rows but must continue reserving each
`clientRequestId` long enough to prevent a destructive command from being accepted again. The first
tombstone format used an unkeyed SHA-256 lookup digest. It omitted the raw request ID, but a database
reader who knew the operation and scope could still test likely low-entropy IDs offline.

This increment replaces every new tombstone lookup value with an independently keyed,
rotation-aware HMAC index. It preserves bounded exact retry lookup, introduces online key rotation,
and fails readiness when a live keyed tombstone cannot be verified.

## Protocol

Each v2 tombstone stores:

- `request_key_id`, the non-secret key generation;
- `request_key`, encoded as `v1.<base64url HMAC-SHA-256>`;
- `record_version=2`;
- operation kind, content-addressed scope key, canonical intent/source fingerprints, lifecycle
  timestamps, and a whole-record fingerprint;
- no raw request ID, claim token, business payload, fixture, or checkpoint content.

The 32-byte root is not used directly. The protector first derives a request-index HMAC key with
the fixed context `bloge.workerQuarantine.requestIndexHmacKey.v1`. The index MAC then length-prefixes
and binds:

```text
bloge.workerQuarantine.requestIndex.v1
request kind
canonical authenticated scope key
clientRequestId
```

Length prefixes prevent tuple ambiguity. Operation and scope binding prevent one valid request index
from being moved to another command family or authorization scope. Exact verification uses a
constant-time MAC comparison. The root-key ring accepts 1 through 16 named 32-byte generations so
lookup cost remains bounded.

The request-index ring is deliberately independent from claim-token encryption/control-fence roots.
Claim credentials and 365-day request reservations have different compromise and retirement clocks;
sharing their key lifecycle would either retain a credential root too long or make request-ID
rotation unsafe.

## Lookup And Migration

In `DUAL_READ_KEYED_WRITE` or `KEYED_ONLY`, new writes use only the active generation. Exact lookup derives candidates for the active key,
configured verification-only keys in stable key-ID order, and the legacy unkeyed v1 digest. A hit is
validated against the supplied request ID and the whole-record fingerprint. An old-key or legacy hit
is rewritten to the active v2 key with an exact compare-and-set fence while the tombstone is locked.

The legacy row intentionally contains no raw request ID. Therefore it cannot be proactively bulk
re-keyed from storage alone. It migrates only when that exact request ID is presented again; otherwise
it remains readable through the bounded legacy candidate until its tombstone deadline and is then
physically deleted. This is a migration limitation, not a reason to restore raw identifiers.

Expired tombstones do not participate in idempotency lookup and may be integrity-checked and purged
without the retired HMAC root. Unexpired v2 tombstones are different: startup scans their distinct
key generations and fails readiness if any key is absent. A process may not silently start with a
lookup blind spot that could resurrect a request identity.

## Rotation Runbook

1. Generate a new random 32-byte root in the deployment secret manager.
2. Add the new generation to every replica's key ring while the old generation remains active.
3. Confirm all replicas are ready and advertise
   `keyedDurableWorkerQuarantineRequestIndex=true`.
4. Change `active-key-id` to the new generation. New tombstones now use it; exact access lazily
   re-keys old or legacy rows.
5. Keep the old root until no unexpired tombstone references it. Readiness deliberately rejects early
   removal.
6. Remove the old root only after those rows have been re-keyed or expired. Expired rows can still be
   purged without that root.

Before step 6, inspect the live generation inventory in the isolated test-runtime database:

```sql
SELECT record_version, request_key_id, COUNT(*) AS live_rows, MAX(expires_at) AS last_expiry
FROM rg_test_durable_worker_quarantine_request_tombstones
WHERE expires_at > CURRENT_TIMESTAMP
GROUP BY record_version, request_key_id
ORDER BY record_version, request_key_id;
```

Do not replace the whole ring in one rollout. During a mixed key-generation rollout every replica
must first know both generations; only then may the active generation change. There is not yet a
public generation-inventory API, so the bounded SQL inventory plus readiness guard is the current
operational proof.

Application-binary rollout uses the implemented
`LEGACY_READ_WRITE -> DUAL_READ_KEYED_WRITE -> KEYED_ONLY` protocol. Deploy N in legacy mode while
N-1 remains, prove every serving instance is N through per-replica capability inventory, and only
then enable keyed writes. Keyed-only is allowed only after every live v1 row has migrated on exact
access or expired. The complete state machine, rollback boundary, and fleet-authority limitation are
documented in the [request-index rolling-upgrade verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-upgrade-verification.md).

## Configuration

| Property | Environment variable | Profile behavior |
| --- | --- | --- |
| `gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id` | `RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID` | local demonstration key in `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-key-protection.key-ring` | `RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING` | `keyId=base64Key[,oldKeyId=base64Key]`; local demonstration ring in `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-key-protection.write-mode` | `RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE` | local `test` defaults to dual; `staging` requires an explicit closed mode |

The launcher checks both request-index values as well as the two claim-token values before starting a
`staging` profile. Invalid key IDs, malformed Base64, roots other than 32 bytes, duplicate/missing
active generations, empty rings, or rings larger than 16 fail application assembly.

## Counterexample Matrix

| Counterexample | Required result |
| --- | --- |
| Database reader guesses a likely request ID | cannot verify a v2 guess without the request-index root |
| Same request ID is tried in another operation or scope | derives a different index |
| Old and new roots coexist | active candidate is first; old rows remain readable |
| Exact request reaches an old-key row | retry semantics are preserved and the row is CAS re-keyed |
| Exact request reaches a legacy SHA row | retry semantics are preserved and the row is upgraded to v2 |
| Live v2 row references a removed key | startup fails readiness |
| Expired v2 row references a removed key | startup succeeds and bounded retention may purge it |
| More than 16 generations are configured | configuration is rejected before serving traffic |
| A selected row's key ID, version, or fingerprint is inconsistent | exact read or retention fails closed |
| Two candidates exist for one request identity | uniqueness check fails closed; no command is rerun |
| Previous binary may still serve | N stays in legacy-write mode; keyed writes remain deployment-gated |
| Legacy mode starts after a live keyed write | readiness fails and rollback safety is not claimed |
| Keyed-only starts with a live v1 row | readiness fails until exact migration or natural expiry |

## Verification Gate

Focused verification covers deterministic domain binding, malformed configuration, ring bounds,
active-first rotation, constant-time exact verification, new v2 writes, lazy old-key and legacy
migration, early key removal, expired-key purge, profile isolation, capability discovery, and
application assembly:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=WorkerQuarantineRequestKeyProtectorTest,WorkerQuarantineClaimTokenProtectorTest,DatabaseDurableWorkerQuarantineControlPlaneTest,DurableWorkerQuarantineServiceTest,DurableWorkerQuarantineControllerTest,TestRuntimeProfileIsolationTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest,DatabaseTestRuntimeSloControlPlaneTest,TestRuntimeSloMonitorTest,TestRuntimeSloTelemetryTest test
```

On 2026-07-17 the focused gate executed 81 tests with zero failures, errors, or skips; the database
authority contributed 40 and the request-index protector contributed 4. Resource Gateway
`clean verify` executed 2,238 tests with zero failures or errors and 34 existing conditional browser
skips, then packaged the executable Spring Boot JAR. Independent test-kit `clean verify` executed 63
tests with zero failures, errors, or skips and passed packaged-schema, shaded CLI, and public JavaDoc
verification.

## Honest Boundary

The HMAC index protects low-entropy request IDs against a database-only offline dictionary attack. It
is not encryption, a bearer credential, a signature, or independent evidence. A process/root-key
compromise can calculate indexes, and simultaneous database-plus-key compromise defeats this
protection. The whole-record fingerprint detects accidental drift when a row is selected; it is not
a keyed authenticator. An out-of-band database writer can delete a row or alter its lookup key so
exact lookup does not select it before retention scans that row. Preventing or proving that class of
omission needs strict database IAM plus an externally anchored append-only manifest/WORM authority;
this increment does not claim it.

The current example uses deployment-injected key material, not KMS/HSM-backed non-exportable keys or
automated retirement attestations. Legacy SHA rows remain susceptible until exact-access migration or
expiry. Database deletion does not prove backup, replica, log, or secret-manager erasure. External
WORM anchoring, legal hold, per-tenant key policy, multi-region rotation qualification, and non-H2
dialect certification remain Stage 4/5 hardening work. Key-generation rotation and the staged
application-binary transition are online, but the deployment platform remains responsible for
proving that every serving replica has reached N before keyed writes begin. There is no signed fleet
attestation or database-enforced barrier that an N-1 binary could understand.
