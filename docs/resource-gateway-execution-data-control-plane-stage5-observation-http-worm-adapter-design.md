# Stage 5 observation HTTPS WORM adapter design

**Implemented non-production transport core (2026-07-20): Resource Gateway can fan one exact,
challenge-bound floor-retirement object out to independently configured HTTPS authorities, verify
signed compliance-retention receipts, treat only a signed non-equal object commitment as an
authenticated conflict, and return a canonical copy-threshold receipt set to the existing
external-first local transaction. Test and staging wiring is explicit; production wiring and the
cross-retention capability remain closed.**

## 1. Strongest judgment

The previous external-archive boundary had the right transaction order but no deployable transport.
An in-process fixture could prove canonical request/receipt handling, yet it could not prove that a
different administrative and storage failure domain had accepted the object. Calling that state
"WORM integrated" would have confused protocol correctness with physical durability.

The correct next increment is not a retention scheduler. It is a narrow, strict adapter that makes
the existing deletion authority reachable without weakening it:

1. freeze one complete signed retirement and its compact archive;
2. derive one deterministic immutable object id and one retention-bearing commitment;
3. send the same fresh request concurrently to configured independent authorities;
4. verify every accepted receipt locally against caller-owned topology and Ed25519 keys;
5. make any authenticated immutable conflict fatal;
6. persist only a canonical receipt set that meets the configured copy threshold;
7. leave local rows untouched on every transport, trust, freshness, or threshold failure.

This closes the transport-shape gap. It does not certify a provider, an account boundary, a legal
retention policy, geographic independence, backup purge, or disaster-recovery continuity.

## 2. Ownership boundary

| Component | Owns | Does not own |
| --- | --- | --- |
| Retirement service | exact signed retirement, requested retain-until, local commit ordering | endpoint discovery, provider certification |
| HTTPS adapter | fresh challenge, bounded concurrent transport, strict parsing, configured trust verification, copy threshold | local database mutation, retries, legal hold |
| External authority | immutable object write, compliance-retention enforcement, accepted/conflict signature | Resource Gateway floor/head mutation |
| Repository | exact receipt-set persistence and atomic archive/floor/head/delete transaction | remote I/O or key resolution |
| Deployment owner | endpoint and failure-domain inventory, public-key lifecycle, retention minimum, provider/IAM certification | changing protocol semantics at runtime |
| Governance/ANEKE | historical trust policy, legal hold/release, evidence consumption, publication gate | pretending an HTTP 200 proves physical independence |

The adapter accepts only public verification keys. Credentials, client TLS private material, secret
headers, provider account ids, and network policy are deployment concerns outside the JSON
protocol and health projection.

## 3. Transport contract

### 3.1 Request

Each authority receives the exact `ExternalArchiveRequest.v1` with:

- one complete signed retirement and nested compact archive;
- configured trust domain and archive-set id;
- deterministic object id in `Idempotency-Key`;
- fresh 256-bit challenge and canonical request fingerprint;
- whole-second request time and an exclusive maximum 60-second admission deadline;
- requested immutable `retainUntil` no shorter than the configured minimum retention.

The body is capped at 2 MiB. The adapter performs one request per authority and never retries;
caller-owned orchestration must create a new challenge for a new attempt. Exact provider retries are
safe because the idempotency key is the content-derived immutable object identity.

### 3.2 Accepted receipt

HTTP 200 is accepted only when all of the following hold:

- exact vendor media type and explicit receipt schema-version header;
- strict JSON with duplicate, unknown, and trailing tokens rejected;
- body no larger than 128 KiB;
- exact request, trust-domain, archive-set, authority, failure-domain, object, retirement, segment,
  retention-policy, and retain-until binding;
- `COMPLIANCE`, external durability, write-once, and early-delete-denied assertions are true;
- issue/expiry times are inside the request window and still live at local confirmation;
- the canonical receipt fingerprint and configured Ed25519 signature verify;
- the signing key was enabled, unrevoked, and active at the signed issue time.

The adapter sorts accepted receipts by authority id and seals the complete set only after the copy
threshold is met. It then runs the same local verifier again before returning the set to the
retirement service.

### 3.3 Authenticated immutable conflict

An unsigned HTTP 409 is not safety truth. It may be a proxy, outage page, stale route, or attacker.
The adapter recognizes a conflict only through
`ExternalArchiveConflictReceipt.v1`, which signs:

- the exact fresh request fingerprint;
- configured authority, failure domain, key, trust domain, and archive set;
- deterministic object id;
- the expected retention-bearing object commitment;
- a different opaque observed commitment already bound by the authority;
- a short issue/expiry window.

The observed commitment is payload-free. It proves non-equality without exporting the conflicting
object. A valid conflict from one configured authority is fatal even if enough other authorities
accepted the candidate. An invalid conflict response is merely an invalid minority and cannot veto
a copy-threshold success.

The strict standalone and authoritative Schemas now include request, accepted receipt, conflict
receipt, and receipt-set definitions:

- [`suite-stability-observation-external-archive-v1.schema.json`](schemas/resource-gateway-testing/suite-stability-observation-external-archive-v1.schema.json)
- [`testing-control-plane-v1.schema.json`](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json)

## 4. Trust and topology configuration

Configuration is explicit under:

```text
gateway.testing.stability-observation-lifecycle.external-archive.http
```

Required deployment facts are:

| Property | Invariant |
| --- | --- |
| `trust-domain` | exact stable identifier |
| `archive-set-id` | exact independently governed set identity |
| `required-copies` | 1..16 and no greater than endpoint count; staging requires at least 2 |
| `minimum-retention-days` | positive and bounded to 100 years |
| `authority-keys-json` | bounded Ed25519 public keys with authority/key id and lifecycle flags |
| `endpoints-json` | exact authority/failure-domain/URI triples |
| `request-timeout-ms` | 100 ms..30 s and shorter than receipt lifetime |
| `maximum-receipt-lifetime-seconds` | 1..60 s |
| `maximum-inventory-snapshot-age-seconds` | 1 s..7 days; default 300 s; bounds accepted pre-generated read snapshots |
| `allow-insecure-loopback` | test-only escape hatch; staging rejects it |

Authority ids, failure domains, and endpoint URIs must each be unique. The authority set in endpoint
configuration must equal the authority set represented by configured keys. At startup, enough
authorities must have an active key to meet the copy threshold.

Receipt-set time ordering deliberately has no cosmetic clock-skew knob. An accepted receipt issue
time must be at or after the request time and no later than local confirmation. A provider with
uncontrolled clock drift fails admission instead of causing Resource Gateway to persist a future
confirmation time. Enterprise deployments must keep authority clocks disciplined or define a new
versioned protocol with separately signed provider and local-receive times; silently loosening v1
would make historical verification ambiguous.

## 5. Concurrency and threshold semantics

Independent retirements are not serialized by one process-wide monitor. Each invocation fans out
concurrently, while aggregate state uses atomic updates. This avoids cross-suite head-of-line
blocking when one provider approaches its timeout.

| Observation set | Result |
| --- | --- |
| all authorities accept | receipt set, status `HEALTHY` |
| threshold accepts, minority unavailable/invalid | receipt set, status `DEGRADED_COPY_SET` |
| fewer than threshold, only outages | `UNAVAILABLE`, no local mutation |
| fewer than threshold and any malformed/trust-invalid response | `INVALID_RECEIPT`, no local mutation |
| any valid signed non-equal conflict | `AUTHENTICATED_CONFLICT`, no local mutation |
| response lost after external write | retry may create external orphan; local state is unchanged |
| local CAS loses after valid receipts | external orphan; local state is unchanged |

Persisting every valid accepted receipt, not only the minimum subset, preserves the exact observed
copy topology for later lifecycle v2 verification and incident analysis.

## 6. Network and parser controls

The adapter enforces:

- HTTPS for every non-loopback endpoint;
- a test-only HTTP loopback escape hatch;
- no user-info, query, or fragment in configured URIs;
- JVM TLS policy and no redirect following;
- per-request connect/end-to-end timeout;
- no automatic retry;
- exact content type and protocol-version header;
- declared and streamed response-body bounds;
- strict duplicate/unknown/trailing JSON rejection;
- payload-free stable exceptions and aggregate state.

Mutual TLS, certificate pinning, egress allowlists, DNS controls, proxy policy, and credential
injection belong to the deployment's `HttpClient`/JVM/network boundary. They must not be serialized
into requests, descriptors, errors, or evidence.

## 7. Spring and profile isolation

The adapter, external-first retirement service, and aggregate Actuator health contributor are
created only when `http.enabled=true` inside the existing `!production & (test | staging)` testing
composition root.

Staging startup fails when:

- insecure loopback HTTP is enabled;
- fewer than two independent copies are required;
- key, endpoint, failure-domain, timeout, retention, or lifecycle configuration is invalid.

Production never loads these beans even if properties are accidentally supplied. There is still no
public retirement endpoint or scheduler in this increment, and
`crossRetentionSuiteStabilityTrend` remains false.

## 8. Health semantics

The Actuator contributor exposes only bounded aggregate fields:

- status and last successful archive time;
- success, failure, and authenticated-conflict counts;
- authority, required-copy, and independent-domain counts.

It is `DOWN/UNVERIFIED` before the first real write, `UP/HEALTHY` after a complete copy set,
`UP/DEGRADED_COPY_SET` after a threshold success with a bad minority, and `DOWN` after a threshold
failure or authenticated conflict. Endpoint, authority, failure-domain, key, object, request,
challenge, fingerprint, and signature identities are never health details.

This is operation-derived health, not a destructive startup probe. A future scheduler readiness
gate must combine static descriptor readiness, latest operation freshness, orphan backlog, legal
hold, and DR continuity rather than treating bean creation as provider health.

## 9. Failure and attack analysis

| Threat/failure | Root cause | Control |
| --- | --- | --- |
| proxy returns 409 | status code mistaken for trust | signed conflict receipt required |
| old receipt replay | request identity not fresh | 256-bit challenge and exact fingerprint echo |
| authority rebind | response accepted by key alone | endpoint authority/domain exact match |
| shared physical domain | copy count mistaken for independence | unique configured failure domains; deployment certification still required |
| retention shortening | provider accepts object under weaker policy | signed retain-until must equal or exceed request |
| overwrite ambiguity | object id reused for different material | deterministic id plus signed expected/observed commitments |
| malformed successful minority | quorum code ignores parser failure | invalid response cannot count; degraded success is observable |
| slow authority | process-wide write serialization | concurrent fan-out and independent invocation concurrency |
| redirect credential leakage | generic client follows redirect | redirect policy `NEVER` |
| oversized response | memory/resource exhaustion | declared and streamed 128 KiB bounds |
| key expires after startup | static startup check becomes stale | descriptor re-evaluates current active-key count per operation |
| database race after remote write | remote and local transaction cannot be atomic | local CAS abort; retain safe external orphan |

## 10. Executable evidence

The focused gate executes 38 tests with zero failures, errors, or skips. It includes 12 real-HTTP
adapter tests proving:

- two-of-three success with one unavailable domain and canonical sorted receipts;
- authenticated conflict precedence over an otherwise successful threshold;
- invalid conflict signatures cannot veto accepted copies;
- invalid accepted signatures and shortened retention cannot contribute;
- old-challenge replay cannot authorize a new attempt;
- unknown JSON, redirect, and timeout paths fail closed;
- exact request/receipt expiry is exclusive;
- duplicate domains, missing authority keys, and insecure remote HTTP fail configuration;
- independent retirement calls reach the provider concurrently;
- health transitions without identity or cryptographic material leakage;
- production isolation, valid HTTPS/two-copy staging wiring, and staging fail-fast behavior;
- standalone and authoritative Schema parity for the signed conflict receipt.

Full Resource Gateway `clean verify` executes 2898 tests with zero failures and errors, two existing
browser-environment skips, and a successful executable-JAR package. Independent test-kit
`clean verify` executes 228 tests with zero failures, errors, or skips and passes ordinary/shaded
JAR, authoritative Schema packaging, and strict public Javadoc gates.

## 11. Deliberately unclaimed and next root gap

This implementation verifies a trusted authority's signed WORM assertions. It does not prove that:

- endpoint labels correspond to physically independent accounts, regions, operators, or vendors;
- configured public-key history was externally published without rollback or equivocation;
- legal hold dominates retention expiry and governed erasure;
- provider replicas, backups, and recovery copies follow the same retention decision;
- disaster recovery preserves lifecycle generation continuity;
- a provider continues to retain an object after its short admission receipt expires;
- every externally written object has a corresponding committed local receipt set.

The read-only signed inventory protocol is now implemented as the first half of external orphan
reconciliation. It challenge-binds each page, pins an immutable snapshot, signs complete count/root,
and exposes no destructive operation; see
[Stage 5 observation external inventory protocol](resource-gateway-execution-data-control-plane-stage5-observation-external-inventory-protocol-design.md).
The remaining root gap is durable multi-replica cycle ownership, exact page staging, final root
replay, bidirectional local comparison, and governed finding lifecycle. Legal hold, erasure,
backup/DR continuity, witnessed non-equivocation, scheduler/readiness, and production capability
remain later gates.
