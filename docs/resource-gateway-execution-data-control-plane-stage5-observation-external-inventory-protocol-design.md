# Stage 5 observation external inventory protocol design

**Implemented read-only transport protocol (2026-07-20): every configured external archive
authority can expose a challenge-bound, Ed25519-signed, immutable paged snapshot over the same
strict HTTPS trust topology used for WORM admission. Snapshot identity commits authority topology,
snapshot time, complete object count, and an order-sensitive root. No delete or retention-mutation
operation exists. Durable multi-replica cursoring and governed finding lifecycle are the next
increment, so orphan reconciliation is not yet claimed complete.**

## 1. Strongest judgment

An object-list endpoint is not reconciliation evidence. A mutable list paged by offset can skip or
duplicate objects while writes continue; an unsigned continuation cursor can be replayed or rebound;
and a partial scan cannot prove that an expected object is missing. Turning such a list directly
into governance findings would manufacture certainty from transport timing.

The transport must first establish five invariants:

1. every page answers one fresh, short-lived request;
2. all pages belong to one immutable provider snapshot;
3. the cursor advances strictly by object id and page sequence;
4. the final ordered item chain reproduces a signed total count and root;
5. the read boundary has no destructive operation, even for a caller with inventory access.

This increment establishes those invariants. It does not yet own durable cycle leases, persisted
page progress, local/remote classification, finding lifecycle, or scheduling.

## 2. Boundary and ownership

| Component | Owns | Does not own |
| --- | --- | --- |
| Inventory authority | immutable snapshot creation, ordered pages, signed count/root | local finding policy, deletion |
| HTTPS adapter | fresh requests, bounded transport, strict parsing, topology/key verification | durable cursor, retries, remediation |
| Reconciler, next increment | database lease, page staging, root completion, local comparison, findings | WORM deletion or retention shortening |
| Governance/ANEKE | finding disposition, owner workflow, legal policy | forging remote completeness from partial pages |
| Storage administrator | provider retention and separately governed erasure | Resource Gateway inventory protocol semantics |

The Java boundary is
`TestSuiteStabilityObservationExternalArchiveInventoryAuthority`. Reflection tests verify that it has
no method containing delete, purge, overwrite, or shorten semantics. This is structural separation,
not an authorization convention that a later code path can accidentally bypass.

## 3. Protocol values

### 3.1 Inventory request

`ExternalArchiveInventoryRequest.v1` binds:

- trust domain, archive set, and exact authority;
- empty snapshot/object cursors and page sequence zero for the first page;
- exact snapshot id, last object id, and increasing page sequence for a continuation;
- a page size from 1 through 500;
- fresh 256-bit challenge entropy;
- whole-second request time and an exclusive response deadline no more than 60 seconds later;
- a canonical fingerprint over every field except the fingerprint itself.

Mixed cursor states are invalid. A caller cannot use a snapshot without an object cursor, skip page
sequence zero, or issue a continuation without pinning both identities.

### 3.2 Inventory item

`ExternalArchiveInventoryItem.v1` is payload-free comparison material:

- deterministic WORM object id and retention-bearing object commitment;
- retirement and compact-segment ids/fingerprints;
- immutable retention-policy fingerprint;
- retain-until and stored-at times;
- canonical item fingerprint.

It never contains retired observations, graph inputs/outputs, credentials, provider diagnostics, or
storage addresses.

### 3.3 Signed page

`ExternalArchiveInventoryPage.v1` carries the exact request plus:

- configured authority, failure domain, and signing key;
- deterministic snapshot id and whole-second snapshot time;
- complete snapshot object count, capped at one billion;
- complete order-sensitive snapshot root;
- up to 500 strictly increasing items after the request cursor;
- either an empty terminal cursor or the exact final item id as continuation cursor;
- short issue/expiry window and Ed25519 signature over the canonical page material.

An incomplete empty page is forbidden. A terminal page has no continuation. Item time cannot be
after the pinned snapshot boundary.

## 4. Snapshot identity and completeness

The initial root is domain-separated:

```text
sha256("...ExternalArchiveInventoryRoot.v1:empty")
```

Each ordered item advances the root with a canonical `RootLink.v1` containing the previous root and
exact item fingerprint. The deterministic snapshot id is then derived from:

```text
trustDomain + archiveSetId + authorityId + failureDomain
+ snapshotAt + completeObjectCount + finalRoot
```

This prevents root, count, authority, failure-domain, or time substitution while retaining compact
continuation requests. Every page repeats the same snapshot identity facts. The adapter verifies the
identity on every page; the future durable reconciler must independently replay all staged item
fingerprints and compare the final count/root before publishing any missing-object conclusion.

The authority can still lie consistently about its own storage. Cryptography proves what the
configured authority signed, not physical truth. Independent provider controls, account boundaries,
audit logs, and witnessed publication remain deployment/governance requirements.

## 5. HTTPS transport

Inventory uses the already configured exact endpoint with a distinct media type:

```text
application/vnd.bloge.suite-stability-observation-external-archive-inventory.v1+json
```

The request protocol header is `ExternalArchiveInventoryRequest.v1`; a successful response header
is `ExternalArchiveInventoryPage.v1`. The adapter enforces:

- HTTPS outside the existing test-only loopback exception;
- no redirects and no automatic retries;
- the existing 100 ms through 30 second request timeout;
- acceptance of pre-generated immutable snapshots only within the configured one-second through
  seven-day age bound (300 seconds by default);
- request size no larger than 2 MiB and response size no larger than 2 MiB;
- exact media type and protocol header;
- duplicate, unknown, and trailing JSON rejection;
- exact endpoint authority/failure-domain binding;
- deterministic snapshot-id recomputation;
- request, item, and page fingerprint verification;
- issue/expiry admission and configured Ed25519 key lifecycle verification.

HTTP 410 maps only to `SNAPSHOT_EXPIRED`. Other non-200 responses are `UNAVAILABLE`; malformed or
untrusted 200 responses are `INVALID_PAGE`. Exceptions are payload-free and do not disclose endpoint,
object, key, signature, or remote response details.

## 6. State and continuation semantics

| Current cursor | Accepted page | Successor |
| --- | --- | --- |
| empty, sequence 0 | signed first page | pinned snapshot + final item + sequence 1 |
| pinned continuation | same snapshot, strictly later items | next exact cursor |
| any cursor | terminal page | no successor |
| pinned continuation | HTTP 410 | abandon or restart under durable policy |
| pinned continuation | different snapshot | invalid evidence |
| any cursor | replayed old-request page | invalid evidence |

`Cursor.after(page)` refuses a terminal page. The adapter performs exactly one remote read per call;
retry, lease ownership, and snapshot restart policy belong to the durable reconciler.

## 7. Failure and attack analysis

| Threat/failure | Root cause | Implemented control |
| --- | --- | --- |
| offset pagination drift | mutable list paged without snapshot | immutable snapshot id/time/count/root |
| page replay | response accepted without fresh request | exact challenge-bound request embedded and fingerprinted |
| snapshot switch mid-scan | continuation trusts provider cursor | caller pins snapshot id; adapter rejects drift |
| object omission/duplication | pages checked independently | strict object cursor plus final count/root replay requirement |
| reordered items | commutative inventory digest | order-sensitive root links and strict lexical order |
| root substitution | snapshot id independent of root | deterministic id commits root and count |
| proxy/provider 200 forgery | status mistaken for trust | configured Ed25519 signature and exact topology |
| parser confusion | permissive JSON | duplicate/unknown/trailing rejection |
| oversized inventory | unbounded response memory | 2 MiB streamed limit and 500-item protocol page |
| on-demand full scan overload | every request forces provider-wide enumeration | bounded pre-generated snapshots are accepted |
| stale pre-generated snapshot | old but correctly signed inventory masks recent state | local maximum snapshot age, future and over-age snapshots fail closed |
| stale continuation | provider no longer holds snapshot | closed 410 `SNAPSHOT_EXPIRED` family |
| accidental remediation | inventory client also owns delete | destructive operations absent from interface |

## 8. Executable evidence

The focused transport/schema gate executes 34 tests with zero failures, errors, or skips. Eleven new
real-HTTP and protocol tests prove:

- two-page snapshot continuity, stable authority order, signed count, and complete root;
- old-challenge replay and mid-scan snapshot drift rejection;
- deterministic snapshot-id rejection after root substitution;
- invalid signatures cannot create inventory evidence;
- unknown fields, duplicate fields, and oversized responses fail closed;
- HTTP 410 maps to snapshot expiry without remote diagnostics;
- exact response expiry is exclusive;
- a snapshot exactly at the configured maximum age is accepted and one second older fails closed;
- mixed and non-advancing cursors are rejected;
- roots are ordered and item fingerprints are canonical;
- the inventory interface exposes no destructive operation.

Standalone and authoritative Draft 2020-12 Schemas freeze all three inventory values alongside the
existing archive request, receipt, signed conflict, and receipt set.

Full Resource Gateway `clean verify` executes 2909 tests with zero failures and errors, two existing
browser-environment skips, and a successful executable-JAR package. Independent test-kit
`clean verify` executes 228 tests with zero failures, errors, or skips and passes ordinary/shaded
JAR, authoritative Schema packaging, and strict public Javadoc gates.

## 9. Deliberately unclaimed and next root gap

This increment makes remote inventory pages trustworthy enough to consume. It does not yet:

- persist a multi-page cycle or recover it after process failure;
- fence two replicas from advancing the same authority cursor;
- normalize committed local object/authority identities;
- classify `COMMITTED`, `PENDING`, `ORPHAN`, `CONFLICT`, and `UNKNOWN`;
- detect a locally acknowledged object missing before retain-until;
- create, reopen, transition, resolve, retain, or export governed findings;
- expose aggregate reconciliation health or schedule bounded work.

The next increment must add a database-clock lease and epoch per authority, persist every verified
page before cursor advance, resume exact snapshot progress after crash, verify final count/root, and
compare both remote-to-local and local-to-remote. Findings must be durable and payload-free. The
repository and service still must not contain any external delete method; remediation remains an
ANEKE/governance workflow with a separately controlled storage authority.

The first prerequisite is now implemented: every accepted authority receipt is normalized into a
payload-free expected inventory item in the exact retirement transaction, with bounded historical
backfill and exact-replay repair. See
[Stage 5 observation external reconciliation control plane](resource-gateway-execution-data-control-plane-stage5-observation-external-reconciliation-design.md).
Remote cycle leases, page staging, final-root replay, comparison, and findings remain unimplemented.
