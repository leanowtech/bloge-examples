# Stage 4 Worker Quarantine Claim Token Protection Verification

## Purpose

Worker-quarantine claims return a server-minted token. A caller must be able to retry a lost claim
response and receive the same fence, so Resource Gateway cannot merely hash the command result.
The first protection increment removed plaintext from
`rg_test_durable_worker_quarantine_claim_commands`, but the live
`rg_test_durable_worker_quarantine_controls` row still retained the same bearer token for equality
checks. The current increment closes that second copy: only the encrypted replay command can recover
the token, while the active control stores a domain-separated HMAC fence.

This increment protects the replay copy without changing the public claim protocol. Bounded command,
approval, history, and tombstone lifecycles are supplied by the later
[retention increment](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-retention-verification.md),
not by encryption alone.

## Storage Contract

New claim commands write:

| Column | Value |
| --- | --- |
| `result_claim_token` | empty compatibility column; never used for a new command |
| `result_claim_token_envelope` | `v1.<keyId>.<base64url nonce>.<base64url ciphertext+tag>` |
| `record_fingerprint` | v2 whole-record fingerprint over the envelope, never the plaintext token |

The envelope uses AES-256-GCM with a fresh 96-bit nonce and a 128-bit authentication tag. Its AAD
binds the scope key, caller request ID, authenticated request fingerprint, run, checkpoint
fingerprint, result version, and claim expiry. Copying a valid envelope to another command row,
changing its ciphertext, or changing one of those bound fields makes replay fail closed.

New active control rows write:

| Column | Value |
| --- | --- |
| `claim_token` | empty compatibility column; never used by a v2 control |
| `claim_token_key_id` | key generation used for the active-fence MAC |
| `claim_token_mac` | `v1.<base64url HMAC-SHA-256>`; not a bearer credential |
| `record_version` | `2` |
| `record_fingerprint` | v2 whole-record fingerprint over key ID and MAC, never the token |

The MAC key is derived as `HMAC-SHA-256(rootKey, fixedKeyContext)` and is distinct from the AES key
object. The MAC message uses length-prefixed fields and a separate fixed message context, then binds
the scope key, run, checkpoint fingerprint, `CLAIMED` state, authenticated owner, control version,
claim expiry, and token. Resolution and approved discard compare the expected and stored MAC in
constant time. Moving a MAC to another control, changing any bound field, or presenting a forged
token fails closed.

The capability probe advertises `encryptedDurableWorkerQuarantineClaimReplay=true` only when the
profile-gated testing runtime is enabled. It independently advertises
`hashedDurableWorkerQuarantineActiveFence=true` for the live-control representation.

## Upgrade And Rotation

Startup first migrates commands and then controls in stable indexed pages of at most 1,000 under
database row locks:

1. A valid v1 plaintext command is fingerprint-verified, encrypted with the active key, upgraded to
   the v2 fingerprint, and has its plaintext column cleared in the same transaction.
2. An envelope written by a non-active key is authenticated, decrypted, and rewrapped with the
   active key.
3. A malformed legacy record, unknown key, invalid tag, or fingerprint drift aborts startup. The
   process does not skip a credential row and pretend migration succeeded.
4. Concurrent replicas use row locks and compare-and-set record fingerprints; no replica can
   silently overwrite another migration result.
5. A valid v1 control is whole-record verified. `AVAILABLE` is upgraded directly. An expired
   physical `CLAIMED` row is database-clock canonicalized to the same-version `AVAILABLE` state, so
   rotation does not depend on a replay command that retention was allowed to remove.
6. A still-live `CLAIMED` row must match exactly one encrypted claim command on
   scope/run/checkpoint/owner/version/expiry before its plaintext is cleared. A v2 live control
   naming an old key is authenticated with that key, cross-checked against the
   encrypted claim command, and re-keyed to the active MAC key. Missing/ambiguous commands, unknown
   keys, MAC drift, or command/control disagreement abort startup and roll back the page.

Key rotation is deliberately two phase:

1. Add the future key to every replica's key ring while the old key remains active. Complete the
   rolling deployment so every live replica can decrypt both keys.
2. Make the future key active and roll again. Startup first rewraps every old-key command and then
   re-keys every old-key active control before that replica becomes ready.
3. Verify all replicas are on the new configuration and neither command envelopes nor active
   controls name the old key, then remove the old key in a later deployment.

Changing the active key before all old replicas know it can make an old replica unable to replay a
row already rewrapped by a new replica. The deployment controller must enforce the two phases.

## Configuration

| Property | Environment variable | Test default | Staging default |
| --- | --- | --- | --- |
| `gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id` | `RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID` | `local-test-v1` | required |
| `gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring` | `RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING` | local non-production AES-256 key | required |

The key ring format is a comma-separated list of `keyId=base64Key` entries. Each decoded key must be
exactly 32 bytes; key IDs are non-secret identifiers matching `[A-Za-z0-9_-]{1,64}`. The active key
must be present. Duplicate IDs, short keys, malformed base64, and missing active keys fail startup.

For staging, inject both values from the deployment secret manager. Do not commit a staging key,
print the key ring, place it on a command line, or expose it through Actuator. The local test key in
`application-test.yml` exists only to keep the example runnable and must not be reused elsewhere.

## Counterexample Proofs

| Counterexample | Required result |
| --- | --- |
| inspect a newly written claim command | plaintext column is empty and envelope does not contain the token |
| inspect a newly written active control | plaintext column is empty; only key ID and HMAC remain |
| replay the exact request | original claim token/version/expiry returns without another audit mutation |
| move envelope to another command identity | GCM AAD authentication fails |
| modify ciphertext or whole-record fields | replay fails closed and error text contains no token |
| remove an old decrypt key too early | startup/replay fails closed rather than inventing a new fence |
| start against a valid v1 plaintext row | row is atomically encrypted and exact replay remains unchanged |
| switch active key with both keys present | startup rewraps old envelopes and exact replay remains unchanged |
| migrate a valid v1 active control | command is authenticated, plaintext is cleared, and the same claim still resolves |
| remove the matching claim command from a live control before re-key | startup aborts and the control migration page rolls back |
| rotate after an expired control's replay command was retained away | row becomes same-version `AVAILABLE`; startup does not require a dead credential |
| modify active MAC or its whole-record fields | list/resolve/discard fails closed without token disclosure |
| configure a 128-bit key or duplicate key ID | application refuses to start |

## Verification Gate

The focused gate covers the cryptographic primitive, database migration and rewrap, tamper
rejection, exact idempotent replay, profile isolation, application assembly, and capability probe:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=WorkerQuarantineClaimTokenProtectorTest,DatabaseDurableWorkerQuarantineControlPlaneTest,DurableWorkerQuarantineServiceTest,DurableWorkerQuarantineControllerTest,TestRuntimeProfileIsolationTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest,DatabaseTestRuntimeSloControlPlaneTest,TestRuntimeSloMonitorTest,TestRuntimeSloTelemetryTest test
```

Release still requires Resource Gateway `clean verify` and independent test-kit `clean verify`.

Verified on 2026-07-17:

- the focused gate ran 72 tests with 0 failures, 0 errors, and 0 skips; the database authority
  contributed 35 tests and the token protector contributed 6 tests;
- Resource Gateway `clean verify` ran 2,229 tests with 0 failures, 0 errors, and 34 existing
  conditional browser skips,
  and produced the executable Spring Boot JAR;
- test-kit `clean verify` ran 63 tests with 0 failures, 0 errors, and 0 skips, including packaged
  authoritative schemas, the shaded CLI, and public JavaDoc verification.

## Honest Boundary

This is application-layer token-at-rest protection, not KMS/HSM-backed envelope encryption. The
configured AES keys exist in Resource Gateway process memory and deployment secret configuration.
There is no key-provider health probe, KMS audit event, automatic cryptographic erasure, or external
key-rotation controller yet.

The active control now keeps only a keyed verifier, but the encrypted command remains deliberately
recoverable during the exact-replay window. A process compromise that exposes the configured root
key and database can therefore recover an unexpired token; HMAC storage is not a substitute for
KMS/HSM custody, process isolation, or least-privilege database access. UUID claim tokens provide
high entropy, so the verifier is not intended to protect low-entropy passwords from offline guessing.

Detailed command/approval rows have bounded retention and payload-free request tombstones, while
history is independently purged. Same-database fingerprints are not external WORM evidence and
physical deletion does not prove backup or replica erasure. The startup migration is indexed and
paged but remains a synchronous readiness gate over all legacy/old-key controls; very large upgrades
must be rehearsed from production-scale snapshots. Legal hold, keyed request indexes, external
workflow binding, and KMS/HSM custody remain separate hardening work and must not be inferred from
either narrow capability flag.
