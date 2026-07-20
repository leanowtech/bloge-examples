# Stage 3 Child Evidence Storage Integrity Verification

## Scope

This increment closes the mutation and storage-substitution window between sanitized child evidence,
its detached signature, and the durable `rg_test_run_records` row. It does not change the public
`bloge.testRunEvidence.v2` or `bloge.testEvidenceIntegrity.v1` wire shape.

## Root Cause

`TestRunEvidence` contains payload-bearing `Object` and `Map<String, Object>` fields. Freezing only
the outer list or map leaves nested maps and lists mutable. An arbitrary Java bean cannot be detached
by collection wrappers at all. Before this increment, the signer fingerprinted the supplied object
and returned only a manifest, while the persistence adapter later serialized the original object.
Concurrent or retained-alias mutation could therefore make "signed A, stored B" possible.

The JDBC adapter also trusted the manifest's `VERIFIED` label on create. On read, the API verified the
evidence signature, but the adapter selected only `record_json`; it did not bind independently indexed
scope, target, status, classification, or retention columns back to the serialized aggregate. A valid
signed child copied into another storage envelope was therefore detected too late or incompletely.

## Implemented Invariants

1. `TestRunEvidence` recursively copies and freezes node/attempt input and output, edge values,
   assertion expected and actual values, and metadata. Cyclic containers, non-string object keys, and
   excessive nesting retain the common protocol rejection semantics.
2. `TestEvidenceIntegrityService.seal` performs an exact `TestRunEvidence` JSON round trip before
   semantic verification, fingerprinting, and signing. `SealResult.evidence` is the independently
   owned value actually signed; callers persist that value rather than the original reference.
3. `TestRunRecordIntegrity` performs a second whole-record canonical round trip at the repository
   boundary. It binds `runId`, target, fixture, effective plan, purpose, completion time, and the five
   signed identity metadata fields to the storage envelope.
4. New current evidence must include `payloadSanitized=true`. New certifiable evidence must verify
   cryptographically before insert. Signer-unavailable records are accepted only as exploratory
   `EVIDENCE_INCOMPLETE` evidence with an exact canonical fingerprint.
5. Historical unsigned v1 records remain decodable for explicit migration policy, but cannot cross
   the new-write boundary. Signed historical evidence must still bind its identity metadata.
6. JDBC reads compare `record_json` with the independently indexed run, tenant, environment, target,
   status, evidence class, creation time, and expiry, then bind the result to the complete authorized
   `tenant/environment/runId` lookup key.
7. The API service treats repository adapters as an untrusted transition. It verifies an alternate
   adapter's create receipt against the exact submitted canonical record and independently verifies
   every read result before projection, so a non-JDBC adapter cannot bypass the storage contract.
8. Canonicalization, signature mismatch, envelope drift, indexed-column drift, and cross-scope
   substitution throw the payload-free `TestRunIntegrityException`. The API maps read failures to
   `409 RG.TEST.EVIDENCE_INTEGRITY_INVALID` and appends a bounded security event before projection.

## Verification Matrix

| Threat | Proof |
| --- | --- |
| Caller mutates a nested list or map after evidence construction | Evidence retains the original value and exposed containers reject mutation |
| Caller mutates an arbitrary bean after sealing | `SealResult.evidence` contains a detached JSON map and still verifies |
| Forged `VERIFIED` manifest reaches JDBC create | Create fails before insert with a payload-free integrity exception |
| Alternate repository returns a different valid record as its create receipt | API rejects the receipt, records a bounded security event, and downgrades the run to signed `EVIDENCE_INCOMPLETE` |
| Serialized record belongs to another tenant while the indexed row remains in the authorized scope | Read fails while comparing canonical record, signed identity, lookup key, and indexed columns |
| Alternate repository substitutes a valid record on read | API rejects it before projection with `RG.TEST.EVIDENCE_INTEGRITY_INVALID` |
| Evidence or signing time changes after signing | Existing detached-signature verification remains `INVALID` |
| Signer is unavailable | Evidence is downgraded to exploratory `EVIDENCE_INCOMPLETE`; no certifiable write is admitted |

Focused evidence, persistence, API, and Spring-wiring verification executed 71 tests with 0 failures,
0 errors, and 0 skips. The project-level
`mvn -f resource-gateway-examples/pom.xml clean verify` gate executed 3063 tests with 0 failures,
0 errors, and 2 conditional browser skips; 35 configured real-browser tests completed and the Spring
Boot executable JAR was repackaged successfully.

## Remaining Trust Boundary

This increment detects mutable aliases, partial row corruption, ordinary adapter substitution, and
cross-scope replay. It does not defeat a storage authority that can replace every indexed column,
signed evidence, and signing-key trust material consistently. External immutable retention,
independent witness publication, backup rollback detection, key-custody controls, and disaster
recovery evidence remain separate industrial requirements.
