# Stage 4 Worker Quarantine Claim Token Protection Verification

## Purpose

Worker-quarantine claims return a server-minted token. A caller must be able to retry a lost claim
response and receive the same fence, so Resource Gateway cannot merely hash the command result.
Before this increment, `rg_test_durable_worker_quarantine_claim_commands` retained that replay token
as plaintext for an unbounded period. Database read access therefore exposed a reusable live token
and converted a short maintenance lease into a long-lived credential asset.

This increment protects the replay copy without changing the public claim protocol. It does not
claim that all quarantine lifecycle data now has bounded retention.

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

The capability probe advertises `encryptedDurableWorkerQuarantineClaimReplay=true` only when the
profile-gated testing runtime is enabled.

## Upgrade And Rotation

Startup processes rows in stable pages of at most 1,000 under database row locks:

1. A valid v1 plaintext command is fingerprint-verified, encrypted with the active key, upgraded to
   the v2 fingerprint, and has its plaintext column cleared in the same transaction.
2. An envelope written by a non-active key is authenticated, decrypted, and rewrapped with the
   active key.
3. A malformed legacy record, unknown key, invalid tag, or fingerprint drift aborts startup. The
   process does not skip a credential row and pretend migration succeeded.
4. Concurrent replicas use row locks and compare-and-set record fingerprints; no replica can
   silently overwrite another migration result.

Key rotation is deliberately two phase:

1. Add the future key to every replica's key ring while the old key remains active. Complete the
   rolling deployment so every live replica can decrypt both keys.
2. Make the future key active and roll again. Startup rewraps every old-key command before that
   replica becomes ready.
3. Verify all replicas are on the new configuration and no envelope names the old key, then remove
   the old key in a later deployment.

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
| replay the exact request | original claim token/version/expiry returns without another audit mutation |
| move envelope to another command identity | GCM AAD authentication fails |
| modify ciphertext or whole-record fields | replay fails closed and error text contains no token |
| remove an old decrypt key too early | startup/replay fails closed rather than inventing a new fence |
| start against a valid v1 plaintext row | row is atomically encrypted and exact replay remains unchanged |
| switch active key with both keys present | startup rewraps old envelopes and exact replay remains unchanged |
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

- the focused gate ran 56 tests with 0 failures, 0 errors, and 0 skips; the database authority
  contributed 22 tests and the token protector contributed 4 tests;
- Resource Gateway `clean verify` ran 2,209 tests with 0 failures, 0 errors, and 2 conditional skips,
  and produced the executable Spring Boot JAR;
- test-kit `clean verify` ran 63 tests with 0 failures, 0 errors, and 0 skips, including packaged
  authoritative schemas, the shaded CLI, and public JavaDoc verification.

## Honest Boundary

This is application-layer token-at-rest protection, not KMS/HSM-backed envelope encryption. The
configured AES keys exist in Resource Gateway process memory and deployment secret configuration.
There is no key-provider health probe, KMS audit event, automatic cryptographic erasure, or external
key-rotation controller yet.

The active quarantine control row still keeps its short-lived comparison fence in the isolated
test-runtime database until release, takeover, or discard. Command, approval, discard, and history
rows still lack bounded retention/tombstone policy. Same-database fingerprints are not external
WORM evidence. Those are separate hardening increments and must not be inferred from the capability
flag's deliberately narrow name.
