# BLOGE Resource Gateway

**Turn external APIs into schema-checked building blocks for business workflows.**

Resource Gateway is a Spring Boot example that shows how BLOGE can turn messy
provider integrations into visible, reusable orchestration. Instead of writing a
new Java operator for every upstream endpoint, teams describe resources with
`ResourceDescriptor`, compose them in `.bloge` graphs or the visual canvas, and
let the same schema contract drive validation, simulation, runtime execution,
and reuse.

The interesting part is not "calling HTTP". The interesting part is making API
integration something the business flow can see, reason about, test, and change.

Resource Gateway 1.4.4 also exposes an authenticated, stateless MCP Agent TDD surface at
`POST /mcp`. It supports contract-first library and Tool authoring, approved GOLDEN
cases, isolated zero-egress RED/GREEN execution, content-addressed evidence history, governed Fixture
promotion, immutable Tool publication, and a structure-only review board with separately authenticated
no-store review details at
`/agent-tdd.html`. See the
[Agent TDD MCP guide](../docs/resource-gateway-agent-tdd-mcp.md) for startup,
purpose mapping, workflow, review endpoints, publish gates, and verification commands.
The repeatable [real Codex certification script](../scripts/certify-agent-tdd-codex.sh) requires a
clean commit, performs a clean package, starts that exact JAR as an owned loopback process with ephemeral credentials,
copies it atomically into a private read-only immutable path, and verifies a one-run nonce plus the commit and
the running process's self-computed JAR digest before and after Codex. The cleanup trap is installed before any
temporary directory or authentication copy is created. Codex receives an isolated auth-only
home; the OS policy denies child-process execution, repository/private-state reads, and writes outside its disposable
runtime while non-MCP features are explicitly disabled. The reducer rejects
every non-MCP action and correlates only post-upsert evidence for one accepted candidate through Tool and CaseSet
creation. It then requires two governed branch cases and the same Tool's structure-only board to expose a
business flow plus a decision table with at least two reviewable branches before emitting a payload-free
certificate containing only ephemeral HMAC identities. The reviewed
example certificate is checked in at
[`docs/acceptance/agent-tdd/codex-certification-v1.json`](../docs/acceptance/agent-tdd/codex-certification-v1.json).
The 41-tool catalog includes `rg.dsl.reference.get` plus the four-entity solution authoring
operations: `rg.feature.define`, `rg.scenario.define`, `rg.instruction.define`, and
`rg.solution.compose`. Each mutation uses the existing exact-response idempotency authority and
stores canonical revisions within the authenticated tenant/project/environment scope. Solution
composition resolves every direct Feature, root Scenario, and Instruction reference before it
persists a pure-function projection; unresolved references fail closed rather than surfacing as a
later DSL surprise. Codex first receives a scoped, versioned,
graph-only and payload-free syntax/contract/example snapshot, then previews and gates the exact source
against that immutable authoring context. Compose accepts the DSL envelope plus its context and receipt
fingerprints rather than trusting a client-authored `GraphDraft`; the mutation reruns the production
parser/compiler/linter/projection pipeline and persists only the matching server projection. Business users
state intent, rules and expected outcomes; they are not asked to write DSL or interpret compiler prose.
Solution WRITE effects remain contract-shaped stubs during GREEN baselines. Unbound effects become
payload-free engineering handoffs; the platform-only `AGENT_TDD_WRITE_EXEC` purpose is absent from MCP,
restricted to sandbox environments, durably reserved before execution, and followed by an exact downstream
reconciliation adapter read. Solution publication binds logic GREEN, implementation binding, reconciliation,
and independent owner signoff to the current Solution revision, contract fingerprint, GOLDEN set, evidence
fingerprint, and current Instruction implementation fingerprint, so an old reconciliation or signoff cannot
authorize a changed binding. Compose persists a server-issued authoring receipt, and commit rejects invented or
stale receipt values.
Design-only platform Features follow the same contract-first handoff. An authoring Agent can call
`rg.feature.handoff` for an unbound Feature, but cannot bind its evaluator. A separately authenticated
feature engineer uses `POST /api/agent-tdd/feature-handoffs/{featureRef}/fulfil` with purpose
`AGENT_TDD_FEATURE_ENG`; Resource Gateway records `IMPLEMENTED`, validates the controlled fixture output
against the declared Feature type, and promotes only a passing implementation to `VERIFIED` / `READY`.
Interactive user Features remain ready through their native component or conversation contract and do not
receive an evaluator binding.
Human reviewers can open `GET /api/agent-tdd/solutions/{solutionRef}/board` with the separate
governance credential. The no-store response joins the approved business cases with payload-free
execution evidence and returns five business panels: rule matrix, dispositions, red/green cases,
Feature cards, and publication gates. It never returns DSL, graph drafts, evaluator references, or
Instruction bindings.
The compiler validates named ports, required bindings, output paths, and provable literal/type contradictions
for all visible operators, including built-in `bloge:*` nodes. Known `session` and `state_machine` roots receive
an explicit graph-profile diagnostic even though the pinned BLOGE 0.8.9 parser has no corresponding AST.
Operator repair candidates are authorized name matches only; the Agent must still read and satisfy the returned
contract instead of treating a similar name as proof of compatibility.
Preview is bounded to 512 KiB, 5 seconds, 25 diagnostics per phase and 100 total. Per authenticated identity,
MCP defaults to 120 total calls/minute, 60 reference calls/minute, and 30 shared preview/gate calls/minute
with four concurrent authoring calls; expired rate windows and fully released identity semaphores are evicted so
historical clients cannot permanently consume admission capacity. The `RG_AGENT_TDD_MCP_*` settings may lower those limits.
Micrometer publishes payload-free reference size/result, preview acceptance/phase/duration, safe diagnostic,
round-trip, stale-context, and limiter-rejection measurements. Labels are restricted to server-owned states,
diagnostic codes, and catalog tool names; identity, source, fingerprints, operator references, and exception prose
are never metric labels. Cross-call repair rounds remain a Codex trace-certificate fact rather than creating
session state in the stateless MCP server.
Sandbox attestation also rejects stable or mixed DNS answers in private and RFC special-purpose ranges, including
documentation, benchmark, protocol-assignment, discard-only, transition and reserved addresses.
The board derives each Tool's position and next business action along the five-act journey from
existing readiness and case-set facts; it does not create a second workflow state or perform extra reads.
Decision-table nodes are projected as business-readable rule matrices with a fixed prose flow summary;
operator references remain available in a collapsed technical view. The adjacent fact-coverage view
reuses the scenario enumerator's bounded predicate domains to show ACTIVE GOLDEN coverage and up to
twenty deterministic blind spots without inventing values for opaque predicates. Opaque predicates
without author samples are reported as unknown dimensions with incomplete coverage; they can never
collapse to a misleading `0 / 0` "fully covered" projection.
Its authenticated `GET /api/agent-tdd/library-overview` projection places business-readable platform
building blocks beside the current scope's declared world model. Imported operations are marked
`草稿世界观 · 待用例检验` or `已接入`, while business types are derived only from declared output schemas; the projection is
payload-free and returned with `no-store` rather than sampling fixtures or provider responses. Its second-act
sample list reads only governed Fixture descriptors. `rg.fixture.provide` validates a supplied sample against
one exact operator output port, then server-derives its Fixture id, scope, schema reference, retention and
SAMPLE lineage; the MCP response never echoes the sample value.
The repository launcher keeps Correctness authoring and encrypted Fixture material opt-in. When both are
enabled for the local Agent TDD guide and no key ring is injected, it creates one private AES-256 demo key
under `target/example-secrets`, reuses it across restarts, reasserts mode `0600`, rejects symlink secret
directories and key paths, and never prints it. Codex receives only the four
`AGENT_TDD_*` purposes; the service derives the narrower Fixture-material write purpose after governance
authorization instead of exposing that credential to the Agent.
When a bound read dependency does not exist yet, `rg.tool.compose` returns
`RESOURCE_NOT_REGISTERED` with every missing resource id in payload-free error details. The authoring-only `rg.resource.declare` bridge registers an allowlisted
`GET`, `HEAD`, or `OPTIONS` descriptor together with its visual design contract; write methods fail
closed until a sandbox substitute and reconciliation contract exist.
Agent TDD resource declaration and sandbox attestation share an exact-host egress policy configured by
`RG_AGENT_TDD_ATTEST_ALLOWED_HOSTS`. It rejects wildcard/suffix matching, user-info, authority templates,
non-HTTP schemes and an empty allowlist; the local default admits only `localhost` and `127.0.0.1`.
Attestation resolves the admitted host twice, rejects empty, changing, mixed public/private and non-routable
answers, and checks the exact address set again before each case. Explicit `localhost` and `127.0.0.1` entries
remain local-sandbox exceptions; a different hostname that resolves to a private or loopback address is rejected.
After a durable logical GREEN, the platform-only `AGENT_TDD_ATTEST` boundary automatically runs the same
approved ACTIVE cases against frozen, descriptor-backed `READ_EXTERNAL` dependencies. It is not an MCP tool:
WORKLOAD callers cannot choose real inputs, bindings, URLs, or execution mode. Production environments and
external writes fail closed. Each real run binds immutable operator snapshots and exact resource descriptors;
the HTTP operator checks the descriptor during admission and again when the rendered request enters the HTTP
transport, closing registry-replace races. The production HTTP client never follows redirects, so an approved
descriptor cannot transfer a request or credential to a second, unapproved host. The resulting `ATTESTATION` stores only per-case Oracle booleans,
HTTP-transport dispatch counts by dependency, environment,
and contract/implementation/evidence fingerprints. The platform commits the reservation before leaving the
database transaction for the real read, then completes it in a separate transaction. Exact completed retries
replay without repeating the read; an unfinished crash residue is projected as `RECOVERY_REQUIRED` for human
recovery. Each HUMAN/USER board confirmation commits a new attempt revision before the external execution, so
a lost recovery process does not permanently reuse its unfinished reservation. Publication and
readiness require current logical GREEN, current `ATTESTED` evidence, and an independently approved owner
signoff; graph, case-set, binding, implementation, or descriptor drift invalidates the prior attestation.
For opaque decision predicates, ACTIVE GOLDEN input values are the author-owned representative samples used by
the board's fact-coverage projection; without those approved values the dimension remains explicitly unknown.
The local demo resolves distinct WORKLOAD and HUMAN credentials; proposals persist proposer identity and
fingerprint, approval enforces maker-checker separation, and Codex never receives the reviewer credential.
The application and repository launcher bind the demo to `127.0.0.1` by default; only an explicitly hardened
deployment should set the launcher's `RESOURCE_GATEWAY_ADDRESS=0.0.0.0` or Spring's `SERVER_ADDRESS` directly.
External PostgreSQL deployments apply the versioned Correctness migrations from `V20260815_005` through
`V20260816_010` and `V20260903_020__agent_tdd_runtime.sql` through the deployment migration system. The
embedded-H2 launcher executes and checksums the complete authoring migration set, including those Correctness
tables; external data sources never receive application-startup DDL.
Its scenario compiler covers the complete seven-behavior dependency vocabulary through the shared
isolated testing kernel, and its deterministic decision-table enumerator implements the bounded
comparison, range, membership, otherwise, and author-sampled opaque grammar.

For the user-facing path from OpenAPI import through Default Fixture simulation,
multi-API Tool composition, whole-Flow Fixture authoring, and automatic simulation,
see the [API, Fixture, and Tool authoring guide](../docs/resource-gateway-api-fixture-tool-authoring-guide.md).
For a terminal-only, executable customer-retention scenario covering three API Resources,
BLOGE-DSL-first DAG composition with immutable dependency pins, caller-selected Fixture Cases,
Tool Fixture authoring, and immutable run evidence,
see the [curl Fixture and Tool demo runbook](../docs/resource-gateway-curl-fixture-tool-demo-runbook.md) and
[`scripts/curl-caller-directed-fixture-demo.sh`](../scripts/curl-caller-directed-fixture-demo.sh). Use the matching
[`scripts/start-caller-directed-fixture-demo.sh`](../scripts/start-caller-directed-fixture-demo.sh) and
[`scripts/stop-caller-directed-fixture-demo.sh`](../scripts/stop-caller-directed-fixture-demo.sh) wrappers to start
the local H2-backed service with the required authoring flags and purpose, then stop the exact managed process.
The DSL save endpoint accepts only the lossless reusable-Flow subset and exact immutable dependency pins;
unsupported syntax, semantic drift, mixed round-trip diagnostics, and malformed wire fields fail closed.

## What You Get

| Capability | Why it matters |
| --- | --- |
| Descriptor-first resources | Add most APIs by changing contracts, not cloning Java operators |
| Graph-level contracts | Every built-in resource graph exposes formal input/output JSON Schema for system integration |
| Schema-aware canvas | Drag, connect, validate, simulate, and publish under server-side schema checks |
| Recoverable authoring sessions | SHA-256 coordinated recovery snapshots, tenant/environment partitioning, safe cross-workspace navigation, explicit Save, stale-receipt fencing, and an injectable encrypted host store |
| Idempotent Graph saves | Content-addressed client attempts, versioned canonical commands, database-serialized cross-replica create/update, exact restart-safe receipts, and explicit same-key drift rejection |
| Contract and Scenario authoring protocol | Versioned Contract/Scenario drafts, exact target and contract fingerprints, strict schemas, deterministic transient compilation, and fail-closed routing of advanced dependency behavior to the governed testing control plane |
| Atomic example workspaces | Complete Graph/Contract/Scenario/fixture seeds, one-click sandbox preview, idempotent test/staging Workspace fork, exact coordinate rebind, and payload-free receipts without first-save rebase |
| Runtime-backed demos | Local upstreams, real gateway execution, mock simulation, SSE examples, and reusable publications |
| Schema-gated table tests | Run 14 built-in cases across all seven resource graphs with F3 transport fixtures, bounded retry consumption, coverage gates, and fidelity evidence |
| Isolated testing control plane | Test/staging-only graph/operator discovery, validator-proven boundary-case planning, reviewed plan-to-suite materialization, immutable fixture registry, caller-driven DAG and operator micro-graph execution, attempt/occurrence-specific doubles, sanitized evidence retention, batch runs, and production control-field guard |
| Explicit execution modes | The unified test kernel now freezes the seven-mode execution vocabulary per selected rule; descriptor protocol and descriptor transport take separate fail-closed paths, and transport fixtures run the real request mapping and response interpretation with zero network |
| Header-isolated test admission | Authenticated test/staging execution can carry a bounded inline fixture in `X-BLOGE-Test-Inline`; all body-bearing testing routes authenticate before DTO parsing, while production execution routes and visual simulation reject test controls at both the servlet boundary and service boundary |
| Durable stability jobs | Authenticated non-blocking submit/query/cancel protocol, deterministic idempotency, database capacity/fairness/deadline control, transaction-bound payload-free cancellation audit, lifecycle views, opt-in current-authority worker, and honest capability discovery |
| Governed run controls | Absolute deadline, monotonic remaining-budget propagation, fenced cancel, durable owner lease/epoch, cross-instance commands, and automatic signed evidence recovery after owner failure |
| Auditable external writes | Versioned write contracts, binding/activation conformance, execution-scoped journal, commit receipts, UNKNOWN_COMMIT DAG guard, and signed reconciliation evidence |
| Dynamic workload identity | Atomic JWKS/revocation refresh, zero-restart key rotation, bounded propagation SLO, group/clearance/delegation claims, and explicit 401/503 semantics |
| Managed evidence signing | Non-exportable KMS/HSM provider protocol, atomic public-key generations, locally verified signatures, rotation/revoke semantics, and machine-readable custody health |
| Consistent draft export | Frozen operator/library/binding/activation/test-suite refs, deterministic dependency fingerprints, and retryable 409 conflict on assembly-time drift |
| Governed capability closures | Sealed Resource/Operator/Graph projections, exact cycle-checked closure for all seven shipped graphs, nested foreach/loop boundary inventory, full enterprise scope, append-only lifecycle revisions, classification-aware reads, and honest mirror readiness flags |
| Business Mirror workspace | The default product entry turns shipped Graph/Contract facts into a searchable Package portfolio, six readiness tasks, an L0-L3 capability map, exact lineage, durable guided edits, and deterministic compilation; English, Simplified Chinese, responsive layouts, and a serverless VS Code fixed task share the same fail-closed domain semantics |
| Capability Proposal authoring | Missing business capabilities have durable, Scope-isolated drafts, immutable history, optimistic revisions, restart-safe exact receipts, strict offline verification, and a fixed `SIMULATION_ONLY` binding that cannot request real network, credentials, or egress |
| Governed capability observations | Signed payload-free invocation facts, operator-owned admission policy, external vault/proof verification, durable admitted-or-quarantined decisions, full-scope idempotency, and independent offline verification |
| Governed capability corpora | Immutable quarantine review, exact admitted-source candidates, metadata risk gates, independent owner-reviewed publication lineage, second source-authority verification, and honest resolver readiness |
| Governed scenario rehearsal | Append-only Scenario assets, exact compilation, durable per-case execution, independently signed aggregate and batch evidence, multi-hold retention/deletion proof at both levels, deterministic ANEKE workbook seeds, separate opt-in regional DAG/KMS schedulers, and a server-authorized two-person remediation transaction kernel |
| Reconstructable domain fidelity | Owner-approved content-addressed coverage inventory, append-only full-scope persistence, managed signed payload-free seven-dimension profiles, protected register/read APIs, independently re-verified Scenario projection, v3 signed read-only Shadow comparison with exact normalization/source-resolution and double-observed online-authority closure, a protected full-scope durable sample-ordinal queue, append-only lifecycle API, optional bounded scheduler, owner/epoch fenced worker kernel, a governed fail-closed data-plane composition with double-observed grant/kill-switch/egress authority and isolated connector boundaries, signed authoritative-outcome observations with pre-treatment cohort proof, event-time delayed/censored/conflicting reconciliation and an independently supplied business-authority verifier, typed dynamic readiness, fail-closed freshness/abstention/low-sample semantics, Wilson 95% confidence, exact source lineage, and independent Test Kit verification without a composite score |
| Runtime certification | Plan-first, non-production-only fault certification with a fixed 12-scenario denominator, single-use external authorization, customer-owned environment adapters, epoch-fenced PostgreSQL journal, recovery SLO enforcement, independently signed reports, and self-contained payload-free replay bundles |
| ANEKE Package integration | Additive protocol 1.1 registry-ingest bundles, exact Package/Evidence closure, externally signed governance projections, monotonic generation fencing, freshness-aware authoring views, and independent offline verification without duplicating ANEKE registry or publish authority |
| Pilot acceptance protocol | A non-waivable ten-gate manifest binds the owner-frozen scenario denominator, exact evidence refs, customer observation window, and external acceptance decision without turning local fixtures into customer approval |
| Stateful mirror sessions | Versioned entity/write/session/checkpoint/write-attempt protocols, atomic multi-entity mutations, exact replay, AES-GCM isolated persistence, lease/fence/CAS concurrency, durable crash-window reconciliation, TTL/destroy, payload-free signed state evidence, signed same-data-plane restart recovery admission, ANEKE workbook seeds, and independently verified clients |
| Governed replay payloads | Payload values detached from immutable evidence, classification ABAC, selective retention, legal hold, bounded expiry, and signed deletion proof |
| Workbook and gate evidence loop | Deterministic sanitized workbook seeds, exact suite/run evidence refs, versioned gate decision basis, stale detection, and transactional gate events |
| Operational controls | Cache, tenant rate limit, circuit breaker, run history, golden cases, and publication history |

### API Resource and Connection authoring persistence, application tracers, and HTTP transport (J2/J3-C4)

The JDBC authoring store, first Resource application facade, and HTTP adapter are opt-in. The transport exposes
`PUT /api/authoring/resources/{resourceId}` only when `gateway.authoring.api-resource.enabled=true`; the default
application configuration remains disabled. The repository launchers `scripts/start-examples.sh` and
`scripts/start-visual-canvas-demo.sh` default both API Resource and reusable Flow authoring to enabled after the
required migrations are installed. For their embedded H2 database, the launchers install each migration once and
record its checksum in `rg_local_authoring_schema_migrations`. PostgreSQL migrations remain external. Set either
`RG_API_RESOURCE_AUTHORING_ENABLED=false` or
`RG_REUSABLE_FLOW_AUTHORING_ENABLED=false` to opt out for a launcher invocation.
Apply the migrations in order:

```text
db/postgresql/V20260830_001__api_resource_authoring.sql
db/postgresql/V20260830_002__api_resource_concurrent_staging.sql
db/postgresql/V20260830_003__api_connection_secret_staging.sql
db/postgresql/V20260830_004__connection_metadata_authority.sql
db/postgresql/V20260830_005__pending_secret_store_protocol.sql
db/postgresql/V20260830_006__pending_secret_store_hardening.sql
db/postgresql/V20260831_007__pending_secret_store_protocol_closure.sql
db/postgresql/V20260831_008__pending_secret_store_child_cas_closure.sql
db/postgresql/V20260831_009__authoring_command_attempt_authority.sql
db/postgresql/V20260831_010__attempt_provenance_closure.sql
db/postgresql/V20260831_011__api_resource_connection_snapshot.sql
db/postgresql/V20260831_012__api_fixture_set_authority.sql
db/postgresql/V20260831_013__authoring_simulation_runs.sql
db/postgresql/V20260901_014__reusable_flow_drafts.sql
db/postgresql/V20260901_015__reusable_flow_publications.sql
db/postgresql/V20260901_016__standalone_flow_fixture_sets.sql
db/postgresql/V20260901_017__fixture_share_requests.sql
```

Direct JAR or production launches still enable the wiring explicitly:

```bash
RG_API_RESOURCE_AUTHORING_ENABLED=true
# Optional: RG_API_RESOURCE_AUTHORING_LEASE_SECONDS=60
```

`GET /api/authoring/availability` remains available while the feature is disabled. The static workbench reads this
payload before rendering Resource or Flow mutation controls, so a default deployment shows an explicit setup message
instead of offering an action that can only return `404`. The endpoint exposes feature booleans only; it does not
report database, secret-provider, or identity details.

When enabled, missing migrations or compiler/readiness prerequisites fail startup; the runtime does not
silently fall back to the in-memory store. J2 covers scoped claim, committed reads, concurrent staging,
transactional stage/commit/fail, lease fencing, restart-safe history, and tamper rejection. The opt-in compiler
resolver/wiring now creates three `READY` exact-subject projections through the server-side `Connection` resolver,
shared Header/API-key policy, strict URI and JSONPath-to-runtime-dot-path mapping, with an explicit `visualadapter`
boundary. J3-A also accepts the pure Connection authority boundary: wire auth/secret variants, same-scope authorized
opaque references, CAS/fingerprint, secret-free views/errors, and HTTPS/header/timeout policy. Missing
resolver/readiness prerequisites fail closed; `FIXTURE_ONLY` and `MANAGED_WRITE` do not perform
real writes until the lossless runtime side-effect contract exists. V011 additionally binds every Resource
revision and its projection-set fingerprint to the exact committed Connection revision and metadata fingerprint
used for compilation. Because V001-V010 did not retain that historical fact, V011 intentionally fails on legacy
Resource rows rather than inventing a migration-time snapshot; such rows require a separately audited export and
re-authoring migration.

J3-C2 added `ApiResourceAuthoringFacade` for the first honest compound-save subset: one `EXISTING` committed
`Auth.NONE` Connection, one API Resource command, and initially `defaultFixture.kind=NONE`. It validates before claim,
resolves opaque historical Resource ETags, preserves receipt-first exact replay after heads advance, stages all
three `READY` projections, verifies that compilation used the same committed Connection snapshot seen during
preflight, and persists an exact `bloge.apiResourceSaveReceipt.v1` receipt. At that slice, nested Connection
`CREATE` and `FROM_EXAMPLES` returned typed capability-unavailable failures before claim; neither was ignored or
partially saved. C7 now enables `FROM_EXAMPLES` through the durable private Fixture child authority below. The
feature-scoped application configuration requires an explicit lifecycle-complete
`ApiConnectionAuthoringStore`, so enabling the feature without Connection authority fails startup.

The focused C2 command covering facade, same-database JDBC integration, adapter-side configuration, the visual
package boundary, projection compiler, Resource store contracts, V001/V002 readiness, and V011 readiness is
**100/100 green** with no failures, errors, or skips. Real PostgreSQL certification, HTTP/auth/problem transport,
credential-bearing Connections, default Fixture generation, and the authoring UI remain subsequent J3/U1 work.
The post-fix full gate also completed with `Tests run: 7,989; failures: 0; errors: 0; skipped: 33` and
`BUILD SUCCESS`.

J3-C3 adds the authenticated HTTP boundary for that exact C2 subset. The controller derives tenant/project/
environment and actor only from `IntegrationRequestAuthenticator`, requires purpose `API_RESOURCE_AUTHORING`,
requires one `Idempotency-Key`, and accepts only `If-None-Match: *` for create or one opaque strong `If-Match`
for update. Successful responses return the canonical receipt body plus `ETag`, `Idempotency-Replayed`,
`Cache-Control: no-store`, and `Pragma: no-cache`. Authentication, media-type, header, malformed-body, semantic,
CAS, conflict, integrity, and persistence failures all use `problem-detail-v1` rather than mixing transport error
shapes. Request decoding is strict at root and nested fields.

The combined C2+C3 focused gate completed at **141/141 green** with no failures, errors, or skips: the prior 100
application/persistence tests plus 28 controller tests, 2 transport configuration tests, and 11 protocol-schema
tests. That C3 slice deliberately rejected Connection `CREATE`; the current compound facade and object page now
accept a nested credential-free `Auth.NONE` Connection command without changing the HTTP envelope. C7 later enabled
`defaultFixture.kind=FROM_EXAMPLES`. Credential providers, reusable Flow/DAG
authoring, real PostgreSQL
certification, and UI acceptance remain open. The post-C3 full gate completed with
`Tests run: 8,021; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.

J3-C4 exposes the payload-free Connection tracer as `PUT /api/authoring/connections/{connectionId}` and
`GET /api/authoring/connections/{connectionId}` under the same opt-in feature flag and trusted
`API_RESOURCE_AUTHORING` purpose. Create and update use the same strict `If-None-Match: *` / single strong
`If-Match` protocol, `Idempotency-Key`, no-store responses, replay marker, and unified Authoring Problem Detail as
Resource authoring. Tenant/project/environment and actor remain derived exclusively from the verified integration
identity; malformed bodies, scope drift, invalid validators and unsupported media fail before the facade. Error
responses and committed Connection views never echo credential values or protected provider locators.

The production Connection application/runtime configuration now supplies one lifecycle-complete JDBC store and
facade when the feature is enabled. A dedicated V010 readiness gate checks immutable attempt authority, exact
Connection revision/head provenance, the `SUPERSEDED` lifecycle closure and recovery indexes; pre-V010, missing or
altered schema fails startup. The current tracer deliberately accepts only `auth.kind=NONE`; BEARER, BASIC and
API_KEY commands return a typed 424 before claim until a production external secret provider is wired. Connection
check, Default Fixture simulation and reusable Flow/DAG authoring remain subsequent slices.

The C4 focused gate completed at **145/145 green** with no failures, errors, or skips across Connection facade/JDBC,
Connection controller/configuration/runtime readiness, Resource transport regressions, protocol schemas and the
visual package boundary. The post-C4 serial `clean verify` completed with
`Tests run: 8,051; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.

J3-C5 adds `GET /api/authoring/connections` for the standard Connection picker. The endpoint derives one exact
tenant/project/environment scope from the verified integration identity and returns only current committed
`ApiConnectionView` records in stable `connectionId` order. Staged revisions and other scopes remain invisible;
the JDBC adapter applies the same attempt, receipt and metadata-integrity closure as a single Connection read.
No credential value or provider locator enters the list wire. The focused Connection authority/application/HTTP
gate completed at **186/186 green** with no failures, errors or skips. Explicit network/authentication check remains
a separate governed-egress slice; this list endpoint does not claim that a Connection is reachable. The post-C5
serial `clean verify` completed with `Tests run: 8,055; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.

J3-C6 freezes `bloge.connectionCheckCommand.v1` and `bloge.connectionCheckResult.v1` and exposes
`POST /api/authoring/connections/{connectionId}:check`. `NETWORK_ONLY` resolves one exact committed Connection and
passes only trusted scope/actor, Connection id/revision, validated base URI and bounded timeout to an explicit
`ApiConnectionCheckGateway`; credentials and business payloads are absent from that seam. Results contain only a
reachable/unreachable/blocked status, code-only stages and an exact egress decision identifier/fingerprint. The
default gateway is fail-closed with 424, so enabling authoring never causes an implicit outbound call. Hosts may
install one governed provider with destination authorization, DNS-rebinding resistance, TLS policy and durable
audit. `SAFE_READ` is frozen in the command schema but returns 424 until the same-Connection READ_ONLY Resource
execution path can enforce simulation-equivalent authorization and redaction. The focused protocol/application/
transport/authority gate completed at **206/206 green** with no failures, errors or skips. The post-C6 serial
`clean verify` completed with `Tests run: 8,065; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.

J3-C7 began the Default Fixture vertical slice with one pure, schema-closed materialization authority.
`DefaultFixtureSetMaterializer` converts an exact committed API Resource revision plus an ordered
`FROM_EXAMPLES` selection into one immutable `PRIVATE_DRAFT` Fixture Set. Each selected example becomes one
Subject `RETURN` control with inline output material; Case inputs, Case mappings and order remain exact. Unknown,
empty or duplicate selections fail closed. The server-derived identity is `{resourceId}:r{resourceRevision}` so
it remains inside the frozen public identifier alphabet; exceptionally long resource ids use an exact Resource
fingerprint fallback. Command, full View, metadata-only Summary and Save Receipt all validate against the frozen
JSON Schemas and round-trip through Jackson. The first C7 commit was deliberately authority-only. The focused
materializer/schema gate was **16/16 green**
with no failures, errors or skips. The post-C7 serial `clean verify` completed with
`Tests run: 8,069; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.

The next C7 persistence slice adds V012 identities/revisions/heads and a private Fixture child store. A
`FROM_EXAMPLES` Resource save now validates named examples before claim, stages the generated Fixture Set,
commits Fixture and Resource in one application transaction, and makes the child readable only after the exact
outer `bloge.apiResourceSaveReceipt.v1` is committed. Replay rematerializes the selection and checks the exact
immutable Fixture revision; altered generated JSON, V012 authority columns, or outer receipt fail closed. Missing
V012 prevents opt-in startup. No standalone Fixture write endpoint, share flow, simulation compiler, or object page
is claimed by this slice. Fixture reads close the exact committed Resource subject revision and outer
command-attempt authority; damaged authority fails with integrity instead of appearing absent. V012 readiness
checks the active schema's columns, keys, indexes and exact CHECK definitions, and child commit requires the
coordinator transaction bound to the store's own DataSource. The focused schema/materializer/facade/store/
configuration gate is **70/70 green** with no failures, errors or skips. The wider gate including the shared
Connection readiness contract is **98/98 green** with no failures, errors or skips. The post-persistence C7
serial `clean verify` completed with `Tests run: 8,090; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.

J3-C8 starts the user-visible Fixture access path without adding a second persistence model. Authenticated
`GET /api/authoring/fixture-sets/{fixtureSetId}?revision={revision}` returns the current or exact committed private
Fixture revision, while `GET /api/authoring/fixture-sets` requires all four Exact Subject coordinates and returns
metadata-only summaries. Both routes derive tenant/project/environment from the trusted integration identity,
use no-store responses, and fail closed on malformed coordinates, missing objects, or damaged persisted authority.
The feature-scoped application configuration requires the V012 authority store instead of silently disabling the
read module. The focused new/affected transport gate is **63/63 green** with no failures, errors or skips. This
slice's serial `clean verify` completed with `Tests run: 8,101; failures: 0; errors: 0; skipped: 33` and
`BUILD SUCCESS`. It does not yet implement standalone Fixture writes, sharing, or
`POST /api/authoring/simulations`.

The next C8 tracer freezes the Java wire model for `bloge.simulationRequest.v1` and
`bloge.simulationRun.v1` and adds a deep `SimulationModule`. It resolves one exact private Fixture Case and its
exact committed API Resource subject, compiles the currently safe SUBJECT `RETURN`/INLINE shape, validates Case
input and returned output against the Resource contract, and records explicit `MOCKED`/`INLINE`/`FIXTURE` and
orthogonal contract/assertion/governance evidence. The run is idempotent by scope and `Idempotency-Key`, and exact
GET semantics are already represented by the immutable run store. This tracer performs no network call:
`AD_HOC`, `ALLOW_EXACT`, internal-node controls and governed material remain fail-closed until their own runtime
authorities exist. Request and run records round-trip against the frozen JSON Schemas and keep protected values
out of `toString()`. The focused module/protocol gate is **17/17 green** with no failures, errors or skips. This
authority-only step does not yet claim JDBC durability or the authenticated Simulation POST/GET adapter.

The durable C8 slice adds V013 `rg_authoring_simulation_runs`, a database-time leased JDBC run authority, and
feature-scoped runtime/application configuration. `POST /api/authoring/simulations` now executes the supported
Fixture Case tracer behind trusted `API_RESOURCE_AUTHORING` authentication and a required `Idempotency-Key`;
`GET /api/authoring/simulations/{runId}` reads the exact immutable completed run inside the verified scope. Both
routes are no-store, replay exposes `Idempotency-Replayed`, and the JDBC authority rejects altered fingerprints,
run JSON, status, scope or duplicate provenance. V013 readiness verifies the exact columns, primary/unique keys,
recovery index, status set, fingerprint shape and RUNNING/completed state closure without repairing schema. The
focused module/store/readiness/configuration/controller/schema gate is **34/34 green** with no failures, errors or
skips. The final serial `clean verify` completed with `Tests run: 8,122; failures: 0; errors: 0; skipped: 33` and
`BUILD SUCCESS`. `AD_HOC`, real external reads, governed Fixture material, internal-node controls, Reusable
Flow/DAG execution and the corresponding object pages remain fail-closed or unimplemented rather than silently
falling back to a different execution path.

Fixture Case execution now also closes the content-lifecycle gate before a run is claimed. A private inline Case is
runnable only while its committed revision is `PRIVATE_DRAFT`; protected material is runnable only from an exact
`TEAM_AVAILABLE` revision through the governed resolver. `SHARING_PENDING`, `STALE`, and `REVOKED` revisions are
rejected for both direct Simulation and parent-Flow `APPLY_CASE`, so a lifecycle transition cannot be bypassed by
holding an older Case coordinate. The focused Simulation/parent-Flow gate is **13/13 green** with no failures,
errors, or skips.

Caller-directed Simulation v2 S1 now freezes `bloge.simulationCommand.v2` and
`bloge.fixtureSetCommand.v2` without changing the v1 HTTP surface. Business `input` and `fixturePlan` are independent;
commands can name an exact Case, a stable condition, or unique automatic matching against an exact Fixture Set
revision and fingerprint. The closed condition language supports only `EQ`, `IN`, `PRESENT`, `ABSENT`, and bounded
`NUMBER_RANGE` over `$` or direct object-property paths. Scripts, regular expressions, environment access, runtime
Invocation Keys, mutable heads, credentials, and caller-supplied Fixture outputs are absent from the wire contract.
The exact v2 Subject union includes API Resource, Flow Draft/Version, Operator Version, and Built-in Function Version.
S1 originally executed only API Resource subjects; the later S3/S4 slices now add Flow DAG, Operator, and independent
built-in Function execution while stable Function Call Sites remain fail closed. The focused S1 plus v1
Fixture/Simulation compatibility gate was **66/66 green** with no failures, errors, or skips.

Caller-directed Simulation v2 S2a now freezes `bloge.simulationRun.v2` and stores immutable evidence for every
dynamic invocation. Each evidence entry has a server-generated Invocation Key, optional parent, exact target and
subject, real-or-mocked execution, selected Fixture Set revision/Case/fingerprint, behavior, fidelity, provenance,
payload fingerprints and a bounded egress decision. The four execution/assertion/contract/governance verdicts stay
independent; `READY` is rejected unless all four pass. The JDBC adapter reuses V013 and shares its scope plus
`Idempotency-Key` coordinate with v1, so one key cannot execute both protocol versions. v1 rows remain readable only
through the v1 store; damaged or unknown evidence fails closed. The opt-in runtime creates both authorities over the
same DataSource and transaction manager. The focused schema/store/configuration gate is **29/29 green** with no
failures, errors, or skips. This slice persists evidence only: API Resource behavior execution and authenticated v2
POST/GET are the next S2 commits.

The S6/S7 caller-facing slice now mounts one typed `Caller-directed simulation` panel on API Resource, reusable Flow,
Operator Fixture, and built-in Function Fixture objects. Input and Fixture Plan remain separate; authors can select
saved Case controls or exact per-target Case/Condition/Auto-match bindings, keep unmatched invocations blocked by
default, preview restricted conditions, and inspect server-owned Resolved Evidence with four independent verdicts.
Reusable Flows expose the whole Subject plus every static node path. Scenario compilation keeps generated
`dependencies=[]` fixture-free and projects only explicitly saved Return dependencies; inline outputs and runtime
Invocation Keys never enter the v2 command. The focused frontend gate is **14 files / 107 tests green**, and
`npx tsc --noEmit` succeeds. The production publication/runtime path still has to persist and consume compiler-owned
Function Call Sites; the UI intentionally does not fabricate them.
The final frontend build passes i18n **39/39**, UX **52/52**, host **21/21**, TypeScript, Vite and bundle gates;
AuthoringWorkbench starts at **201.83 KiB** and AuthorCanvas at **349.89 KiB**, below the 350 KiB budget. The serial
`mvn -f resource-gateway-examples/pom.xml clean verify` completed with `Tests run: 8,329; failures: 0; errors: 0;
skipped: 39` and `BUILD SUCCESS` in 11:42.

S2b connects that evidence authority to a deep API Resource runtime module. Exact Case, stable condition and unique
automatic matching all execute against the caller's business input. `RETURN` supports private inline and exact
governed material; `ERROR` and `TIMEOUT` produce deterministic failures without sleeping or exposing configured
messages; `REPLAY` resolves one exact authorized recording. Input and successful output are checked against the exact
Resource contract. Missing controls, unavailable material and unconfigured real reads are persisted as honest
`BLOCKED` evidence and never fall through to the network. Governed usage is projected only from committed COMPLETED
invocations through an idempotent `runId + invocationKey + asset` port, so exact replay does not inflate usage. The
S1/S2 module, store, schema and v1 compatibility gate is **62/62 green** with no failures, errors, or skips. Authenticated
v2 transport is still the remaining S2 boundary.

S2c closes that transport boundary without introducing a parallel endpoint. The existing authenticated
`POST /api/authoring/simulations` dispatches strictly by `schemaVersion` to v1 or v2, and
`GET /api/authoring/simulations/{runId}` checks the immutable v2 authority before the v1 authority. Both versions
derive scope and actor only from the trusted integration identity, require one valid `Idempotency-Key`, return
no-store responses and expose replay/run-id headers. v2 callers may use the dedicated
`AUTHORING_SIMULATION_RUN` purpose; existing `API_RESOURCE_AUTHORING` clients remain compatible. Unknown v2 fields,
caller-supplied Invocation Keys and incomplete commands fail before execution. Fixture subject, condition,
automatic-match, overlap, stale and material failures map to exact payload-free problem codes instead of a generic
server error. Optional protected-material, replay and usage providers remain fail-closed when absent. The focused
controller/configuration/v2-module gate is **20/20 green** with no failures, errors or skips.

S3 adds a dedicated Flow compiler/runtime rather than extending the API Resource executor with DAG concerns.
`FlowFixturePlanCompilerV2` loads an exact draft or published version, recursively pins nested Flow revisions,
builds bounded hierarchical `NODE_PATH` topology, rejects missing/cyclic/overlapping targets and preserves pinned
Fixture revisions. Condition and automatic selection run only after DAG mapping produces the actual node input.
`CASE_CONTROLS` becomes a fixed reusable tool plan, while explicit bindings may select different Cases for each API
node. `FlowSimulationModuleV2` assigns a fresh Invocation Key per dynamic node; nested Flow containers become parent
invocations, whole-Flow Fixtures suppress descendants, and unbound child Flows expand locally. Unmatched external API
nodes remain blocked with zero network access. Node and Flow input/output contracts are validated, and governed usage
continues through the committed invocation evidence seam. The focused S3 plus v1 parent-Flow compatibility gate is
**48/48 green** with no failures, errors or skips.

S4 adds one exact component authority shared by independent simulation and reusable-Flow compilation. Operator
subjects resolve an immutable Operator Library revision, canonical operator ref, contract fingerprint, and port-derived
input/output schemas. Built-in Function subjects resolve a catalog revision plus exact signature and runtime
fingerprints. Drift or a missing component is rejected before a run claim. The API Resource behavior executor is reused
for component `RETURN`, `ERROR`, `TIMEOUT`, `REPLAY`, contract validation, immutable invocation evidence, and
idempotency; no second mock runtime or material store was introduced. Reusable Flows can now place exact Operator
versions as DAG nodes and replace those nodes through `NODE_PATH` fixtures without executing the component. The focused
component authority, independent runtime, Operator DAG, configuration, and schema gate is **48/48 green** with no
failures, errors, or skips. Stable compiler-owned Function Call Sites, component Fixture Set authoring, UI/Scenario
bridges, and browser acceptance remain subsequent slices.

S5 now adds the compiler/runtime seam for exact built-in Function Call Sites. `StableFunctionCallSiteCompilerV2`
derives identity from a server-owned persisted authoring id plus callable, bindings, and input/output contracts;
layout and source diagnostics cannot change that identity. Semantic edits mint a new Call Site id, while unchanged
semantics preserve the previous id. Flow Fixture compilation accepts `CALL_SITE` targets only when the exact Operator
authority contains that id, rejects node/Call Site overlap, and resolves Fixture Sets against the built-in callable
rather than the owning Operator. `ComponentCallSiteRuntimeV2` intercepts every dynamic invocation immediately before
the real function call. Each retry or repeated invocation re-evaluates Fixture conditions against its actual input and
gets a fresh Invocation Key; two same-name calls in one Operator cannot share a Fixture accidentally. Missing production
component runtime support blocks honestly instead of falling through to a global function-name replacement. The focused
compiler/runtime/configuration/schema gate is **41/41 green** with no failures, errors, or skips. Durable publication of
the Call Site authority, a production Operator runtime adapter, standalone Operator/Function Fixture Set authoring,
UI/Scenario bridges, and browser acceptance remain subsequent slices.

The first component-Fixture application slice adds `ComponentFixtureSetModule` and
`ComponentFixtureSetMaterializer`. Exact Operator and built-in Function authorities can now be converted into private,
revisioned whole-subject Fixture material through the existing CAS/idempotency store contract. Inputs, inline Return
outputs, and expectations are checked against compiler-owned schemas; component Fixtures reject Node controls,
`APPLY_CASE`, and unsupported fidelity before persistence. The in-memory application gate is **3/3 green**. JDBC
subject-coordinate persistence, authenticated dispatch, and production discovery are intentionally the next slice.

Forward-only V019 now closes that persistence boundary. The existing standalone Fixture revision/head/command tables
retain their CAS and idempotency protocol while adding an exact subject discriminator, component member id, and Function
runtime fingerprint. Flow rows migrate in place; Operator and Function rows have database-enforced shapes and round-trip
through the same `FixtureSetAuthorityReader`. The authenticated Fixture PUT dispatches component Subjects to
`ComponentFixtureSetModule`, and list discovery accepts `subjectMemberId` plus the Function-only
`subjectRuntimeFingerprint`; malformed or incomplete coordinates fail before authority access. The combined S5,
JDBC/readiness, application, configuration, and transport gate is **76/76 green** with no failures, errors, or skips.

The first Reusable Flow slice now freezes the Java wire authority for
`bloge.reusableFlowSaveCommand.v1` and compiles it into one deterministic DAG plan. A Flow is explicitly a
`TOOL` or `SOLUTION`; every node uses an exact `API_RESOURCE` or immutable `FLOW_VERSION` coordinate. Direct
`FLOW_INPUT`, `NODE_OUTPUT`, and `CONSTANT` mappings are the only edge authority. The compiler resolves exact
dependency revisions and fingerprints, validates required target inputs and compatible direct schema paths,
derives a stable topological order, and fails closed on missing/drifted dependencies, duplicate targets, cycles,
invalid constants, output drift, or layout drift. Contract/dependency schema envelopes and constant JSON use
defensive copies. The focused compiler/protocol gate is **19/19 green** with no failures, errors, or skips. The
serial `clean verify` completed with `Tests run: 8,127; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.
This slice does not yet claim Flow draft persistence, authenticated save/read, immutable publication, Flow
Fixture simulation, or the Tool/Solution object pages.

The follow-up Flow draft authority adds `ReusableFlowModule` and a complete in-memory reference adapter for
revision/head/history, create/update CAS, actor-and-scope-isolated idempotency, exact committed replay, opaque
strong ETags, and server-generated stable draft identity. Invalid DAGs are compiled before an idempotency key is
observed. Replaying an already committed update is resolved before current-head CAS, while a new stale command
fails without occupying its key. Layout-only edits create a new revision and ETag but preserve the content
fingerprint; the full command still participates in the request fingerprint. `ReusableFlowDraft` and
`ReusableFlowSaveReceipt` round-trip against their frozen schemas. The focused compiler/module/protocol gate is
**24/24 green** with no failures, errors, or skips. JDBC durability/readiness and authenticated PUT/GET remain
the next slice; this reference adapter is not production persistence evidence. The serial `clean verify`
completed with `Tests run: 8,132; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.

The durable Flow draft slice adds V014 identities, immutable revisions, exact heads, and committed
idempotency commands behind the same `ReusableFlowDraftStore` contract. One local JDBC transaction commits
the revision, head, receipt, and command; exact replay is checked before current-head CAS, including simultaneous
same-key creates. Reads close draft JSON, receipt, content fingerprint, stable draft identity, revision, and strong
ETag, so damaged head/revision authority fails as integrity rather than appearing absent. The opt-in
`gateway.authoring.reusable-flow.enabled` runtime is disabled by default and fails startup unless the read-only
V014 readiness probe verifies all tables, primary keys, the exact head foreign key, and command expectation
closure. This slice still has no authenticated Flow PUT/GET, production Resource catalog adapter, publication,
Flow Fixture simulation, or object page; those remain the next vertical slices. The focused compiler/module/JDBC/
readiness/schema gate passed `35/35` with no failures, errors, or skips. The serial `clean verify` completed with
`Tests run: 8,143; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.

The authenticated Flow authoring slice now exposes that authority through
`PUT /api/authoring/flows/{flowId}` and revision-exact
`GET /api/authoring/flows/{flowId}?revision={revision}`. Trusted tenant, project, environment, and actor come
only from `IntegrationRequestAuthenticator`; self-reported scope is rejected. Create uses
`If-None-Match: *`, update uses one opaque strong `If-Match`, and every save requires a bounded
`Idempotency-Key`. Successful saves return the exact persisted receipt, strong ETag, and
`Idempotency-Replayed`; reads and writes are no-store. Historical ETag resolution preserves committed replay
after the head advances, while a new command using the same stale ETag fails CAS. The production catalog
adapter resolves only exact committed API Resource revision/fingerprint coordinates; immutable
`FLOW_VERSION` publication remains fail-closed until its authority is implemented. The feature-scoped
application configuration is disabled by default and fails startup when the Resource authority or Flow draft
store is missing. The focused compiler/module/JDBC/readiness/catalog/configuration/controller/schema gate is
**43/43 green** with no failures, errors, or skips. The serial `clean verify` completed with
`Tests run: 8,151; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`. Immutable Flow publication,
whole-flow Fixture simulation, and Tool/Solution object pages remain subsequent vertical slices.

The immutable Flow publication slice adds V015 publication identities, append-only versions, and committed
publish commands behind `ReusableFlowPublicationStore`. Publishing recompiles one exact Flow Draft revision,
checks its draft id and content fingerprint, and snapshots the authored business graph, schemas, mappings, and
dependency coordinates without carrying editor layout into the immutable version. A stable server-owned
publication id receives monotonically increasing revisions; same-key exact replay returns the persisted receipt,
while changed intent conflicts. `POST /api/authoring/flows/{flowId}:publish` derives scope and actor only from the
trusted integration identity and requires a bounded `Idempotency-Key`. The production composable catalog now
resolves exact `FLOW_VERSION` publication id, revision, and fingerprint coordinates, so a published Flow can be a
dependency of another Tool or Solution. The opt-in runtime fails startup when the read-only V015 readiness probe
cannot prove table, key, foreign-key, and `PUBLISHED` state closure. The focused compiler/module/draft/publication/
readiness/catalog/configuration/controller/schema gate is **58/58 green** with no failures, errors, or skips. The
serial `clean verify` completed with `Tests run: 8,166; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.
JDBC/readiness evidence remains H2 PostgreSQL mode rather than real PostgreSQL certification. Whole-flow Fixture
simulation and the Tool/Solution object pages remain the next vertical slices.

The first whole-flow Fixture tracer adds `WholeFlowFixtureMaterializer` and extends `SimulationModule` without
changing the existing API Resource path. A Fixture for one exact immutable `FLOW_VERSION` must contain Cases with
exactly one `SUBJECT + RETURN/INLINE` control; node controls, nested `APPLY_CASE`, real execution, governed assets,
and protocol/transport fidelity fail closed. Case input, return output, and optional expectation are validated
against the published Flow contract before the Fixture authority is produced. Executing the Case returns explicit
`SIMULATED_ONLY` evidence for the exact Flow Version and an empty node list, proving that no internal API Resource
or child Flow executed. The optional publication authority is wired into the existing opt-in Simulation module;
resource-only deployments keep their prior behavior. The focused materializer/simulation/configuration/wire gate
is **26/26 green** with no failures, errors, or skips. The serial `clean verify` completed with
`Tests run: 8,171; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`. This tracer does not yet provide durable
standalone Flow Fixture save/read or its authenticated HTTP endpoint; those are the next persistence slice.

The durable standalone Flow Fixture slice adds V016 identities, immutable revisions, exact heads, committed
idempotency commands, and opaque strong ETags without changing the V012 Resource-child authority. Authenticated
`PUT /api/authoring/fixture-sets/{fixtureSetId}` accepts only one exact published `FLOW_VERSION`, requires
`If-None-Match: *` for create or one strong `If-Match` for update, and requires a bounded `Idempotency-Key`.
Exact replay is resolved before current-head CAS; historical revisions remain readable and stale new updates fail
with precondition semantics. A fail-closed composite reader unifies V012 child and V016 standalone Fixture Sets for
existing GET/list and Simulation paths while rejecting an id that appears in both authorities. V019 later generalizes
the standalone subject coordinate to Flow Draft/Version, Operator Version, and built-in Function Version without
weakening the revision/head protocol. V016 and V019 are externally applied; enabling reusable Flow authoring without
them fails startup. The focused materializer/module/store/readiness/
configuration/controller/simulation gate is **48/48 green** with no failures, errors, or skips. Real PostgreSQL,
parent-Flow `NODE + APPLY_CASE`, sharing, and Tool/Solution object pages remain subsequent slices. The serial
`clean verify` completed with `Tests run: 8,182; failures: 0; errors: 0; skipped: 33` and `BUILD SUCCESS`.

Parent reusable Flows can now consume exact leaf Fixture Cases through explicit `NODE + APPLY_CASE` controls.
The compiler requires one control for every parent node, resolves the referenced Fixture Set revision and Case,
proves that its Subject exactly matches the node's immutable API Resource or Flow Version, and accepts only a
single terminating `SUBJECT + RETURN/INLINE`. Parent mappings use the parent Case input and preceding mocked
outputs; the referenced Case's saved input is never substituted. Simulation evaluates the mapping-defined DAG,
does not expand child Flows or perform egress, and returns per-node `APPLY_CASE` evidence with inherited API
fidelity. Partial controls, `REAL`, governed assets, and recursive `APPLY_CASE` remain fail-closed. The focused
compiler/materializer/application/configuration/controller/simulation/protocol gate is **51/51 green** with no
failures, errors, or skips. The serial `clean verify` completed with `Tests run: 8,187; failures: 0; errors: 0;
skipped: 33` and `BUILD SUCCESS`. Tool/Solution/Fixture object pages and real browser/PostgreSQL acceptance remain
open.

The first simplified object page is available at `/workbench/` and is now also the default `/` entry. Its landing page exposes only the approved
**Connect an API**, **Create a tool**, and **Create a solution** intents. The API Resource path asks for one name,
a new credential-free Connection name/base URL or one existing Connection, Method/Path, and one request/response
example; it infers the supported flat JSON Schema
and bindings, sends one compound Resource command, creates the private Default Fixture from that example, and runs
the exact returned Fixture Case with all external reads and writes denied. Design, Fixture, Simulation, and Version
tasks stay on the same object page. `GET /api/authoring/resources/{resourceId}` provides a trusted-scope,
no-store, strong-ETag deep link; a reload discovers payload-free Fixture summaries by the exact Resource subject
and can rerun the saved Case. The route is lazy and its startup closure is enforced by the production bundle
budget. The focused backend gate is **39/39 green**; the focused frontend model/transport/component/route gate is
**37/37 green**, i18n is **39/39 green**, TypeScript passes, and the full frontend production build and bundle
budget pass. The serial `clean verify` completed with `Tests run: 8,188; failures: 0; errors: 0; skipped: 33` and
`BUILD SUCCESS`. The original tracer deliberately supported only existing Connections and flat object examples;
the current nested-create slice closes the `Auth.NONE` Connection gap. Credential-bearing Connection creation,
advanced binding editing, and external provider certification remain separate explicit boundaries.

The API object page now defaults to **Create** and visibly collects only a Connection name and absolute HTTP(S)
base URL. Its wire command uses the frozen `connection.mode=CREATE` plus nested `command` schema; the server validates
`Auth.NONE`, derives a stable child Connection id from the acquired command authority, stages the Connection and
Resource against one exact attempt, compiles against that staged payload-free snapshot, commits both children in one
Resource transaction, and publishes the Connection only after the canonical Resource receipt is durable. **Existing**
remains available for committed Connection reuse. Credential-bearing nested commands still fail before claim.
Focused backend evidence is **141/141 green** across Controller, compound facade/JDBC rollback, application wiring,
projection compiler, and in-memory/JDBC Connection stores. Focused frontend model/component evidence is **12/12**;
i18n **39/39**, UX **52/52**, host **21/21**, TypeScript, Vite, and bundle gates pass. Three real Chrome methods are
**3/3 green**, including the measured two-API → DAG → Default Fixture task at **28 primary actions**; each API owns a
different server-derived Connection and no preseeded Connection id is used. The final serial `clean verify` completed
with `Tests run: 8,259; failures: 0; errors: 0; skipped: 38` and `BUILD SUCCESS` in 11:43.

Tool and Solution composition now reads one trusted-scope, payload-free Catalog from
`GET /api/authoring/catalog`. The list contains committed API Resource heads and the latest immutable Flow Versions;
every choice carries its exact revision/fingerprint and input/output Contract. A published child Flow can therefore
be selected directly as a parent Solution node without exposing draft layout, Fixture values, credentials, or
protected material. The Fixture task offers two explicit modes: a whole-Flow `SUBJECT + RETURN`, or complete
per-node `NODE + APPLY_CASE` controls. Parent Simulation resolves the selected child Case, applies parent mappings,
does not expand or execute the child Flow, and reports `MOCKED`, `APPLY_CASE`, inherited fidelity, and `NO_EGRESS`
for the exact node. The focused frontend gate is **20/20 green** and the affected backend Catalog/publication/
Fixture/simulation gate is **28/28 green**. The production frontend build passes i18n **39/39**, UX **52/52**,
host **21/21**, TypeScript, Vite, and bundle checks; `AuthorCanvas` starts at **349.73 KiB / 21 files** under the
350 KiB ceiling. A shared real-Chrome acceptance starts and completes at both 1280×900 and 390×844. Each run creates
three API Resources with independent server-derived Connections, re-authors them as one three-node Tool, publishes
the exact immutable version, composes a parent Solution, applies the child Case, and completes protected share/review
plus the governed rerun in **41 primary actions**. Both focused browser runs are green, and each viewport finishes
without page-level horizontal overflow. The most recent serial `clean verify` before this browser-only extension
reported `Tests run: 8,262; failures: 0; errors: 0; skipped: 38` and `BUILD SUCCESS`; the next final gate must refresh
that project-wide count.

The same `/workbench/` route now owns Tool and Solution object pages. Authors add committed API Resources in
execution order; the page pins every exact Resource revision/fingerprint and derives the DAG only from explicit
input mappings. A same-name, same-type field is wired from the nearest prior node output; otherwise it remains a
declared Flow input. Saving returns an exact Flow Draft coordinate. Its Fixture task authors one visible whole-flow
`SUBJECT + RETURN` Case, saves it through the standalone Fixture Set protocol, and immediately runs a deny-all
Simulation; internal API nodes are not executed or misreported as real. The Versions task publishes the exact
Draft as an immutable reusable Flow Version. Tool and Solution share this protocol and page instead of maintaining
separate runtimes. The focused object-page model/transport/component gate is **44/44 green**, i18n is **39/39
green**, TypeScript passes, and the production Vite/UX/host/bundle gates pass; the AuthoringWorkbench startup
closure is **186.90 KiB / 10 files**, below the 350 KiB ceiling. Fixture sharing/promotion and real browser/
PostgreSQL acceptance remain open.

The standalone Fixture writer now resolves a `FLOW_DRAFT` Subject against the exact committed scope, draft id,
revision, and content fingerprint before materializing a Case. A draft Fixture intentionally supports only one
whole-subject `RETURN`; node-level `APPLY_CASE` controls remain a published Flow Version capability. This closes the
object page's former mock-only gap without weakening immutable-version controls or exposing Fixture material. The
focused draft-store/materializer/application/configuration/controller gate is **34/34 green** with no failures,
errors, or skips. The serial `clean verify` completed with `Tests run: 8,192; failures: 0; errors: 0; skipped: 55`
and `BUILD SUCCESS`. Fixture sharing/promotion, an independent Fixture object page, and real browser/real PostgreSQL
acceptance remain open.

The independent Fixture object page is now available at `/workbench/?fixtureSetId=<id>`. It reads the exact trusted
Fixture authority, status, Subject, and Case instead of reconstructing them in the browser. Standalone Flow Draft and
Flow Version Fixtures expose their real strong ETag and support visible whole-subject Return editing, CAS save, and
deny-all Simulation. An API Resource Default Fixture remains read-only and links back to its Resource page; the server
does not fabricate an ETag for parent-governed material. The application packages the workbench under
`static/workbench`, and `/workbench` forwards to that production entry point. Focused frontend model, transport, and
component tests are **13/13 green**; i18n is **39/39**, UX **52/52**, host contracts **21/21**, TypeScript and the Vite
production bundle pass, with AuthoringWorkbench at **188.67 KiB / 11 files** and AuthorCanvas at
**349.87 KiB / 22 files** under the 350 KiB budget. The focused backend gate is **38/38 green**. A real Chrome run at
1280 px visibly edits, saves, and simulates an exact Flow Draft Fixture, verifies revision/output against server
authority, and repeats at 390 px without horizontal overflow (**1/1 green**). The final serial `clean verify` completed
with `Tests run: 8,198; failures: 0; errors: 0; skipped: 34` and `BUILD SUCCESS`. Fixture share/promotion remains a
separate lifecycle action; this new browser path also does not claim deployment-specific external Vault/provider or
the exact workbench flow on a production PostgreSQL installation.

The API Resource object page accepts inline OpenAPI JSON or YAML through the preview-only
`POST /api/authoring/resources:preview-openapi` preview endpoint. The endpoint uses the trusted authoring identity,
returns `no-store`, performs no persistence, and projects only safely importable GET/POST/PUT/DELETE
operations into the same typed Resource save command used by manual authoring. Path, query, header, and body bindings,
flat request/response schemas, success matching, and deterministic examples survive the visible operation-selection
step; unrelated blocked operations do not hide valid choices. Explicitly requested invalid operations still fail
closed. `REMOTE` commands now pass one exact trusted scope/actor request to an optional
`RemoteOpenApiDocumentGateway`. The module requires credential-free HTTPS URLs, rejects userinfo/query/fragment,
provides explicit 10 MiB/15 s budgets, and independently checks response media type, UTF-8, and size. The host gateway
must own destination authorization, DNS pinning/rebinding defense, redirect denial, and committed Connection/Secret
Store resolution. Without that governed adapter, `REMOTE` remains an honest `424` boundary. The focused backend
gate is **63/63 green**; frontend model/transport/component tests are **13/13**, i18n **39/39**, UX **52/52**, and
host contracts **21/21**; TypeScript, Vite, and the bundle budget pass with AuthoringWorkbench at **189.43 KiB / 11
files** and AuthorCanvas at **349.93 KiB / 22 files**. A real Chrome method verifies visible import, selection, exact
field projection, zero persistence, and 1280/390 px layout (**1/1 green**). The final serial `clean verify` completed
with `Tests run: 8,207; failures: 0; errors: 0; skipped: 35` and `BUILD SUCCESS`. Remote OpenAPI fetch, simplified
workbench Fixture share/promotion, production PostgreSQL/Vault certification, and measured user-task complexity remain
open rather than being inferred from this local preview slice.

Standalone Flow Version Fixtures can now be shared from the same object page without exposing their inline material.
The author supplies classification, bounded retention, a redaction-profile version, and non-root JSON Pointer paths;
the server locks the exact `PRIVATE_DRAFT` source revision, writes protected Fixture Assets through the correctness
material authority, submits each asset as `PROPOSED`, and atomically derives an immutable `SHARING_PENDING` Fixture
Set revision plus a payload-free review receipt. The private source revision remains readable and runnable by exact
coordinate, while the pending revision cannot run, be edited, or be reused. Exact idempotent replay does not repeat
protected writes. V017 persists the share command and pending review authority; missing V016/V017 fails opt-in
startup. The share application module keeps a transport-neutral trusted identity and protected-material port inside
the visual boundary; the integration and correctness types are translated only by the outer adapter. The focused
backend gate is **29/29 green** (including production correctness writer assembly); frontend
transport/component tests are **8/8**, i18n **39/39**, and TypeScript passes. The production frontend build passes
i18n **39/39**, UX **52/52**, host **21/21**, Vite and bundle gates, with AuthorCanvas at **349.93 KiB / 22 files**.
A real Chrome method visibly saves, simulates, shares, reloads the derived revision, verifies disabled execution and
the server-side `PROPOSED` protected asset (**1/1 green**). The final serial `clean verify` completed with
`Tests run: 8,218; failures: 0; errors: 0; skipped: 35` and `BUILD SUCCESS`.

The same Fixture object page now completes the independent-review lifecycle. A reviewer different from the creator
opens the exact `SHARING_PENDING` revision, records redaction/schema/material attestations and a bounded comment, and
submits one strong-ETag/idempotency-protected review command. The server verifies the immutable pending request,
resumes each exact protected asset from `PROPOSED`, verified `PROPOSED`, `APPROVED`, or `ACTIVE`, and publishes one
immutable `TEAM_AVAILABLE` Fixture Set only after every asset is active. Retry after a partial multi-asset activation
does not duplicate or skip governance transitions. V018 persists the review intent and payload-free receipt; missing
V016–V018 fails opt-in startup. The focused backend gate is **51/51 green**; frontend transport/component tests are
**10/10**, TypeScript passes, and the production frontend build passes i18n **39/39**, UX **52/52**, host **21/21**,
Vite and bundle gates, with AuthorCanvas at **349.95 KiB / 22 files**. A real Chrome method performs the visible
creator-to-reviewer handoff, reaches `TEAM_AVAILABLE`, restores enabled execution, and verifies the protected asset is
`ACTIVE` (**1/1 green**). The final serial `clean verify` completed with `Tests run: 8,225; failures: 0; errors: 0;
skipped: 35` and `BUILD SUCCESS`. This local evidence does not claim external Vault certification, remote authenticated
OpenAPI egress, or deployment migration evidence on a production PostgreSQL service.

The simplified workbench now has one measured end-to-end authoring task. In a single real Chrome session, an author
imports two inline OpenAPI GET operations, saves their exact API Resources and private Default Fixtures, composes a
two-node Tool DAG, publishes one immutable Flow Version, authors a whole-Flow Fixture against that version, shares
its protected material with `/customerLabel` redaction, hands the pending revision to a visibly signed-in independent
reviewer, reaches `TEAM_AVAILABLE`, and runs the reviewed Fixture. The simulation module resolves protected material
only through the exact ACTIVE Fixture Asset authority and trusted identity; it emits `FIXTURE_ASSET` source plus a
passed governance verdict and one synthetic whole-subject evidence node, validates the safe redacted output against
the immutable Flow contract, and performs no API-node egress. The page reloads the exact server-authoritative latest
Flow Version for Fixture authoring instead of falling back to the mutable draft after publication, and the API page
discovers committed payload-free Connections for visible selection instead of relying on a hidden identifier. The
shared revision derives its expected output from the same exact redacted material, so the final visible evidence is
`SUCCEEDED`, `SIMULATED_ONLY`, contract/assertions/governance `PASSED`, and
`subject · MOCKED · FIXTURE_ASSET · OUTPUT_LEVEL · FIXTURE · NO_EGRESS`. The browser gate completes in
**27 primary actions** and **9.899 seconds** of measured page-task time (**1/1 green**, 21.39 seconds including
startup). Focused frontend tests are **27/27 green**; the simulation/publication/share backend gate is **55/55 green**;
i18n **39/39**, UX **52/52**, host **21/21**, TypeScript, Vite, and bundle gates pass.
The final serial `clean verify` completed with `Tests run: 8,235; failures: 0; errors: 0; skipped: 36` and
`BUILD SUCCESS` in 11:50 after the default-entry change.
Deployment-specific `RemoteOpenApiDocumentGateway` and external secret-provider implementations,
external Vault certification, and real PostgreSQL migration certification remain separate external-environment
evidence rather than hidden local fallbacks.

The default object workbench also exposes an authenticated, read-only **Existing assets** inventory. It projects
legacy Resource Descriptor/Design Contract pairs, scoped Graph Drafts and Publications, and payload-free Fixture
references into `READY_TO_REAUTHOR`, `NEEDS_REPAIR`, or `LEGACY_ONLY`. Each row carries only a source coordinate,
display label, reason codes, reference count, and a server-selected relative application action; descriptor URLs,
headers, schemas, Fixture values, governed material identifiers, credentials, and external destinations are never
returned. The inventory remains available when durable API Resource writes are disabled because it does not depend
on V001–V018 or mutate any authority. The entry remains visible for an empty or temporarily unavailable inventory,
so operators can inspect the explicit state instead of losing the recovery path. Resource re-authoring still requires
a visible Connection decision (create credential-free or select existing), advanced non-data Graph edges remain in
Legacy Author, and no migration is performed automatically.
The focused migration/schema/boundary gate is **23/23 green**, frontend transport/component tests are **12/12**,
and the production frontend build plus the real Chrome inventory method are green. The final serial
`clean verify` completed with `Tests run: 8,240; failures: 0; errors: 0; skipped: 37` and `BUILD SUCCESS`.

Each `READY_TO_REAUTHOR` Resource row now opens an authenticated, read-only Descriptor + Contract preview at
`GET /api/authoring/migrations/legacy-assets/resources/{resourceId}:preview`. The preview contains only the relative
path, supported request bindings, response success/output-path rules, simplified schemas, and one generated example;
it never carries the legacy host, default headers, authentication, credentials, Fixture contents, or protected
material references. The API form is visibly prefilled and proposes a credential-free Connection name; the author
must visibly supply its base URL or switch to an existing committed Connection before save-and-simulate creates the
new Resource and Default Fixture.
Nothing is mutated by opening the preview. Write resources, ambiguous mappings, unsafe paths, and unsupported
contracts remain `NEEDS_REPAIR` instead of being guessed or silently migrated.
The focused backend migration/schema gate is **28/28 green**, focused frontend model/transport/component tests are
**19/19**, i18n **39/39**, UX **52/52**, host **21/21**, TypeScript, Vite, and bundle gates pass
(AuthoringWorkbench **193.66 KiB / 12 files**, AuthorCanvas **349.98 KiB / 22 files**). A real Chrome method follows
the visible inventory → preview → Connection selection → save-and-simulate path and is **1/1 green**. The final serial
`clean verify` completed with `Tests run: 8,247; failures: 0; errors: 0; skipped: 38` and `BUILD SUCCESS` in 11:48.

Each API-only legacy Graph Draft or frozen Publication that can be proved from exact committed Resource heads now
offers an authenticated, read-only reusable-Flow preview at
`GET /api/authoring/migrations/legacy-assets/flows/{sourceKind}/{sourceId}:preview?revision={revision}`. The
`bloge.legacyReusableFlowReauthorPreview.v1` response preserves direct input/node-output/constant mappings, graph
input and output contracts, and layout. It carries only exact Resource coordinates and fingerprints; legacy
`nodeFixtures`, governed Fixture references, material, and payloads are counted and diagnosed but never copied.
Advanced/control edges and unprovable Resource dependencies remain `LEGACY_ONLY` or `NEEDS_REPAIR`.
The visible Existing assets action opens a prefilled Flow object page, requires review, and then uses the existing
save -> publish -> Fixture -> simulation -> review path; nothing is mutated by opening the preview.
The focused backend migration/schema gate is **31/31 green**, focused frontend transport/component tests are
**26/26**, i18n **39/39**, UX **52/52**, host **21/21**, TypeScript, Vite, and bundle gates pass
(AuthoringWorkbench **194.11 KiB / 12 files**, AuthorCanvas **349.95 KiB / 22 files**). A real Chrome method completes
the visible two-Resource -> legacy inventory -> Flow save/publish -> Fixture/simulation/review chain in 26 primary
actions and is **1/1 green**. The final serial `clean verify` completed with `Tests run: 8,250; failures: 0; errors: 0;
skipped: 38` and `BUILD SUCCESS` in 11:51.

Legacy `GraphDraft.nodeFixtures` now have a separate explicit re-authoring path. Before a structurally exact reusable
Flow draft exists, the inventory reports `NEEDS_REPAIR` and directs the author to `REAUTHOR_FLOW`. After the author
saves that Flow, the same legacy item becomes `READY_TO_REAUTHOR` and opens the exact Flow draft's Fixture tab. The
`bloge.legacyFixtureReauthorPreview.v1` response contains only the legacy draft coordinate, exact target Flow draft,
suggested Fixture Set identifier, node identifier, material kind, fidelity, and whether an expected input existed.
Legacy inline input/output, governed asset identifiers, fingerprints, receipts, credentials, and protected material
never enter the response or the new Fixture. The author enters new whole-Flow input and output, then uses the existing
save, simulation, share, independent review, and governed-run path. Sharing resolves the exact committed Flow draft
contract and retains that draft coordinate as the Fixture authority; it does not silently retarget the Fixture to a
later publication. The focused backend migration/schema/share gate is **39/39 green**, and focused frontend transport/
component tests are **20/20 green**. i18n **39/39**, UX **52/52**, host **21/21**, TypeScript, Vite, and bundle gates
pass (AuthoringWorkbench **194.65 KiB / 12 files**, AuthorCanvas **349.96 KiB / 22 files**). A real Chrome method
completes the visible two-Resource -> legacy Flow -> legacy Fixture re-authoring -> simulation -> share -> review ->
governed-run chain in **28 primary actions** and **9.574 seconds** (**1/1 green**, no skips). The final serial
`clean verify` completed with `Tests run: 8,253; failures: 0; errors: 0; skipped: 38` and `BUILD SUCCESS` in 11:44.
This closes the approved local single-item Fixture re-authoring path. Batch mutation, migration coverage receipts,
real PostgreSQL migration/concurrency, remote authenticated OpenAPI egress, and external Vault/provider certification
remain separate work or deployment evidence.

Legacy inventory assessment now has a separate read-only receipt at
`GET /api/authoring/migrations/legacy-assets/assessment`. The
`bloge.legacyMigrationAssessment.v1` wire records exact classified/total counts, zero-or-greater unclassified
coordinates, fixture-reference count, non-ready public coordinates, and a canonical inventory fingerprint. Its
strong `ETag` can be supplied as `If-Match` to replay the same source snapshot; a changed inventory returns `412`,
while weak, wildcard, or list validators return `400`. The Existing assets page shows coverage, required-action
count, and a shortened snapshot fingerprint. This is assessment evidence only: it performs no migration and does
not label any row as migrated. The focused backend gate is **34/34 green**, frontend transport/component tests are
**15/15 green**, and i18n **39/39**, UX **52/52**, host **21/21**, TypeScript, Vite, bundle, and the real Chrome
inventory method all pass. The serial `clean verify` completed with `Tests run: 8,266; failures: 0; errors: 0;
skipped: 39` and `BUILD SUCCESS`. Batch mutation remains intentionally outside this read-only protocol.

The frozen authoring wire catalog now has an executable completeness gate. All **33 top-level schemas** under
`docs/schemas/resource-gateway-authoring` (excluding the definitions-only `common-v1`) are mapped to explicit
minimal, complete, and invalid golden examples. The protocol test compares that map with the schema directory, so
adding a new top-level wire schema without its golden family fails immediately; family routing also uses exact
minimal/complete/invalid qualifiers instead of ambiguous filename prefixes. The focused
`AuthoringProtocolSchemaTest` gate is **18/18 green** with no failures, errors, or skips. The post-golden serial
`clean verify` completed with `Tests run: 8,266; failures: 0; errors: 0; skipped: 39` and `BUILD SUCCESS` in 11:56.

J3-C1 adds the standalone Connection application tracer. `ApiConnectionAuthoringFacade` accepts one
lifecycle-complete `ApiConnectionAuthoringStore`, so JDBC claim and Connection persistence are constructed over
the same `DataSource`; the in-memory reference store keeps claim and Connection state together. The tracer
validates the pure command before ETag lookup, fingerprinting, or claim, currently accepts only `Auth.None`,
uses exact historical strong ETags for replay/CAS, and returns only `ApiConnectionView` plus its strong ETag.
The focused command
`mvn -f resource-gateway-examples/pom.xml -Dtest=InMemoryApiConnectionCommitStoreTest,JdbcApiConnectionCommitStoreTest,ApiConnectionAuthoringFacadeTest,JdbcApiConnectionAuthoringFacadeTest,ApiConnectionAuthorityTest,JdbcApiResourceCommitStoreClaimTest,JdbcApiResourceCommitStoreMutationTest test -DfailIfNoTests=false`
is **147/147 green** (InMemory Connection 28, JDBC Connection 50, facade 19, JDBC facade 1, authority 15,
Resource claim 11, Resource mutation 23; Failures: 0, Errors: 0, Skipped: 0). It includes a real H2
`MODE=PostgreSQL` same-database claim/replay integration, exact journal/immutable-attempt receipt closure,
custom-mapper replay, lease-loss/retry classification, typed lifecycle-failure cleanup, and shared JDBC
attempt-cleanup coverage. The subsequent serial resource-gateway
`mvn -f resource-gateway-examples/pom.xml clean verify` completed with `Tests run: 7,970; Failures: 0;
Errors: 0; Skipped: 33` and `BUILD SUCCESS`. Credential write capabilities, HTTP/controller transport, real
PostgreSQL certification, and UI acceptance remain outside this tracer.

J3-B1a adds the Connection persistence foundation: V003 defines five scoped identity, revision, head, pending
lease, and binding tables with secret-free metadata, exact command/attempt provenance, staged/committed head
fencing, and database checks, foreign keys, unique constraints, and recovery indexes. Its read-only
`ApiConnectionSchemaReadiness` probe verifies the required columns and constraint/index closure. Commits
`c2a25a63c`, `99fa6f806`, `955c1ef18`, and `294331ddc` passed Spec and Standards review; the focused readiness
suite is 11/11 green on H2 PostgreSQL mode. This slice does not implement the JDBC Connection store, external
Vault lease/activation, `AuthoringFacade`, or HTTP endpoints. Real PostgreSQL certification and full `clean verify`
were not run.

J3-B1b-1 adds the pure API Connection commit seam and the in-memory reference adapter. It proves explicit child
revision CAS, endpoint binding, attempt fencing, staged invisibility, committed history, and safe strong ETag
handling without exposing credentials or business payloads. Commits `4589c51de`, `7315c6490`, `112d00f17`,
`076fa184b`, and `6958bc2b4` passed Spec and Standards review with P0/P1/P2 findings all at zero; the concrete
`InMemoryApiConnectionCommitStoreTest` contract suite is 19/19 green. This remains a pure commit seam/reference
adapter: the JDBC Connection store, external Vault/pending-secret integration, `AuthoringFacade`, HTTP endpoints,
and full `clean verify` are not implemented or certified.

J3-B1b-2 adds the pure External Secret Provider seam. It accepts only an external provider: a caller-owned,
destroyable `VALUE`, or a scope-bound `SECRET_REF`; the final template closes the exact scope, provider, and
attempt. `prepare`, `activate`, `abort`, and `resolve` define idempotency and compensation behavior, while
Jackson serialization, `toString`, and code-only errors remain secret-free. `FakeExternalSecretProviderContractTest`
is 13/13 green, and Spec plus Standards review is Accepted with P0/P1/P2 all at zero. This is only the provider
seam: `PendingSecretStore`, JDBC/production Vault providers, `AuthoringFacade`, HTTP endpoints, UI, full
`clean verify`, and real PostgreSQL are not implemented; there is no local AES/JDBC provider.

The subsequent V003 hardening commits `d4d57c5d6`, `eb3c40540`, and `19a5c8a1f` constrain active bindings to an
exact foreign key targeting only a `COMMITTED` revision, constrain pending leases to `PENDING` or `ABORT_REQUIRED`,
and fix a deterministic seven-column recovery index. Readiness semantically accepts H2 `IN` and PostgreSQL `ANY`
forms plus safe casts, while failing closed on extra, wrong, or semantic-changing casts. The initial focused
`ApiConnectionSchemaReadinessTest` after `d4d57c5d6` was 19/19 green before the semantic changes; the latest
semantic commits have independent `javac`/helper checks and static Spec review Accepted with P0/P1/P2 all at zero.
On detached clean HEAD `4893e29a3` (including `19a5c8a1f`), the focused
`mvn -f resource-gateway-examples/pom.xml -Dtest=ApiConnectionSchemaReadinessTest test` run completed at
2026-08-30 18:48 +08 in 40.712 seconds: 21/21 green, with no failures, errors, or skips and `BUILD SUCCESS`.
The earlier 19/19 result after `d4d57c5d6` preceded the semantic changes; the latest semantic commits also have
independent `javac`/helper checks and static Spec review Accepted with P0/P1/P2 all at zero. The shared main
worktree concurrently contains unrelated uncommitted Connection/JDBC compile errors; that state must not be
confused with the isolated HEAD evidence. Real PostgreSQL was not run, and PG metadata semantics are covered only
by contract tests. This hardening does not increase the broader completion estimate beyond 35%.

J3-B1c–e closes the PendingSecretStore persistence protocol as a persistence seam
(2026-08-31). The in-memory adapter is the exact reference model: staged batches
are invisible to active reads, retries replay only the complete matching attempt,
and newer attempts or competing commands are fenced before bindings change. The
JDBC adapter reconstructs the outer command CAS from the journal and the child
connection CAS from each pending row, requires the journal to remain `PREPARING`
for every mutation, uses the database clock and the earlier provider/journal
deadline, claims complete recovery batches in stable DB-bounded order while
excluding live claims, and requires an ambient coordinator transaction only for
the final binding commit; stage and recovery own local store transactions.
V007 is retained as history and clamps the effective lease while preserving
`provider_lease_until`; forward-only V008 replaces its nullable three-valued
child-CAS check with an explicit non-null boolean closure and fails closed on
unprovable legacy rows. The focused persistence regression is 62/62 green
(26 JDBC, 30 in-memory, 6 migration-readiness tests) in H2 PostgreSQL mode.
This does not accept or wire the `AuthoringFacade`, HTTP endpoints, UI, or real
PostgreSQL certification.

The follow-on V009/V010 attempt-provenance closure keeps every command attempt
immutable. Takeover marks the prior `PREPARING` attempt `SUPERSEDED` before
advancing the journal pointer; current stage/finalize/commit operations require
the exact current `PREPARING` attempt, while recovery can reconstruct an exact
historical `PREPARING` or `SUPERSEDED` attempt and close it as `FAILED`. V010
retargets Connection head and active-binding foreign keys to the exact attempt,
so a retained old staged revision cannot collide with a replacement attempt.
Binding commit locks the scoped connection identity and exact parent revision in
that order, then updates only the same command/attempt; a competing owner is
`LEASE_FENCED`, and a rolled-back winner releases the row for a later winner.
The current focused JDBC evidence is 89/89 green (40 Connection, 11 Resource
claim, 30 PendingSecretStore, and 8 migration-readiness tests), including
two real H2 JDBC connections for binding winner/rollback and concurrent recovery
claims. This is H2 `MODE=PostgreSQL` evidence only; real PostgreSQL certification,
the `AuthoringFacade`, HTTP endpoints, and the authoring UI remain unaccepted.

The current follow-up closure adds exact attempt predicates to every staged-to-committed
revision transition and committed historical read. A JDBC Connection child is provisional
until a transaction synchronization proves the exact outer Resource journal and immutable
attempt are both `COMMITTED`; a child-only transaction therefore rolls back, and the
transaction-scoped fence is unbound after completion. Resource failure refuses to remove
its stage while exact pending-secret rows remain; pending compensation may terminalize the
attempt first, after which Resource performs only the exact `FAILED` stage cleanup after
rechecking the complete immutable authority. Resource takeover removes an abandoned
providerless nested child stage but retains rows needed for pending-secret recovery.
Recovery and current mutation paths follow one journal -> attempt -> connection
identity/revision/binding/pending lock order. The recovery observer is a package-private
pre-mutation test seam; an injected observer failure leaves the claim untouched because no
claim mutation has started. Connection receipt closure is endpoint-bound, and ambiguous
historical provenance fails closed. Committed Connection reads now join the mutable journal
to the immutable attempt on the exact command/attempt/token and require endpoint/target
agreement (with `API_CONNECTION_SAVE` also matching the child connection). The latest focused
closure evidence is 120/120 green (46 Connection, 11 Resource claim, 23 Resource mutation,
32 PendingSecretStore, and 8 schema-readiness tests), still H2 `MODE=PostgreSQL` only. The serial
resource-gateway `clean verify` evidence reports `Tests run 7,942; failures 0; errors 0; skipped 33`;
no claim of real PostgreSQL certification, Facade, HTTP, or UI acceptance is made.

The stage-zero implementation for the world-model evolution plan is intentionally additive. The
current kernel explicitly compiles `SCHEMA_STANDIN`, `DESCRIPTOR_PROTOCOL`,
`DESCRIPTOR_TRANSPORT`, and `BINDING_REAL`; unsupported future modes remain closed rather than
being simulated implicitly. Schema stand-in is selected only by an exact server-owned Java hint,
never inferred from an ordinary output fixture, and always produces exploratory evidence.
Descriptor protocol and transport fixtures no longer require a deployed `httpResource` binding:
the isolated test engine receives a run-scoped fail-closed binding while the existing
`ResourceFixtureRuntime` performs the real descriptor mapping, protocol validation, and payload
extraction over a zero-network transport stub. Neither the graph nor the shared operator registry
is mutated. The stub implements the Resource Gateway-local `HttpRequestTransport` seam directly,
so test construction does not instantiate the production HTTP client; the existing Spring
constructor still accepts the production `HttpRequestOperator` unchanged.
The kernel's run identity and evidence wall clock are explicit injected dependencies. Repeated
logical-clock executions keep stable plan and semantic-result fingerprints while retaining distinct
run identities and full evidence fingerprints; a class-file architecture gate prevents the core
service from directly reading system time or randomness.
Compilation follows the same rule: plan fingerprints are content-deterministic, while an injected
plan identity source preserves a distinct auditable `planId` for every compilation. The compiler
core never generates UUIDs directly, and blank identity-source results fail closed.
The four bounded `X-BLOGE-Test-*` headers now have a strict parser and an authenticated
test/staging HTTP admission path. `X-BLOGE-Test-Inline` accepts exactly one bounded
`fixtureBundle` object and cannot be combined with a legacy body fixture source; business context
remains in the request body. Scenario and world-model references remain fail-closed until the
stage-one authorized asset resolver exists. Spring-owned visual simulation now delegates through
the visual-owned port to the unified test kernel; the legacy runner remains available only as the
stage-zero differential oracle until the remaining parity and security gates close. See the
[implementation ledger](../docs/rg-evolution-design-1.2.1-implementation-ledger.md) for the exact
evidence and remaining gates.

## Provider Conformance TCK

Capability Studio deployments use the Provider Conformance TCK as a narrow protocol-mechanism
gate. Historical strict Draft 2020-12
[`v1`](../docs/schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-provider-conformance-result-v1.schema.json)
reports retain six historical checks: `LOCAL_PROTOCOL`, `BASELINE_AUTHORITY_ACCEPTANCE`,
`DETERMINISTIC_REPLAY`, `RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED`,
`EVIDENCE_POLICY_TAMPER_FAIL_CLOSED`, and `OWNER_AUTHORITY_TAMPER_FAIL_CLOSED`. Current
[`v2`](../docs/schemas/resource-gateway-capability-studio/capability-studio-stage-acceptance-provider-conformance-result-v2.schema.json)
adds `AUTHORITY_BINDING`, making the current v2 obligation set seven checks, plus a deployment-pinned
Provider fingerprint. The verifier preserves v1 semantics and dispatches by version. `CONFORMANT` requires every version-fixed check to pass and
`challengeCount > 0`.

Deployment onboarding is deterministic: put the deployment-owned Provider and its
`ServiceLoader` registration on the Test Kit classpath, require exactly one Provider, validate
the local result before external calls, run the positive and negative replay/tamper challenges,
recompute the summary and `reportFingerprint`, and write the result to the explicit output path.
There is no Provider fallback and no self-signed trust substitute. The expected command shape is:

```bash
CapabilityStudioStageAcceptanceProviderConformanceCli \
  --result <stage-acceptance-result.json> \
  --output <provider-conformance-result.json>
```

The CLI, TCK, strict result builder, independent verifier, and both packaged schemas are implemented.
The Provider TCK slice's 20/20 and Stage Authority/CLI integration's 44/44 are historical v1
observations, not permanent denominators. Current development observations are cross-version
Provider/TCK/CLI/shell 58/58, mounted Bundle 15/15, reference Provider 6/6, and Test Kit
`clean verify` 1030/1030. The deployment runner now enforces the frozen contract obligations before
formal acceptance:

```bash
BLOGE_EXPECTED_TEST_KIT_JAR_SHA256='<64 lowercase hex>' \
BLOGE_EXPECTED_STAGE_RESULT_SHA256='<64 lowercase hex>' \
BLOGE_EXPECTED_PROVIDER_CLASSPATH_SHA256S='<64 lowercase hex>,<64 lowercase hex>' \
JAVA_TOOL_OPTIONS='-Dbloge.capabilityStudio.authorityBundleRoot=/mnt/authority-bundle' \
BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT='sha256:<64 lowercase hex>' \
JAVA_BIN="$(command -v java)" \
resource-gateway-test-kit/scripts/verify-capability-studio-stage-acceptance.sh \
  --test-kit-jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --provider-classpath '<enterprise-provider.jar>:<provider-dependency.jar>' \
  --stage-result '<stage-acceptance-result-v2.json>' \
  --conformance-output '<new-provider-conformance-result-v2.json>'
```

The current Bundle, cross-version gate, and reference Provider suites pass 15/15, 58/58, and 6/6;
the Test Kit `clean verify` passes 1030/1030 tests with no skips, including ordinary/shaded JAR
Schema packaging and Javadoc/doclint. The reference mounted Provider is under
`resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/`. These
are development mechanism observations, not a conformance claim for an enterprise deployment Provider.
The normative denominators are `PCTCK-AC-01..10`, `DEPLOY-AC-01..08`, and the frozen
`AUTHORITY-BUNDLE-PROVIDER-v1` inventory `ABP-001..024`; its `AUTHBUNDLE-AC-01..10` rows are
aggregation rows, not extra obligations. Test counts cannot shrink or redefine the inventory.
The three artifact pins and the formal CLI expected binding pin must be supplied out-of-band. The
checked-in runner reads these exact names and rejects missing, malformed, out-of-order, or
mismatched pins before Java starts.
`CONFORMANT` proves mechanism consistency with the current deployment trust configuration only;
it does not increase `formalPassCount`, or replace organization ownership, KMS/HSM custody, real
target-environment transport, deployment egress enforcement, or Owner process attestation.
`NON_CONFORMANT`, `BLOCKED`, and `INPUT_INVALID` remain explicit outcomes and cannot be hidden by
omitting checks or falling back to a successful path.

## Try It In VS Code, No Server Required

The reference extension packages the real Business Mirror and Author workspaces with offline
Business Mirror projections plus an operator and built-in function catalog. It opens no port and
needs no Spring Boot process:

```bash
cd resource-gateway-examples/vscode-extension
npm run prepare:webview
code --new-window --extensionDevelopmentPath="$PWD"
```

Run **Resource Gateway: Open Authoring Workspace**. Business Mirror opens first: import one of the
three offline Graph projections, fill its business definition, save, and compile readiness. Select
**Author** to edit a complete canvas example; **Resource Gateway: Save Recovery and Close** keeps
that canvas in host-encrypted recovery. A remote runtime is optional and remains behind workspace
trust, HTTPS, SecretStorage credentials, and path restrictions. See the
[extension guide](vscode-extension/README.md) and
[real-host UX evidence](../docs/resource-gateway-ux-round3-s5-vscode-host-integration.md).

## Start The Demo

From the repository root:

```bash
./scripts/start-visual-canvas-demo.sh --open
```

The default `test`-profile startup enables Capability Studio and the read-only
Correctness Studio sample. `--open` opens `/capabilities/`, where the cancellation-fee
golden pack exposes four API capabilities, one Feature, one Tool, and nine business
scenarios without requiring technical identifiers. Contract and scenario discovery are
implemented. The tutorial branch also supports a business-sentence timeout edit followed by
an isolated preflight with zero unresolved dependencies, zero real calls, and no fallback to
real services. The tutorial branch uses a database-backed head plus immutable revisions, so
saved edits survive application restart and concurrent saves use optimistic revision control.
The Stage 0 Dataset compiler deterministically adapts all nine Cases to the existing
`ScenarioDraftSet` model. Every Case carries a complete four-API `RUNTIME_CONTROL` set; idempotency
and forbidden-write obligations remain separate `BUSINESS_EXPECTATION` metadata and are not
miscompiled as fixtures over the Tool itself. The compiler preserves RETURN, ERROR, TIMEOUT,
ordered consumption, and `MUST_NOT_CALL`, and rejects real fallback, ambiguous selectors,
incomplete exact references, or unsupported lowerings. The governed adapter delegates to the existing
`ScenarioGovernedCompiler` and deterministically produces FixtureBundle/TestSuite registration
plans with a payload-free source map. The source map is now lowered into a typed, sorted exact-ref
provenance closure that participates in FixtureBundle and TestSuite content addressing. The
**Quality & impact** task turns that same Dataset into a strict, payload-free GP-09 admission and
impact projection. The demo truth is intentionally falsifiable: `9 DRAFT / 0 ACTIVE / 0 STALE`, five
definition-coverage metrics at `100%`, freshness `UNVERIFIED`, admission `BLOCKED`, and exactly two
blockers (`FRESHNESS_EVIDENCE_MISSING`, `NO_ACTIVE_CASES`). Selecting one Case highlights its Source,
Oracle, applicable Contract, four runtime dependencies, and target; the complete projection contains
37 exact-reference nodes and 81 semantic edges with no orphan Case. `PAYLOAD_NOT_EXPORTED` proves only
that this projection carries no request/response content; it does not claim that source payloads have
been semantically de-identified. The startup probe, JSON Schema, Test Kit verifier, and real-browser
acceptance all enforce these facts independently.

The governed asset publisher registers those plans only through the existing testing registry, ignores
write receipts, and independently re-reads and re-fingerprints every fixture and suite. The
candidate service then executes that exact suite through the existing `TestSuiteExecutionService`.
It snapshots `CapabilityStudioDeploymentCandidateAuthority` at construction time; its public run
API has no candidate argument, so controllers, baseline orchestration, and other callers cannot
replace the deployment-owned build identity for an individual run. Bound deployments put that same
candidate into every execution intent, while explicitly unbound local deployments retain the honest
unbound limitation. Aggregate evidence retains the full exact-ref closure; each child run carries
the exact suite reference plus compact provenance and source-map fingerprints so it remains below
the 16 KiB child
request metadata boundary. Legacy Scenario compilation omits this metadata and retains its previous
content-addressed output. A Data Lens read model also projects the existing
`TestRunEvidence` in structure-only or payload-visible modes. The requested mode is not an
authorization claim: the Feature endpoint first authenticates the workload with the dedicated
`CAPABILITY_STUDIO_REHEARSAL` purpose, and payload-visible projection additionally requires the
server-resolved identity to have `CONFIDENTIAL` clearance. Missing credentials, forbidden purposes,
insufficient clearance, forged identity headers, and an unavailable security-audit sink fail closed
before the graph runs. The test/staging-only Feature
Rehearsal endpoint now executes an actual BLOGE graph with four `HttpResourceOperator` nodes,
one pure aggregator, and one pure decision; Capability Studio renders the same Trace as a stable
6-node/5-edge DAG and Data Lens. In the default timeout Case, the compensation-history attempt is
retained as `TIMEOUT`, BLOGE applies the declared fallback, and the Feature finishes `PASSED` with
`MANUAL_REVIEW` and `COMPENSATION_HISTORY_TIMEOUT`; the run still exposes zero real calls and never
invokes its fail-fast HTTP delegates. The same Feature graph can now be
wrapped as the canonical Tool binding and executed through the existing
`OperatorMicroGraphRunner -> TestRunService -> BLOGE nested graph` path; nested fixture selectors
require both `graphPath` and `nodeId`, remain occurrence-addressable, and the fail-fast HTTP delegates
still observe zero calls. Its composability manifest declares the four exact Resource dependencies,
so execution target snapshots can distinguish a declared closure from an opaque or empty registry.
The fixed 9 Case x 3 Oracle baseline remains available for detailed business semantics. The Tool page
now adds a governed 9 x 3 development run over the same compiler, registry, write-after-read verification,
and exact-suite execution boundary. Its test/staging-only POST endpoint produces three unique suite runs
and 27 unique passing child runs, keeps publication, provenance, and source-map fingerprints stable, and
observes zero in-process real external calls. After each suite completes, the service re-reads all signed
child evidence through the authorized API and closes exact run/target/Fixture identity, evidence integrity,
per-Case semantic-result fingerprints, business assertions, and Fixture-control counts. The v3 public schema
and independent Test Kit verifier require nine passing business Oracles, 27 passing assertions, stable results,
and explicit timeout-fallback, duplicate-idempotency, and forbidden-write proofs. A deployment-owned authority
can now bind the actual packaged JAR SHA-256, clean Git commit, and source revision to every canonical execution
intent; the Test Kit independently reconstructs that intent and rejects tampering. Failed-closed responses use
`NOT_VERIFIED` and do not fabricate an evidence class, publication, fingerprints, or runs. The UI deliberately
shows both "development verification passed" and "release acceptance remains closed." The four Canonical
Resource descriptors are registered in the application `ResourceRegistry`, and `RETURN` fixtures now enter as
transport-level responses through the real `HttpResourceOperator` mapping path. Their child evidence is
`CERTIFIABLE`; unresolved descriptors fail before scheduling, while output-level substitutes remain
`EXPLORATORY`. Demo descriptor registration is idempotent and fails startup on a same-ID content conflict
instead of overwriting an existing enterprise descriptor. Target-environment attestation, deployment-level egress observation, field-level source maps,
and release Owner sign-off remain incomplete, so the release gate stays `NO_GO`.

To inspect one exact Tool result without rerunning it:

1. Open `/capabilities/` and select **Tool**.
2. Run the governed `9 × 3` verification.
3. Select one round cell in the Case matrix. The exact-evidence panel reads that persisted child `runId`,
   shows the complete Tool/Contract/Dataset/Case/Binding/Fixture closure, and states that no rerun occurred.
4. Open the Feature graph from the evidence panel. The URL retains `runId`, `scenarioId`, and `nodeId`;
   refresh the page or return to Tool to confirm that the same run and focused node are preserved.

The Feature canvas intentionally shows only the focused graph path's six business nodes. The structure-only
Data Lens remains the full seven-node runtime trace, including the outer Tool `subject`. This keeps the diagram
readable without weakening the audit record. The exact-read API is test/staging-only, requires the demo Bearer
credential and `X-Purpose: CAPABILITY_STUDIO_REHEARSAL`, and never accepts payload or rerun controls.
To disable the Capability Studio sample and
open the legacy Business Mirror:

```bash
./scripts/start-visual-canvas-demo.sh --no-capability-studio --open
```

Correctness Studio still verifies the Workspace and target-catalog capabilities. Its
guided picker binds the loan-decision Graph and sole Correctness Definition automatically.
Exact deep links remain compatible under the advanced coordinate panel. To disable that
sample as well:

```bash
./scripts/start-visual-canvas-demo.sh --no-correctness --open
```

The sample contains one loan-decision correctness definition, a frozen `9 / 7 / 2`
coverage denominator, 8 governed Cases, 5 Fixture descriptors, Business Oracle and
Assertion summaries, publication metadata, and a five-axis verdict. It deliberately
advertises `correctnessWorkspaceApi=true` and `correctnessRunApi=false`: the Run view
must remain unavailable until a deployment assembles the real governed runtime. See the
[Correctness Studio demo guide](../docs/resource-gateway-correctness-studio-demo-guide.md)
for the walkthrough, exact URL, API probe, stop command, and capability boundary. The
[reference candidate API guide](../docs/resource-gateway-reference-candidate-api-guide.md)
documents metadata-only Graph/Operator/Function discovery, Correctness Target/Definition lookup,
authenticated scope, bounded cursor pagination, exact re-resolution, and enterprise Provider SPI. The
[product manual](../docs/resource-gateway-product-manual.md#38-使用-correctness-studio-定义运行与校准业务正确性)
continues with the full authoring, publication, run, evidence, calibration, and ANEKE workflow.

This packages the Spring Boot gateway with the React frontend and starts the
demo on `http://localhost:8080`. The dedicated demo script activates the `test`
profile by default so `/api/testing/**` is available; use
`--profile production --no-correctness --no-capability-studio` to demonstrate that the
testing and Capability Studio demo beans and endpoints are structurally absent. Production run
protocols also reject nested fixture, stub, binding-override, dependency-behavior, Dataset,
mirror, replay, and replacement controls before DTO deserialization and commit a payload-free
security audit. This boundary is tested for `production`, `production,test`, and
`production,staging`; zero-egress Capability execution remains a separate, unfinished Spike.
Add `--stateful` to assemble the encrypted stateful-mirror Session API and its
dedicated local data plane. Add `--scenario-batch` to start bounded autonomous
DAG workers plus isolated evidence-finalization lanes for one exact
`test`/`staging` regional queue partition.
Add `--shadow-jobs` to assemble the durable read-only Shadow submit/read/lifecycle
API. `--shadow-detached-data-plane` additionally installs the exact signed-binding
baseline/candidate connectors, independent source resolver, source-resolution
attestation store, and immutable payload-free equality policy.
`--shadow-scheduler` also starts bounded pollers. The demo still leaves the
managed signer, enterprise root-policy trust, and online egress authority
unavailable, so worker and end-to-end serving readiness remain false instead of
silently consuming work.

For frontend-only development, `npm run dev` proxies `/api` and `/admin` to
`http://localhost:8080` by default. Point it at an existing demo instance or another
worktree without starting a second database-backed server:

```bash
cd resource-gateway-examples/src/main/frontend
VITE_DEV_API_TARGET=http://localhost:18091 npm run dev
```

| Open | Best first move |
| --- | --- |
| `http://localhost:8080/` | Start the default object workbench: connect an API, create a reusable Tool, or create a Solution |
| `http://localhost:8080/capabilities/` | Inspect 4 API capabilities, 1 Feature, 1 Tool, 9 scenarios, exact refs, contracts, and the truthful acceptance state |
| `http://localhost:8080/capabilities/?lang=zh-CN&task=quality` | Inspect GP-09 quality coverage, admission blockers, the payload boundary, and each Case's exact impact closure |
| `http://localhost:8080/business-mirror/` | Open the legacy Business Mirror Portfolio, import a legacy Graph as a Package, complete guided business fields, and compile readiness |
| `http://localhost:8080/author/` | Build a schema-constrained graph on the visual canvas |
| `http://localhost:8080/agent-tdd.html` | With the separate HUMAN reviewer token, inspect Agent TDD readiness, open exact no-store review details, approve Oracle proposals, review payload-free real-integration attestation, confirm a failed sandbox retry, and sign the exact GREEN + implementation fingerprint |
| `http://localhost:8080/libraries/` | Resume durable exact revisions from status queues, discover existing DSL/API/runtime assets, create libraries, infer schemas, run exact-draft tests, and commit |
| `http://localhost:8080/rehearsals/` | Triage exact-scope Scenario batches, or use automatic Samples fallback without `--scenario-batch` |
| `http://localhost:8080/correctness/` | Inspect the exact, payload-free Correctness Workspace enabled by default; use the printed deep link rather than omitting its target coordinate |
| `http://localhost:8080/showcase/` | Run real Gateway examples and inspect sample outputs; diagram JSON and the legacy runner stay under Advanced |
| `http://localhost:8080/examples/gateway` | Use the legacy Custom Composer regression surface |
| `http://localhost:8080/api/integration/capabilities` | Verify protocol versions, endpoints, feature flags, identity provider, payload policy, and signer readiness |
| `http://localhost:8080/api/capability-studio/demo-pack` | Read the test/staging-only payload-free golden demo projection when the Capability Studio sample is enabled |
| `http://localhost:8080/api/capability-studio/acceptance-baseline` | Read the truthful Stage 0 `NO_GO`/`NOT_RUN` acceptance projection; this is not runtime evidence |
| `GET http://localhost:8080/api/capability-studio/scenario-dataset` | Read the strict payload-free nine-Case Dataset projection with exact refs, business quality, source, Oracle, Contract, and behavior metadata |
| `GET http://localhost:8080/api/capability-studio/scenario-dataset/quality-impact` | Read the strict GP-09 quality/admission/impact projection; requires Bearer authentication plus `X-Purpose: CAPABILITY_STUDIO_REHEARSAL` and is independently verifiable with `CapabilityStudioScenarioQualityImpactVerifier` |
| `GET http://localhost:8080/api/capability-studio/tutorial-branch` | Read the durable tutorial head revision and content fingerprint |
| `PUT http://localhost:8080/api/capability-studio/tutorial-branch/behaviors/compensation-history` | Save a strict business-shaped timeout behavior with optimistic revision control; test/staging only |
| `POST http://localhost:8080/api/capability-studio/tutorial-branch/preflight` | Prove exact branch binding, zero unresolved dependencies, zero real calls, and no real-service fallback |
| `GET http://localhost:8080/api/capability-studio/feature-rehearsal?caseId=case-compensation-history-timeout&permission=STRUCTURE_ONLY` | Run the non-production cancellation Feature through BLOGE and read the payload-free 6-node/5-edge Trace; use `PAYLOAD_VISIBLE` only for controlled demo values |
| `GET http://localhost:8080/api/capability-studio/feature-rehearsal-baseline` | Run the fixed 9 Case × 3 round development baseline and read payload-free Oracle, semantic/business fingerprint, operator side-effect, unique Run ID, and zero in-process real-call evidence; strict v1 Schema and independent Test Kit verification; `DEVELOPMENT_TEST_OWNED`, not release acceptance |
| `POST http://localhost:8080/api/capability-studio/governed-baseline` | Run the Tool page's governed 9 × 3 development verification through the existing compiler, application Resource Registry, real Resource Operator mapping, exact-suite runtime, signed child-evidence readback, and independent v3 verifier; returns 3 suite/27 child runs, 9/9 business Oracles, 27/27 assertions, stable semantic results, three high-risk proofs, and `CERTIFIABLE` child evidence; a clean script-launched artifact also exposes its candidate build and execution-intent fingerprint, while `NO_GO` remains until target-environment attestation, deployment egress, and Owner sign-off exist |
| `GET http://localhost:8080/api/capability-studio/governed-runs/{runId}/evidence?expectedCaseId={caseId}` | Read one persisted child run without rerunning it; returns the strict v1 structure-only exact Tool/Contract/Dataset/Case/runtime/Binding/Fixture/Behavior/source-map/provenance closure, focus node, full Data Lens, and deterministic fingerprints; requires Bearer authentication plus `X-Purpose: CAPABILITY_STUDIO_REHEARSAL` and is independently verifiable with `CapabilityStudioGovernedRunEvidenceVerifier` |
| `POST http://localhost:8080/api/mirror/sessions` | Create an encrypted stateful simulation Session after starting with `--stateful` (test/staging only) |
| `GET http://localhost:8080/api/mirror/sessions/{sessionId}/write-attempts/{attemptId}` | Read one authenticated payload-free durable write outcome for recovery or governance evidence |
| `POST http://localhost:8080/api/mirror/sessions/{sessionId}/checkpoints` | Sign a payload-free exact Session/store-generation checkpoint after starting with `--stateful` |
| `POST http://localhost:8080/api/mirror/sessions/{sessionId}/recoveries` | Re-verify a checkpoint against the current encrypted data-plane head and return an exact run binding |
| `POST http://localhost:8080/api/mirror/rehearsal-jobs` | Submit an exact-plan batch after starting with `--scenario-batch`; the script aligns the worker partition with the demo identity |
| `GET http://localhost:8080/api/mirror/rehearsal-jobs?limit=25` | List the authenticated exact scope's newest payload-free batches with immutable creation-time keyset pagination for the Owner workbench |
| `GET http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/items` | Read stable payload-free item pages while bounded regional workers progress the batch |
| `GET http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/items/{itemIndex}/attempts` | Read the exact payload-free attempt timeline, retry budget, batch fallback, and optional fingerprint-bound Author target; older retained jobs remain explicitly aggregate-only |
| `GET http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/evidence` | Read one signed payload-free terminal batch index whose request, manifest, item results, and child evidence references can be verified offline |
| `GET http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/workbook-seed` | Read one bounded, root-signed ANEKE batch correctness projection; Test Kit verifies it without one request per child (`X-Purpose: GOVERNANCE_EVIDENCE_INGESTION`) |
| `GET http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/finalization` | Inspect payload-free `PENDING`, `SIGNING`, `RETRY_WAIT`, `QUARANTINED`, or `FINALIZED` evidence-publication state without exposing worker or signer diagnostics |
| `GET http://localhost:8080/api/mirror/rehearsal-jobs/finalization-health` | Read the authenticated enterprise scope's payload-free backlog, age, quarantine, policy-drift, and failure-class SLO projection |
| `POST http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/finalization/remediations` | Compare-and-set one reviewed `QUARANTINED` generation into a new immutable intent and renewed retention floor (`X-Purpose: MIRROR_REHEARSAL_FINALIZATION_ADMIN`) |
| `POST http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/remediations` | Freeze one blocked signed predecessor and its exact successor proposal for two-person business review (`X-Purpose: MIRROR_REHEARSAL_REMEDIATION`) |
| `GET http://localhost:8080/api/mirror/rehearsal-remediations/{remediationId}` | Read the content-addressed plan, approval chain, derived state, and optional successor receipt |
| `GET http://localhost:8080/api/mirror/rehearsal-remediations/{remediationId}/comparison` | Reconstruct resolved, remaining, and introduced blockers from the submitted lineage and two independently verified root-signed batch workbooks |
| `POST http://localhost:8080/api/mirror/rehearsal-remediations/{remediationId}/approvals` | Append one server-authorized `OWNER` or `INDEPENDENT_REVIEWER` decision with generation CAS |
| `POST http://localhost:8080/api/mirror/rehearsal-remediations/{remediationId}/submissions` | Atomically admit the exact frozen successor after both distinct human approvals |
| `GET http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/retention` | Rebuild and verify the signed batch retention projection (`X-Purpose: GOVERNANCE_EVIDENCE_INGESTION`) |
| `POST http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/retention/holds` | Place an independent batch legal hold without replacing other holds (`X-Purpose: LEGAL_HOLD`) |
| `POST http://localhost:8080/api/mirror/rehearsal-jobs/{jobId}/retention/purge` | Delete an eligible batch closure and return a signed logical-deletion proof (`X-Purpose: PAYLOAD_RETENTION_ADMIN`) |
| `POST http://localhost:8080/api/mirror/domain-fidelity/inventories` | Register one immutable owner-approved coverage denominator revision (`X-Purpose: MIRROR_FIDELITY_GOVERNANCE`; trusted human owner identity required) |
| `GET http://localhost:8080/api/mirror/domain-fidelity/inventories/{inventoryId}/latest` | Read and revalidate the current full-scope denominator (`X-Purpose: GOVERNANCE_EVIDENCE_INGESTION` or `MIRROR_FIDELITY_GOVERNANCE`) |
| `GET http://localhost:8080/api/mirror/domain-fidelity/domains/{domainId}/profiles/latest` | Read and revalidate the newest managed-signed profile; profile projection remains unavailable until verified source adapters are assembled |
| `POST http://localhost:8080/api/mirror/outcome-observations` | Verify, sign, and append one immutable outcome revision from an authorized customer connector (`X-Purpose: MIRROR_OUTCOME_INGESTION`; independent authority bean required) |
| `GET http://localhost:8080/api/mirror/outcome-observations/{observationId}/head` | Reverify the current business-authority closure and read its durable reconciliation head |
| `GET http://localhost:8080/api/mirror/outcome-observations/{observationId}/lifecycle?afterOrdinal=0&limit=100` | Read one bounded append-only lifecycle suffix for offline audit |
| `POST http://localhost:8080/api/mirror/outcome-selected-populations/uploads` | Begin or exactly replay one resumable selected-population upload intent (`X-Purpose: MIRROR_OUTCOME_SELECTION`) |

### Author spine rollout (A0 contract)

The additive author-spine contract is enabled only by an exact, single
`spine=v1` query parameter. Unknown, repeated, or differently-cased values
fail closed to `off`; the existing `/` Capability Studio remains the default
unless the exact `/?spine=v1` rollout URL is used. That URL now opens the
five-intent Launcher and its seven-workspace menu. Navigation coordinates are UI-only
(`toolId`, `toolName`, `stage`, and optional draft position) and must not be
written into GraphDraft or Scenario protocol payloads.

For rollback, remove `?spine=v1` from the URL. The contract helpers and their
tests live in `src/main/frontend/src/spine/authorSpine.ts`; coordinate-aware
ObjectBreadcrumb and ToolThreadRail are mounted only when a valid ToolCoordinate
is present. Responsive 390/1280 end-to-end geometry remains a later gate.

#### External API and tool authoring models (B0)

The pure model seams in `src/main/frontend/src/external-api/` and
`src/main/frontend/src/tool/` now provide deterministic form-to-descriptor and
visual-contract projections, bounded sample schema inference, draft/published
tool signatures, and existing-publication references. They are intentionally
transport- and UI-free: API persistence, Launcher integration, and the E2
390/1280 geometry gate remain later slices.

#### External API authoring (B1)

When the exact `spine=v1` rollout is active, the Author palette exposes one
inline External API object. Its Request, Response, and Output schema sections
save through the real descriptor and visual-contract PUT endpoints in order;
only a completed pair refreshes the visual operator catalog. Failed saves stay
retryable and never display response bodies. This is still a browser authoring
surface, not a GraphDraft mutation; full E2 geometry evidence remains pending.

#### Tool publication and composition (B2)

With an exact `spine=v1` ToolCoordinate, the Author palette now projects the current
GraphDraft's real lifecycle and input/output schemas. Missing draft facts remain
`Unknown`/`Opaque I/O`; the UI never invents a typed or published state. Publish uses
`POST /api/visual/drafts/{draftId}/publish` with the current revision and only treats
the returned immutable `publicationId` plus frozen source `draftRevision` as a
published tool. A successful publication refreshes `GET /api/visual/operators`, where
`resource:` entries are External APIs and `publication:` entries are Published tools;
the latter retain their frozen port types when added through the existing operator
insertion path. A publication remains visible if catalog refresh fails, with an
explicit retryable warning. Removing `spine=v1` keeps these new controls unmounted.

#### Graph-node Fixture promotion

`POST /api/visual/graphs/{draftId}/nodes/{nodeId}/fixtures:promote` turns an exact
captured node output into governed correctness material. The request is bounded to
`bloge.graphNodeFixturePromote.v1`, classification, 1--30 day retention, and redaction paths;
there is no client-controlled simulation/provenance flag. The server derives scope, lineage, schema
reference, fingerprints, retention expiry, and the DRAFT lifecycle. When the server has a recent,
matching successful `POST /api/visual/graphs/simulate` receipt for the same saved draft/node,
operator, and output, promotion records `source=SCENARIO`; otherwise it fails closed to
`source=SAMPLE`. The receipt contains only bounded fingerprints, is short-lived, and is never
derived from a client provenance flag. Invalid body fields
return 400, missing drafts/nodes return 404, absent captured output or an opaque/non-unique/incompatible
schema returns 422, and an existing governed id returns 409. A successful response is `201 Created`
with `Cache-Control: no-store`; both response and persisted audit contain no business payload.
Activation continues through the existing four-eyes Fixture governance endpoints.

#### Graph-node Fixture controls (C1a)

The independent `src/main/frontend/src/fixture-asset/` slice now owns the pure
promotion request/provenance/staleness model and reusable pin/promote/picker
controls. Its request matches the backend contract exactly: redaction paths are
bounded, non-root JSON Pointers such as `/phone`, and no client provenance flag
is sent. Only a complete governed fixture reference can produce `governed`; the
resource-node controls are mounted in AuthorCanvas.

#### Governed fixture reuse and simulation (C1b)

`GET /api/visual/fixture-assets` returns a payload-free
`bloge.correctnessApi.v1` metadata envelope. The server authenticates the caller
and derives the tenant, organization, project, environment, and region scope from
that identity; caller-supplied scope is never accepted. Every row contains
descriptor metadata, an integer reverse-index `usageCount`, and no fixture
material or business payload. Responses are `Cache-Control: no-store`.

The collection accepts `activeOnly` (default `true`), `limit` (default `50`,
maximum `100`), `offset` (`0..100000`), and optional `operatorRef`. The fixture
picker uses the default `ACTIVE` collection, defensively filters `ACTIVE` rows,
and sorts by fixture name then id for deterministic display. When `operatorRef`
is supplied, the service compares the fixture's schema fingerprint with the
operator's current unique typed output schema and returns
`currentSchemaFingerprint` plus a boolean `compatibleWithOperatorRef`; an absent
comparison is treated as incompatible. A stale reference cannot be submitted for
simulation.

Resource nodes expose three per-node fidelity selections: `OUTPUT_LEVEL`,
`PROTOCOL_DERIVED`, and `TRANSPORT_LEVEL`. The selection is request metadata;
the server decides and returns the executed `nodeFidelity` evidence, which the
AuthorCanvas trace displays instead of echoing the selected value. Requests
contain only `resourceFidelity` and, when reused, the exact governed reference;
raw bodies, material payloads, and response sidecars are not accepted. The
saved draft also carries that payload-free coordinate and requested fidelity so
reloading a graph restores the picker and fidelity choice; executable fingerprints
continue to exclude node fixtures.
The resource selector is mounted on the selected resource node in the v1 spine and
is absent when the spine is off. Reverse-index usage links are idempotent, with
a residual crash window between simulation completion and usage-link commit.

During `POST /api/visual/graphs/simulate`, a node-level governed reference is
resolved only after an authenticated material-read in the exact server-derived
scope. Resolution requires the exact ACTIVE revision, exact descriptor/schema
closure, the referenced node in the submitted draft, that operator's current
single non-opaque output schema, and a value that still validates against that
schema. Protected material remains request-scoped and is never returned as
catalog metadata. A successful simulation records both an idempotent graph-consumer
usage link and a short-lived, payload-free server capture receipt; failed simulations and
client-supplied fixture overrides do not establish promotion lineage.

#### Decision scenario enumeration

The decision-table workbench enumerates from the current editor snapshot with
`per-rule` or bounded `combinatorial` mode and a cap from `1..10000`. Enumeration
requires an authoritative target and contract fingerprint, records opaque or
non-exhaustive coverage explicitly, and stratifies truncation toward boundaries.
The supported output kinds are `object`, `scalar`, `plan`, and model-only
`dispatch`. A persisted set is marked stale when its source fingerprint no
longer matches the table; the explicit re-enumerate action rebuilds it from the
current snapshot and contract evidence.

The React workspaces support English and Simplified Chinese. Use the `EN / 中文`
segmented control in the global header; the choice persists across workspace navigation and reloads.
For deterministic demo links, append `?lang=en` or `?lang=zh-CN`, for example
`http://localhost:8080/author/?lang=zh-CN`. Operator references, DSL, JSON Schema, fixture payloads,
JSONPath expressions, fingerprints, and run identifiers are intentionally never translated.
Commands, lifecycle states, diagnostics, deep Contract/Scenario/Library surfaces, and ReactFlow
accessibility labels are localized. Protocol diagnostics keep the original code and message under
Technical details while the primary UI shows a localized title, explanation, and remediation.
Run `npm run check:i18n` from the frontend directory to enforce typed-message completeness, deep
surface coverage, dynamic registry/state-machine coverage, and locale parity. Implementation rules
are documented in
[`docs/bloge-visual-canvas-localization.md`](../docs/bloge-visual-canvas-localization.md), the shared
product vocabulary is in
[`docs/resource-gateway-localization-glossary.md`](../docs/resource-gateway-localization-glossary.md),
and Stage 4 verification is recorded in
[`docs/resource-gateway-ux-stage4-localization-governance.md`](../docs/resource-gateway-ux-stage4-localization-governance.md).

For an end-to-end product walkthrough across Business Mirror, Author, Libraries, Rehearsals, and
Showcase, with the business-correctness definition and verification-data accumulation workflow,
current Chinese screenshots, and demo/production boundaries, use the
[Resource Gateway product manual](../docs/resource-gateway-product-manual.md).

To exercise the new Business Mirror Package authoring API with the cancellation-fee fixture, use the
[Business Mirror Package Authoring guide](../docs/resource-gateway-business-mirror-package-authoring-guide.md).
It includes the required identity Scope, create/save/read/replay commands, stable error recovery,
PostgreSQL migration boundary, compile/read commands, and stop command. The
[Capability Proposal Authoring guide](../docs/resource-gateway-business-mirror-proposal-authoring-guide.md)
covers create/save/history, exact replay, strict isolation and offline verification. The
[Capability Proposal Simulation guide](../docs/resource-gateway-business-mirror-proposal-simulation-guide.md)
covers exact Package/Graph/Capability/Suite/Fixture prerequisites, isolated execution, durable
replay, layered evidence, offline verification, PostgreSQL deployment, and the hard boundary between
`SIMULATED` and implementation/conformance. The
[Implementation Binding guide](../docs/resource-gateway-business-mirror-implementation-binding-guide.md)
covers the runtime-owned adapter, exact Proposal/Simulation/Contract/implementation closure,
immutable signed binding, PostgreSQL deployment, independent verification, and the separate
capability signals for binding API versus customer runtime readiness. The
[Implementation Conformance guide](../docs/resource-gateway-business-mirror-implementation-conformance-guide.md)
covers target-only plan derivation, exact same-suite execution, fixture-to-real behavior
fingerprints, signed durable reports, `CONFORMANT` Proposal state, PostgreSQL fencing, offline
verification, and failure recovery. The
[Business Asset Impact guide](../docs/resource-gateway-business-asset-impact-index-guide.md)
covers deterministic L0-L3 transitive impact, transactional projection outbox, cross-replica
worker leases, `CURRENT/STALE` freshness, bounded rebuild, exact Deep Links, PostgreSQL deployment,
and Test Kit offline verification. Successful Package compilation now also admits a payload-free
Package Evidence projection job in the same transaction. The evidence service persists independent
L0/L1/L2/L3/Calibration conclusions and the existing seven Fidelity dimensions without producing
an overall score. Current Package indexes and domain portfolios are available from
`/api/integration/domain-capability-packages/{packageId}/evidence-index` and
`/api/integration/domain-portfolios/{domainId}`. Authoring routes can refresh an index and manage
optimistic owner tasks; resolving a task requires an exact evidence reference. The
[Package Evidence and Fidelity guide](../docs/resource-gateway-package-evidence-and-fidelity-guide.md)
covers the five proof layers, seven-dimensional Fidelity model, capability probe, Author/Integration
API, worker recovery, PostgreSQL migration, fixed reference evidence, and offline Test Kit
verification. The
[production Outcome Source guide](../docs/resource-gateway-production-outcome-source-guide.md)
defines the deployment-owned live baseline, bounded source adapter, independent page/command
authority, durable stage/apply/commit checkpoint, Backfill and generation-revocation operations,
dynamic readiness, PostgreSQL migration, incident handling, and the boundary between repository
certification and a real customer connector. The
[Regional Data Plane certification guide](../docs/resource-gateway-regional-data-plane-certification-guide.md)
defines the seven-component deployment contract, short-lived externally signed certification,
KMS/CA age and overlap checks, v2 isolation binding, three runtime rechecks, restart-safe persistence,
dynamic capability probe, offline Test Kit verification, and the boundary between repository
enforcement and customer-owned KMS/Vault/PKI/network infrastructure. The
[ANEKE Package integration guide](../docs/resource-gateway-aneke-package-integration-guide.md)
defines protocol 1.1 compatibility, immutable registry-ingest closure, signed external governance
projection, monotonic generation persistence, stale/expired behavior, Test Kit verification,
PostgreSQL certification, startup steps, and the RG/ANEKE authority boundary. The
[cancellation-fee pilot acceptance guide](../docs/resource-gateway-cancellation-fee-pilot-acceptance-guide.md)
defines the owner-frozen denominator, ten non-waivable exit gates, exact evidence kinds,
observation window, customer decision boundary, reference fixture, and independent fail-closed
verification. The
[Business Mirror Workspace guide](../docs/resource-gateway-business-mirror-workspace-guide.md)
covers the default Portfolio, seven-step Package workflow, L0-L3 map, browser task, VS Code offline
mode, and recovery semantics.

Existing graphs can be migrated incrementally without rewriting their topology. The
[Legacy Graph migration guide](../docs/resource-gateway-business-mirror-legacy-migration-guide.md)
covers the seven built-in Graph previews, formal gap inventory, fixed compatibility fixture,
idempotent Package import, compilation, and offline Test Kit verification. Migration deliberately
keeps inferred business fields blocked and does not relabel Contract tests as governed Scenarios.

The deterministic Package compilation kernel, frozen dependency authority port, readiness semantics,
and offline verification boundary are documented in the
[Business Mirror PackageCompiler guide](../docs/resource-gateway-business-mirror-package-compiler.md).
The compiler HTTP/persistence vertical slice is available at
`POST /api/business-mirror/packages/{packageId}/compile`. The bundled composite Authority resolves
the seven shipped Graphs and their formal Contracts through the existing DSL, operator, Resource,
Contract, and contract-test authorities. Unsupported Scenario, Fidelity, Outcome, and business-asset
kinds remain explicit `MISSING` observations, so an incomplete Package still returns a durable
`BLOCKED` report instead of being mistaken for publishable business capability.

Author Workspace v2 now distinguishes **recoverable** from **saved**. After an edit, the lifecycle
beside the draft identity advances from `DIRTY` to `RECOVERABLE` when the current tab has captured a
TTL-bound recovery snapshot. The Save icon creates or updates the authoritative server revision.
Cross-workspace links flush the newest graph, fixture, Scenario, and operator-suite authoring state
before navigation; a decision dialog appears only when that flush fails. Returning to `/author/`
restores the same session automatically. Browser demo storage is session-ephemeral, while VS Code
and enterprise hosts can inject a `HOST_ENCRYPTED` recovery store. The behavior, security boundary,
and verification commands are documented in
[`docs/resource-gateway-ux-round3-s0-workspace-continuity-implementation.md`](../docs/resource-gateway-ux-round3-s0-workspace-continuity-implementation.md).

Author edits are also reversible. Save, Undo, and Redo live beside the draft identity and expose
platform keyboard shortcuts. Deleting a node with fixtures, operator tests, publications, or an
output binding first shows an exact impact inventory; confirmation commits the node, connected
edges, and validation assets as one transaction, so one Undo restores all of them. Example/DSL
imports, bindings, contracts, Scenarios, Decision Tables, Transform mappings, and applied layouts
share the same bounded mutation journal. Runtime history is capped at 100 entries/20MB and the
copy embedded in recovery storage is capped at the nearest 24 entries/1.5MB. Details and browser
evidence are in
[`docs/resource-gateway-ux-round3-s1-reversible-mutations-implementation.md`](../docs/resource-gateway-ux-round3-s1-reversible-mutations-implementation.md).

The header also provides `Comfortable / Compact` density modes. Comfortable is the default;
Compact persists across reloads and tightens spacing without reducing the text-size floor. At
840px and below, workspace navigation is an explicit two-column disclosure instead of a hidden
horizontal scroller. At 390px, Compose keeps the primary action plus collapsible Readiness and
Tools, while Contract/Scenarios/Evidence remove duplicate command summaries. Touch and coarse
pointer controls remain at least 40px in both density modes. Run `npm run check:ux` to enforce the
typography, density, touch-target, mobile-navigation, task-layout, and scroll-affordance contracts.
Scenario Case uses an explicit mobile task projection: **Run** shows a Case picker, four readiness
facts, targeted edit links, and one run command; **Build** mounts only the active Input, Fixtures,
Expected, or Run step, so inactive complex editors leave the tab order. Formal mobile tasks hide the
duplicate shell handoff and keep the run command above the fold. **Topology** opens as a temporary
304px overlay, so the 390px task surface never collapses into a narrow residual column.
Library Workbench uses the same task policy at 840px and below: **Review** replaces the tree with an asset
picker and one readiness summary; **Edit basics** autosaves only bounded metadata. Nested schemas,
signatures, test definitions, and runtime governance remain unchanged and expose an exact
draft/revision/asset desktop deep link instead of a compressed pseudo-editor.
Library save/readiness/runtime messages use stable `messageId + params` descriptors. Runtime reason
codes map to localized product conclusions; the original protocol message is available only in the
collapsed Technical details area, so dynamic counts and new server messages cannot silently fall
back to English in the Chinese workspace.
Auto Layout uses the same descriptor boundary: geometry and perception engines return metrics and
stable reason codes, while the presenter owns localized summaries, notices, numeric formatting, and
collision coordinates. Candidate review always describes the candidate's own perceptual report;
changing the locale does not recompute or mutate pending node positions.
The seven built-in Run examples graphs and four Rehearsal samples also use typed bilingual metadata.
Unknown server-owned scenarios remain verbatim. Sample-only evidence times are relative to the batch
anchor, so a demo remains temporally coherent; live server evidence keeps localized absolute time.
Known Rehearsal blocker codes project to localized business reasons in the root summary; raw codes remain
available only in collapsed Technical details. In **Samples**, open **Grounding policy regression**, select
execution `#0`, then choose **Run sample retry** to replace the blocked illustrative predecessor with the
deterministic **Release candidate ready** successor. The receipt explicitly states that this browser-local
recovery produces no governance evidence; **Reset sample** restores the original failure for another demo.
Static `t()`, typed `m(messageId, params)`, and registered dynamic `d(value)` calls have separate
contracts. Critical surfaces reject new `t(variable)` calls in CI. Unknown dynamic product text is
replaced by a localized conservative conclusion while raw protocol detail stays collapsed.
Matrix keeps Behavior, Proof strength, Freshness, and Governance eligibility independent. A row is
publish-eligible only when execution succeeds, authored assertions pass, evidence is current, and
proof strength is `CERTIFIABLE`; a Mock pass remains useful authoring feedback but never becomes a
publish credential. Not-run rows show Freshness as **Not evaluated**, not **Current evidence**.
Implementation and browser evidence are recorded in
[`docs/resource-gateway-ux-round3-s3-proof-semantics-localization-implementation.md`](../docs/resource-gateway-ux-round3-s3-proof-semantics-localization-implementation.md).

At 840px and below, Scenario Matrix now projects canonical rows into vertical result summaries instead
of compressing the desktop table. Three cases remain comparable at 390px, and one click opens a
diff-first Expected/Actual/Diff view. Selection, expansion, focus, and task mode survive transitions
between compact and desktop layouts; the full table returns at 1024px. Canvas Overview intentionally
hides unreadable body text and edge labels, while Focus/Inspect enforce a 12px effective title floor.
Library runtime readiness groups affected assets by root reason and shows only one highest-priority
blocker for the selected asset. The responsive contract, viewport measurements, screenshots, and
verification record are in
[`docs/resource-gateway-ux-round3-s4-responsive-projection-implementation.md`](../docs/resource-gateway-ux-round3-s4-responsive-projection-implementation.md).

Top-level workspaces are route-lazy and are prefetched only after pointer or keyboard navigation
intent. The production build enforces a `180 KiB` application-shell budget and a `350 KiB` maximum
for every application chunk, plus a `350 KiB` gzip budget for each route's complete static JS/CSS
startup closure. The current shell is `21.17 KiB` (`7.61 KiB` gzip), the largest Author interaction
chunk is `308.05 KiB` (`91.27 KiB` gzip), and the complete Author startup closure is `348.17 KiB`
gzip. In a VS Code WebView,
a versioned bridge replaces fetch and browser recovery
with correlated host requests and a `HOST_ENCRYPTED` store. The extension host requests disposal,
waits for every authoring surface to flush, and destroys the panel only after a ready receipt.
Implementation details and the honest E2/E3 boundary are documented in
[`docs/resource-gateway-ux-round3-s5-performance-host-evidence-implementation.md`](../docs/resource-gateway-ux-round3-s5-performance-host-evidence-implementation.md).
The runnable reference host, security boundary, real VS Code findings, geometry measurements, and
screenshots are in
[`docs/resource-gateway-ux-round3-s5-vscode-host-integration.md`](../docs/resource-gateway-ux-round3-s5-vscode-host-integration.md).
The fixed-task study, evidence schema, empty template, and evaluator for twelve target users plus two
teams over two release cycles are in
[`docs/resource-gateway-ux-round3-e3-e4-field-study-runbook.md`](../docs/resource-gateway-ux-round3-e3-e4-field-study-runbook.md).

For the shortest trustworthy Author demo, load **Loan policy fallback** from the start dialog. Its
card previews Graph/Contract size, three runnable `GOLDEN`/`NEGATIVE`/`BOUNDARY` cases, mocked
operators, and exploratory proof strength before loading. Open **Scenarios**, select rows and choose
**Run selected (N)**: selection stays exact across filter/sort changes, rows retain independent
Execution/Assertions/Freshness/Proof verdicts, and the batch remains in Matrix. Use **Why** for the
first row-level failure or **Open** to edit one case in Given/Dependencies/Then. **Run current case**
from that Case opens the bound Run Evidence with assertion comparison, terminal output,
mocked/real node boundary, and exploratory content fingerprint. A green execution is deliberately
still `Evidence incomplete` until Contract and Governance are checked.

Contract, Scenarios, and Evidence own their visible commands: the global header no longer runs a
hidden current Case on those surfaces. Matrix buttons expose `SELECTION` or `SUITE`, the exact Case
count, and a payload-free preview fingerprint; execution locks selection and the Server batch returns
the authoritative ordered closure and canonical fingerprint. The preview fingerprint is a UI
coordinate, not a substitute for the server receipt. After submission, a visible payload-free Command
receipt carries the same correlation id from UI intent through server admission to each Matrix row's
Proof. It keeps preview and canonical fingerprints separate, so users can verify what they requested
and what the server actually admitted without exposing Scenario, fixture, or output values. A local
single-Case run projects the same receipt into Run Evidence.

Graph and Operator Scenario surfaces now consume one canonical task-state projection. A blocked Run
always includes a stable reason, a human explanation, and a direct remediation action; no secondary
single-Case Run can bypass it. Each target retains its own exact coordinate and simulation response,
so Graph and Operator Evidence cannot overwrite or visually validate one another. For a focused
Operator demonstration, double-click **Fetch applicant**, open **Contract -> Open Contract
Workspace**, and run the default Case. The wrapped `params` input and `payload` output are validated
at their real port boundaries. `CURRENT` means the result matches the visible target;
`EXPLORATORY`, `DURABLE`, and `GOVERNED` separately state what that evidence may be used for. The
engineering invariants and verification record are in
[`docs/resource-gateway-ux-stage2-task-state-and-evidence-trust.md`](../docs/resource-gateway-ux-stage2-task-state-and-evidence-trust.md).

The Scenario workbench now defaults to seven decision columns: **Case, Result, Given,
Dependencies, Assertions, Duration, Currentness**. Use the Failed/Changed/Impacted/Stale/Unproven
facets to narrow work, then **Inspect** for subject-under-test boundaries and field-level
Expected/Actual/Diff. Unsaved built-in examples run locally, so **Load example -> Loan policy
fallback -> Scenarios -> Run all** produces three current results without a save prerequisite.
**Open** uses the same Given/Dependencies/Then/Review & run rail for Graph, Operator, and Function
cases. Preset creation supplies schema-aware Golden, Negative, Boundary, and Regression starting
data while keeping unknown negative oracles visibly unresolved. Design and verification details are
in [`docs/resource-gateway-ux-stage3-table-driven-testing-workbench.md`](../docs/resource-gateway-ux-stage3-table-driven-testing-workbench.md).

Choose **Scenarios -> Coverage** to inspect six separate denominator inventories: Case intent,
Contract boundaries, DAG paths, dependency behaviors, assertions, and current Evidence. The view
never hides them behind one percentage. Select **Target gap** when useful, set the deterministic
seed and case/work-unit budgets, then choose **Generate candidates**. Boundary, invalid-input,
error-contract, and known dependency-behavior generators create an ephemeral review set; they do
not edit the Scenario set on open. Every row names its coverage contribution, generator version,
and `Needs oracle` state. **Accept** is the only transition into canonical Scenario authoring;
after one acceptance, the remaining source-bound candidates become stale and must be regenerated.
Open **Case** to author the expected business assertion before any promotion decision. Pairwise is
shown honestly as `Not installed` until an independently audited generator adapter is configured.

For the server-authoritative batch path, save the example Graph, complete the explicit Contract
compatibility review/rebase, then choose **Run all**. The Matrix shows one durable Server batch with
its exact closure, row progress, cancellation, retry attempts, and promotion result. A conclusive
full run becomes the baseline; **Run failed (N)**, **Run changed (N)**, and **Run affected (N)**
preview their exact payload-free selection and are disabled at zero. Editing one Given cell updates
the changed/affected counts immediately. Partial differential runs remain `Partial only`; cancelled
or budget-stopped runs cannot become baselines. Batch endpoints are test/staging-only, use
`X-Purpose: TEST_EXECUTION`, and are advertised by the integration capability probe.

To import existing test data, save the complete example Workspace once; Graph and Scenario are
atomically persisted against the same server Contract, so first save no longer requires rebase.
Then open **Scenarios → Matrix → Import cases**. Choose CSV or JSON, or use
**Load sample**, and proceed through **Inspect source → Map columns → Review plan → Materialize**.
The browser masks sensitive preview fields; the server independently re-parses the bounded source,
checks the exact source/mapping/Contract/target closure, and returns a payload-free durable receipt.
The receipt stores hashed row identities, never source business keys. This API is advertised and
available only with the test/staging testing control plane.

For 501–10,000-case integrations, do not download the full Scenario set for table navigation. Use
the source-bound Matrix API. The first request sends the exact stored revision/fingerprint and an
empty cursor; subsequent requests preserve the same filter/sort and pass `nextCursor` unchanged:

```text
POST /api/visual/scenario-draft-sets/{id}/matrix/query       X-Purpose: TEST_SUITE_READ
POST /api/visual/scenario-draft-sets/{id}/matrix/bulk-edits  X-Purpose: TEST_SUITE_WRITE
```

The query returns at most 200 rows, each with its canonical index and case fingerprint. Bulk edit
requires both the draft fingerprint and every touched case fingerprint, applies at most 5,000 cells
as `ALL_OR_NOTHING`, and returns a payload-free receipt. A stale editor receives fingerprint-only
conflict coordinates and must refresh; it never overwrites another author. A set may contain 10,000
cases, but one suite run remains limited to an exact 500-case shard. See
[Stage 5 verification](../docs/resource-gateway-table-driven-testing-stage5-verification.md).

For the shortest Library demo, open **Libraries**. The first screen lists durable drafts with
Recent, Mine, confirmation, runtime-drift, test-gate, and ownership queues. **Resume rN** opens the
exact mutable revision; an older deep link opens a read-only snapshot with **Resume latest** and
**Fork this revision**, so history cannot overwrite the current head. Choose **Create library**,
then **Customer Support Triage** under **Complete examples**; select an operator or built-in
function in the left tree and edit its structured fields. The right side is a
server-authoritative canonical preview: autosave updates the exact revision in the URL,
diagnostics jump back to their source field, and **Import Design Catalog** is enabled only while
that exact preview remains importable. An ETag conflict opens a side-by-side fact comparison.
**Fork local work** creates a new saved draft without overwriting the head; **Reload latest** only
discards local edits after a second explicit confirmation. A response-lost fork retry reuses the
same reserved coordinate and adopts it only when the stored document is identical.

Library edits share the Author continuity kernel: a recovery checkpoint is captured before the
700ms authoritative autosave, route/page/VS Code disposal uses the same leave guard, and the
Workspace Context Bar distinguishes recoverable local work from a saved revision. Returning to
**Libraries** after an immediate route change restores the draft, selection, and expected revision.
See the [S0/S1 resilience closure](../docs/resource-gateway-ux-round3-s0-s1-resilience-closure.md).

To experience schema inference, choose **Infer from Samples** on the Library start page and keep
the two prefilled JSON records. **Create and analyze** creates a minimal operator draft; then
**Analyze samples** shows the nested candidate, payload-free field observations, and every
required declaration decision. **Use recommendations** is an explicit bulk choice, not an
automatic promotion. **Apply declared schema** remains disabled until all decisions are present,
then the server replays the exact request and atomically returns the next draft revision. The
operator field tree expands the applied object instead of flattening it to `any`.

To migrate existing assets, choose **Discover Existing Assets**. **Runtime**, **BLOGE DSL**,
**Capability**, **AsyncAPI**, and **OpenAPI** each have a runnable starting point. **Scan source**
returns one fingerprinted fact projection with assets, dependencies, runtime parity, and a review
queue. Declared API/catalog contracts can open directly as a structured draft. DSL scans stay
topology-first because usage alone cannot prove input/output schemas; **Open Graph Author** uses a
bounded one-time same-origin handoff, then automatically previews, renders, and lays out the DSL.
Only exact authoritative contracts show `BOUND`; an implementation with no framework signature
metadata remains honestly `RUNTIME DISCOVERED`.

To experience exact-draft tests, open **Customer Support Triage**, select
`support:classify-ticket`, and choose **Open test table**. Pick a case on the left and edit its
schema-generated **Given** inputs and **Then** mocked outputs on the right; Raw JSON is available
only under **Advanced JSON**. The generated case checks the uncommitted canonical schemas and is
labelled `SCHEMA CONTRACT`. Select the `trim` function to see signature arguments as named fields
and run its generated boundary case for a real `BOUND` BLOGE runtime call; custom
`support.normalizeText` remains visibly `UNBOUND` instead of producing a false pass. These rows
are ephemeral and fingerprint-bound. In a `test`/`staging` deployment, use **Save fixture** on any
test case or **Save samples as fixture** after sample analysis. A right-side sheet collects data
classification, retention, JSON Pointer redaction, optional prior revision, and a redacted payload
preview without opening a second dialog. It then binds the
exact draft and asset fingerprints, redacts sensitive fields, encrypts the payload, appends a
payload-free audit event in the same transaction, and erases ciphertext at retention expiry. The
success receipt intentionally shows fingerprints and `Payload returned: No`, never the raw test
data. Function rows execute through the `bloge-core-isolated-process.v1` one-shot JVM worker;
the test header shows `Runner ISOLATED PROCESS`, and evidence binds that profile plus the exact
runtime fingerprint. The local worker has bounded heap/metaspace/direct memory, a 250 ms invocation
watchdog, a 2 second supervisor kill, a 15 second suite budget, fail-fast concurrency limits, a
cleared environment, and an ephemeral working directory. It intentionally accepts only trusted
pure BLOGE core callables; custom binary functions still require a container or remote sandbox.

The `test` profile supplies a deterministic local-only fixture key so the demo script works without
secret setup. A `staging` start fails closed unless both `RG_AUTHORING_FIXTURE_ACTIVE_KEY_ID` and
`RG_AUTHORING_FIXTURE_KEY_RING` are supplied; the key ring uses comma-separated
`keyId=base64Encoded32ByteKey` entries and must retain old read keys during rotation.

| `PUT http://localhost:8080/api/mirror/outcome-selected-populations/uploads/{uploadId}/chunks/{chunkIndex}` | Stage or exactly replay one manifest-declared content-addressed chunk |
| `GET http://localhost:8080/api/mirror/outcome-selected-populations/uploads/{uploadId}` | Read payload-free durable progress, expiry, and finalization state |
| `POST http://localhost:8080/api/mirror/outcome-selected-populations/uploads/{uploadId}/finalize` | Finalize a complete upload through the existing governed population admission |
| `DELETE http://localhost:8080/api/mirror/outcome-selected-populations/uploads/{uploadId}` | Abort an open upload and destroy its staged chunks |
| `POST http://localhost:8080/api/mirror/outcome-continuous-assessments` | Register or exactly replay one server-owned continuous completeness projection (`X-Purpose: MIRROR_FIDELITY_GOVERNANCE`) |
| `GET http://localhost:8080/api/mirror/outcome-continuous-assessments/{projectionId}` | Read database-observed freshness, authority readiness, worker state, and the latest immutable assessment reference |
| `GET http://localhost:8080/api/mirror/outcome-continuous-assessments/{projectionId}/lifecycle?afterOrdinal=0&limit=100` | Read and independently chain one bounded append-only projection lifecycle page |
| `POST http://localhost:8080/api/mirror/outcome-continuous-assessments/{projectionId}/remediations` | Requeue one exact reviewed quarantine under projection/lifecycle double CAS (`X-Purpose: MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ADMIN`) |
| `POST http://localhost:8080/api/mirror/shadow/source-bindings` | Resolve an exact candidate evidence bundle, double-address and sign one payload-free detached source pair (`X-Purpose: MIRROR_SHADOW_SOURCE_ADMIN`; explicit source-binding protocol header required) |
| `GET http://localhost:8080/api/mirror/shadow/source-bindings/{bindingId}/revisions/{revision}?fingerprint=...` | Read one exact currently valid detached source pair without latest-revision fallback (`X-Purpose: MIRROR_SHADOW`, `MIRROR_SHADOW_SOURCE_ADMIN`, or `GOVERNANCE_EVIDENCE_INGESTION`) |
| `GET http://localhost:8080/api/mirror/shadow/source-resolutions/{attestationId}/revisions/{revision}?fingerprint=...` | Read one exact signed proof that both detached sources were independently re-resolved for a stable `executionId` (`X-Purpose: MIRROR_SHADOW` or `GOVERNANCE_EVIDENCE_INGESTION`; explicit source-resolution protocol header required) |
| `POST http://localhost:8080/api/mirror/shadow-jobs` | Admit one immutable, payload-free read-only Shadow command after starting with `--shadow-jobs` (`X-Purpose: MIRROR_SHADOW`) |
| `GET http://localhost:8080/api/mirror/shadow-jobs/{jobId}` | Read the exact-scope, integrity-verified durable projection without worker owner or payload |
| `GET http://localhost:8080/api/mirror/shadow-jobs/{jobId}/request` | Read the immutable command needed for independent job verification |
| `GET http://localhost:8080/api/mirror/shadow-jobs/{jobId}/comparison` | Read the signed v3 comparison with complete online-authority proof after terminal success |
| `GET http://localhost:8080/api/mirror/shadow-jobs/{jobId}/lifecycle?afterSequence=0&limit=100` | Read append-ordered, payload-free transition facts with a monotonic cursor |
| `GET http://localhost:8080/api/integration/capability-snapshots/{capabilityId}?revision=0` | Read the latest authorized capability snapshot; use a positive revision for an exact read |
| `PUT http://localhost:8080/api/integration/capability-snapshots/{capabilityId}/revisions/{revision}` | Append one exact sealed capability snapshot revision |
| `POST http://localhost:8080/api/integration/capability-snapshots/{capabilityId}/lifecycle-transitions` | Append an optimistically fenced lifecycle-only revision |
| `POST http://localhost:8080/api/integration/capability-closures/project` | Project a portable visual `GraphDraft` into a sealed, scope-bound root-plus-external-leaf capability closure |
| `http://localhost:8080/api/gateway/graphs/contracts` | Inspect resource graph input/output contracts |
| `GET http://localhost:8080/api/testing/targets/graphs/{graphName}` | Freeze the graph/resource target fingerprint before authoring fixtures (test/staging only) |
| `POST http://localhost:8080/admin/visual-operator-library-authoring/drafts/{draftId}/fixtures` | Save one exact-draft, encrypted, redacted authoring fixture revision (`If-Match`, `X-Purpose: TEST_FIXTURE_WRITE`; test/staging only) |
| `GET http://localhost:8080/admin/visual-operator-library-authoring/fixtures/{fixtureId}?revision={revision}` | Read one authorized exact fixture material (`X-Purpose: TEST_FIXTURE_READ`; test/staging only) |
| `GET http://localhost:8080/admin/visual-operator-library-authoring/drafts/{draftId}/tests/evidence/{runId}` | Verify one immutable payload-free signed authoring-test record and recalculate `CURRENT/STALE` (`X-Purpose: TEST_SUITE_READ`) |
| `GET http://localhost:8080/admin/visual-operator-library-authoring/drafts/{draftId}/tests/gate` | Evaluate the current draft's per-asset `TEST_EVIDENCED` baseline; this is not a production publish decision |
| `GET http://localhost:8080/admin/visual-operator-library-authoring/discovery/runtime` | Discover the process-local operator/function inventory through `bloge.visualAuthoringFactProjection.v1` |
| `POST http://localhost:8080/admin/visual-operator-library-authoring/discovery/{capability-catalog,asyncapi,openapi,dsl}` | Normalize an existing source into fingerprinted facts, runtime parity, review items, and an optional safe structured draft |
| `GET http://localhost:8080/api/testing/targets/graphs/{graphName}/boundary-cases` | Generate bounded, validator-proven graph input candidates and explicit coverage gaps (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/graphs/{graphName}/property-cases?seed=...` | Generate reproducible, bounded graph trials with validator-proven shrink paths; this is an authoring plan, not execution evidence (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/graphs/{graphName}/mutation-cases?maxMutants=...` | Plan bounded, independently compiling pure-DSL graph mutants without changing external operators; planning is not execution, evidence, or a score (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/graphs/{graphName}/mutation-suites` | Freeze an exact reviewed mutation plan, baseline fingerprints, oracle suite, complete matrix, and score policy as immutable V5 (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/graphs/{graphName}/boundary-suites` | Materialize an explicitly selected, fingerprint-locked boundary-plan subset as an immutable schema-admission suite (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/graphs/{graphName}/property-suites` | Freeze one exact property plan's complete root/shrink closure against an existing assertion-bearing fixture (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/executions` | Execute an exact immutable V1-V4 suite revision, including bounded property root/shrink closures, and emit signed aggregate evidence (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/mutation-executions` | Execute an exact V5 suite baseline-first, classify every regenerated mutant, and emit signed mutation-score evidence (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-executions` | Execute one exact V1/V2/V4 suite with deterministic request v1, fixed-horizon statistical request v2/v3, or anytime-valid maximum-horizon request v4, under a cross-replica parent lease, then retain signed payload-free evidence (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-trend-analyses` | Derive and sign a bounded retained-history trend for one exact suite revision, with explicit retention/truncation gaps, execution-regime drift, case transitions, and non-causal correlation signals (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-cross-retention-trend-analyses` | Preview a signed floor/head/cursor-pinned compact-observation range; disabled unless `gateway.testing.stability-cross-retention-preview-enabled=true`, absent in production, and not advertised as a capability yet |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-observation-ledger-lifecycle-pages` | Discover and prove up to ten signed floor-retirement generations under one current-floor/head snapshot; shares the default-disabled cross-retention preview flag, is absent in production, and does not make the capability true |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-observation-ledger-lifecycle-archive-pages` | Return lifecycle v2 with the exact external archive receipt set for every retirement; requires independent caller-pinned archive trust policy, shares the non-production preview flag, and cannot downgrade to v1 |
| `GET http://localhost:8080/api/testing/stability-executions/{stabilityRunId}` | Read one retained stability analysis with its exact ordered source-run closure and detached signature (test/staging only) |
| `GET http://localhost:8080/api/testing/stability-executions/{stabilityRunId}/progress` | Poll payload-free `RUNNING`, `RECOVERABLE`, or `COMPLETED` durable parent progress; v2 distinguishes planned horizon, observed prefix, and terminal reason without exposing owner/epoch/source ids/payloads (test/staging only) |
| `POST http://localhost:8080/api/testing/suites/{suiteId}/stability-jobs` | Submit an exact stability request without blocking; returns `202`, deterministic `jobId`, query `Location`, and payload-free lifecycle (test/staging only; fresh submission requires the opt-in worker) |
| `GET http://localhost:8080/api/testing/stability-jobs/{jobId}` | Read one organization/project-scoped durable job without exposing principal, request metadata, lease fence, cancellation fingerprint, or row seal (test/staging only) |
| `POST http://localhost:8080/api/testing/stability-jobs/{jobId}/cancellations` | Idempotently cancel queued work or request cooperative running cancellation; every first command, including `COMMITTING`/terminal no-ops, commits one payload-free semantic audit event with the job mutation (test/staging only) |
| `GET http://localhost:8080/api/visual/fixture-assets` | Read a payload-free, exact-scope governed fixture metadata page with deterministic ACTIVE picker ordering, compatibility metadata, and reverse-index usage counts |
| `POST http://localhost:8080/api/testing/executions` | Run an isolated inline or governed fixture plan and retain sanitized evidence (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions` | Idempotently create an exact graph test at its first unique signal suspension (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/operators/{operatorRef}` | Idempotently freeze an exact operator test at its server-owned start gate (test/staging only) |
| `GET http://localhost:8080/api/testing/durable-executions/{runId}` | Inspect an integrity-verified, payload-free durable checkpoint view before recovery (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/worker-acquisitions` | Pull at most one authorized expired execution through an atomic payload-free worker assignment (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/owner-claims` | Re-authorize an exact expired v2 checkpoint and atomically claim its lease; this does not resume BLOGE (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/heartbeats` | Renew one exact issued recovery fence under the same authenticated authority (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/recovery-steps` | Signal one exact claimed suspension and atomically commit the next suspended or terminal boundary (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/recovery-sequences` | Automatically consume a reserved sequence of up to 16 signals across freshly claimed suspension boundaries (test/staging only) |
| `POST http://localhost:8080/api/testing/durable-executions/{runId}/terminal-recoveries` | Signal one exact claimed suspension and atomically commit only a server-derived terminal result (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/operators/{operatorRef}` | Inspect frozen binding/schema/state fingerprints and executable testability (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/operators/{operatorRef}/boundary-cases` | Project an operator input schema and generate bounded, validator-proven candidates (test/staging only) |
| `GET http://localhost:8080/api/testing/targets/operators/{operatorRef}/property-cases?seed=...` | Generate reproducible, bounded operator trials while disclosing schema-projection and generation gaps (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/operators/{operatorRef}/boundary-suites` | Materialize selected operator boundary candidates under suite-write authority (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/operators/{operatorRef}/property-suites` | Materialize the complete reviewed operator property plan as immutable executable `bloge.testSuite.v4` (test/staging only) |
| `POST http://localhost:8080/api/testing/targets/operators/{operatorRef}/executions` | Run the exact synchronous binding as a controlled one-node BLOGE graph (test/staging only) |
| `GET http://localhost:8080/api/integration/test-suites/{suiteId}/revisions/{revision}/semantic-correctness-workbook` | Export a payload-free ANEKE seed for one exact semantic suite and its verified terminal evidence (test/staging only) |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/draft` | Generate editable graph mock/table suites from graph and resource schemas |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/run` | Run schema-gated mock/table contract suites |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/suites/run-all` | Run every stored contract suite with coverage policy checks |
| `POST http://localhost:8080/api/visual/operators/tests/draft` | Generate editable operator mock/table suites from operator schemas |
| `POST http://localhost:8080/api/visual/operators/tests/suites/run-all` | Run every stored operator schema mock/table suite |
| `POST http://localhost:8080/api/mirror/plans` | Resolve reviewed graph/closure/fixture/replay artifacts and compile an immutable mirror plan (explicit mirror switch plus test/staging only) |
| `GET http://localhost:8080/api/mirror/plans/{planId}` | Read a verified payload-free mirror plan in the complete authenticated enterprise scope |
| `POST http://localhost:8080/api/mirror/executions` | Run one sealed mirror generation under durable request-id fencing and return a payload-free summary |
| `GET http://localhost:8080/api/mirror/runs/{runId}` | Read a verified payload-free terminal mirror summary in the complete scope |
| `GET http://localhost:8080/api/mirror/runs/{runId}/evidence` | Export the independently verified signed `HASH_ONLY` evidence bundle |
| `GET http://localhost:8080/api/mirror/runs/{runId}/state-workbook-seed` | Export a deterministic payload-free ANEKE seed from one verified stateful v3 bundle |
| `GET http://localhost:8080/api/mirror/runs/{runId}/state-transition-workbook-seed` | Export deterministic committed/replayed write assertions from one verified stateful v4 bundle |
| `GET http://localhost:8080/api/mirror/runs/{runId}/state-write-outcome-workbook-seed` | Export every terminal state-write attempt and its governance blockers from one verified stateful v5 bundle |
| `POST http://localhost:8080/api/mirror/trust/deployment-isolation/authority-key-sets` | Verify and append one current isolation-authority key-set generation |
| `GET http://localhost:8080/api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/latest` | Distribute the re-verified current authority floor |
| `POST http://localhost:8080/api/mirror/trust/read-only-shadow/authority-key-sets` | Root-verify and atomically append one Shadow authority key-set successor |
| `GET http://localhost:8080/api/mirror/trust/read-only-shadow/authority-key-sets/pages` | Read one frozen, contiguous Shadow authority trust-distribution page |
| `POST http://localhost:8080/api/mirror/trust/deployment-isolation/attestations` | Verify and append an operator-pinned attestation bootstrap or continuous successor |
| `GET http://localhost:8080/api/mirror/trust/deployment-isolation/attestations/{attestationId}/latest` | Read one atomic current attestation and local status bundle |
| `POST http://localhost:8080/api/mirror/trust/deployment-isolation/attestations/{attestationId}/revocations` | Irreversibly revoke one exact current attestation status |
| `POST http://localhost:8080/api/mirror/observations` | Admit or quarantine one signed payload-free capability observation under operator-owned policy and external payload-proof verification |
| `POST http://localhost:8080/api/mirror/observations/{observationId}/reviews` | Append one terminal quarantine review without changing the original admission |
| `POST http://localhost:8080/api/mirror/corpus-candidates` | Freeze ordered admitted observations into a non-serving corpus candidate and compute metadata risk |
| `POST http://localhost:8080/api/mirror/corpus-publications` | Publish one current eligible candidate after owner authorization and a second source-authority check |
| `POST http://localhost:8080/api/mirror/corpus-trajectories` | Publish one explicit owner-reviewed retry sequence from the exact current corpus |
| `POST http://localhost:8080/api/mirror/corpus-clusters` | Publish one externally validated, owner-reviewed recorded cluster without moving payload into Resource Gateway |
| `POST http://localhost:8080/api/mirror/scenarios/assertions` | Append one exact payload-free handling assertion revision |
| `POST http://localhost:8080/api/mirror/scenarios/checkpoints` | Append one live signed Session checkpoint for a stateful scenario case |
| `POST http://localhost:8080/api/mirror/scenarios/cases` | Append one ScenarioCase after its assertion/checkpoint closure exists |
| `POST http://localhost:8080/api/mirror/scenarios/packs` | Append one complete ScenarioPack revision |
| `GET http://localhost:8080/api/mirror/scenarios/packs/{packId}?revision=...&fingerprint=...` | Read one exact content-addressed ScenarioPack revision |
| `POST http://localhost:8080/api/mirror/scenarios/packs/{packId}/compiled-plans` | Resolve TestSuite, FixtureBundle, MirrorPlan and checkpoint authority, then compile a payload-free execution license |
| `GET http://localhost:8080/api/mirror/scenarios/compiled-plans/{planId}?revision=...&fingerprint=...` | Read one exact compiler-issued rehearsal plan |
| `POST http://localhost:8080/api/mirror/scenarios/runs` | Run one exact compiled Scenario plan and return an independently verified signed aggregate bundle |
| `GET http://localhost:8080/api/mirror/scenarios/runs/{runId}/evidence` | Re-read and independently verify one full-scope append-only Scenario aggregate |
| `GET http://localhost:8080/api/mirror/scenarios/runs/{runId}/workbook-seed` | Export a deterministic payload-free ANEKE seed after re-verifying Plan, aggregate evidence, retention proof, cases, assertions, and blockers |
| `GET http://localhost:8080/api/mirror/scenarios/runs/{runId}/retention` | Rebuild and verify the aggregate retention projection and latest signed event |
| `POST http://localhost:8080/api/mirror/scenarios/runs/{runId}/retention/holds` | Place one independent idempotent legal hold (`X-Purpose: LEGAL_HOLD`) |
| `POST http://localhost:8080/api/mirror/scenarios/runs/{runId}/retention/hold-releases` | Release one exact legal hold without affecting any other hold |
| `POST http://localhost:8080/api/mirror/scenarios/runs/{runId}/retention/purge` | Delete eligible aggregate evidence and return a signed deletion proof (`X-Purpose: PAYLOAD_RETENTION_ADMIN`) |

Deployment-agent authority/attestation GETs require vendor negotiation in addition to normal
`MIRROR_TRUST_DISTRIBUTION` or `MIRROR_REHEARSAL` authentication:

```http
Accept: application/vnd.bloge.mirror-deployment-isolation-trust.v1+json
X-BLOGE-Mirror-Trust-Protocol: mirror-deployment-isolation-trust-v1
```

Stop it with:

```bash
./scripts/stop-visual-canvas-demo.sh
```

Capability snapshot and closure projection endpoints require `Authorization: Bearer ...` and a purpose accepted by
the operation. Use `CAPABILITY_PROJECTION` for exact append or visual draft projection,
`CAPABILITY_GOVERNANCE` for lifecycle transitions, and
`MIRROR_REHEARSAL` or `CHANGE_SYNC` for reads. Scope and clearance come from verified identity claims;
`X-Tenant-Id` and similar headers are only consistency hints. The demo token includes these purposes, while
enterprise deployments should issue separate author, governor, and rehearsal identities.

The `/rehearsals/` Owner workbench is an evidence-first operational projection
over the protected Scenario APIs. Its left queue discovers newest batches with
stable keyset pagination; the center separates execution, evidence, blocker
assertion, governance, warning, and passed items; the evidence drawer lazily
loads case/assertion detail only after an Owner selects a terminal entry.
Running batches are labelled `Live projection` and cannot be mistaken for
publish-gate evidence. Terminal batches switch to the root-sealed
`Signed workbook`, show gate blockers, and retain a deep link in the form
`/rehearsals/?jobId=<jobId>&entry=<manifest-index>`. The browser never fetches
raw Fixture, request, response, Session state, or customer payload values.
For a blocked terminal workbook, the center also opens a
`Reviewed remediation` workflow:

1. freeze either an exact rerun or selected compiled-plan replacements against
   the signed predecessor and an exact governance-ticket reference;
2. append the Owner decision, then a decision from a distinct independent
   reviewer;
3. admit only the frozen successor using the exact approval generation/head;
4. compare predecessor and successor root-signed workbooks as
   resolved/remaining/introduced blockers and per-entry gate transitions.

The deep link then includes `remediationId`. Rejection remains immutable and
cannot reveal a submit action. The workbench still exposes no cancellation,
finalization-admin, legal-hold, purge, arbitrary JSON/DSL, or raw-payload
control. Zero-DSL case adjustment remains a later workbench phase.

The reviewed-remediation API freezes an exact successor from independently
verified predecessor workbook/evidence, requires server-authorized `OWNER`
then `INDEPENDENT_REVIEWER` facts, and atomically commits successor admission,
receipt, state, and success audit. `GET /api/integration/capabilities` advertises
`mirrorScenarioRehearsalReviewedRemediationApi`,
`mirrorScenarioRehearsalSignedRemediationComparison`, and the five exact routes only
when the isolated Mirror execution surface is assembled. Every operation uses
`X-Purpose: MIRROR_REHEARSAL_REMEDIATION`; preview and submit additionally
require a human in `RESOURCE_GATEWAY_SCENARIO_OWNER`, while independent review
requires a different human in
`RESOURCE_GATEWAY_SCENARIO_INDEPENDENT_REVIEWER`. The default demo workload
token deliberately cannot satisfy these human-role checks, and
`--scenario-batch` does not mint human credentials. The frontend therefore
keeps three explicit host credential slots: `READ`, `OWNER`, and
`INDEPENDENT_REVIEWER`. A browser or authenticated host installs short-lived
credentials before rendering the workbench:

```ts
import {
  setRehearsalRemediationCredentialsProvider,
} from './api';

setRehearsalRemediationCredentialsProvider((slot) => {
  const credential = hostIdentityBroker.current(slot);
  return credential && {
    headers: { Authorization: `Bearer ${credential.accessToken}` },
    principalLabel: credential.logSafePrincipal,
    expiresAt: credential.expiresAt,
  };
});
```

The label is display-only; the server authorizes the actual credential and
enforces actor/delegation separation. The client overwrites caller-supplied
`X-Purpose`/content headers, never persists credentials, and disables the
role's actions when its slot is missing or expired. After a host rotation,
`Refresh identities` re-reads all three slots without reloading the lineage.
A VSCode Webview should
additionally use `setBlogeApiTransport(...)` so the extension host holds bearer
material and returns only a log-safe label to the Webview.

The standalone Test
Kit exposes preview/read/approve/submit/comparison methods, validates every command and
response against the packaged Schema, and independently re-derives the complete
lineage before returning a read. `ScenarioRehearsalRemediationVerifier` can
perform the same payload-free verification offline without a server or database
connection. `findScenarioRehearsalRemediationComparison` first verifies the
lineage and both complete workbook signature closures, then uses
`ScenarioRehearsalRemediationComparisonVerifier` to reconstruct root and entry
blocker differences, gate transitions, correctness counters, replacement fences,
and the comparison fingerprint. The comparison is content addressed but is not
a third signature: its trust anchors remain the two independently signed
workbooks.

Scenario authoring now has a strict protocol base:
`resourceGateway.scenarioPack.v1`, `resourceGateway.scenarioCase.v1`, and
`resourceGateway.caseHandlingAssertion.v1`. These content-addressed,
scope-bound, payload-free assets reference existing TestSuite, FixtureBundle,
MirrorPlan, Session checkpoint, and write/state artifacts instead of creating a
second fixture or test-case format. Generation-one policy is sequential,
isolates stateful cases, denies real calls, credentials, and network egress,
and permits only `HASH_ONLY` evidence. This milestone supplies models, strict
Schemas, producer integrity checks, the standalone verifier, append-only
same-scope registration, and deterministic
`resourceGateway.compiledScenarioRehearsalPlan.v1` compilation. Compilation
fails closed unless every exact TestSuite case, FixtureBundle, MirrorPlan,
fault rule, assertion, execution-service binding, and optional signed
checkpoint agrees. The compiled object contains coordinates and policy only,
never test input or fixture payload. The synchronous Scenario runtime accepts
only an aggregate request id and exact compiled-plan ref, resolves TestSuite
input server-side, reuses the durable Mirror child-run coordinator, verifies
each signed evidence bundle, evaluates the complete handling-assertion closure,
and returns content-addressed per-case and aggregate results. The aggregate is
sealed under a separate Ed25519 signature domain, immediately re-verified,
stored append-only under complete enterprise scope, and available from an
exact run evidence endpoint. Aggregate execution now has a separate full-scope
database-clock lease, monotonic epoch, and append-only case-progress cursor.
Concurrent callers are rejected before child orchestration; after release or
lease expiry, a successor resumes at the first incomplete case. Case
checkpoint writes are fenced, while signed evidence insertion and terminal
request transition share one transaction, so stale workers cannot publish
orphan evidence. Protected Scenario run and evidence-read operations also use
the existing mandatory payload-free audit boundary. A successful run commits
its audit fact in the same transaction as evidence and request terminalization;
audit failure rolls the protected result back. Every committed aggregate
transition also appends a database-timestamped lifecycle fact containing only
scope, exact artifact references, lease epoch, cursor, reason code, and content
addresses. Lifecycle audit failure rolls the corresponding state transition
back. The independent Test Kit re-derives nested result
addresses, outcomes, summary counters, bundle identity, key policy, and the
Scenario-specific Ed25519 signature without linking server classes. Outcomes and
summary counters are server-derived;
stateful retries reach completed child idempotency before checking a possibly
advanced Session head. The final evidence commit now atomically registers a
30-day minimum retention boundary. A signed append-only event chain governs
multiple independent legal holds and database-clock purge; purge deletes only
aggregate evidence/progress, retains child Mirror evidence and tombstones, and
returns an offline-verifiable deletion proof. Retention reads and mutations
also use mandatory payload-free operation audit. The Test Kit packages strict
hold/purge/event/state Schemas and independently re-derives the latest event
address, projection closure, signing-time key policy, and Ed25519 seal.
The workbook endpoint projects the exact compiled plan, signed aggregate,
revision-one signed retention commitment, ordered case/assertion results, and
conservative blockers into
`resourceGateway.scenarioRehearsalWorkbookSeed.v1`. Its `gateReady` field is
derived, not accepted as policy input. The Test Kit can fetch the seed,
aggregate, exact plan, and both public keys with
`findScenarioRehearsalWorkbookSeed`, then independently recheck both
signatures, every content address and source join, and the gate decision before
returning the seed.
Autonomous batches add payload-free execution-control checkpoints before case
resolution, before each external case, after its durable progress checkpoint,
and before aggregate commit. Each checkpoint verifies the exact batch
owner/epoch/item fence using database time, records a monotonic heartbeat and
case cursor, and atomically observes cancellation or deadline expiry. A
running cancellation therefore converges after at most the current bounded
case; completed case progress is retained while the current batch item becomes
conservatively `INDETERMINATE`. Heartbeats deliberately do not extend the
immutable plan timeout plus commit reserve.
Every protected batch submit/read/evidence/cancel operation now uses the same
payload-free operation-audit vocabulary as individual rehearsals. Submit and
cancel success audit is committed inside the queue mutation transaction;
missing reads are audited as `NOT_FOUND`, and audit failure prevents a
protected response. Separately, the append-only
`scenario_rehearsal_batch_lifecycle_audit` records only meaningful durable
transitions (`ADMITTED`, `CLAIMED`, item terminal/retry, cancellation intent,
and evidence-backed terminalization). Heartbeats are intentionally excluded
from lifecycle audit to prevent an operational liveness signal from becoming
an unbounded governance log. A queued cancellation now follows the same signed
terminal evidence path as worker completion instead of creating an unaudited,
terminal-without-evidence job.
Batch terminalization now atomically commits signed evidence, immutable
retention registration, job/item terminal state, and lifecycle audit. The
retention floor is frozen at admission as `deadlineAt + terminalRetention`;
early completion cannot shorten it. Independent legal holds are replay-safe,
and purge re-verifies the signed bundle plus every job/item fingerprint before
deleting only the batch job, item rows, and batch evidence. Child Scenario
evidence and both audit streams remain retained. The signed purge event proves
this database-level logical deletion with exact row counts; it does not claim
physical-media, backup, WORM, or cross-region erasure.
Remote signing is isolated from the DAG queue transaction. The worker first
freezes a content-addressed finalization intent and exposes
`FINALIZING_EVIDENCE`; a separate region-bound finalizer claims it with a
database lease, prepares KMS and retention signatures outside the transaction,
then atomically publishes evidence, retention, audit, and the terminal job.
Stable signing time and KMS idempotency key make takeover and lost-response
replay byte-identical. Transient failures use bounded backoff, permanent
material/signature failures enter `QUARANTINED`, and one quarantined intent
does not block later work. The v2 evidence protocol carries this explicit
state while the Test Kit continues to verify historical v1 bundles.
An authorized owner can recover a reviewed quarantine through a strict
compare-and-set remediation command containing `commandId`,
`expectedAttemptCount`, and `expectedUpdatedAt`. Acceptance creates a new
content-addressed intent and signing id, increments the stale-lease fence,
resets the automatic attempt budget, and renews the retention floor to at
least `acceptedAt + terminalRetention`. The immutable receipt links both intent
fingerprints and is exactly replayable; stale consoles, reused command ids with
different content, non-quarantined jobs, and failed mandatory audits change
nothing.
The finalization lane also exposes one shared health policy through the
protected full-scope API, Actuator readiness, and fixed-cardinality Micrometer
gauges. A single database-clock aggregate accounts for every known and unknown
state, eligible/stale work, inconsistent records, policy-generation drift,
closed failure classes, and maximum attempt pressure. Unknown control rows and
policy drift are critical rather than silently omitted. `DEGRADED` remains
operational for reviewed quarantine or slow active signing, while `CRITICAL`
is `OUT_OF_SERVICE` and a store observation failure is `DOWN`. The protected
API includes only the authenticated tenant/organization/project/environment/
region scope; the deployment health contributor aggregates only its local
region/environment scheduler partition and is never exposed as a tenant query.
Metrics do not label region, tenant, project, job, provider, or exception text.

The conservative defaults can be tuned with the following deployment variables:

| Variable | Default | Meaning |
| --- | ---: | --- |
| `RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_OBSERVATION_INTERVAL_MILLIS` | `30000` | Monitor refresh interval |
| `RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_ELIGIBLE_BACKLOG` | `100` | Critical actionable-intent count |
| `RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_OLDEST_ELIGIBLE_AGE_SECONDS` | `300` | Critical oldest actionable age |
| `RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_ACTIVE_SIGNING_AGE_SECONDS` | `90` | Degraded live-signing age |
| `RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_QUARANTINED_BACKLOG` | `0` | Degraded quarantine count |
| `RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_CRITICAL_QUARANTINED_BACKLOG` | `100` | Critical quarantine count |
| `RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_SIGNER_UNAVAILABLE_BACKLOG` | `10` | Critical current KMS-unavailable count |
| `RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_CONTROL_UNAVAILABLE_BACKLOG` | `10` | Critical current control-store count |

`--scenario-batch` waits for the API, both schedulers, the installed SLO
monitor, and a non-critical finalization assessment before reporting the demo
ready. Use `./scripts/stop-visual-canvas-demo.sh` to stop both lanes; database
lease/epoch fencing remains authoritative if shutdown drain expires.
The capability probe therefore reports Scenario execution, evidence API,
retention API, legal hold, deletion proof, workbook seed,
`mirrorScenarioRehearsalBatchCooperativeControl=true`, and
`mirrorScenarioRehearsalBatchEvidence=true`,
`mirrorScenarioRehearsalBatchEvidenceFinalizationApi=true`,
`mirrorScenarioRehearsalBatchFinalizationRemediationApi=true`,
`mirrorScenarioRehearsalBatchEvidenceFinalizationScheduling=true`,
`mirrorScenarioRehearsalBatchFinalizationHealthApi=true`,
`mirrorScenarioRehearsalBatchFinalizationSloIntegrated=true`, and
`mirrorScenarioRehearsalBatchFinalizationSloReady=true` while the current
assessment is non-critical,
`mirrorScenarioRehearsalBatchRetentionApi=true`,
`mirrorScenarioRehearsalBatchLegalHold=true`, and
`mirrorScenarioRehearsalBatchDeletionProof=true` as available while keeping
`mirrorScenarioRehearsalEvidence=false`: enterprise policy authority,
WORM/transparency anchoring, consumer/environment certification, hard
termination of an uncooperative operator, external deletion certification, and owner UX
remain required before this becomes publish-gate evidence. See the
[scenario rehearsal compiler guide](../docs/resource-gateway-scenario-rehearsal-compiler.md).

Stored suites and fixtures now use `bloge.storedTestSuite.v2` and
`bloge.storedFixtureBundle.v2`. Their identity and database keys include tenant,
organization, project, environment, and region; a binding fingerprint also
detects indexed-scope movement. Historical v1 rows stay in separate legacy
tables and are never promoted during a runtime read. Migrate them by
re-registering the authoritative FixtureBundle and TestSuite definitions under
the destination full-scope identity, then recompiling dependent scenarios.

The capability closure projection request carries only the portable draft, positive target revision, deterministic creation time,
and a classification no higher than the caller's clearance. Tenant, organization, project, environment, region,
purpose, ownership, and `DRAFT` lifecycle are server-derived. The capability probe reports snapshot/closure
protocol, projection, seven built-in graph closures, visual draft closure projection, API, lifecycle, and the
sealed `resourceGateway.mirrorPlan.v1` and recorded-corpus `resourceGateway.mirrorPlan.v2` wire models plus
`resourceGateway.mirrorServingGenerationToken.v1` as available. Protocol availability is deliberately separate from
runtime readiness. With the mirror switch disabled, every mirror runtime flag remains false and no `/api/mirror/**`
route exists. With `RG_MIRROR_RUNTIME_ENABLED=true` under `test` or `staging`, the protected plan adapter now reports
`mirrorPlanCompilation=true` and `mirrorExternalLeafInterception=true`; the fully assembled durable run/evidence
adapter additionally reports `mirrorOperationObservability=true` and reports `mirrorServing=true` while its signer
is usable. Signer readiness is re-evaluated on each
probe; a signer outage leaves the installed endpoints discoverable but changes serving to false and execution fails
closed with `503`. Serving means the isolated exploratory API is callable. The probe separately reports
`mirrorIsolationRunTrustReady` and `mirrorCertifiableEvidenceServingReady`; only the latter means
a certification-required plan can obtain double-observed deployment trust. Exploratory runs remain
explicit, while eligible runs emit v2 `CERTIFIABLE` evidence with admitted and committed agent
snapshot references. The complete protocol and lifecycle rules are in the
[mirror schema guide](../docs/schemas/resource-gateway-mirror/README.md) and
[runtime trust-binding guide](../docs/resource-gateway-mirror-runtime-trust-binding.md).

Observation capability discovery uses the same separation. `mirrorObservationProtocol=true`
means the wire model is supported, `mirrorObservationAdmissionApi=true` means the non-production
route is assembled, and `mirrorObservationAdmissionReady=true` means both the operator-owned
policy source and external sanitized-payload reference authority are currently usable. The default
providers are unavailable, so enabling Mirror runtime does not invent trust. Producer workloads
need the dedicated `MIRROR_CORPUS_INGESTION` purpose and exact
tenant/organization/project/environment/region scope. Integration, receipt semantics, stable
errors, and outage drills are documented in the
[capability observation admission guide](../docs/resource-gateway-capability-observation-admission.md).

Corpus governance has a separate readiness boundary. `mirrorCorpusGovernanceProtocol=true` means
the review/candidate/publication wire objects and strict Schemas are supported;
`mirrorCorpusGovernanceApi=true` means the three non-production routes are assembled; and
`mirrorCorpusGovernanceReady=true` requires both the operator-owned governance policy provider and
external source-lifecycle authority to be currently available. The defaults are unavailable.
`mirrorCorpusExactResolverProtocol=true` means the runtime understands strict
`fixtureBundle.metadata.mirrorCorpus` bindings and the fixed
`OWNER_SPECIFIED -> RECORDED_EXACT -> RECORDED_TRAJECTORY -> RECORDED_CLUSTER ->
GOVERNED_REPLAY -> ABSTAINED` chain.
`mirrorCorpusResolverReady=true` is stronger: the policy provider, source-lifecycle authority,
regional `CapabilityCorpusPayloadAuthority`, shared `MirrorServingGenerationAuthority`, and
operator-owned `MirrorServingGenerationTrustProvider` must all be currently usable. The default
payload, generation, and trust authorities are unavailable, so enabling the mirror profile never
invents payload-vault or generation trust. The probe also reports
`mirrorServingGenerationFencing` and `mirrorServingGenerationAuthorityReady` from this complete
dynamic chain.
Governance workloads require `MIRROR_CORPUS_GOVERNANCE`; plan/run workloads continue to require
`MIRROR_REHEARSAL`.

Recorded retry trajectories have their own honest probe boundary.
`mirrorCorpusTrajectoryPublicationProtocol=true` advertises the strict command/publication
protocol, `mirrorCorpusTrajectoryPublicationApi=true` reports route assembly, and
`mirrorCorpusTrajectoryPublicationReady=true` requires current corpus policy, retry policy, and
source-lifecycle authorities. `POST /api/mirror/corpus-trajectories` accepts only an explicit
owner-reviewed 2..32-attempt sequence from the exact latest corpus publication. The service
revalidates source membership, grants, one request fingerprint, trace/span ordering, current retry
policy, retryable intermediate failures, and a terminal final attempt before appending the
payload-free artifact. It never infers retries from nearby observations.

`mirrorCorpusTrajectoryResolverProtocol=true` means the runtime also accepts strict
`fixtureBundle.metadata.mirrorTrajectories` bindings. Every binding must repeat the exact
capability and corpus publication selected in `mirrorCorpus`; materialization rechecks the current
trajectory head, current retry policy, source lifecycle, grants, trace/order, and response content
addresses. `mirrorCorpusTrajectoryResolverReady=true` additionally requires exact-corpus serving
authorities and the retry-policy authority to be live; it is probed independently from the
trajectory publication route so a read-only serving deployment can report its real capability.
The binding parser rejects raw values that the strict Schema rejects instead of normalizing
lowercase kinds, padded/oversized identifiers, or non-64-bit revisions. The frozen sequence is
indexed separately from standalone exact samples and is consumed by the real BLOGE one-based retry
loop. A plan is rejected before execution when a trajectory needs more attempts than the node's
`retryAttempts + 1`; sequence exhaustion never repeats the final sample or falls through to a real
external call.

Recorded clusters have a separate publication boundary.
`mirrorCorpusClusterPublicationProtocol=true` means the validation, command, and publication v1
objects plus strict Schemas are supported.
`mirrorCorpusClusterPublicationApi=true` means `POST /api/mirror/corpus-clusters` is assembled,
while `mirrorCorpusClusterPublicationReady=true` additionally requires live operator-owned
`CapabilityCorpusClusterPolicyProvider`, external
`CapabilityCorpusClusterValidationAuthority`, corpus policy, and source-lifecycle authority. The
default cluster policy and validation providers are unavailable.

Publication rechecks the current corpus and policy, exact source membership, common response
Schema, `EXACT_REPLAY + CLUSTER_MODELING` grants, retention/horizon, holdout counts, false-positive
rate, and the independently recomputed 95% Wilson precision interval. Identity-free clusters
cannot declare projections; request-projection clusters must map current request identity into
globally disjoint response JSON Pointer paths. The publication route never reads payload.

`mirrorCorpusClusterResolverProtocol=true` advertises strict
`fixtureBundle.metadata.mirrorClusters` binding and runtime `RECORDED_CLUSTER` support. Each binding
repeats the exact capability and corpus publication selected by `mirrorCorpus`. Before compilation,
the serving boundary rechecks the current cluster and corpus heads, current corpus/cluster policies,
current validation proof, every member grant/lifecycle/horizon, exact match values, distinct
identity support, and all request/representative-response content addresses. Runtime matching uses
exact JSON Pointer equality only. For request-projection clusters it first verifies every declared
source and destination, then replaces all identity destinations from the current request; a missing
path abstains and multiple matching clusters fail closed.
`mirrorCorpusClusterResolverReady=true` is the independent dynamic serving probe. Publication API
readiness does not imply resolver readiness, and both default to false until operator-owned policy,
validation, source-lifecycle, and regional payload authorities are installed.

The binding selects an exact latest publication for one exact external capability revision. Plan
creation and every runtime materialization recheck the publication head, current policy, source
lineage, exact-replay grant, retention, classification, region, tombstone state, and response
content address before freezing response JSON in the in-memory run generation. Payload bytes are
not written to the public plan, database, HTTP response, evidence, audit, metrics, or logs.
Single retryable-error observations still fail closed; only an explicitly published and
fixture-bound full attempt sequence can produce retryable runtime behavior. The immutable fact
model, fixture bindings, provider contracts, request
examples, errors, startup commands and remaining production gates are in the
[capability corpus governance guide](../docs/resource-gateway-capability-corpus-governance.md).

### Durable read-only Shadow control plane

Start only the protected queue and lifecycle surface:

```bash
./scripts/start-visual-canvas-demo.sh --shadow-jobs
```

To demonstrate autonomous bounded polling as a separate readiness fact:

```bash
./scripts/start-visual-canvas-demo.sh --shadow-scheduler
```

To install the first real payload-free detached-source data-plane slice:

```bash
./scripts/start-visual-canvas-demo.sh --shadow-detached-data-plane
```

The second command sets an exact scheduler region/environment equal to the demo
identity and waits for `mirrorReadOnlyShadowJobApi=true`,
`mirrorReadOnlyShadowLifecycleAudit=true`, and
`mirrorReadOnlyShadowScheduling=true`. It does not wait for or claim
`mirrorReadOnlyShadowWorkerReady` or `mirrorReadOnlyShadowServingReady`: the
default `GovernedReadOnlyShadowDataPlane`, immutable payload-free comparison
policy, and database-authoritative execution guard are assembled. With
`--shadow-detached-data-plane`, exact source-binding baseline/candidate
connectors plus independent source re-resolution and attestation are also
assembled. Signed current-head sampling-grant, kill-switch, and shared
guard-policy protocols plus their database publication source are also
installed, but the default dynamic authority trust store, managed signer, and
deployment egress authority remain intentionally unavailable. Therefore the
signed authority adapters fail closed before connector use, the scheduler does
not consume an attempt, and no external system is touched.

The default composition fixes the production call order: heartbeat, exact
grant/kill-switch/egress admission, shared guard acquisition, isolated baseline,
isolated candidate, terminal authority confirmation, independent source
resolution, typed comparison, and guard completion. Its connector result type
cannot carry raw request/response payloads; only signed source coordinates,
normalized fact fingerprints, and measured write-capability counters cross the
boundary. The egress proof kind is the deployment-isolation protocol's exact
`DEPLOYMENT_ISOLATION_ATTESTATION`.

`DatabaseReadOnlyShadowExecutionGuard` shares concurrency, fixed-window start
budget, circuit state, cool-down, and one global half-open probe across replicas.
The signed sampling decision owns a physical `guardScope` and exact
`SHADOW_EXECUTION_GUARD_POLICY` revision, so multiple execution projects may
share one real-source budget without allowing request JSON to choose that pool.
Random lease tokens plus monotonic epochs fence crashed workers; retries of the
same immutable execution id do not double-charge logical starts.

The online authority boundary uses three strict, payload-free publications:

- `resourceGateway.readOnlyShadowSamplingGrantPublication.v1` binds one exact
  execution scope, active state, sample ceiling, authority-owned `guardScope`,
  and exact guard-policy material;
- `resourceGateway.readOnlyShadowKillSwitchPublication.v1` carries a short-lived
  operational enable/deny decision;
- `resourceGateway.readOnlyShadowGuardPolicyPublication.v1` owns the shared
  concurrency, fixed-window rate, and circuit limits.

`DatabaseReadOnlyShadowAuthorityPublicationRepository` persists append-only
current heads under complete scope, kind, and stream identity. It serializes
competing successors, rejects rollback/fork/gap/wrong-predecessor writes, and
admits a publication only after dynamic trust verifies its exact scope, kind,
issuer, key, signature, and time window. A valid content address alone therefore
cannot create or poison a head. Concurrent first-generation writers retry the
whole transaction after a head-initialization uniqueness race; the repository
does not continue inside a database-aborted transaction. It revalidates indexed
identity against strict canonical JSON on every read. Runtime
adapters deliberately fetch only the current head and dynamically resolve the
public key on every observation. Each key is delegated to one exact scope and
publication type; retired keys accept only signatures created before
`retiredAt`, and revoked keys accept none. A sampling decision preserves both
the grant and current guard-policy attestation: an inactive/disabled successor, policy
successor, key revocation, source outage, expiry, or any exact-ref drift takes
effect without a long-lived positive cache.

`resourceGateway.readOnlyShadowAuthorityKeySetPublication.v1` now provides the
managed trust core behind that lookup. One short-lived stream binds the complete
enterprise scope, one publication kind, one issuer, a root trust domain, an
M-of-N threshold, and an accepted policy generation. Its `generation` is the
durable revocation cursor. Every previous key must remain in later generations;
key material and validity cannot be rebound, `ACTIVE -> RETIRED/REVOKED` is
one-way, and `REVOKED` can never reactivate. The database repository commits the
immutable publication and floor together, including a PostgreSQL-safe genesis
race retry. `ReadOnlyShadowAuthorityKeySetService` verifies local root policy,
all supplied root signatures, freshness, and the current floor before append,
so untrusted content cannot poison the head. The default
`ManagedReadOnlyShadowAuthorityTrustStore` re-verifies the database-current
publication on every lookup. Its bootstrap policy provider remains unavailable
by default, so no real sampling is authorized until the operator connects a
separately governed root-policy source. The strict key-set schema is packaged by
`resource-gateway-test-kit`.

Protected cross-process trust distribution is available only in explicit
test/staging Mirror composition. `POST
/api/mirror/trust/read-only-shadow/authority-key-sets` requires
`MIRROR_TRUST_ADMIN`; authentication and complete-scope binding happen before
strict JSON decoding. `GET
/api/mirror/trust/read-only-shadow/authority-key-sets/pages` requires
`MIRROR_TRUST_DISTRIBUTION` or `MIRROR_SHADOW`, plus:

```text
Accept: application/vnd.bloge.read-only-shadow-authority-trust.v1+json
X-BLOGE-Shadow-Authority-Trust-Protocol: read-only-shadow-authority-trust-v1
```

The page query identifies `publicationKind` and `issuer`, then supplies the
durable `afterGeneration` and `afterPublicationFingerprint` checkpoint. The
repository freezes one high-water head under lock and returns at most 128
contiguous successors. Consumers must independently verify every successor
before persisting `throughGeneration`; terminal pages include the complete
`highWaterPublication`, which is re-verified against the current clock so an
empty page cannot disguise an expired trust head. The standalone
`ReadOnlyShadowAuthorityKeySetVerifier` in `resource-gateway-test-kit` performs
strict Schema validation, content-address reconstruction, M-of-N root
verification, exact binding, cursor continuity, freshness, and irreversible
key-lifecycle checks without linking server or Spring classes.

The decision-publication wire schemas and independent offline verifier are
packaged by `resource-gateway-test-kit`.

Detached evidence must first become one exact signed source pair. The
registration command omits fingerprints and the seal; Resource Gateway
independently loads the exact `MIRROR_EVIDENCE_BUNDLE`, closes its
scope/run/plan/target/request/completion coordinates, computes the nested
baseline and outer binding addresses, signs, and appends the immutable
revision:

```bash
curl -i -X POST \
  http://localhost:8080/api/mirror/shadow/source-bindings \
  -H "Authorization: Bearer $SOURCE_ADMIN_TOKEN" \
  -H "X-Purpose: MIRROR_SHADOW_SOURCE_ADMIN" \
  -H "X-BLOGE-Shadow-Source-Binding-Protocol: read-only-shadow-source-binding-v1" \
  -H "Accept: application/vnd.bloge.read-only-shadow-source-binding.v1+json" \
  -H "Content-Type: application/json" \
  --data @read-only-shadow-source-binding-registration.json
```

Use the returned `bindingId`, `revision`, and `bindingFingerprint` as the v2
job's exact `sourceBindingRef`; set `sourceMode` to `DETACHED_EVIDENCE`. The v1
job request remains the online-source protocol and cannot carry a detached
reference. Neither endpoint performs latest-run inference, and a valid source
binding does not by itself make the baseline/candidate runtime connectors
ready.

When `--shadow-detached-data-plane` is enabled, the connector path accepts only
v2 `DETACHED_EVIDENCE` jobs. It resolves the exact signed binding revision,
independently verifies the exact candidate bundle, and normalizes only
payload-free evidence facts. A second resolver repeats those reads and checks
before signing
`resourceGateway.readOnlyShadowSourceResolutionAttestation.v1`. The proof binds
the stable `executionId`, request, authority admission, source binding,
comparison policy, historical source completion times, current resolution
times, normalized facts, evidence class/completeness, and zero-write counters.
It is append-only and has no latest fallback.

Producer/consumer compatibility is frozen by
[`read-only-shadow-source-resolution-stage1-v1.fixture.json`](../docs/schemas/resource-gateway-mirror/read-only-shadow-source-resolution-stage1-v1.fixture.json).
The server rehydrates and verifies the candidate evidence, source binding, and
source-resolution proof under three distinct public keys; the standalone Test
Kit independently recomputes every content address, deterministic identity,
policy fact, time relation, zero-write claim, and signature from the same file.
Run `CapabilityMirrorProtocol.readOnlyShadowSourceResolutionCompatibilityFixture().verify()`
in dependency and crypto-provider upgrade probes. A passing fixture proves wire
compatibility only; it does not claim current authority, data-use permission,
or online connector readiness.

Online baseline acquisition is a separate, mutually exclusive deployment mode.
It does not send a customer request, response, endpoint, or credential through
Resource Gateway. A v1 online Shadow job becomes a payload-free
`resourceGateway.onlineReadOnlyShadowBaselineCommand.v1`; the regional TEE
sidecar resolves the scenario request, production read binding, short-lived
workload identity, and payload-vault policy inside its own trust domain. It
returns a content-addressed
`resourceGateway.onlineReadOnlyShadowBaselineObservation.v1` carrying only
hashes, normalized facts, exact attestations, an opaque vault receipt, and
measured write-capability counters.

Enable the adapter only in the existing non-production Mirror composition:

```yaml
gateway:
  testing:
    mirror:
      enabled: true
      read-only-shadow:
        online-baseline:
          enabled: true
          base-uri: https://baseline-sidecar.ap.example.test
          request-timeout-millis: 5000
          maximum-response-bytes: 524288
        online-candidate:
          enabled: true
          base-uri: https://candidate-sidecar.ap.example.test
          request-timeout-millis: 5000
          maximum-response-bytes: 8388608
```

The deployment must also provide exactly one bean for each role:

- `OnlineReadOnlyShadowBaselineTransport`: private trust store, exact server
  SPKI pin, mTLS, and certificate-bound client/server workload identities;
- `HttpOnlineReadOnlyShadowBaselineAuthority.RequestHeadersProvider`: fresh
  application authorization for each exact URI;
- `OnlineReadOnlyShadowBaselineEvidenceAuthority`: independently governed
  sidecar observation verification authority. It is intentionally not a
  `VisualEvidenceSigner`, so it cannot be injected as the Resource Gateway
  local evidence signer.
- `OnlineReadOnlyShadowCandidateTransport`: a second role-separated private
  trust store, exact server SPKI pin, mTLS identity, and certificate binding;
- `HttpOnlineReadOnlyShadowCandidateAuthority.RequestHeadersProvider`: fresh
  candidate-sidecar authorization for each exact URI.

`online-candidate.enabled=true` without `online-baseline.enabled=true` is a
startup error. The two transport role types are deliberate: adapting one
underlying client or certificate to both roles is an explicit deployment
decision and must survive independent security review.

The adapter performs a live
`GET /api/mirror/shadow/online-baseline/capabilities` probe and requires every
safety fact to be fresh and positive. Commands use
`X-BLOGE-Shadow-Execution-Id` as the source idempotency identity; POST timeout
is the earlier of the configured bound and durable job deadline. Responses
must use:

```text
Content-Type: application/vnd.bloge.online-read-only-shadow-baseline+json
X-BLOGE-Online-Baseline-Protocol: 1.0
```

It rejects redirects, system trust, missing pin/mTLS/identity binding,
duplicate/unknown/trailing JSON, protocol downgrade, oversized bodies,
coordinate drift, stale identity, invalid content addresses, and an invalid
authority signature. Repeating the same command must resolve the same immutable
observation. Measured write exposure is not erased by the connector; it crosses
as a boolean/count so `GovernedReadOnlyShadowDataPlane` can fail closed.

An online candidate is installed when the deployment either provides an
`OnlineReadOnlyShadowCandidateAuthority` or enables the built-in strict HTTP
adapter and supplies its transport and request-header roles. Resource Gateway then emits
`resourceGateway.onlineReadOnlyShadowCandidateCommand.v1`. The command contains
no business value; it binds the sealed plan to the independently verified
baseline observation, opaque payload-vault receipt, request-context fingerprint,
access grant, admission, and deadline. The authority must use the command
fingerprint as the signed `MirrorRunEvidence.requestId`, so a changed source,
plan, grant, or time coordinate cannot idempotently reuse an older candidate
run. `OnlineReadOnlyShadowCandidateConnector` independently verifies the
returned Mirror bundle and its scope, plan, target, request context, and time
window before projecting payload-free normalized facts.

The candidate connector re-resolves and re-verifies the baseline artifact
instead of trusting the preceding connector projection. It also requires the
Mirror evidence isolation model to prove that no production context carrier,
external credential, real external call, or network egress reached the
candidate runtime. Calling it without the exact baseline observation fails
closed.

The HTTP candidate adapter probes
`GET /api/mirror/shadow/online-candidate/capabilities`, submits
`POST /api/mirror/shadow/online-candidate/executions`, and exact-reads
`GET /api/mirror/shadow/online-candidate/evidence/{runId}/revisions/{revision}`.
Every response must use:

```text
Content-Type: application/vnd.bloge.online-read-only-shadow-candidate+json
X-BLOGE-Online-Candidate-Protocol: 1.0
```

The capability must freshly assert payload isolation, sealed-plan execution,
idempotency, signed evidence, production-credential prohibition, and exact
artifact reads. The client rejects redirects, public system trust, missing
pin/mTLS/identity binding, wrong media/version, duplicate or unknown JSON,
empty/oversized bodies, stale deadlines, response coordinate drift, and an
exact read whose returned scope or content address differs from the requested
coordinates. Timeout/overload stays retryable; deterministic protocol rejection
does not.

Do not enable this mode together with
`gateway.testing.mirror.read-only-shadow.detached-data-plane.enabled`; startup
rejects the conflict. No permissive candidate authority is installed. When the
deployment supplies the baseline authority/integrity, candidate authority/
integrity, source-resolution repository/signer, and built-in policy,
`OnlineReadOnlyShadowSourceResolutionVerifier` is assembled after both
connectors. It independently exact-reads and verifies both artifacts,
reconstructs both commands, reruns normalization, and signs
`resourceGateway.readOnlyShadowSourceResolutionAttestation.v2`. V2 binds both
command fingerprints and records `confirmedAt <= resolvedAt <= issuedAt`, so a
historical source completion cannot masquerade as the current exact read.

`SyntheticRegionalReadOnlyShadowProvider` is a bounded certification provider
for tests and staging. It offers role-separated baseline/candidate authorities,
append-only exact reads, full command pairing, fixed capacity, and atomic
idempotency under concurrent retries. The certification suite now puts its two
roles behind separate real loopback HTTP servers and drives the complete
governed data plane through one baseline POST, one candidate POST, two baseline
exact reads, one candidate exact read, independent evidence verification, and a
signed v2 source-resolution proof.

`OnlineReadOnlyShadowProviderProcessCertificationTest` adds a stronger,
test-only deployment boundary. It launches baseline and candidate in separate
child JVMs with distinct PIDs, private CAs, server/client certificates, URI SAN
workload identities, server SPKI pins, and Ed25519 evidence keys. The parent
holds only public verification keys and reaches both roles through the
production HTTP authorities. Cross-role trust is rejected during TLS; a
same-CA client with the wrong subject/SAN is rejected by the provider identity
policy. A second scenario durably commits candidate evidence, terminates the
child with exit 86 before the HTTP response, restarts a new JVM on the same
port, and proves the retry returns the original bundle with one physical
generation.

The same process certification also exercises a rolling server-leaf change for
both roles. During overlap, configure each transport with both the incumbent
and successor server SPKI pins while retaining the exact CA and server URI SAN.
The suite warms each `HttpClient`, stops both providers, restarts them on the
same ports with fresh server keys, and reuses the original authority objects to
complete another governed data-plane run. An old-only client succeeds before
the change and fails afterward; a new-only client has the inverse result. This
certifies leaf-key rollover and pooled-connection re-handshake, not CA
replacement, certificate revocation, client-certificate hot reload, or
cross-region convergence.

Candidate response recovery is certified after the durable evidence commit,
not just before execution. The child can inject a one-shot process halt,
declared-length body truncation, delayed headers, or a body-prefix stall. A
durable marker and process-local CAS ensure one fault injection even under
retry pressure. Each transport fault must surface as retryable candidate
unavailability; a subsequent DAG attempt returns the original signed bundle
with `candidateGenerations=1`. Complete but malformed protocol content remains
a deterministic rejection and is not made retryable by this behavior.

Together these tests prove the wire adapters, composed network call graph,
physical process separation, private-PKI role isolation, and one committed
response-loss recovery path. The child provider is never auto-configured and
still uses a pre-admitted payload-free command plus local test files. It is not
a payload vault, authorized production read binding, HSM-backed signer,
production regional sidecar, or PostgreSQL/partition/rotation certification.

The v2 proof is persisted by the same append-only source-resolution repository
used by detached v1 evidence. Fresh tables index `source_mode` and both online
command fingerprints; startup adds those columns with empty legacy defaults
when it encounters the previous v1 table shape. V1 rows retain their exact
signed JSON and detached-binding index. Online v2 rows keep that legacy index
empty and instead require all three new indexes to match the signed document on
every read. Index drift, including a validly signed JSON paired with an altered
command index, fails closed.

The certification suite also drives this composition through the real H2
durable job, proof, comparison, and lifecycle repositories. One case injects a
transient post-confirmation exact-read outage and observes
`RETRY_SCHEDULED -> CLAIMED -> SUCCEEDED`; the provider reuses the same
baseline/candidate artifacts and invokes the candidate factory once. A second
case terminates the first worker after the data plane has produced its v2 proof
but before terminal job commit. The old lease remains `RUNNING`; only after its
database deadline can a new owner and higher epoch emit `TAKEN_OVER`, rerun the
same execution identity, reuse the one append-only proof, and atomically commit
the final comparison plus `SUCCEEDED` lifecycle head. This certifies the durable
in-process reference path plus the dual-role HTTP protocol path. The
independent-process suite separately certifies child JVM failure, private-PKI
role separation, and committed-response-loss recovery. It does not certify
PostgreSQL multi-process or HA behavior, network partitions, private-PKI
rotation/revocation, KMS/HSM, or production data use.

`DatabaseReadOnlyShadowPostgresCertificationTest` closes the first target
database gap with a real PostgreSQL 14.22 process. Two independent data sources
and transaction managers concurrently drive the shared queue and execution
guard. The test proves one admission for a sampling-grant ordinal, one worker
publication, higher-epoch lease takeover with stale-owner rejection, one
terminal comparison, and one guard lease when the shared concurrency limit is
one. PostgreSQL durability settings (`fsync`, `synchronous_commit`, and
`full_page_writes`) stay enabled.

Lock/state initialization uses a nested JDBC savepoint. This matters because a
PostgreSQL constraint violation aborts the current transaction unless it is
rolled back to a savepoint; H2-only tests did not expose that difference.
Steady-state calls first observe the existing row, so ordinary traffic does not
manufacture duplicate-key errors. Queue JSON columns use portable `TEXT`, and
the runtime includes the PostgreSQL JDBC driver. Construction fails fast unless
the transaction manager owns the same datasource and enables nested
savepoints. The certification uses an exact pre-insert barrier plus PostgreSQL
lock/statement timeouts and bounded Futures, so the initialization race is
forced and a lock regression cannot wait indefinitely. Re-run the 26-case
target-database/H2 gate with:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseReadOnlyShadowPostgresCertificationTest,DatabaseReadOnlyShadowJobRepositoryTest,DatabaseReadOnlyShadowExecutionGuardTest \
  test
```

This is a single PostgreSQL process with two connection boundaries and two
workers in one JVM. It does not certify worker-process kill, database restart
or failover, replication, network blackholes, rolling schema upgrades,
backup/restore, or capacity. Terminal job, comparison, and lifecycle rows share
one database transaction; this path has no outbox and makes no outbox claim.
The certification covers this Shadow queue/guard slice, not every repository
or migration in the example application. A PostgreSQL deployment must also set
the Spring datasource URL, username, password, and driver class explicitly.

`GET /api/integration/capabilities` reports the online-baseline boundary as
separate facts:

- `mirrorReadOnlyShadowOnlineBaselineProtocol`: the three public protocol
  versions and schemas are supported;
- `mirrorReadOnlyShadowOnlineCandidateProtocol`: the candidate command and
  live-capability protocols and strict Schemas are supported;
- `mirrorReadOnlyShadowOnlineBaselineConnectorInstalled`: the configured
  baseline connector bean exists;
- `mirrorReadOnlyShadowOnlineBaselineAuthorityReady`: the live regional
  sidecar capability probe currently passes;
- `mirrorReadOnlyShadowOnlineBaselineEvidenceVerificationReady`: the
  independent observation verification authority is currently usable;
- `mirrorReadOnlyShadowOnlineBaselineReady`: connector, authority, and
  evidence verification are all ready in one sampled projection;
- `mirrorReadOnlyShadowOnlineCandidateConnectorInstalled`: the same-input
  candidate connector bean exists;
- `mirrorReadOnlyShadowOnlineCandidateAuthorityReady`: the candidate exact-read
  authority is currently usable;
- `mirrorReadOnlyShadowOnlineCandidateEvidenceVerificationReady`: signed Mirror
  evidence can currently be independently verified;
- `mirrorReadOnlyShadowOnlineCandidateReady`: all three candidate facts are
  true in one sampled projection;
- `mirrorReadOnlyShadowOnlinePairedResolverInstalled`: the v2 independent
  paired-source resolver bean exists;
- `mirrorReadOnlyShadowOnlinePairedResolverReady`: both exact-read authorities,
  both evidence verifiers, policy, repository, and proof signer are usable;
- `mirrorReadOnlyShadowOnlineDataPlaneReady`: baseline, candidate, and paired
  resolver are all ready. It remains false in the default deployment.

The probe samples each dynamic dependency once per response and fails closed on
exceptions. `Protocol`, `Installed`, `BaselineReady`, and `OnlineDataPlaneReady`
are intentionally different lifecycle facts.

The five strict online schemas and three server-produced public-only fixtures are
packaged in `resource-gateway-test-kit`. Run both probes on every JSON, JDK,
crypto provider, or Test Kit upgrade:

```java
OnlineReadOnlyShadowBaselineCompatibilityFixture fixture =
        CapabilityMirrorProtocol
                .onlineReadOnlyShadowBaselineCompatibilityFixture();
var verified = fixture.verify();
if (!verified.verified() || !verified.zeroWrite()) {
    throw new IllegalStateException(verified.reasonCode());
}

OnlineReadOnlyShadowSourceResolutionCompatibilityFixture paired =
        CapabilityMirrorProtocol
                .onlineReadOnlyShadowSourceResolutionCompatibilityFixture();
var pairedVerification = paired.verify();
if (!pairedVerification.verified()) {
    throw new IllegalStateException(
            pairedVerification.reasonCode());
}

OnlineReadOnlyShadowWorkerCompatibilityFixture worker =
        CapabilityMirrorProtocol
                .onlineReadOnlyShadowWorkerCompatibilityFixture();
var workerVerification = worker.verify();
if (!workerVerification.verified()) {
    throw new IllegalStateException(
            workerVerification.reasonCode());
}
```

The standalone verifier does not link server or Spring classes. It independently
recomputes strict command/observation closure, canonical fingerprints,
deterministic identity, idempotency, time windows, public-key policy, and the
Ed25519 signature. The paired verifier additionally checks both exact commands,
both signed source artifacts, same-input closure, current exact-read time,
built-in normalization, v2 identity/content address, zero-write facts, and the
third authority signature. Passing either fixture proves wire compatibility,
not current sidecar, data-use, workload-identity, enterprise trust propagation,
or production readiness.

The worker fixture adds the immutable request, terminal job, complete
admission/claim/heartbeat/takeover/success lifecycle, signed comparison, and a
fourth comparison authority key. Its one-shot verifier rejects individually
valid artifacts from different executions, incomplete lifecycle pages,
consumer expectation drift, source-role drift, and authority-key aliasing. It
is the preferred ANEKE/CI compatibility gate because it verifies the complete
public evidence chain without starting Resource Gateway. A pass still does not
certify PostgreSQL multi-process/HA behavior or a production regional sidecar.

Read the exact proof referenced by a successful v3 comparison:

```bash
curl -i \
  "http://localhost:8080/api/mirror/shadow/source-resolutions/$ATTESTATION_ID/revisions/$REVISION?fingerprint=$FINGERPRINT" \
  -H "Authorization: Bearer $GOVERNANCE_TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION" \
  -H "X-BLOGE-Shadow-Source-Resolution-Protocol: read-only-shadow-source-resolution-attestation-v1" \
  -H "Accept: application/vnd.bloge.read-only-shadow-source-resolution-attestation.v1+json"
```

Submit an online request that validates against
[`read-only-shadow-job-request-v1.schema.json`](../docs/schemas/resource-gateway-mirror/read-only-shadow-job-request-v1.schema.json),
or a detached request that validates against
[`read-only-shadow-job-request-v2.schema.json`](../docs/schemas/resource-gateway-mirror/read-only-shadow-job-request-v2.schema.json):

```bash
curl -i -X POST http://localhost:8080/api/mirror/shadow-jobs \
  -H "Authorization: Bearer $SHADOW_TOKEN" \
  -H "X-Purpose: MIRROR_SHADOW" \
  -H "Content-Type: application/json" \
  --data @read-only-shadow-job.json
```

The authenticated identity, not JSON, owns tenant, organization, project,
environment, and region. The submitted `scope` must match those claims exactly.
`requestId` is idempotent for identical content; a different request using the
same id or the same sampling-grant fingerprint plus sample ordinal is rejected.
The response returns `202`, a deterministic `jobId`, and `Location`.

Read the verification closure and lifecycle:

```bash
curl http://localhost:8080/api/mirror/shadow-jobs/$JOB_ID \
  -H "Authorization: Bearer $GOVERNANCE_TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION"

curl "http://localhost:8080/api/mirror/shadow-jobs/$JOB_ID/lifecycle?afterSequence=0&limit=100" \
  -H "Authorization: Bearer $GOVERNANCE_TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION"
```

Lifecycle events expose only scope, content addresses, transition/status,
attempt/epoch, owner fingerprint, bounded failure code, and database time.
They cannot represent samples, credentials, exceptions, or stack traces.
`ReadOnlyShadowLifecycleVerifier` independently distinguishes a complete
admission-to-head proof from a valid truncated page. Protocol, authority, and
offline-verification details are in the
[domain fidelity guide](../docs/resource-gateway-domain-fidelity-profile.md).

### Authoritative outcome fidelity

`resourceGateway.authoritativeOutcomeObservation.v1` is the payload-free
boundary between one simulated outcome and independently governed business
facts. It binds the exact Fidelity inventory unit, outcome definition,
attribution policy, authority set, pre-treatment cohort and sampling frame,
subject/correlation fingerprints, event-time window, complete authority
watermarks, source-record references, and a content-addressed `attestedAt`.
Key lifecycle and future-time decisions use that signed field rather than the
detached seal's independently mutable timestamp. `MATCH`, `MISMATCH`, `PENDING`,
`CENSORED`, and `CONFLICT` are derived from that closure and cannot be asserted
by a producer.

The non-production Mirror composition deliberately provides no default business
authority. A host must supply its own independently governed bean:

```java
@Bean
AuthoritativeOutcomeAuthorityVerifier authoritativeOutcomes(
        CustomerOutcomeAuthority authority) {
    return new AuthoritativeOutcomeAuthorityVerifier() {
        @Override
        public boolean available() {
            return authority.ready();
        }

        @Override
        public void verify(AuthoritativeOutcomeObservation observation) {
            authority.verifyExactClosure(observation);
        }
    };
}
```

Only then does Spring assemble
`AuthoritativeOutcomeObservationIntegrity` and
`AuthoritativeOutcomeDomainFidelitySource`. Internal projector workloads call
`DomainFidelityService.projectOutcomes(...)`; there is no public projection
endpoint. The transaction rechecks current inventory,
authority closure, cohort consistency, content address, Resource Gateway seal,
projection, persistence, and success audit. Pending, censored, and conflicting
facts remain distinct abstention debt, while omitted inventory units remain
missing.

The durable reconciliation product boundary is assembled only after that
independent authority bean exists:

- `DatabaseAuthoritativeOutcomeInboxRepository` appends immutable signed
  revisions and maintains a full-scope, content-addressed head;
- every successor requires the exact current predecessor fingerprint, preserves
  immutable attribution coordinates, and advances authority watermarks;
- `AuthoritativeOutcomeReconciliationWorker` claims one pending head with a
  database-time owner/epoch fence, invokes a host
  `AuthoritativeOutcomeConnector`, signs a verified successor, and atomically
  advances or settles the head;
- valid no-change polls, retryable dependency failures, invalid results,
  lease expiry, external successor fencing, commit replay, and chained
  lifecycle integrity have separate tested behavior.

The repository verifies the local Resource Gateway seal inside short database
transactions. Admission and reads repeat the external business-authority
verification outside those transactions, so a slow customer ledger cannot
hold database locks. An unsigned initial command or exact unsigned retry is
verified and signed by Resource Gateway; signed successors must carry the
exact current predecessor. The service distinguishes unsigned idempotent retry
by a material fingerprint that excludes only the server signature,
`attestedAt`, and seal. It never treats different business facts as a replay.

The protected API is physically absent from production and exists in `test` or
`staging` only when Mirror is enabled and the authority bean is present:

| Operation | Route | Purpose |
|---|---|---|
| Admit one revision | `POST /api/mirror/outcome-observations` | `MIRROR_OUTCOME_INGESTION`; `SERVICE`/`WORKLOAD` in `RESOURCE_GATEWAY_OUTCOME_CONNECTOR` |
| Read exact revision | `GET /api/mirror/outcome-observations/{id}/revisions/{revision}` | ingestion, Fidelity governance, or governance evidence |
| Read current revision | `GET /api/mirror/outcome-observations/{id}/latest` | same read purposes |
| Read durable head | `GET /api/mirror/outcome-observations/{id}/head` | same read purposes |
| Read lifecycle suffix | `GET /api/mirror/outcome-observations/{id}/lifecycle?afterOrdinal=0&limit=100` | same read purposes |

Requests are authenticated before strict JSON decoding. Duplicate or unknown
fields, trailing JSON, oversized/deep documents, caller-scope drift, invalid
authority closure, revision gaps, forks, rollback, or corrupt stored state fail
closed. There is deliberately no `run-now` HTTP route: connector execution is
owned by the durable worker and its database lease.

Supply a payload-isolated `AuthoritativeOutcomeConnector` to install that
worker. Autonomous polling is opt-in:

```yaml
gateway:
  testing:
    mirror:
      enabled: true
      outcome-reconciliation:
        scheduler:
          enabled: true
          instance-id: outcome-reconciler-sg-1
          region: sg
          environment-id: staging
          maximum-pollers: 2
          initial-delay-millis: 1000
          poll-interval-millis: 1000
          drain-timeout-millis: 30000
```

`prod`, `production`, and `live` scheduler targets are rejected. Spring also
publishes six non-equivalent capability facts:
`mirrorAuthoritativeOutcomeInboxApi`,
`mirrorAuthoritativeOutcomeLifecycleAudit`,
`mirrorAuthoritativeOutcomeConnectorReady`,
`mirrorAuthoritativeOutcomeWorkerReady`,
`mirrorAuthoritativeOutcomeScheduling`, and
`mirrorAuthoritativeOutcomeContinuousReady`. The last is true only when API,
lifecycle, connector, worker, and scheduler are all ready.
`mirrorDomainFidelityOutcomeAdapterReady` remains a separate projection fact.

The standalone Test Kit packages all five strict inbox Schemas and
`AuthoritativeOutcomeInboxLifecycleVerifier`. It recomputes entry/event content
addresses and verifies ordinal, predecessor-event, observation-revision, scope,
time, and current-head closure without linking Spring or server classes. A
valid suffix page is not mislabeled as complete history.

No default customer connector or permissive business authority is installed.
A selected-population completeness manifest, customer authority/key
distribution, production connector, cross-region/HA certification, and
Fidelity-to-outcome calibration are still required before a cohort can be
called bias-resistant or a deployment production-ready. Exact protocol,
integration, and offline-verification rules are in the
[domain fidelity guide](../docs/resource-gateway-domain-fidelity-profile.md).
The server-produced public-only
`authoritative-outcome-observation-stage1-v1.fixture.json` is consumed by both
the server model and standalone Test Kit to catch canonical JSON, reconciliation,
signature-domain, signed-attestation-time, and trusted-time drift. Its permissive authority callback is
strictly a wire-compatibility stub, not business evidence.

#### Selected-population completeness

The outcome inbox proves every submitted result, but it cannot detect a producer
that omits selected members. The selected-population product freezes that
denominator under an independent selection authority, preserves legal deletion
inside the denominator, and signs a coherent assessment over current outcome
and disposition heads.

Spring assembles this surface only in `test` or `staging` when Mirror is enabled
and all three independent external trust boundaries exist:

```java
@Bean
AuthoritativeOutcomeSelectedPopulationAuthorityVerifier selectionAuthority(
        CustomerSelectionLedger ledger) {
    return new AuthoritativeOutcomeSelectedPopulationAuthorityVerifier() {
        public boolean available() {
            return ledger.ready();
        }

        public void verify(
                AuthoritativeOutcomeSelectedPopulationManifest manifest,
                List<AuthoritativeOutcomeSelectedPopulationChunk> chunks) {
            ledger.verifyExactSelection(manifest, chunks);
        }
    };
}

@Bean
AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier deletionAuthority(
        CustomerDeletionLedger ledger) {
    return new AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier() {
        public boolean available() {
            return ledger.ready();
        }

        public void verify(
                AuthoritativeOutcomeSelectedPopulationDisposition disposition) {
            ledger.verifyExactApproval(disposition);
        }
    };
}
```

The existing `AuthoritativeOutcomeAuthorityVerifier` remains the third,
business-outcome authority. Resource Gateway signing is a fourth trust boundary;
it never substitutes for any customer authority.

| Operation | Route | Purpose / default group |
|---|---|---|
| Submit population | `POST /api/mirror/outcome-selected-populations` | `MIRROR_OUTCOME_SELECTION` / `RESOURCE_GATEWAY_OUTCOME_SELECTION_AUTHORITY` |
| Submit legal disposition | `POST .../{populationId}/dispositions` | `MIRROR_OUTCOME_DISPOSITION` / `RESOURCE_GATEWAY_OUTCOME_DELETION_AUTHORITY` |
| Project one assessment | `POST .../{populationId}/assessments` | `MIRROR_FIDELITY_GOVERNANCE` / `RESOURCE_GATEWAY_FIDELITY_PROJECTOR`; caller owns the explicit revision |
| Register continuous assessment | `POST /api/mirror/outcome-continuous-assessments` | `MIRROR_FIDELITY_GOVERNANCE` / `RESOURCE_GATEWAY_FIDELITY_PROJECTOR`; server owns the assessment stream and revisions |
| Read continuous status | `GET /api/mirror/outcome-continuous-assessments/{projectionId}` | `MIRROR_FIDELITY_GOVERNANCE` or `GOVERNANCE_EVIDENCE_INGESTION` |
| Read continuous lifecycle | `GET .../{projectionId}/lifecycle?afterOrdinal=0&limit=100` | `GOVERNANCE_EVIDENCE_INGESTION`; caller owns the exclusive ordinal and predecessor checkpoint |
| Remediate continuous quarantine | `POST .../{projectionId}/remediations` | `MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ADMIN` / `RESOURCE_GATEWAY_FIDELITY_PROJECTOR`; exact reviewed projection and lifecycle head are mandatory |
| Read exact/current facts and source pages | population, disposition, assessment and `/sources` GET routes | `GOVERNANCE_EVIDENCE_INGESTION` or another admitted read purpose |

Writes authenticate before strict decoding. Scope comes only from the workload
identity. Unsigned commands are independently verified, content-addressed and
signed by the server. An exact retry, including the race where another replica
commits after the first recovery lookup, returns the original fact with
`idempotentReplay=true`; changed unsigned material fails closed.

Assessment uses a two-phase database cut. The first short transaction freezes
member-ordered observation/disposition fingerprints, external authorities are
called outside the transaction, and commit rechecks the cut under the shared
partition lock. Historical source rows are committed with the assessment and
are read with a bounded content-addressed cursor; current heads are never used
to fill holes in old evidence.

The capability probe reports independent immutable-registry and continuous-runtime facts:

- `mirrorAuthoritativeOutcomeSelectedPopulationApi`
- `mirrorAuthoritativeOutcomeSelectedPopulationDurable`
- `mirrorAuthoritativeOutcomeSelectedPopulationSourceClosure`
- `mirrorAuthoritativeOutcomeSelectedPopulationStagedUpload`
- `mirrorAuthoritativeOutcomeContinuousAssessmentApi`
- `mirrorAuthoritativeOutcomeContinuousAssessmentDurable`
- `mirrorAuthoritativeOutcomeContinuousAssessmentLifecycle`
- `mirrorAuthoritativeOutcomeContinuousAssessmentRemediation`
- `mirrorAuthoritativeOutcomeContinuousAssessmentWorkerReady`
- `mirrorAuthoritativeOutcomeContinuousAssessmentScheduling`
- `mirrorAuthoritativeOutcomeSelectedPopulationReady`

The final fact is true only while the API, durable registry, source closure,
staged transport, continuous projection store, selection authority, outcome
authority, deletion authority, signer, worker, and bounded scheduler are all
usable. The normal demo scripts intentionally do not install fake customer
authorities, so these routes remain absent:

```bash
./scripts/start-visual-canvas-demo.sh --profile test
curl -fsS http://localhost:8080/api/integration/capabilities | jq \
  '.payload.features
   | with_entries(select(.key | startswith(
       "mirrorAuthoritativeOutcomeSelectedPopulation")))'
./scripts/stop-visual-canvas-demo.sh
```

Install the three host-owned authority beans in a staging deployment, then use
the same scripts (or the packaged Boot JAR) to start and stop it. Do not add a
permissive authority merely to make readiness green. The original complete
population command remains bounded to 64 MiB. For a larger retryable transfer,
use the staged protocol while preserving one logical population revision:

```java
JsonNode upload = client
        .beginAuthoritativeOutcomeSelectedPopulationUpload(uploadRequest);

for (int index = 0; index < chunks.size(); index++) {
    client.stageAuthoritativeOutcomeSelectedPopulationUploadChunk(
            uploadId, index, chunks.get(index));
}

JsonNode status = client
        .findAuthoritativeOutcomeSelectedPopulationUpload(uploadId);
if (status.path("nextMissingChunkIndex").asInt() != -1) {
    throw new IllegalStateException(
            "population upload is incomplete");
}

JsonNode populationAdmission = client
        .finalizeAuthoritativeOutcomeSelectedPopulationUpload(uploadId);
```

Chunks may be uploaded out of order. Begin and chunk PUT are exact-replay
idempotent, so a caller should retry the same `uploadId`, index, and bytes after
an ambiguous response. Never generate a new upload id for each transport retry.
`nextMissingChunkIndex` is the lowest durable gap and status never returns
selected-member data. Use
`abortAuthoritativeOutcomeSelectedPopulationUpload(uploadId)` to abandon an
`OPEN` upload.

The closed lifecycle is `OPEN -> FINALIZING -> FINALIZED`, with `ABORTED` and
`EXPIRED` terminal alternatives. A two-minute database lease and monotonic
epoch let another replica recover a crashed finalizer. Finalize reuses the
existing idempotent population admission, so a crash after population commit
but before upload terminalization is recoverable without creating a second
revision.

Default server-owned bounds are 16 active uploads and 1 GiB staged bytes per
exact scope, 256 MiB per upload, a 24-hour incomplete TTL, and seven-day
terminal retention. Capacity exhaustion returns 429 with a bounded retry hint.
Override the
`AuthoritativeOutcomeSelectedPopulationUploadPolicy` bean to apply a reviewed
deployment policy; requests cannot override capacity or lifecycle. Cleanup
defaults to 100 candidates every 60 seconds:

```properties
gateway.testing.mirror.selected-population.upload-cleanup-batch-size=100
gateway.testing.mirror.selected-population.upload-cleanup-interval-ms=60000
```

The capability probe advertises
`mirrorAuthoritativeOutcomeSelectedPopulationStagedUpload=true` only when the
staging repository, service, controller, selection authority, and population
admission boundary are assembled.

This is resumable transport, not an unlimited streaming data plane. The
default 256 MiB policy is intentional, and finalize currently materializes the
bounded chunk closure in one JVM before governed admission. Million-scale
deployments must certify heap, database, authority latency, and policy bounds;
populations beyond those bounds require an object-store/streaming admission
data plane rather than a larger bean value.

##### Continuous completeness projection

An explicit assessment proves one coherent source cut once. It does not prove
that the conclusion is still current after a late outcome, conflict resolution,
or legal disposition changes a member head. Continuous assessment closes that
gap without letting callers control freshness or revision allocation.

Registration contains only a stable `projectionId` and one exact immutable
population artifact reference. Resource Gateway derives
`continuous-assessment:{projectionId}`, checks the population through the
external selection authority, and stores a payload-free database projection.
The request cannot set polling, lease, retry, freshness, assessment id, or
assessment revision.

Each worker turn takes a database-time owner/epoch lease, freezes the current
observation and disposition source closure, and compares it with the latest
immutable assessment:

- an unchanged closure renews `currentThrough` and the exclusive
  `freshUntil` deadline without manufacturing another evidence revision;
- a changed closure calls the existing audited three-authority assessment
  service and publishes the next immutable assessment revision;
- a committed assessment whose response was lost is adopted from the
  immutable assessment head before any successor is allocated;
- retryable authority/cut failures use bounded exponential backoff and never
  extend old freshness; structural corruption or eight consecutive failures
  enter `QUARANTINED`.

`QUEUED`, `RUNNING`, `RETRY_WAIT`, and `QUARANTINED` describe durable work.
`UNINITIALIZED`, `CURRENT`, `REFRESHING`, `STALE`, and `QUARANTINED` describe
consumer-visible source freshness. Governance must use the top-level `ready`
field, which is true only when freshness is `CURRENT` and all four trust
boundaries are currently usable. A historical `lastAssessmentRef` remains
auditable when `ready=false`; it must not be treated as a current gate fact.

Every committed coordination transition is also appended as a payload-free,
content-addressed lifecycle event. `REGISTERED`, `CLAIMED`,
`ASSESSMENT_PUBLISHED`, `SOURCE_UNCHANGED`, `RETRY_SCHEDULED`,
`LEASE_EXPIRED`, and `QUARANTINED` expose the complete resulting projection,
an opaque actor fingerprint, a monotonic ordinal, and the previous event
fingerprint. A pre-upgrade projection receives one explicit `MIGRATED`
baseline on its first later transition; the gateway never invents historical
events.

`QUARANTINED` stops autonomous scheduling and is never cleared by a blind
retry. An authorized operator must first read and independently verify the
current status and complete lifecycle head, repair the external cause, then
submit one command carrying all three reviewed fences:

```json
{
  "schemaVersion": "resourceGateway.authoritativeOutcomeContinuousAssessmentRemediationRequest.v1",
  "commandId": "incident-4821-recovery-1",
  "expectedProjectionFingerprint": "sha256:<quarantined projection>",
  "expectedLifecycleHeadOrdinal": 17,
  "expectedLifecycleHeadFingerprint": "sha256:<verified event 17>",
  "reasonCode": "OUTCOME_AUTHORITY_REPAIRED"
}
```

Send it to
`POST /api/mirror/outcome-continuous-assessments/{projectionId}/remediations`
with purpose `MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ADMIN`. The existing
`RESOURCE_GATEWAY_FIDELITY_PROJECTOR` group is reused, but the dedicated
purpose prevents ordinary projection and read credentials from performing
recovery. Authentication occurs before strict JSON decoding.

Acceptance appends `REMEDIATION_ACCEPTED`, changes only the work status to
`QUEUED`, clears the current failure streak, and sets `nextEligibleAt` to
database commit time. It does not change cumulative attempts, lease epoch,
population, assessment/source closure, or the old freshness deadline. The
immutable receipt embeds the reviewed quarantined projection and accepted
lifecycle event. Exact retries by the same actor return the same receipt even
after a later worker transition; a reused command id with changed content or
actor fails closed.

Read lifecycle pages from ordinal zero and retain the last verified
`nextOrdinal` plus event fingerprint as the continuation checkpoint:

```bash
curl -fsS \
  -H "Authorization: Bearer ${RESOURCE_GATEWAY_TEST_TOKEN}" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION" \
  "http://localhost:8080/api/mirror/outcome-continuous-assessments/refund-completeness/lifecycle?afterOrdinal=0&limit=100"
```

The page repeats the caller's `afterOrdinal`, returns the fingerprint at that
cursor as `predecessorFingerprint`, and then returns a contiguous oldest-first
suffix. Continue with the returned `nextOrdinal`; independently retain the
last event's `eventFingerprint`. Deleting or rewriting the lifecycle head,
breaking an ordinal, substituting another projection, or presenting a stale
checkpoint fails closed. The Test Kit performs these checks before returning a
page.

Enable autonomous workers only after installing all customer authorities:

```bash
export RG_MIRROR_RUNTIME_ENABLED=true
export RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_SCHEDULER_ENABLED=true
export RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_INSTANCE_ID=outcome-projector-sg-1
export RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGION=sg
export RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_ENVIRONMENT=staging
export RG_MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_MAXIMUM_POLLERS=2

./scripts/start-visual-canvas-demo.sh --profile staging \
  --outcome-continuous-assessment
./scripts/visual-canvas-demo.sh status
curl -fsS http://localhost:8080/api/integration/capabilities | jq \
  '.payload.features
   | with_entries(select(.key | contains(
       "OutcomeContinuousAssessment")))'
./scripts/stop-visual-canvas-demo.sh --port 8080
```

The script passes these variables into the Spring configuration. Stop waits up
to `BLOGE_VISUAL_CANVAS_STOP_TIMEOUT` seconds (default `40`), leaving the
scheduler's default 30-second drain window intact before forcing termination.
`prod`, `production`, and `live` partitions
are rejected at both configuration and service boundaries. Merely setting the
environment variables does not install customer authority beans; in the stock
demo the capability remains false and startup with
`--outcome-continuous-assessment` fails its readiness probe. Use this flag only
in a customer assembly that supplies all advertised authorities.

The default server policy is a five-minute lease, one-minute source freshness,
five-second initial retry, five-minute maximum retry, and quarantine after
eight consecutive failures. Override
`AuthoritativeOutcomeContinuousAssessmentPolicy` as reviewed deployment code,
not request data. Scheduler timing and process-local concurrency use:

```properties
gateway.testing.mirror.outcome-continuous-assessment.scheduler.enabled=true
gateway.testing.mirror.outcome-continuous-assessment.scheduler.instance-id=outcome-projector-sg-1
gateway.testing.mirror.outcome-continuous-assessment.scheduler.region=sg
gateway.testing.mirror.outcome-continuous-assessment.scheduler.environment-id=staging
gateway.testing.mirror.outcome-continuous-assessment.scheduler.maximum-pollers=2
gateway.testing.mirror.outcome-continuous-assessment.scheduler.initial-delay-millis=1000
gateway.testing.mirror.outcome-continuous-assessment.scheduler.poll-interval-millis=1000
gateway.testing.mirror.outcome-continuous-assessment.scheduler.drain-timeout-millis=30000
```

The public-only
`authoritative-outcome-selected-population-stage1-v1.fixture.json` is generated
from the server model and consumed independently by the Test Kit. It freezes
population/chunk addressing, `MATCH`/`MISMATCH` observations, one legal
disposition, zero-missing assessment arithmetic, source pagination, and all
Resource Gateway Ed25519 signatures without carrying a private key or business
payload. Twenty-four strict Schemas, the fixed fixture, resumable-upload,
controlled quarantine-remediation, and
continuous-assessment clients, and the standalone Test Kit verifier are
described in the
[Test Kit guide](../resource-gateway-test-kit/README.md#verify-selected-population-completeness).

### Stateful mirror Session data plane and DAG reads

The Stage 3 vertical freezes `StateModel`, `StateReadSpec`, `WriteEffectSpec`,
`SessionStateSpace`, five Session lifecycle objects, five checkpoint/recovery objects, and a closed
bounded expression AST. The
`MirrorStateTransactionEngine` serializes one session's writes, atomically
applies ordered multi-entity mutations, returns the original receipt for exact
idempotent retries, rejects same-key command drift, validates entity schemas and
complete unique business keys, and derives logical time, IDs, and sequences
without ambient nondeterminism. Update/delete copy-on-write accepts only an
exact recorded corpus sample or owner-specified fixture; tombstoned identities
cannot be recreated.

Session integrity requires a contiguous revision/receipt journal and exact
event closure, while each entity, tombstone, event, receipt, current world, and
complete session is content-addressed. The test kit packages the same strict
Schemas and refund fixture, seals canonical payloads, verifies them without
linking server classes, and exposes bounded create/read/command/checkpoint/
recover/destroy client methods.

With `test` or `staging` plus `--stateful`, `/api/mirror/sessions` stores the
complete payload under AES-256-GCM in a dedicated JDBC data plane. The public
descriptor remains payload-free. Same-process fair locking, cross-replica DB
lease/fence, expected-state checks, CAS, exact lease release, TTL and destroy
define the concurrency and lifecycle boundary. Authentication happens before
decoding and scope comes only from verified identity.

The database serializes create, payload-growth commit, and expiry capacity
decisions under one cross-replica guard. Global and exact enterprise-scope
limits cover both active-session count and all canonical serialized payload
bytes not yet erased, including expired payload awaiting cleanup. Commands also pass a fair,
non-blocking replica-local admission gate before waiting on a session lock.
Saturation is a stable retryable `429`; exact idempotent replay remains
available while full. A bounded oldest-first worker erases expired ciphertext,
and aggregate health plus fixed-cardinality metrics never expose customer
dimensions.

Before a new virtual write evaluates mutation logic, the state plane persists
a payload-free `mirrorStateWriteAttempt.v1` intent under the exact Session
lease, initial head, run lease epoch, invocation site, occurrence, delegate
attempt, write effect, request fingerprint, and engine-computed command
fingerprint. A successful CAS advances the encrypted Session and marks that
intent `COMMITTED` in the same database transaction. Exact command replay
marks its distinct execution attempt `REPLAYED`; known rejection and proven
pre-commit failure become immutable terminal records.

If a process dies while an intent is still `IN_PROGRESS`, a bounded
cross-replica reconciler waits for its database lease to expire, locks the
Session then the attempt, and derives the result from the append-only receipt
journal. It emits `COMMITTED`, `REPLAYED`, `PRE_COMMIT_FAILED`, or conservative
`COMMIT_OUTCOME_UNKNOWN`; it never infers unchanged state from a missing or
unverifiable Session. Each attempt uses a short transaction, so one corrupt row
cannot roll back healthy records in the same page. The authenticated query
route and independent test-kit verifier expose only payload-free facts.

The state plane also initializes one content-addressed durable store
generation that remains stable across process restart and changes for an
independently initialized store. Checkpoint creation reads this generation and the
encrypted Session head in one database transaction, then signs a `HASH_ONLY`
bundle in a checkpoint-specific Ed25519 domain. The portable bundle closes the
full enterprise scope, Session/plan/model/read/effect dependencies, committed
revision, logical clock, world/state/payload/descriptor fingerprints, and
Session times. It contains no business payload, lease/fence, payload-encryption
key id, or key material.

Recovery never writes the checkpoint into the state store. It verifies strict
Schema, nested fingerprints, detached signature, scope, store generation,
dependency closure, and the exact current state before returning a new
`MirrorSessionRunBinding`. A signature problem, database replacement,
dependency drift, or any command after checkpoint is a distinct fail-closed
error. This supports process or worker continuation against the same durable
encrypted data plane; it is not a database backup, cross-region payload
restore, or rollback mechanism.

A full database clone or backup restore preserves the generation because it is
part of the durable dataset. The exact state comparison still rejects a stale
clone, but generation alone cannot elect an active region or detect two
identical clones. Production HA/DR must add deployment-authority ownership and
split-brain fencing before enabling recovery traffic.

State-model-backed `READ_ONLY` and `VIRTUAL_MUTATION` external capabilities can
now be compiled with `SESSION_STATE` first in resolver precedence. Execution
request v2 binds the run to one caller-reviewed Session state fingerprint. A
read-only run uses that immutable head throughout. A read/write run owns one
fair, serialized run session: each virtual write passes through the same
admission, database lease, idempotency, optimistic fence, CAS, and audit path as
the protected Session command API, then atomically advances the head visible to
downstream nodes. The write binding is exactly
`[SESSION_STATE, ABSTAINED]`, so the graph can never invoke the registered real
write operator. Reads return live entities through the declared bounded
projection; absent keys may continue to lower governed sources, while an
indexed tombstone is terminal. Missing read or write specifications are
plan/Session closure defects rejected before graph scheduling.

Read-only Session runs emit nested
`resourceGateway.mirrorStateRunEvidence.v1` inside mirror evidence,
attestation, and bundle v3. Legacy successful read/write runs use nested v2
state evidence and bundle v4. New read/write runs emit
`resourceGateway.mirrorStateRunEvidence.v3` inside independently
domain-separated run evidence, attestation, and bundle v5. V3 binds both
initial and final Session heads, every exact read, and every executed write
attempt to one terminal outcome: `COMMITTED`, `REPLAYED`, `REJECTED`,
`PRE_COMMIT_FAILED`, or `COMMIT_OUTCOME_UNKNOWN`. Each attempt records its
bounded stage and whether state was `ADVANCED`, `UNCHANGED`, or `UNKNOWN`.
Successful attempts retain the v2 request/receipt/event transition closure;
failed attempts retain only state coordinates, stable error metadata, and a
recomputable failure fingerprint. Entity values, business keys, raw
idempotency keys, command inputs, and responses are never retained.

Every state access and write attempt closes against the matching node delegate
attempt and `MirrorResolution`. `COMMIT_OUTCOME_UNKNOWN` requires
`WRITE_COMMIT_OUTCOME_UNKNOWN` on both nested and outer evidence, so it cannot
be certified as unchanged or published without reconciliation. Existing
v1/v2 stateless and v3/v4 stateful bundles remain readable with their original
signature semantics. Repository reads after restart rehydrate the exact
state-evidence subtype and re-verify its nested seal and detached signature.

The standalone test kit independently verifies v3 read closure, v4 successful
transition closure, and v5 failure-aware write-attempt closure. It derives
`resourceGateway.mirrorStateWorkbookSeed.v1` from a verified v3 bundle and
`resourceGateway.mirrorStateTransitionWorkbookSeed.v1` from a verified v4
bundle. It derives
`resourceGateway.mirrorStateWriteOutcomeWorkbookSeed.v1` from v5. The v5 seed
closes initial/final heads, all five outcome counts, ordered write-attempt
assertions, and successful receipt/event transitions where present. Rejected
writes remain explicit until ANEKE supplies an expected rejection assertion;
pre-commit failures and unknown commit outcomes are blockers. The three
protected seed routes return the same deterministic projections in the
authenticated scope. The independent client fetches evidence and its signing
key, reconstructs the matching seed locally, and compares its canonical
fingerprint with the producer seed. A seed names exact evidence coordinates
and conservative blockers; it does not replace the signed bundle or let
Resource Gateway make ANEKE's workbook, owner-approval, or publish-gate
decision.

This is not yet a production-certified stateful runtime: TEE/KMS custody,
organization-pinned checkpoint trust, cross-region payload restore,
cryptographic deletion proof, target-database capacity/lock certification and
HA/DR certification remain. In-process rejected, pre-commit failure, and
ambiguous commit outcomes are evidence-complete. The durable write-attempt
journal now closes the single-database process-crash ambiguity window and can
recover a committed result after its response is lost without repeating the
write. Journal records contain only the domain-separated fingerprint of a
runtime correlation value, never its raw business value. Stateful writes fail
closed while the journal or reconciliation query path is unavailable. Real
process-kill, network partition, vendor-database, region failover,
and restored-clone certification are still required. The probe reports
`mirrorStatefulResolverReady`
only when mirror execution, the Session API, and the encrypted state store are
all ready. It separately reports `mirrorStateRunEvidenceReady`,
`mirrorStateTransitionEvidenceReady`, `mirrorStateWriteOutcomeEvidenceReady`,
the three workbook-seed API flags, and their matching readiness flags. All
three workbook readiness flags follow the stateful resolver's current health.
`mirrorStateWriteAttemptDurableReconciliationReady` becomes true only when the
stateful resolver, encrypted store, attempt table, and reconciliation query
path are currently ready. The probe also separates
`mirrorStateCheckpointProtocol`, `mirrorStateCheckpointApi`,
`mirrorStateCheckpointReady`, and `mirrorStateRecoveryReady`; readiness requires
both the state store and signing authority. `mirrorStatefulRuntimeReady` remains
false until process-kill/network/HA/DR and environment certification are
complete. Startup, request v2 usage, Java usage, capacity
configuration, stable errors, and remaining industrial work packages are in the
[stateful mirror kernel guide](../docs/resource-gateway-stateful-mirror-kernel.md).

The Stage 1 compiler and run kernels verify Capability Closure against the recursively
frozen BLOGE invocation inventory, adapt the existing FixtureBundle into mandatory external-site controls, retain
the exact Graph/fixture/control generation in process, and execute it through the independent test engine after
scope, purpose, TTL, fingerprint, coverage, and logical deadline checks. Mirror fixtures cannot replace internal
business nodes, and unmatched external leaves fail closed. Set `RG_MIRROR_RUNTIME_ENABLED=true` only with the
`test` or `staging` profile to assemble this internal kernel and its append-only mirror stores. The `production`
profile physically excludes the compiler, runtime, integrity service, and repositories even when `test` is also
active. Sealed public plans and independently verified `HASH_ONLY` evidence now persist under a complete
tenant/organization/project/environment/region compound key; exact retries are idempotent, conflicting identities
and tampered rows fail closed, and no fixture/replay/context/result payload column exists. This is still not a
production-certified runtime. The protected plan endpoint authenticates `MIRROR_REHEARSAL`, requires complete project and
region scope, fingerprints the current registered graph, independently verifies the exact stored fixture envelope,
freezes governed replay dependencies without changing caller purpose, derives all isolation policy on the server,
and persists only the payload-free plan. Current fixture registration writes a v2 envelope and repository key with
tenant/organization/project/environment/region. The existing append-only mirror scope binding remains
defense-in-depth for plan authorization. Historical v1 fixtures stay isolated in the legacy table and must be
explicitly re-registered under the destination full-scope identity. Plan requests expose no real-call, credential, egress, region, lifecycle,
or clearance override. The execution endpoint accepts only requestId, exact plan identity, reviewed plan fingerprint,
and business context. It server-binds BLOGE tenant/project scope, reconstructs Graph/Fixture/Replay artifacts, and
requires the complete recompiled plan to equal the stored plan before execution. A payload-free durable request row
coordinates concurrent retry and restart recovery with lease owner, epoch, and hard-expiry fencing. Claim, expiry,
takeover, release, commit fencing, and `retryAfterSeconds` all use the coordination database clock, so replica wall-clock
skew cannot change execution authority. Signed evidence and terminal request state commit atomically. Expired authority
cannot publish even before takeover, and authority-row locking occurs before time sampling so lock wait cannot bypass
expiry. H2 time is sampled through an independent short connection after locking because its transaction timestamp is
frozen; configure the datasource with capacity for the transaction connection plus the clock connection. The
deployment-egress proof and M-of-N authority key-set publication protocols are frozen. Authority publications now
have full-scope append-only trusted distribution, a durable database CAS floor, strict protected APIs, and read-time
local re-verification. The deployment agent now admits certification plans before durable claim,
confirms the same stable attestation-bundle decision after execution, and holds a read permit through
atomic evidence commit. Routine refresh may advance only the local cache generation; revocation,
successor attestation, expiry, rollback, or decision drift fail closed. V1 evidence remains readable
and v2 adds the signed run-trust binding. Multi-node revocation convergence, customer PKI/KMS/IdP,
database HA/DR, managed clock controls, and non-Java v2 compatibility still require environment
certification. The fixture reuse decision is in
[ADR-004](../docs/adr/ADR-004-mirror-plan-reuses-fixture-bundle.md).

### Mirror dynamic occurrence budget

`maximumInvocations` is a whole-run operator-occurrence limit, not merely a count of nodes in the
saved graph. Plan compilation now rejects a limit smaller than the recursively frozen static
inventory with `RG.MIRROR.INVOCATION_BUDGET_TOO_SMALL`. At runtime, a run-scoped atomic budget is
checked by BLOGE's inherited `ExecutionOperatorResolver` after exact inventory verification and
before fixture binding or operator execution. Root nodes, nested graph re-entry, every foreach or
loop item, streaming nodes, and compensation consume one occurrence. Retries remain ordered
attempts inside the already admitted occurrence and do not consume another occurrence.

Parallel expansion uses compare-and-set admission, so no race can admit more than the sealed plan
limit. Once exhausted, later work fails non-retryably with
`RG.MIRROR.INVOCATION_BUDGET_EXHAUSTED` before the operator can perform a side effect. Already
admitted work may finish; no new occurrence is admitted. The terminal signed evidence remains
available with status `EXECUTION_FAILED` and limitation `INVOCATION_BUDGET_EXHAUSTED`. Internal
test evidence also records only the maximum/admitted/rejected counters under
`mirrorInvocationBudget`; it stores no site, correlation, input, output, or exception data.

Choose the value from reviewed worst-case expansion, not the static node count alone. For a root
with one foreach node and an item graph with two nodes, a five-item input can require
`1 + (5 * 2) = 11` occurrences before compensation. Timeout, ingress limits, tenant concurrency,
and the occurrence budget protect different resources and must all remain enabled.

### Mirror operation observability

Every protected Plan, Run, Evidence, deployment-trust, observation, corpus, trajectory, and cluster
operation reaches exactly one terminal observer before its service result is returned. The observer
pre-registers a fixed set of Micrometer series; no tenant, organization, actor,
correlation, request, plan, run, exception, or business value can become a tag.

| Metric | Tags | Meaning |
|---|---|---|
| `resource.gateway.mirror.operations` | `operation`, `outcome` | Terminal operation count |
| `resource.gateway.mirror.duration` | `operation`, `outcome` | Terminal service duration timer |
| `resource.gateway.mirror.failures` | `operation`, `reason` | Rejected/failed count by bounded reason class |

The closed `operation` vocabulary is `plan_create`, `plan_read`, `run_create`, `run_read`,
`evidence_read`, `authority_key_set_publish`, `authority_key_set_read`,
`isolation_attestation_ingest`, `isolation_attestation_read`,
`isolation_attestation_revoke`, `observation_ingest`, `observation_review`,
`corpus_candidate_create`, `corpus_publish`, `corpus_trajectory_publish`, and
`corpus_cluster_publish`.
Outcomes are `succeeded`, `rejected`, and `failed`; failure reasons are `invalid_request`, `forbidden`, `not_found`,
`conflict`, `expired`, `capacity`, `unavailable`, `audit_unavailable`, and `unexpected`. This produces 240 bounded
series in total. Registry exporters may rename timer units according to their normal conventions.

The append-only `mirror_operation_audit` table is the durable authority. Each row contains database sequence/time,
complete enterprise scope, correlation and actor coordinates, the closed operation/outcome/reason, exact stable
`RG.MIRROR.*` code, optional request/plan/run ids, and duration. Its schema cannot represent context, fixture,
replay, node/edge input or output, exception message, or stack trace. There is deliberately no public audit-read
endpoint in this increment; platform audit pipelines should consume the restricted database projection.

Success audit is written in the same local transaction as plan persistence or the evidence/request terminal commit.
If that audit cannot commit, the business mutation rolls back and the caller receives retryable
`503 RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE`. A rejection/failure audit uses an independent `REQUIRES_NEW`
transaction so it survives the business rollback it explains; if this mandatory write also fails, the original
result is replaced by the same sanitized 503. Metrics remain advisory and never weaken this fail-closed rule.

Operations should page immediately on any `audit_unavailable`, alert on sustained `unavailable`/`unexpected`, and
track rejection ratios by operation without adding identity tags. Size the JDBC pool for an outer business
transaction plus the independent failure-audit transaction. The current table has no in-process deletion path;
production rollout still requires deployment-owned partitioning, access control, retention/archive policy, capacity
alerts, and a tested full-disk response. `mirrorOperationObservability=true` means this bounded observer is assembled,
not that an active audit-store health probe or those deployment controls have been certified.

The Plan command is recursively strict and its canonical JSON tree is capped at 16 MiB. Because MVC materializes
JSON before the command decoder runs, deployments must also enforce raw request-body size, connection, and rate
limits at the ingress boundary; the application decoder alone is not a denial-of-service boundary.

The execution command takes buffered raw bytes so duplicate keys, scalar coercion, non-canonical whitespace, raw size,
canonical size, depth, and node count are rejected before a typed command is created. Spring still buffers those bytes;
proxy/container streaming-body, connection, and rate limits remain production gates.

After compiling and reviewing a plan, execute that exact generation with a stable request id. Reusing the same id
and context returns the stored terminal result; changing plan or context under that id returns
`RG.MIRROR.RUN_IDEMPOTENCY_CONFLICT`. A concurrent identical call returns retryable
`RG.MIRROR.RUN_REQUEST_IN_PROGRESS` and `retryAfterSeconds`.

```bash
PLAN_ID='reviewed-plan-id'
PLAN_FINGERPRINT='sha256:replace-with-the-fingerprint-returned-by-plan-compilation'

curl -sS http://localhost:8080/api/mirror/executions \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  -H 'Content-Type: application/json' \
  --data "{
    \"schemaVersion\": \"resourceGateway.mirrorExecutionRequest.v1\",
    \"requestId\": \"demo-rehearsal-001\",
    \"planId\": \"${PLAN_ID}\",
    \"expectedPlanFingerprint\": \"${PLAN_FINGERPRINT}\",
    \"context\": {\"customerId\": \"C-1001\"}
  }"

curl -sS http://localhost:8080/api/mirror/runs/REPLACE_WITH_RUN_ID \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'

curl -sS http://localhost:8080/api/mirror/runs/REPLACE_WITH_RUN_ID/evidence \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'

# Stateful v3 runs only
curl -sS http://localhost:8080/api/mirror/runs/REPLACE_WITH_RUN_ID/state-workbook-seed \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'

# Stateful v4 read/write runs only
curl -sS http://localhost:8080/api/mirror/runs/REPLACE_WITH_RUN_ID/state-transition-workbook-seed \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'

# Stateful v5 failure-aware read/write runs only
curl -sS http://localhost:8080/api/mirror/runs/REPLACE_WITH_RUN_ID/state-write-outcome-workbook-seed \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'
```

Do not place `bloge.tenantId`, `bloge.namespace`, or `__nodeOutput:` keys in `context`; the service derives scope
from the authenticated identity and rejects internal engine-state injection. Run summaries expose only identities,
fingerprints, timestamps, status/trust class, and trace counts. Use the evidence endpoint for signed hash-only
node/edge/resolution facts; neither response returns business payload values.
The state-workbook route rejects stateless or incomplete runs with
`RG.MIRROR.STATE_WORKBOOK_SEED_UNAVAILABLE`; it never manufactures an empty
state workbook. The transition route rejects non-v4 evidence with
`RG.MIRROR.STATE_TRANSITION_WORKBOOK_SEED_UNAVAILABLE`; it reports only
observed committed/replayed transitions and never equates an empty transition
list with proof that no write was attempted. The write-outcome route rejects
non-v5 evidence with
`RG.MIRROR.STATE_WRITE_OUTCOME_WORKBOOK_SEED_UNAVAILABLE`; it reports every
executed write attempt and fails the gate on unresolved rejection,
pre-commit failure, or unknown commit outcome.

The strict `resourceGateway.mirrorResolution.v1` protocol is also frozen. It binds every future resolver outcome to
the exact run, plan, capability and invocation attempt; separates resolved null, visible/redacted output, hash-only
evidence, resolved error, rejection and abstention; and fingerprints both visible output and the complete artifact.
The protocol is implemented, schema-tested, produced by the run kernel, and exported inside signed evidence through
the authenticated durable serving surface.

Mirror compilation now freezes source-first selection separately from ordinary test selection. Owner rules precede
governed replay before selector specificity is considered, while ambiguity within one source remains fail-closed.
The exact strategy and per-site order are fingerprinted, and a mirror with no external edges still rejects fixtures
that target internal business nodes.

`MirrorResolver` and `MirrorResolverChain` now provide the bounded runtime extension point. The first adapters serve
exact owner rules and governed replay rules; future sources can be added without changing source precedence. The
chain owns final abstention and fails closed for unavailable or duplicate sources, ordinary controls, and
same-source runtime ambiguity. Mirror controls now execute through that chain and a single-completion journal emits
sealed, coordinate-ordered `MirrorResolution` records. Requests and outputs are represented by bounded canonical
fingerprints; owner and replay results retain exact artifact provenance; business error, rejection, and abstention
remain distinct. Ordinary tests keep their previous selection path. The probe advertises interception only when
the protected plan adapter is assembled, and advertises serving only when the complete run/evidence chain is assembled.

The portable evidence protocol supports the frozen v1 and v2 stateless
generations, a v3 read-only stateful generation, a v4 successful read/write
generation, and a v5 failure-aware read/write generation. V3 requires nested
`resourceGateway.mirrorStateRunEvidence.v1` and uses distinct
`resourceGateway.mirrorRunEvidence.v3`,
`resourceGateway.mirrorEvidenceAttestation.v3`, and
`resourceGateway.mirrorEvidenceBundle.v3` schemas and signature domain. V4
requires nested `resourceGateway.mirrorStateRunEvidence.v2` and similarly uses
distinct run-evidence, attestation, bundle schemas and a v4 signature domain.
V5 requires nested `resourceGateway.mirrorStateRunEvidence.v3`; every write
attempt has a terminal outcome, stage, state disposition, and independently
recomputable success or failure closure under a v5 signature domain.
Every generation signs only a
`HASH_ONLY` projection that binds request context, plan, capability closure, execution control, fixture revision,
semantic result, ordered node/edge traces, every sealed resolution, and explicit isolation facts. Newly produced
Ed25519 signatures and the complete bundle fingerprint are verified immediately. The internal run kernel now
projects its real node/attempt/edge values to bounded fingerprints, proves exact closure against every external
resolution, and refuses to return a result when no explicit signer exists or immediate signature verification
fails. A cryptographically signed run is still exploratory unless deployment egress denial is bound to an exact
isolation attestation and every limitation is closed. Payload-free persistence and protected plan serving are
complete; run/evidence serving is also complete for isolated test/staging use. Agent-snapshot admission,
terminal confirmation, v2 evidence binding, and transaction commit fencing are complete. Pre-materialization
ingress controls, non-Java v2 fixtures, cross-language canonicalization, and environment certification remain
production gates.
V3 additionally closes one immutable Session head and every state access
against the existing node-attempt/resolution trace. V4 closes successful
advancing Session heads, exact read revisions, virtual-write receipts and
transition events against the same trace. V5 also closes rejected,
pre-commit-failed, and in-process ambiguous outcomes without exposing payloads.
The independent test kit verifies v1/v2/v3/v4/v5, rejects mixed generations,
and derives the matching read-only, transition, or write-outcome workbook seed
only after generation-specific verification. A local exploratory demo will normally return
`gateReady=false` with blockers such as `EVIDENCE_NOT_CERTIFIABLE` and
`RUN_EVIDENCE_LIMITED`; that is an honest trust result, not a transport failure.
The Spring kernel now has
profile/property isolation and
ordinary business run APIs reject nested mirror, replay, replacement, and scenario controls before DTO binding,
while runtime readiness is derived from profile-owned assembled adapters rather than configuration text.

### Mirror deployment-isolation attestation protocol

The strict `resourceGateway.mirrorDeploymentIsolationAttestation.v1` protocol now defines the
external proof required to close `DEPLOYMENT_EGRESS_NOT_ATTESTED`. It binds an exact deployment
scope, cluster, namespace, workload, service account, immutable image digest, out-of-process
enforcement layers, fail-closed deny facts, policy fingerprints, bounded non-business egress
classes, and payload-free policy-proof references. Its validity is at most 15 minutes, signing may
lag observation by at most 5 minutes, and the complete mirror execution must fit inside the signed
window.

`MirrorDeploymentIsolationAttestationIntegrity` independently checks both canonical fingerprints,
the detached Ed25519 signature, an externally pinned authority key and issuer, key lifecycle and
signing window, exact local deployment identity, and execution-window coverage. The authority key
is separate from the mirror evidence signer. The independent test-kit verifies the same fixed
signed fixture without server or Spring classes.

Authority keys are now distributable through the separate strict
`resourceGateway.mirrorDeploymentIsolationAuthorityKeySetPublication.v1` protocol. Each publication binds the
complete enterprise scope, exact deployment, attestation issuer, stable key-set stream, bootstrap-root trust
domain, exact local M-of-N threshold, policy fingerprint, short validity window, and a monotonic generation plus
predecessor fingerprint. All supplied signatures must verify under distinct locally pinned authorities using
distinct root public-key material; an unknown, revoked, out-of-window, or cryptographically invalid extra signature
rejects the whole publication even after the threshold is met.
`MirrorDeploymentIsolationAuthorityKeySetIntegrity` rejects scope/identity drift, threshold
downgrade, rollback, fork, skipped generation, and predecessor mismatch, then exposes only verified public
attestation keys. The standalone test-kit implements the same checks and packages a public-only two-root fixture.

Authority publications now pass through a full-scope repository and protected trusted-distribution API. The
operator-owned `MirrorDeploymentIsolationAuthorityTrustPolicyProvider` supplies local binding and bootstrap roots;
the default is unavailable and request bodies cannot select trust. Immutable publication insertion, per-stream
`SELECT ... FOR UPDATE` floor CAS, and success audit commit together. Reads re-resolve local policy, re-verify the
current publication, and never serve a historical generation as trusted. The capability probe separately reports
protocol support, route assembly, and trust-provider readiness.

Attestations now pass through a second full-scope trust-control plane. An operator-owned admission provider pins the
exact first external revision, eliminating empty-database trust on first use; later revisions must be continuous.
Immutable proof bodies, append-only `ACTIVE`/`REVOKED` status publications, and one current CAS head commit with the
mandatory success audit. Reads return one canonical atomic bundle. Active bundles must still bind to the same current
authority generation and active time window, while revoked bundles remain distributable during authority outage so a
security denial cannot be blocked. Exact reads never expose historical generations as trusted.

The reusable deployment agent now pulls these current artifacts through private-PKI, SPKI-pinned,
identity-bound mTLS, requires an operator-provisioned non-TOFU bootstrap floor, rejects rollback,
fork, gaps and same-revision reactivation, and atomically replaces a durable read-only snapshot.
Revocation can commit without a positive authority read; stale ACTIVE use is bounded by the local
hard snapshot age. Certification-required runs now bind an admitted and committed agent snapshot
to v2 evidence and keep a transaction-lifetime trust permit through terminal commit. Exploratory
runs remain explicit; only an eligible plan with a stable ACTIVE decision can become `CERTIFIABLE`.
Customer PKI/KMS/IdP, multi-replica revocation convergence, non-Java v2 compatibility, and
environment certification remain deployment responsibilities. See the
[mirror schema guide](../docs/schemas/resource-gateway-mirror/README.md#deployment-isolation-attestation-boundary).
Operational wiring, endpoint semantics, stable failures, and rollout checks are in the
[authority trusted-distribution guide](../docs/resource-gateway-mirror-authority-trusted-distribution.md) and
[attestation control-plane guide](../docs/resource-gateway-mirror-attestation-control-plane.md). Deployment-side
assembly, cache ownership, revocation SLOs and recovery are in the
[deployment-agent guide](../docs/resource-gateway-mirror-deployment-agent.md).

### Mirror recorded-payload generation lifecycle

Recorded exact, trajectory, and cluster payloads now belong to one explicit in-process generation.
Unbound serving results and compiler site-bound views share the same owner. `MirrorRunService`
acquires an execution lease before any admission or engine work; `MirrorRunIntegrationService`
closes the owning `CompiledMirrorPlan` after evidence commit or on every failure path. Plan creation,
which persists only the payload-free public plan, closes its temporary generation immediately.

The lifecycle is `OPEN -> DRAINING -> CLOSED`. Owner close rejects new runs, lets already admitted
leases finish, then synchronously overwrites owned response JSON and private cluster-match byte
buffers. Escaped `Sample`, `Trajectory`, `Cluster`, or bound-view references fail closed after
destruction. Repeated owner and lease close calls are safe. Internal diagnostics can inspect
`ResolvedCorpusPayloads.lifecycle()` for payload-free active-lease, resident-byte, and
zeroized-byte accounting; business values never enter that snapshot or `toString()`.

Recorded-cluster matching owns its projected response only for the resolver call and zeroizes it
immediately after lowering it into a runtime rule. Failed capability, trajectory, cluster, or
whole-generation assembly also closes every payload object whose ownership already transferred.
Once attached, nested payload objects reject direct close; only the generation owner can destroy
them after active leases drain. Attachment and destruction carry the same process-local owner
token, so a failed second generation cannot clean up payloads held by the first. Payload-authority
materializations, their verification copies, and cluster-projection serialization buffers are
independently zeroized at their serving or resolver boundary.

Direct Java callers that materialize a runtime generation own it and must use try-with-resources:

```java
try (CompiledMirrorPlan generation = mirrorPlans.materialize(plan, identity)) {
    MirrorRunResult result = mirrorRuntime.execute(new MirrorRunRequest(
            requestId, generation, context, scope, "MIRROR_REHEARSAL"));
    // Persist or project terminal payload-free evidence before this block exits.
}
```

The protected HTTP run endpoint already applies this lifecycle and requires no client change.
Production certification still requires the planned forked-JVM heap-residue scan, asynchronous
cancellation/crash injection, fixed-cardinality leak telemetry, and a production payload authority
that minimizes heap plaintext with a direct-memory or sidecar-handle implementation.

### Mirror serving-generation fencing

Every non-empty recorded exact, trajectory, or cluster payload generation must now obtain a signed
current-floor token before compilation. The token binds the full enterprise scope, purpose,
payload-free materialized dependency closure, monotonic generation/predecessor, revocation cursor,
expiry, and signed maximum floor-cache staleness. The compiler emits `mirrorPlan.v2`; plans without
recorded corpus remain v1-compatible.

Every new run forces a shared authority floor read. Operator occurrences may reuse that verified
floor only inside the signed staleness window. A newer floor lets already admitted occurrence work
finish but rejects later occurrences before fixture selection, resolver use, or business operator
execution. Authority outage after the cache boundary, rollback, expiry, key failure, token drift,
and stale generation all fail closed with stable evidence codes. Metrics use only bounded
`check` and `outcome` tags.

Deployments must replace the default unavailable `MirrorServingGenerationAuthority` and
`MirrorServingGenerationTrustProvider`; Resource Gateway never treats an authority response or
locally generated key as a trust root. Wiring requirements, stable failures, metrics, rollout
checks, and revocation drills are documented in the
[serving-generation guide](../docs/resource-gateway-mirror-serving-generation.md).

Useful variants:

```bash
./scripts/start-visual-canvas-demo.sh --port 18080
./scripts/start-visual-canvas-demo.sh --no-build
./scripts/start-visual-canvas-demo.sh --api-only
./scripts/start-visual-canvas-demo.sh --run-tests
./scripts/start-visual-canvas-demo.sh --stateful
./scripts/start-visual-canvas-demo.sh --profile production --no-correctness
./scripts/visual-canvas-demo.sh status
./scripts/visual-canvas-demo.sh restart
./scripts/stop-visual-canvas-demo.sh
```

`--stateful` starts the Session data plane, read/virtual-write resolver, v3/v4/v5
evidence projection, v3 read-only workbook-seed route, and signed
checkpoint/recovery routes in the same service. No extra sidecar is required
for the local demonstration; use the ordinary stop script above. Stop preserves
the local JDBC data and AES key, so a checkpoint created before restart can be
admitted afterward when its exact Session head is unchanged.

`--stateful` uses conservative local defaults: 1,000 global and 100 per-scope
active sessions, 4 GiB global and 512 MiB per-scope retained canonical payload,
32 concurrent commands per replica, a 100-session expiry page every 30
seconds, and a 100-attempt reconciliation page every 5 seconds. Override them with
`RG_MIRROR_STATEFUL_MAXIMUM_ACTIVE_SESSIONS`,
`RG_MIRROR_STATEFUL_MAXIMUM_SCOPE_ACTIVE_SESSIONS`,
`RG_MIRROR_STATEFUL_MAXIMUM_RETAINED_PAYLOAD_BYTES`,
`RG_MIRROR_STATEFUL_MAXIMUM_SCOPE_RETAINED_PAYLOAD_BYTES`,
`RG_MIRROR_STATEFUL_MAXIMUM_CONCURRENT_COMMANDS`,
`RG_MIRROR_STATEFUL_EXPIRY_BATCH_SIZE`, and
`RG_MIRROR_STATEFUL_EXPIRY_SWEEP_INTERVAL_MILLIS`,
`RG_MIRROR_STATEFUL_WRITE_ATTEMPT_RECONCILIATION_BATCH_SIZE`, and
`RG_MIRROR_STATEFUL_WRITE_ATTEMPT_RECONCILIATION_SWEEP_INTERVAL_MILLIS`.
These are hard safety
bounds, not production sizing recommendations. Target database dialect, row-lock
semantics, guard contention, expiry lag, and peak/soak behavior require
deployment-specific certification; this payload counter is not a physical
database disk quota, and the repository test gate currently certifies H2 only.

`staging` requires two independent deployment-secret key rings before `--profile staging`:
`RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID` plus
`RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING` protect claim replay/control credentials, while
`RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID` plus
`RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING` protect low-entropy request tombstone indexes. Ring
values use `keyId=base64-encoded-32-byte-key[,oldKeyId=...]`.
`RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE` is also required and must be
`LEGACY_READ_WRITE`, `DUAL_READ_KEYED_WRITE`, or `KEYED_ONLY`. The launcher fails early when any
value is absent or the mode is invalid. Staging also fails closed unless the independent external
discard-authorization trust is complete: set
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN`,
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS`,
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD`, and
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON`. The last value is a JSON array of
external authority IDs, key IDs, validity windows, state, and X.509-encoded Ed25519 public keys;
private keys never belong in Resource Gateway. The public capability probe reports quorum readiness
and counts without key material. The `test` profile defaults to unavailable external trust, so
discard approval returns `503` until all four values are supplied. Its committed local symmetric
keys are demonstration-only and must not be reused.
Worker-quarantine detailed replay defaults to 30 days, token-free history and request tombstones to
365 days, and leased cleanup to 100 rows per category every hour. Override these with
`RG_TEST_WORKER_QUARANTINE_COMMAND_RETENTION_DAYS`,
`RG_TEST_WORKER_QUARANTINE_HISTORY_RETENTION_DAYS`,
`RG_TEST_WORKER_QUARANTINE_TOMBSTONE_RETENTION_DAYS`,
`RG_TEST_WORKER_QUARANTINE_RETENTION_PAGE_SIZE`, and
`RG_TEST_WORKER_QUARANTINE_RETENTION_INTERVAL_MS` before startup.

The compact-observation external WORM adapter is a separate, default-off test/staging integration.
Enable it with `RG_TEST_STABILITY_OBSERVATION_ARCHIVE_HTTP_ENABLED=true` and provide the trust
domain, archive-set id, minimum retention, copy threshold, bounded timeout/lifetime, public
Ed25519-key JSON, and authority/failure-domain/HTTPS-endpoint JSON documented in the
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md). Staging rejects
loopback HTTP and fewer than two copies; production never installs the adapter or retirement bean.
The same endpoint now accepts a distinct signed, challenge-bound read-only inventory protocol for
immutable snapshot paging. A provider may serve a pre-generated snapshot, but the client rejects
future snapshots and snapshots older than the configured bound (300 seconds by default). There is
no inventory controller or delete operation. Durable inventory, frozen comparison, governed
findings, bounded derived-evidence retention, and bounded source-history retirement can now run
autonomously when the separate
`RG_TEST_STABILITY_OBSERVATION_ARCHIVE_RECONCILIATION_ENABLED=true` flag and stable
`RG_TEST_STABILITY_OBSERVATION_ARCHIVE_RECONCILIATION_INSTANCE_ID` are supplied. The scheduler
drains findings and comparisons before opening another inventory cycle, isolates one failed
authority, and remains physically absent in production. Its aggregate Actuator indicator now
combines scheduler freshness, fingerprint-verified database progress for inventory/comparison/
finding, completed-evidence age, and derived-evidence retention freshness/backlog. Startup grace,
transient failure budget, stage-idle and lifecycle thresholds are bounded configuration. A separate
source-retention lane defaults to 365-day processed history, 30-day expired snapshots, one 100-row
dependency segment per hour, and a 120-second database lease. Its database last-success, permanent
active-marker age, and both eligible backlogs enter readiness; normal lease contention does not.
Authority,
object, cursor, lease and fingerprint identities never appear. `/api/integration/capabilities`
separately reports `configured` and current `ready` truth. Descriptor v2 embeds an independent
`sourceRetention` state, and dedicated configured/readiness/health feature flags keep source
lifecycle truth visible even if another stage is degraded. Open governance findings are reported as
an aggregate business outcome and do not make the control loop unhealthy. Inventory authority and
cycle rows now use the same versioned whole-record fingerprint in collection, comparison, and
readiness paths. Signed inventory page JSON and its indexed columns, normalized item ownership,
comparison-authority pointers, and classification commit metadata also have separate whole-row
fingerprints; collection, classification export, and finding projection verify them before use. The
first upgraded test/staging startup establishes a one-time baseline for legacy rows and then requires
non-null fingerprints; perform that upgrade with all replicas stopped because it is not an N/N-1
production migration protocol. These local unkeyed seals expose drift but do not replace database
access/audit controls or an externally witnessed integrity commitment.

`--no-build` validates that a visual-demo jar contains `/author/`, `/libraries/`,
`/rehearsals/`, and `/showcase/` before starting Java. Reusing an API-only jar in
the default visual mode fails with a rebuild instruction; pass `--api-only` when
only the API surface is intended. The start command becomes ready only after the
integration capability probe and every packaged visual route succeed. API-only
status output does not advertise unavailable visual URLs. Process output is
written to `target/example-logs/visual-canvas-demo.log`;
the PID and selected port are kept under `target/example-pids/`.

The testing API requires `Authorization: Bearer bloge-aneke-demo-token` and a
least-privilege `X-Purpose` (`TEST_EXECUTION`, fixture read/write, or suite read/write) in the local
test profile. Immutable suites use `TEST_SUITE_READ` and `TEST_SUITE_WRITE`; exact suite execution
and suite-run query use `TEST_EXECUTION`. Materialize the seven built-in graph suites into exact
common fixture/TestSuite revisions with:

```bash
curl -sS -X PUT http://localhost:8080/api/testing/catalogs/gateway-graph-contract-v1 \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_SUITE_WRITE'
```

Stored fixture identity is verified at every trust boundary: the database repository and each
execution, suite-publication, and durable-recovery consumer reconstruct an independently owned,
deeply frozen canonical snapshot, recompute its bundle fingerprint, and bind it to the complete
tenant/organization/project/environment/region/id/revision lookup key. A binding fingerprint
additionally detects indexed ownership movement. Create responses must also match the submitted
immutable identity and content, while idempotent retries preserve first-write provenance. A valid same-key
replacement is treated as dependency drift; mutable aliases, cross-scope
substitution, malformed storage, and tampering fail closed without exposing fixture content.
See the
[stored fixture integrity verification](../docs/resource-gateway-execution-data-control-plane-stage2-fixture-registry-integrity-verification.md)
for invariants, failure semantics, and remaining trust assumptions.

Stored TestSuite revisions use the same fail-closed ownership model across all v1-v5 generations.
Registration first canonicalizes caller-owned case inputs and metadata; repository create/read and
service create/read independently detach the returned value, recompute its exact-generation
fingerprint, bind its v2 envelope to the full
tenant/organization/project/environment/region/suiteId/revision key, verify the scope binding
fingerprint, and verify create receipts. Malformed JSON, content drift, or a valid cross-scope substitution produces the payload-free
`RG.TEST.SUITE_INTEGRITY_INVALID`; idempotent retries retain first-write provenance, while a valid
different suite at the same key remains an immutable revision conflict. These local hashes detect
drift but do not replace external signing or WORM storage. See the
[suite registry verification](../docs/resource-gateway-execution-data-control-plane-stage2-suite-registry-verification.md).

Governed F4 replay values now cross the same kind of hostile storage boundary. The vault detaches
caller JSON/beans, recomputes the available value fingerprint, verifies exact create receipts and
tenant/environment/id/revision lookups, and binds descriptor JSON to every indexed projection. A
second payload-free record commitment protects scope, provenance, and lifecycle state after expiry
erases the value; read-time and scheduled expiry replace that commitment in the same CAS update.
Malformed rows or valid cross-key adapter substitutions emit payload-free
`RG.TEST.REPLAY_INTEGRITY_INVALID` failures. Legacy available rows are revalidated on upgrade;
historical tombstones receive an explicit value-free baseline and therefore are not presented as
retroactively externally authenticated. See the
[replay vault storage integrity verification](../docs/resource-gateway-execution-data-control-plane-stage2-replay-storage-integrity-verification.md).

Signed child evidence now uses one canonical snapshot from seal through storage. Payload-bearing JSON
containers are recursively frozen, arbitrary Java values are detached by an exact evidence round
trip, and the signer returns the value it actually signed. JDBC verifies that signature before a new
write and binds serialized evidence to signed identity metadata, target/fixture/plan fingerprints,
the full tenant/environment/run lookup, and independently indexed row columns on every read.
Mutation, a forged `VERIFIED` manifest, indexed/JSON drift, or cross-scope substitution fails before
projection as payload-free `RG.TEST.EVIDENCE_INTEGRITY_INVALID`. See the
[child evidence storage integrity verification](../docs/resource-gateway-execution-data-control-plane-stage3-child-evidence-storage-integrity-verification.md).

See
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md)
for the complete target-discovery, fixture-registration, execution, evidence,
and production-isolation workflow. Java/JUnit/CI consumers can use the independent
[Resource Gateway Test Kit](../resource-gateway-test-kit/README.md) for fixture and immutable-suite
builders, bounded deterministic identity/feature-flag fixture controls, typed catalog materialization,
exact suite execution, signed bounded stability analysis,
signed retained-window and compact-range trend reconstruction, pinned-key-set offline verification, payload-free
assertions/XML, and the fail-closed CLI instead of
hand-assembling HTTP requests or interpreting aggregate evidence ad hoc. The same independent JAR
now packages all Stage 0 capability-mirror schemas plus the shared compatibility fixture. ANEKE and
other governance consumers can negotiate `/api/integration/capabilities` and verify snapshot/closure
schema, canonical fingerprint, exact dependency closure, and enterprise scope without linking this
Spring Boot application. See the test-kit's capability-mirror section and
[`docs/schemas/resource-gateway-mirror`](../docs/schemas/resource-gateway-mirror/README.md).

The stability protocol's
terminal publication now also verifies and signs a payload-free compact observation, then commits
that observation, its contiguous per-suite ledger coordinate, the full terminal record, progress
consumption, and lease consumption in one database transaction. This is the durable write-side
foundation for history beyond full-run retention. A bounded, signed range read plus strict Schema and
independent five-layer test-kit verification now exist as a default-disabled test/staging preview.
An internal database-authoritative core can sign a bounded retirement intent, atomically retain its
payload-free local archive, move the durable floor/head coverage, and delete only the exact active
prefix. A separate default-disabled lifecycle endpoint and independent test-kit now prove the
ordered local retirement chain from generation zero to one snapshot-pinned current floor/head,
which can then seed the active compact-range request. `crossRetentionSuiteStabilityTrend`
intentionally remains disabled. The strict multi-authority HTTPS adapter closes the test/staging
write shape with concurrent bounded requests, signed conflict receipts, exact topology/key
verification, and aggregate health. Its signed immutable-snapshot inventory protocol now closes the
read transport shape and exposes a fail-closed historical-page verifier that checks canonical
material, configured topology, snapshot identity, and signing-time key validity without incorrectly
reapplying the page's consumed live-admission deadline. The default-off test/staging control loop
now wires durable lease/cursor, frozen classification, replay-verified finding projection,
downstream backpressure, and bounded
finding/evidence retention. Its source-history retention core now separately fences processed and
expired sources, verifies signing-time trust for stored pages, deletes one bounded dependency segment
per transaction, and permanently gates classification export during and after retirement. It now
runs on its own profile-gated fixed-delay scheduler and reports database-authoritative freshness,
stalled progress, backlog, and nested capability truth. Certified providers, historical trust
publication, legal-hold/erasure/backup and recovery controls,
and witnessed non-equivocation are still required. See the
[lifecycle protocol design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-floor-lifecycle-protocol-design.md),
the [HTTPS WORM adapter design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-http-worm-adapter-design.md),
the [external inventory protocol design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-external-inventory-protocol-design.md),
and the [external reconciliation design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-external-reconciliation-design.md).
The stability protocol's
v2+ evidence keeps behavioral stability separate from release eligibility: every verified source
suite promotion verdict is signed into the attempt closure, so `STABLE + BLOCKED` remains visible
when behavior is repeatable but source certification is insufficient. Historical v1 evidence stays
auditable but cannot enter a release gate. Current statistical v4 reserves the first verified vector
as baseline, signs `verifiedAttempts - 1` comparison trials, and reports an exact one-sided rate
upper bound for complete zero- or non-zero-event samples. Any censoring remains fail closed, while
deterministic `FLAKY` still blocks promotion even when a configured statistical rate ceiling is
satisfied. Historical v3 retains its original zero-event wire semantics for audit; neither
generation is a correctness proof. A database-clock parent
lease now returns retryable `429` to concurrent duplicates before child execution. Every verified
source reference and lease renewal then commit atomically before another attempt can start. Crash
takeover verifies the durable prefix and executes only the remaining horizon; terminal insertion
atomically consumes both progress and lease. The public progress projection exposes only lifecycle,
suite identity, and counts. V5 adds a precommitted alternative and anytime-valid e-process, stops only
at the first reconstructed crossing, first censor, or maximum horizon, and signs the actual observed
prefix plus terminal reason. The durable asynchronous queue is available in test/staging; physical
distribution and isolation of individual attempts are not yet provided.
The invariants and deliberately unclaimed guarantees are recorded in
[Stage 5 suite-stability verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-verification.md)
and the focused
[execution-lease verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-execution-lease-verification.md)
and [durable-progress verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-durable-progress-verification.md).

Fixture `executionServices` v2 can resolve opaque test-secret references without storing plaintext.
The default authority is unavailable. Set `RG_TEST_SECRET_AUTHORITY_HTTP_ENABLED=true`, the HTTPS
base URI, exact authority id, and either a strict public Ed25519 key array or the opt-in dynamic JWKS
settings to enable the built-in adapter in `test`/`staging`. It calls
`/v1/test-secret-resolutions` once per fresh or recovered run with a new
256-bit challenge and accepts only an exact short-lived signed `AUTHORIZED` or `DENIED` response.
Unsigned HTTP denial, redirect, timeout, malformed/oversized response, stale or revoked key, closure
drift, and signature failure all fail closed; values remain run-scoped and never enter a checkpoint.
Set `RG_TEST_SECRET_AUTHORITY_JWKS_ENABLED=true` and its HTTPS URI to get atomic ETag refresh,
unknown-key rotation, explicit `enabled`/`revoked` propagation, a hard maximum snapshot age, and
payload-free Actuator health without restart. Static and dynamic key modes are mutually exclusive;
dynamic bootstrap or any ambiguous refresh fails closed. Multi-replica deployments can additionally
set `RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED=true` plus one stable fleet scope, immutable deployment
cohort, exact instance slot and artifact fingerprint. In staging, also enable the required
deployment-signed inventory and supply its trust domain, accepted policy fingerprints, Ed25519
M-of-N public authority keys, and strict signed JSON envelope. The signed material is authoritative
for the complete serving-slot set and also binds the test-secret authority identity; an optional
configured list is equality-only. Database-clock process-start leases then block secret resolution
until every exact slot is live, healthy, on one complete JWKS generation and on one signed-inventory
generation. Duplicate starts, overlapping deployments, inventory rollback/fork, runtime expiry and
generation drift fail closed. Set the signed-inventory `remote` properties to consume a strict
vendor-media HTTPS publication with ETag refresh, signed `ACTIVE/REVOKED` state, an independent
witness quorum and a namespaced durable publication/witness floor. Candidate verification and floor
advance complete before one atomic local publish; any transport, protocol, signature, freshness,
chain or floor ambiguity blocks resolution without discarding the last diagnostic head. A valid
successor recovers without restart. Capability and health expose aggregate readiness only. Staging
additionally requires managed roots: independent deployment and witness bootstrap-root quorums sign
one atomic deployment/witness runtime-key publication, whose strict HTTPS/ETag refresh and database
floor permit routine key rotation without restarting Resource Gateway. A root generation change
closes resolution until the inventory is reverified, including after an inventory `304`; managed and
legacy static runtime keys cannot be mixed. Staging also requires an external `3f+1 / 2f+1`
challenge-bound notary quorum for both the composite publication/witness stream and the atomic
runtime-root stream. External compare-and-append completes before each local database floor; a
signed conflict is fatal, while an external success followed by local failure is exact-retry safe.
The smallest staging topology is four independent notaries with three accepted receipts. Notary
bootstrap roots are themselves restart-free: staging pins one public genesis, replays a complete
cross-signed root chain from a strict HTTPS bundle, and advances a dedicated durable floor before a
root head becomes usable. The demo startup script validates this configuration before build or Java
startup. Staging also authenticates the external notary, managed receipt-trust publication, and
complete root-bundle source with separately pinned mutual-TLS identities. Automated certificate
rotation, HSM/KMS custody, authority HA/chaos, root anti-equivocation, target-database and DR
certification remain open.
A changed member topology still
requires a coordinated new cohort generation.
See the
[testing control-plane guide](../docs/resource-gateway-testing-control-plane-api.md#421a-control-identity-feature-flag-and-secret-built-ins),
the [managed test-secret trust-root verification](../docs/resource-gateway-execution-data-control-plane-stage4-test-secret-trust-root-rotation-verification.md),
and the [test-secret external non-equivocation verification](../docs/resource-gateway-execution-data-control-plane-stage4-test-secret-external-non-equivocation-verification.md).

The durable stability queue and authenticated asynchronous submit/query/cancel protocol are present
in the isolated `test`/`staging` datastore. Query and cancellation remain available while the worker
is disabled or draining; fresh submission then returns
`503 RG.TEST.STABILITY_JOB_SUBMISSION_UNAVAILABLE`. Set
`RG_TEST_STABILITY_JOB_WORKER_ENABLED=true` only when one current-authority provider is ready. The
built-in provider is enabled with `RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_ENABLED=true`; it sends a
credential-free, challenge-bound request to
`<RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_BASE_URI>/v1/stability-job-authorizations` and accepts only a
short-lived Ed25519-signed `AUTHORIZED` or `REVOKED` response from
`RG_TEST_STABILITY_JOB_AUTHORITY_ID`. Configure one or more X.509-encoded public keys through
`RG_TEST_STABILITY_JOB_AUTHORITY_KEYS_JSON`, or set
`RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_ENABLED=true` and provide
`RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_URI` for restart-free Ed25519 rotation. Dynamic mode performs
an atomic bootstrap, ETag-based background refresh, cooldown-bound unknown-key refresh and hard
snapshot-age expiry. Any refresh ambiguity closes fresh admission; it never silently continues with
stale revocation state. HTTPS is mandatory; the insecure-loopback escape hatches exist only for
local tests. Multi-replica dynamic-JWKS deployments can additionally enable
`RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ENABLED=true`. Each process then publishes a database-clock
lease keyed by fleet scope, deployment cohort, serving instance, and process start. Fresh submission, worker
claim, and post-claim reauthorization remain closed until the exact expected instance set is
live, healthy, on one artifact/protocol/policy, and observing one complete JWKS generation. A stable
scope elects only one live deployment cohort, so overlapping rollouts cannot each self-admit. This
can retain local configured inventory in the `test` profile. Staging additionally requires an
M-of-N Ed25519-signed deployment serving inventory delivered through a strictly versioned HTTPS
publication. Deployment authorities sign `ACTIVE`/`REVOKED` state and an independent witness domain
signs the same sequence and predecessor chain. Atomic ETag refresh, hard source age, signed expiry,
revocation, protocol downgrade, chain ambiguity, and refresh failure all fail closed. Every live
cohort member must also publish one identical private publication/witness generation before
convergence. Before any verified generation becomes observable, a database-clock stable-scope floor
atomically persists its sequence and publication/witness fingerprints; complete process or fleet
restart therefore cannot accept rollback, fork, gap, or a broken predecessor while the test-runtime
database remains intact. Floor corruption or database outage fails startup/refresh closed. The nested
inventory revision, material/policy fingerprints, expiry, exact set,
artifact, scope, cohort, and protocol remain bound into policy; a durable stable-scope revision floor
rejects rollback and same-revision forks. The optional local instance list is only an equality
assertion. Static document injection remains a `test` fallback and is forbidden with staging remote
mode.

Physical-attempt terminal projection has a separate opt-in local runtime. It remains disabled by
default through `RG_TEST_PHYSICAL_ATTEMPT_TERMINAL_PROJECTION_ENABLED=false` and is physically absent
from every profile containing `production`. An embedding deployment may enable it only after
supplying pinned `TestSuiteStabilityPhysicalAttemptStartVerifier`,
`TestSuiteStabilityPhysicalAttemptObservationVerifier`, and
`TestSuiteStabilityAttemptCancellationVerifier` beans. Startup also requires the isolated database
`DatabaseTestSuiteStabilityJobRepository`; a generic or remote queue adapter is rejected because the
terminal queue transition and physical-slot release must share its transaction authority. The same
switch activates physical-attempt orphan-slot fencing on that queue and startup verifies the fence is
actually enabled; a legacy fence-off database queue is rejected. Set a
stable replica identity with `RG_TEST_PHYSICAL_ATTEMPT_TERMINAL_PROJECTION_WORKER_ID`. The remaining
`RG_TEST_PHYSICAL_ATTEMPT_TERMINAL_PROJECTION_*` settings bound pollers, zero-queue call capacity,
lease/call/completion budgets, retry, and readiness SLOs; invalid combinations fail startup without
echoing configured identities. Actuator health exposes aggregate work counts and local capacity only,
and Micrometer labels are closed enums. This lane consumes already registered terminal work.

Autonomous retained-start discovery is a second, independently gated test/staging runtime. First
enable the terminal-projection lane, then set
`RG_TEST_PHYSICAL_ATTEMPT_OBSERVATION_RECONCILIATION_ENABLED=true`, configure a stable
`..._WORKER_ID`, and supply exactly one
`TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority` bean. An arbitrary function or Map
resolver no longer satisfies startup. Static signed inventory remains useful for isolated component
tests, but Tool Studio intentionally reports it as `DYNAMIC_INVENTORY_REQUIRED` rather than fleet
readiness.

The product dynamic authority is opt-in with
`RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_ENABLED=true` and is physically absent from any profile
containing `production`. The embedding deployment must register exactly one
`TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog`; this catalog is only the installed adapter
superset. The signed publication remains the sole admission authority and must name every expected
Resource Gateway replica. There is deliberately no local expected-replica list that could narrow a
fleet. Configure the trust domain, scope, cohort, accepted policy fingerprints, independent
deployment/witness trust domains, local replica/artifact identities, and the HTTPS source using the
`RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_*` variables documented in `application-test.yml` and
`application-staging.yml`. Exactly one verification mode is allowed. Static migration mode supplies
the deployment/witness thresholds and public Ed25519 keys directly. Managed mode sets those four
static fields to `0`/`[]` and enables
`RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_TRUST_ROOT_ENABLED=true`.

Managed mode consumes one atomically signed deployment/witness runtime-key publication. Configure
`..._TRUST_ROOT_SET_ID`, accepted root policy fingerprints, independent deployment/witness bootstrap
root domains, thresholds and public-key arrays, the root publication URI, refresh/timeout/unknown-key
budgets, and the hard maximum age. The root endpoint must return
`application/vnd.bloge.physical-attempt-provider-inventory-trust-root-publication.v1+json` and the
exact `X-BLOGE-Physical-Provider-Inventory-Trust-Root-Protocol` value
`bloge.testSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication.v1`. It must not reuse
the inventory publication URI. The root authority bootstraps before the inventory consumer, owns an
independent database sequence floor and Actuator health contributor, supports restart-free atomic
dual-key rotation, and closes only after its consumer during context shutdown.

The publication endpoint must return
`application/vnd.bloge.physical-attempt-provider-inventory-publication.v1+json`, the exact
`X-BLOGE-Physical-Provider-Inventory-Protocol` value
`bloge.testSuiteStabilityPhysicalAttemptProviderInventoryPublication.v1`, and a strict signed
`ACTIVE` or `REVOKED` envelope. Bootstrap and each ETag refresh verify the nested inventory,
deployment quorum, independent witness quorum, predecessor chains, exact signed replica set, hard
freshness, and the database publication/witness floor before atomically exposing a generation.
Refresh ambiguity immediately closes resolution; a valid successor restores it without restart.
Previously resolved wrappers are generation-fenced and also close after successor or revocation.

Each process start publishes a database-clock lease keyed by the signed scope/cohort and local
replica identity. Cohort readiness requires the exact signed replica set, one publication generation,
one artifact and protocol, and no missing, unexpected, duplicate, drifted, expired, or corrupt row.
The heartbeat interval must not exceed half the lease. Startup rejects missing catalogs, unknown
properties, unsafe timing, unsigned fallback, and non-database floor/cohort composition without
echoing configured identities.

Adapter descriptors must exactly reproduce signed key, isolation, latency, and retention facts
before provider I/O. Inventory hard expiry closes both new resolution and previously resolved
wrappers without restart. The reconciliation runtime also rejects non-database start, observation,
or terminal-work journals and unsafe deadline/window/lease/capacity combinations. Its database
journal discovers retained starts in bounded fair-scope pages; a verified terminal completion and
projection-work registration commit atomically.
Observation provider-call capacity is enforced by nonblocking admission permits. Active and
interrupt-ignoring lingering calls consume permits and reject excess work immediately. A separate
bounded executor buffer is only a worker-handoff mechanism after a provider has returned; it prevents
a completed sequential descriptor/observation call from being misclassified as saturation while the
worker is still in executor bookkeeping. Timeout, interruption, and close remove unstarted handoffs
and recycle their permits, while a started provider retains its permit until it actually exits.
Actuator health reports only aggregate discovery lag, due age, quarantine, scheduler state, and
provider-call capacity. The provider-inventory health contributor similarly exposes aggregate
refresh/cohort facts without replica, provider, deployment, key, fingerprint, or URI identities.
Micrometer uses only closed labels. Tool Studio publishes the typed, identity-free
`physicalAttemptRuntime` capability and reaches `READY` only while the dynamic authority, exact cohort,
terminal projection, and observation reconciliation are simultaneously healthy. All switches remain
disabled by default, and the standalone demo script intentionally enables none of these physical
lanes. The physical Spring composition now binds publication and witness into one
domain-separated, external-first composite head and reports external versus Byzantine quorum truth
separately through a dedicated domain marker and the shared strict HTTP/quorum adapter. `test` keeps
this optional for local migration, so disabled deployments remain honestly database-floor-only. An
enabled anchor must resolve to exactly one available, externally durable and challenge-bound marker
bean. A configured non-zero fault bound also requires a Byzantine descriptor; hidden, duplicate,
unsafe, or invalid-quorum anchors fail startup.

Configure this path below
`RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_EXTERNAL_ANCHOR_*` in the profile YAML. The groups are:
the notary set and timing policy, `..._TRANSPORT_*` for the notary HTTPS identity,
`..._TRUST_*` plus `..._TRUST_TRANSPORT_*` for managed receipt-key publication, and
`..._BOOTSTRAP_ROOT_*` plus `..._BOOTSTRAP_ROOT_TRANSPORT_*` for its complete-chain root source.
Staging also requires managed roots with `..._TRUST_ROOT_ENABLED=true`,
`..._TRUST_ROOT_REQUIRED=true`, strict HTTPS, and every insecure-loopback escape hatch off. It
refuses the physical inventory unless external anchoring is enabled and required with
`minimum-faults >= 1` and `maximum-faults >= 1`; managed receipt trust, complete-chain bootstrap
roots, and all three private-PKIX/SPKI/mTLS/workload-identity transports must also be enabled and
required and carry exact client/server certificate identities, with every insecure-loopback escape
hatch off. Staging YAML makes each identity requirement follow transport enablement, and Java
preflight independently rejects any unbound transport so direct property injection cannot downgrade
the policy. The three transport identities are now
part of the shared 15-target restart-free certificate-rotation inventory. Aggregate health exposes
only strength and availability facts. The strict product entry point is the
[physical external-anchor configuration Schema](../docs/schemas/resource-gateway-testing/physical-attempt-provider-inventory-external-anchor-configuration-v1.schema.json).

N/N-1 root and publication backfill, bounded evidence retention, HSM/KMS custody, root-publisher
HA/anti-equivocation certification, a certified process/container adapter, external notary
production certification, fleet failover/chaos evidence, and production profile wiring remain
open. See the
[dynamic provider-inventory verification](../docs/resource-gateway-execution-data-control-plane-stage4-dynamic-physical-provider-inventory-verification.md),
[external non-equivocation core verification](../docs/resource-gateway-execution-data-control-plane-stage4-physical-provider-inventory-external-non-equivocation-core-verification.md),
[external non-equivocation runtime verification](../docs/resource-gateway-execution-data-control-plane-stage4-physical-provider-inventory-external-non-equivocation-runtime-verification.md),
[managed trust-root consumer verification](../docs/resource-gateway-execution-data-control-plane-stage4-physical-provider-inventory-managed-trust-root-consumer-verification.md),
[managed trust-root product-composition verification](../docs/resource-gateway-execution-data-control-plane-stage4-physical-provider-inventory-managed-trust-root-product-composition-verification.md),
and the strict
[publication](../docs/schemas/resource-gateway-testing/physical-attempt-provider-inventory-publication-v1.schema.json),
[generation floor](../docs/schemas/resource-gateway-testing/physical-attempt-provider-inventory-publication-generation-v1.schema.json), and
[cohort binding](../docs/schemas/resource-gateway-testing/physical-attempt-provider-inventory-cohort-binding-v1.schema.json)
schemas.

Staging also requires managed serving-inventory runtime keys. One canonical publication atomically
carries the deployment and witness key sets and is independently approved by an M-of-N deployment
bootstrap-root quorum and an M-of-N witness bootstrap-root quorum. Configure
`RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_ENABLED=true`, the HTTPS source,
stable root-set id, accepted policy fingerprints, independent root domains, thresholds, and public
root-key arrays under the corresponding `...INVENTORY_*_ROOT_*` variables in
`application-staging.yml`. Legacy static runtime trust domains, thresholds, and key arrays must be
unset (`0`/`[]`) in managed mode. The source performs strict bootstrap, ETag refresh, bounded
unknown-key refresh, hard-age expiry, dual-quorum verification, and a database-backed sequence
floor before exposing a new immutable runtime key snapshot. Inventory verification binds the exact
root generation; rotation A to B therefore needs no application restart, while root outage,
rollback, fork, partial publication, threshold revocation, or generation disagreement closes
admission. The root health contributor and cohort descriptor expose only aggregate state and
boolean protocol facts.

Staging additionally requires both mutable ordering streams to be anchored outside the rollbackable
Resource Gateway database. Set
`RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENABLED=true` and configure one
stable trust domain/set id and one HTTPS endpoint plus distinct failure domain per notary. Staging
also requires `...EXTERNAL_ANCHOR_MANAGED_TRUST_ENABLED=true` and
`...EXTERNAL_ANCHOR_BOOTSTRAP_ROOTS_ENABLED=true`. Deployment pins the public-only
`...EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_GENESIS_JSON`; a strict HTTPS
`...EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_BUNDLE_URI` supplies the complete cross-signed successor chain.
Only a full genesis replay accepted by its dedicated database floor can authorize the strict
HTTPS/ETag notary publication containing the exact key lifecycle set, policy, quorum, validity
window and monotonic predecessor chain. Legacy bootstrap thresholds/key arrays and static notary
keys must be `0`/`[]`. Unknown
receipt keys trigger one cooldown-bound refresh, so routine key rotation and revocation require no
Resource Gateway restart; any refresh, signature, lifecycle, rollback, fork, gap or durable-floor
ambiguity immediately fails receipt verification closed. The built-in policy uses `3f+1`
authorities and a `2f+1` accepted-receipt
threshold; staging enforces `f>=1`, so the smallest deployment is four independently operated
notaries with a three-signature quorum. Every request carries a fresh 256-bit challenge and exact
publication or trust-root sequence head. A valid signed conflict is fatal even when another quorum
accepts; unavailable or malformed minority responses are tolerated only while the acceptance
threshold remains. The external compare-and-append commits before the local database floor. Thus an
external success followed by local failure is safe to retry, while no local generation can become
visible without an external checkpoint. This closes complete database-backup rollback only under
the declared `<=f` Byzantine and independent-failure-domain assumptions; deploying, certifying,
backing up, and monitoring the external notary service remains a deployment responsibility. The
embedded bootstrap-root maker/checker service now automatically renews an exact database-issued
successor fence during long signer calls, freezes renewal before terminal commit, and fails closed
on response ambiguity, malformed successors, expiry, or shutdown. Optional unattended recovery now
uses one fixed-delay daemon lane per root-set journal. Discovery, failed-attempt backoff, automatic
attempt budget, expired-lease takeover, and new fence issuance occur atomically under database time;
the policy fingerprint is bound to the durable root-set lock so replicas with different retry
pressure fail startup. Runtime authority resolution starts only after acquisition and the approved
public cohort is recomputed before signing. Resolution, descriptor, and signature calls all pass
through configurable wall-clock deadlines and one fixed-capacity, zero-queue daemon pool;
interrupt-ignoring calls remain visible as bounded lingering occupancy, while saturation rejects new
work immediately. Heartbeats and recovery never extend checker approval or the proposal execution
deadline. A successful `PRODUCED` transition now atomically enqueues one content-addressed,
complete-chain publication request in a separate durable outbox. The outbox verifies its source
ceremony on every claim, preserves root sequence order, uses database leases/backoff/attempt limits,
backfills legacy produced rows, and rejects policy drift or whole-row corruption. Remote publishers
must replay the exact `publicationId`; only a matching receipt advances the outbox to `PUBLISHED`.
The built-in publisher adapter sends that request over strict HTTPS with the publication id as the
idempotency key and the predecessor as an HTTP conditional. It accepts only bounded strict JSON
whose fresh response material, request fingerprint, status, publisher binding, and Ed25519 signature
all verify against one statically pinned public response key. A database-fenced publication service
and one-lane scheduler perform automatic delivery through a fixed-capacity zero-queue call
supervisor. Invalid or unsigned `409` responses enter ordinary retry backoff; only a meaningful
signed conflict enters durable `QUARANTINED` and blocks every successor sequence. Close each
recovery/publication scheduler before its owning service. A profile-gated Spring composition root
can now run one publication lane in `test` or `staging`; it is physically absent whenever
`production` is active, including mixed-profile startup, and remains disabled until
`RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENABLED=true`. Enabling it requires scope, root-set, worker,
endpoint, publisher/trust identity, static Ed25519 response key and whole-second key lifecycle
values under the matching `RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_*` variables in the two profile YAML
files. Staging additionally requires `TRANSPORT_ENABLED=true`, `TRANSPORT_REQUIRED=true`, one
dedicated PKCS#12 client identity, one to sixteen server SPKI pins, and either JVM PKIX roots or an
explicit PKCS#12 trust store. Every enabled staging control-plane transport also requires
`EXPECTED_CLIENT_SUBJECT_DN`, `EXPECTED_CLIENT_URI_SAN`, `CLIENT_ISSUER_SPKI_PINS`,
`EXPECTED_SERVER_URI_SAN`, and `SERVER_ISSUER_SPKI_PINS` under its existing transport prefix.
The staging profile derives `certificate-identity-required=true` from `TRANSPORT_ENABLED`; the test
profile leaves it false for explicit compatibility tests. Keystore passwords are opaque references
resolved only while the immutable TLS context is built; the demo resolver accepts `env:VARIABLE`, while embedders may
provide exactly one vault/workload-identity resolver. PKIX, hostname verification, SPKI pinning and
mTLS must all succeed. The test profile retains the historical system-trust adapter only as an
explicit migration path. Unknown fields, partial transport identity, insecure staging loopback,
unsafe timeout/lease margins, or reuse of the inventory/managed-root client identity fail before
credential resolution, journal DDL, or protocol-adapter assembly. Aggregate Actuator health and the
service snapshot report only system/private trust, pinning, mTLS and certificate-identity-bound
booleans, never paths, references, pin values or certificate selectors. The v1 Java snapshot
constructor remains as an explicit
system-trust compatibility projection. The default lane uses the
isolated test-runtime database journal/outbox; deployments may supply an equivalent durable outbox
or publisher bean. Close the caller-owned publisher after the service. See the
[publisher transport verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-publisher-transport-verification.md).

For test/staging paths that require restart-free certificate replacement, the transport package now
provides `ControlPlaneCertificateIdentityPolicy` and `RotatingControlPlaneHttpTransport` as a kernel.
The policy binds one client key to an exact Subject and single workload URI SAN, validates its chain
with a pinned issuer as the PKIX trust anchor at the declared activation instant, and constrains the
server issuer and workload URI independently. The rotation kernel preloads exactly the next
generation outside the request-state lock, enforces bounded activation and old/new certificate
overlap, and lets a cached client atomically select one complete TLS generation per request. The
static identity policy is wired through all control-plane transport properties, both Spring
profiles, strict configuration schemas, health, recovery-fleet capability v4, and Tool Studio
features. Staging therefore rejects an enabled publisher, inventory, trust-root, notary,
managed-trust, or root-bundle link without complete workload identities. Embedders can now place
`ControlPlaneCertificateRotationController` in front of that kernel. Its strict v1 event is bound
to deployment scope, target, active settings fingerprint, contiguous generation, candidate
settings fingerprint and activation window; an independent public-key-only M-of-N Ed25519 policy
must verify before an opaque material id can be resolved. Exact concurrent replay resolves and
stages once, failures preserve the old generation, and target drift fails closed without exposing
paths, secret references or resolver errors. A database-clock rotation floor linearizes accepted
generations across replicas, journals exact event identities, advances due successors atomically,
rejects restart rollback and same-generation forks, and exposes a strict credential-free snapshot
v1. The test/staging product runtime now binds this floor-first state machine to all 15 stable
transport ids: it verifies a strict out-of-band baseline, restores an active or pending durable
successor from the controlled material catalog, and lets exact replay repair a replica whose local
staging failed after durable acceptance. Set
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED=true` and
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED=true`, then provide the exact deployment scope,
trust domain, accepted policy fingerprints, M-of-N public Ed25519 authorities, timing bounds, target
baseline generation/material ids, and public material-location catalog under the adjacent
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_*` variables. The demo script rejects partial policy,
malformed bounds/JSON, private keys, or resolved passwords before building the service. The
fleet-convergence primitives now also define an exact replica inventory, process-start leases,
strict `STAGED/ACTIVE/FAILED` acknowledgements, all-replica or fenced-quorum stage thresholds, one
database-authoritative active fleet, and an external inventory revision/downgrade floor. Their
aggregate snapshot separates `activationPermitted` from exact all-replica `converged` and never
returns replica ids or TLS material locations. The test/staging runtime now runs the exact
process-start heartbeat, caches every database decision for at most two heartbeat intervals, and
uses the same monitor to fence both the durable floor and live transport. A due successor first
requires a fresh all-replica `STAGED` proof at or after signed database time, then advances the
durable floor, then activates locally; the new generation cannot serve until every configured slot
reports exact `ACTIVE`. Restarted signed generations must republish that proof, database loss or
lease expiry closes admission, and a failed candidate leaves the old generation usable while health
stays degraded. Multi-replica mode requires externally attested inventory;
`FENCED_QUORUM` is rejected at startup until a real traffic fence exists. Enable the sibling
`gateway.testing.control-plane-certificate-rotation-convergence` policy with the adjacent
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_*`, fleet, process, artifact, inventory and
lease variables. Actuator and Tool Studio now report bounded convergence integration,
availability, current proof and serving readiness.

Signed certificate-status admission is available beside rotation in the `test` and `staging`
profiles. Set `RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED=true` only with required signed
rotation and the exact same deployment scope. Configure an independent status trust domain,
accepted policy fingerprints, M-of-N public Ed25519 authority keys, a pinned publication-chain
baseline, bounded refresh policy, and a private-PKIX/SPKI/mTLS identity under
`RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_*`. The source accepts only strict normalized
v2 responses containing an M-of-N signed exact source head and at most one complete-snapshot
successor. Independent database-clock floors reject head/publication gaps, forks, rollback, omitted
targets, stale head renewal, and revoked-to-valid resurrection before updating the local dual-clock
admission cache.
Every live rotating transport checks the exact target, generation, and settings fingerprint before
request dispatch. `REVOKED`, `UNKNOWN`, mismatch, or hard expiry therefore fails before handler I/O.

The status source deliberately uses a static, separately governed client identity rather than one
of the rotating transports it protects; this avoids a recursive bootstrap dependency. A transient
source outage may continue serving only while the last durable signed snapshot remains inside its
wall-clock and monotonic hard-expiry lease. Actuator and Tool Studio distinguish source
availability from cached admission freshness, and expose no URI, certificate, fingerprint,
credential reference, or provider diagnostic. A sibling fixed-cardinality SLO assessment tracks
startup grace, current source outage, exact source-head availability and lag, last-success age,
hard-expiry headroom, and mature refresh failure and admission-denial ratios. Configure its bounded
thresholds with `RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_*`, including
`RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_SOURCE_HEAD_LAG`. Consecutive batch-limit
cycles remain diagnostic only. A fresh cache may still admit
exact requests while the SLO reports `SOURCE_UNAVAILABLE`; availability policy and alert truth are
intentionally separate. Micrometer and Tool Studio export only closed decisions and aggregate
counts. The demo preflight validates exact scope binding, HTTPS, public-only trust material, finite
I/O/scheduler/SLO bounds, private trust, workload identities, and cross-source client-identity
isolation before Maven build. See the
[certificate-status product verification](../docs/resource-gateway-execution-data-control-plane-stage4-certificate-status-product-verification.md).

Authenticated CA event delivery is now available as a separate test/staging product path. Set
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED=true` only after signed rotation
and convergence are both enabled and required, then configure an HTTPS endpoint, pinned baseline
sequence/fingerprint, bounded poll/page policy, and an independent
`RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_*` client identity. The source
requires private PKIX, server SPKI pinning, mTLS, and exact client/server workload identities. Its
TLS identity authorizes page delivery only: every event in a fingerprint-chained page still passes
the independent M-of-N rotation trust policy. A stable serving-slot database cursor performs
`fetch -> stage exact page -> apply every event -> commit`; partial failure leaves the page staged,
and restart accepts only exact replay. The watcher pauses without source I/O while fleet serving
admission is fenced, bounds each cycle to 1..32 pages, and exports only fixed-cardinality health and
Tool Studio booleans. The demo preflight rejects HTTP/loopback staging sources, missing convergence,
weak transport, unreadable key stores, invalid bounds, or resolved credentials before Maven build.
See the [event watcher product verification](../docs/resource-gateway-execution-data-control-plane-stage4-certificate-rotation-event-watcher-product-verification.md).

`productionReady` deliberately remains false. The delivered test/staging path does not yet prove a
certified enterprise CA/OCSP/CRL normalizer, dynamic status-authority trust rotation, CA source HA
and retention/compaction, event/status-source client-certificate hot rotation, external alerting
and burn-rate routing, multi-region source-head equivocation witnessing, HSM custody, production
database certification, or HA/DR/chaos behavior.
See the
[certificate identity and rotation kernel verification](../docs/resource-gateway-execution-data-control-plane-stage4-certificate-identity-and-rotation-kernel-verification.md).

The same single-root journal can run unattended ceremony recovery by also setting
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_ENABLED=true`. Recovery requires publication to remain enabled,
one strict public-only genesis document, accepted ceremony policy fingerprints, a worker identity,
bounded signer/scheduler policy, and exactly one application-provided
`ExternalSequenceAnchorBootstrapRootAuthorityResolver` bean. The resolver maps the exact approved
public cohort to opaque signer ports; private keys, HSM credentials, authority endpoints, and
provider inventories must not be bound through recovery properties. Staging additionally rejects a
genesis without Byzantine fault tolerance. The recovery scheduler and ceremony service close before
the shared journal; the service close gate prevents a shutdown poll from consuming a new durable
attempt. Its aggregate-only Actuator health treats no work, approval wait, competing lease, and
retry delay as healthy workflow states, but fails on attempt exhaustion, latest scheduler/execution
failure, fence loss, or fully lingering signer capacity.

Embedders that already compose multiple root-set services can place exact service/resolver pairs in
an `ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory`. Each `Lane` must carry the service's
exact immutable `ExpectedBinding` and a reviewed `sha256:` runtime-binding fingerprint; mismatched or
duplicate scope/root-set identities are rejected before polling. Publish every add/remove/rebind as a
strictly newer inventory generation, then call
`ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.runCycle()`. A cycle visits at most the
configured lane budget and resumes after the last attempted canonical key, including after a failed
lane, so a poison prefix cannot starve later root sets. Generation rollback, same-generation
descriptor drift, and same-generation service/resolver replacement fail closed. Lane failures are
returned without exception text or provider diagnostics; the underlying per-root journal remains the
only acquisition, retry, attempt-budget, and fence authority. Close the worker before its caller-owned
services; close waits for an admitted cycle but never closes inventory, resolvers, or services.

For continuous embedding, wrap that worker in an
`ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler`. Its single fixed-delay daemon lane and
explicit `runOnce()` path share one admission monitor, so local cycles never overlap. Runtime failures
remain visible but do not stop later polls; a fatal error terminates the periodic future after
publishing a bounded failure snapshot. The scheduler also reports an overdue idle timer or active
cycle against explicit health budgets. Register
`ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth` when aggregate Actuator readiness is
needed: closed, overdue, cycle-wide, and latest lane failures are DOWN, while empty/no-work cycles are
UP. Health reads no inventory and exports no lane identity or diagnostics. Close in strict order:
scheduler, worker, then caller-owned services/resolvers. Reentrant scheduler close from an active
cycle is rejected; concurrent close callers share a completion barrier without holding the cycle
monitor, preventing shutdown lock inversion.

The test/staging Spring composition can now install the durable fleet runtime, but it is disabled by
default and is not a new HTTP endpoint or deployment-wide inventory registry. Contribute exactly one
caller-owned `ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory` bean, then set
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED=true`, a stable
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ID`, and a per-replica
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_WORKER_ID`. Partition count, lease, lane budget, initial delay,
poll interval, cycle budget, and drain timeout use the adjacent
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_*` settings in `application-test.yml` and
`application-staging.yml`. The composition preflights inventory/topology before coordinator tables,
then owns database coordinator, worker, scheduler, and aggregate health. Spring closes scheduler
before worker and never closes the caller-owned inventory, lane services, resolvers, or database.
Fleet mode and `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_ENABLED=true` are mutually exclusive; any active
`production` profile physically removes the fleet composition.

Deployments can replace a self-asserted local lane list with
`ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority`. Its strict signed
attestation binds the complete canonical lane descriptors to deployment scope, artifact,
`fleetId`, fixed partition count, generation, policy, and hard validity window under distinct-authority
Ed25519 M-of-N verification. Only then are signed lane keys resolved from a reviewed local in-memory
catalog, and every resolved descriptor must match exactly. Worker admission rechecks the same signed
generation around each lane and before durable cursor commit; expiry or generation change fails the
cycle closed. The separate inventory health projection remains aggregate-only and honestly advertises
that this static mode has no automatic refresh, signed revocation, or durable generation floor.

Deployment governance can now publish that exact attestation inside a strict witnessed
`ACTIVE`/`REVOKED` predecessor chain. The publication machine contract binds deployment scope,
fleet, policy, sequence, inventory identity, state, validity, and both publication/witness
predecessors. A
`DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor` serializes
verified heads by scope and fleet, survives process reconstruction, and rejects rollback,
same-sequence forks, gaps, broken predecessors, nested-inventory rollback or same-generation drift,
same-inventory reactivation after revocation, corrupt rows, and cross-fleet reuse. Its v2 record
upgrades a v1 row only after an exact cryptographically verified replay of the stored dual head;
it never guesses missing legacy inventory state. The
`DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority` now consumes that
protocol through bounded HTTPS/ETag refresh, independently verifies deployment and witness
M-of-N signatures, revalidates the nested inventory, advances the durable floor before local
publication, and atomically exposes only an exact `ACTIVE` runtime snapshot. A verified `REVOKED`
publication advances the floor without resolving removed lanes and immediately closes recovery
admission. Any refresh, protocol, signature, runtime-binding, or floor failure also fails closed;
the last verified object remains diagnostics-only and cannot be used as stale admission authority.

The trust-root control plane defines how the deployment and witness runtime verification keys
rotate as one signed generation. Independent deployment-root and witness-root quorums
approve the same strict, short-lived material; exact scope/fleet/protocol/policy binding, four-domain
independence, canonical Ed25519 keys, dual thresholds, and sequence/predecessor continuity are
verified before a durable database floor advances. Only then can one immutable dual-key set become
observable. A signed emergency revocation advances the floor but closes runtime admission when a
threshold is no longer satisfiable. A strict HTTPS/ETag authority now refreshes that publication,
performs cooldown-bounded unknown-key refresh, and supplies the exact same immutable key-set
generation to the dynamic inventory verifier. Root generation drift closes admission until the
inventory is reverified, including on a source `304`; disjoint replacement roots therefore reject a
cached inventory instead of extending its trust. The test/staging Spring path now exposes this as a
product mode under `bootstrap-root-recovery-fleet-dynamic-inventory.trust-roots`. Managed mode
forbids every static runtime domain, threshold, and key; owns a separate durable root floor and
aggregate health indicator; and closes inventory before roots. Staging requires dynamic inventory,
managed roots, external Byzantine ordering for the publication and atomic-root streams, and pinned
mutual TLS for every control-plane call. Inventory, inventory trust-root, bootstrap-root publisher,
and each product domain's notary, managed receipt-trust publication, and complete root-bundle source
must use independent client identity configurations. All links retain PKIX and hostname verification,
add one to sixteen exact server SPKI pins, and reject insecure loopback. Test retains explicit
system-trust/static compatibility paths for migration. The demo script checks the same downgrade and
global identity-isolation invariants before build, while Spring remains the authoritative gate.
External-anchor descriptor and health surfaces expose only fixed transport booleans, never endpoints,
paths, secret references, pins, or certificate identities. See the
[recovery fleet trust-root kernel verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-trust-root-kernel-verification.md)
and [dynamic trust-root verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-dynamic-trust-root-verification.md), the
[managed trust-root Spring verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-managed-trust-root-spring-verification.md), plus the strict
[dynamic inventory Spring configuration v2 JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-dynamic-inventory-configuration-v2.schema.json),
[external anchor configuration JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-external-anchor-configuration-v1.schema.json),
[transport and non-equivocation verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-transport-and-non-equivocation-verification.md),
[trust-root publication JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-trust-root-publication-v1.schema.json)
and [dynamic snapshot JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-dynamic-trust-root-snapshot-v1.schema.json).

Embedders that share the test-runtime database can also construct a
`DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator` and pass it, one stable
`fleetId`, and one fixed partition count to every worker replica. The coordinator uses database-clock
partition leases, active-command retry deduplication, generation fencing, exact renewal/completion,
failure abandonment, and durable per-partition cursors; the worker heartbeats independently of slow
lane execution. A busy coordinator is reported separately from an empty completed inventory. The
lane journal remains the only execution/write fence.

The test/staging Spring path now supports two explicit inventory modes. Embedders may continue to
supply one static or custom authority bean. Alternatively, enabling
`bootstrap-root-recovery-fleet-dynamic-inventory` constructs the witnessed HTTPS authority from
public-only strict properties, requires exactly one caller-owned reviewed lane resolver, and uses a
database publication/witness floor unless one custom durable floor is supplied. Stateless topology,
trust-domain, public-key, binding, URI, and duration validation completes before floor DDL or remote
I/O. Staging additionally requires the certified dynamic mode and rejects static fallback; test keeps
it optional. Both configurations are physically absent when `production` is active. The existing
worker fences every lane and cursor commit against current authority generation and availability.

The Spring path does not generate trust roots or discover lane runtimes. The existing
`GET /api/integration/capabilities` endpoint now publishes an identity-free, versioned recovery-fleet
state machine and conservative boolean projections. Capability v1 remains frozen; v2 adds managed
root availability/status/sequence, atomic dual-root and floor strength, plus combined
non-equivocation claims; v3 adds separate aggregate transport-authentication facts for inventory and
managed-root sources. Its probe reads only startup-frozen bean
candidates and fresh process-local snapshots; it does not perform bootstrap I/O. Online partition
rebalance, external fleet-wide alert/convergence wiring, production-profile wiring, pinned mTLS for
managed notary-trust and bootstrap-root consumer endpoints, response-key hot rotation,
publisher/notary HA and gossip
certification, target-database/DR/chaos certification,
provider-confirmed cancellation, and HSM custody remain deployment gates. The genesis, complete
bundle, and publication HTTP Schemas, failure matrix, runtime wiring, and remaining ceremony limits
are documented in the
[bootstrap-root ceremony verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-ceremony-kernel-verification.md)
and [recovery fleet kernel verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-kernel-verification.md).
The signed lane-inventory protocol, runtime reverse binding, hard-expiry fence, health projection, and
remaining dynamic-control-plane gates are in the
[recovery fleet signed inventory verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-signed-inventory-verification.md).
The import/export wire contract is the strict
[fleet inventory JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-v1.schema.json).
The witnessed publication wire contract is the strict
[fleet inventory publication JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-publication-v1.schema.json),
with kernel verification in the
[publication floor verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-publication-floor-kernel-verification.md).
The dynamic consumer, revocation, refresh, runtime-fence, health, and failure semantics are in the
[dynamic fleet inventory verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-dynamic-inventory-verification.md).
The profile/configuration/lifecycle contract and H2 context-rebuild proof are in the
[recovery fleet runtime composition verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-runtime-composition-verification.md).
The strict dynamic properties, startup ordering, staging downgrade fence, ownership contract, and
real signed-HTTP Spring proof are in the
[dynamic fleet inventory Spring composition verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-dynamic-inventory-spring-composition-verification.md).
The capability state machine, strict Schema, no-I/O projection, compatibility, and integration proof
are in the
[recovery fleet capability verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-capability-verification.md),
with its current machine contract in the
[recovery fleet capability v3 JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v3.schema.json), the frozen
[v2 JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v2.schema.json),
and the frozen
[v1 JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v1.schema.json).

Enabled test/staging fleets also install a versioned process-local SLO assessment and 41
fixed-cardinality Micrometer series. The monitor reuses the authority-bracketed immutable capability
projection and performs no inventory, lane, database, network, or payload I/O. It evaluates current
runtime failure independently from startup grace, successful-poll freshness, and mature poll/cycle/
lane failure ratios. An unattested local inventory, snapshot tear, or observation failure is never
healthy; unavailable value gauges use `-1`, not a false zero. Staging cannot disable this monitor.
Configure the strict policy with `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_SLO_*`; startup grace must
cover initial delay plus one poll interval, and maximum success age must cover two poll intervals.
The assessment carries the exact policy used, while metric tags are limited to closed status,
violation, outcome, and scope vocabularies. Registry/exporter configuration, alert routing,
long-lived SLI storage, and fleet-wide convergence remain deployment responsibilities. See the
[recovery fleet SLO verification](../docs/resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-slo-verification.md)
and strict
[SLO assessment JSON Schema](../docs/schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-slo-assessment-v1.schema.json).

Scope, cohort, recovery-fleet inventory/root sources, static-key exclusion, managed-root freshness,
external-anchor quorum/timing, authenticated transport, global client-identity isolation, and lease
settings are checked by
`scripts/visual-canvas-demo.sh` before staging startup. A deployment may
instead contribute one custom
`TestSuiteStabilityJobAuthorizer` with a ready key-free descriptor. There is no allow-all fallback:
zero providers, multiple providers, an undeclared provider, missing trust, unsafe URI, or invalid
time policy fails startup. Queue capacity, fairness, retry, lease, deadline, and retention use the
`RG_TEST_STABILITY_JOB_*` variables documented in `application-test.yml` and
`application-staging.yml`. Worker environment/lane and heartbeat/lease contradictions also fail
startup. Startup readiness is not cached as perpetual authority: every fresh submission and every
capability probe reevaluates the single provider's local, key-free descriptor. An expired/revoked
local trust key, provider ambiguity, or descriptor failure immediately closes fresh admission and
reports submission unavailable without probing the remote PDP. Retained exact idempotent replay is
resolved before this mutable readiness check, so key rotation cannot make an accepted request
appear absent or cause it to execute twice. The capability probe separates
`asyncSuiteStabilityJobProtocol` from
`asyncSuiteStabilityJobSubmission`, and additionally reports
`suiteStabilityCurrentAuthorityRevalidation` plus
`signedChallengeBoundSuiteStabilityAuthority`. Dynamic deployments additionally expose
`dynamicSuiteStabilityAuthorityTrust` and `suiteStabilityAuthorityTrustRefreshSlo`, so clients do
not infer executability merely because the routes or Schema exist. Cohort deployments also expose
`exactSuiteStabilityAuthorityTrustCohort` and the current-state
`convergedSuiteStabilityAuthorityTrustCohort`; descriptors and health contain aggregate counts and
status only. `externallyAttestedSuiteStabilityServingInventory` separately proves that the expected
set comes from a currently verified external attestation;
`dynamicSuiteStabilityServingInventory` and
`witnessedSuiteStabilityServingInventoryPublications` describe the stronger refresh protocol;
`durableSuiteStabilityServingInventoryPublicationFloor` confirms that its ordering floor survives a
complete fleet restart. Managed deployments additionally report
`restartFreeSuiteStabilityServingInventoryKeyRotation` and
`atomicDualQuorumSuiteStabilityServingInventoryTrustRoots`; convergence requires every member to
declare the same atomic dual-root protocol. The stronger
`externallyAnchoredSuiteStabilityServingInventoryOrdering` and
`byzantineQuorumSuiteStabilityServingInventoryNonEquivocation` flags are true only when both
publication and managed-root ordering streams have the corresponding external guarantee. None
expose instance ids, cohort ids,
inventory ids, endpoints, key ids, signatures, or trust fingerprints. The private authority request
excludes credentials, correlation id,
execution metadata, fixture/context/payload and node output. HTTP denial, redirect, timeout,
malformed/oversized JSON, stale decision, echo mismatch, unknown/revoked key, or invalid signature
is `UNAVAILABLE`; only a verified signed revocation is definitive. Capacity responses use
`Retry-After`, configured with
`RG_TEST_STABILITY_JOB_API_RETRY_AFTER_SECONDS` (default `5`). See the focused
[current-authority verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-current-authority-verification.md),
[dynamic authority trust verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-dynamic-authority-trust-verification.md),
[authority cohort verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-authority-cohort-verification.md),
[signed serving-inventory verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-serving-inventory-verification.md),
[managed trust-root rotation verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-trust-root-rotation-verification.md),
[external non-equivocation verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-external-non-equivocation-verification.md),
[machine-readable serving-inventory Schema](../docs/schemas/resource-gateway-testing/suite-stability-serving-inventory-v1.schema.json),
[machine-readable managed trust-root publication Schema](../docs/schemas/resource-gateway-testing/suite-stability-serving-inventory-trust-root-publication-v1.schema.json),
[machine-readable external checkpoint Schema](../docs/schemas/resource-gateway-testing/suite-stability-external-sequence-checkpoint-v1.schema.json),
[machine-readable compact-observation external archive Schema](../docs/schemas/resource-gateway-testing/suite-stability-observation-external-archive-v1.schema.json),
[strict HTTPS WORM adapter design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-http-worm-adapter-design.md),
[signed external inventory protocol design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-external-inventory-protocol-design.md),
the [receipt-aware lifecycle v2 design](../docs/resource-gateway-execution-data-control-plane-stage5-observation-lifecycle-v2-external-proof-design.md),
and [machine-readable authority Schema](../docs/schemas/resource-gateway-testing/suite-stability-authority-v1.schema.json).
Actuator exposes separate stability-queue, dynamic-authority-trust, and managed inventory-root
health contributors. The
`RG_TEST_STABILITY_JOB_SLO_*` settings bound per-environment queue depth, oldest wait, observation
interval, and expired live leases; the depth SLO cannot exceed hard queue capacity. Micrometer
publishes only closed environment/status/outcome dimensions. Business test failures remain visible
status totals but do not make the deployment unhealthy. Database observation failure is `DOWN`, and
metric-registry failure cannot stop worker execution. See
[queue observability verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-queue-observability-verification.md).

Expired terminal job detail can now be replaced transactionally by a payload-free request tombstone.
Exact replay then returns `REPLAY_WINDOW_EXPIRED`; another intent using the same scoped request key
remains an idempotency conflict. Tombstones keep only a tenant/environment-bound, independently
domain-separated keyed HMAC index and an integrity fingerprint, never the plaintext request id or
job payload. Configure `RG_TEST_STABILITY_JOB_REQUEST_KEY_ACTIVE_ID` and
`RG_TEST_STABILITY_JOB_REQUEST_KEY_RING` with deployment secrets; append the new generation fleet-
wide before changing active, and keep every old verification key until its final tombstone expires.
Startup fails when a live tombstone references a missing generation. A profile-gated retention
service now acquires one database-clock lease across replicas, processes independent bounded detail
and tombstone pages atomically, advances integrity-protected cumulative counters, and publishes
aggregate-only metrics plus freshness/backlog readiness. Defaults are a 120-second lease, 100 rows
per page, a one-hour interval, and 365-day tombstones; terminal job detail still follows the queue's
30-day default. A page that outlives its lease rolls back in full. Configure and operate it with the
`RG_TEST_STABILITY_JOB_RETENTION_*` variables in the profile YAML; invalid page, lease, interval, or
freshness combinations fail startup even while the worker is disabled. See
[job tombstone verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-job-tombstone-verification.md)
and the [retention service verification](../docs/resource-gateway-execution-data-control-plane-stage5-suite-stability-retention-service-verification.md).

### Create a durable graph test

Freeze the target through graph discovery and publish an immutable fixture revision first. Then create
one idempotent graph-contract test from those exact fingerprints:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestExecutionCreateRequest.v1",
    "clientRequestId": "create-approval-flow-20260717-01",
    "target": {
      "kind": "GRAPH",
      "id": "approvalFlow",
      "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    "executionPurpose": "GRAPH_CONTRACT_TEST",
    "context": {"requestId": "REQ-42", "amount": 25000},
    "fixtureBundleRef": {
      "fixtureBundleId": "approval-fixture",
      "revision": 3,
      "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
  }'
```

The graph creation v1 contract accepts only exact graph targets and stored fixtures and succeeds only at one unambiguous
persisted signal wait. The response wraps a payload-free suspended execution view containing the
server-minted run id and recovery fence. A committed success or deterministic unsupported-boundary
rejection is replayed for the same authenticated `clientRequestId`; a live concurrent preparation
returns `409` with its run id and lease deadline. This graph endpoint does not accept operator
targets, inline/latest dependencies, timers/tasks/streams, terminal fresh runs, or multiple live
suspensions.

The server owns the preparation identity and lease through
`gateway.testing.durable.creation.instance-id` and
`gateway.testing.durable.creation.lease-duration-seconds` (default `120`, valid `3..3600`). A
process-local coordinator renews the exact database-fenced `PENDING` reservation while the staged
fresh run is preparing. Set `gateway.testing.durable.creation.heartbeat-interval-seconds` to `0`
(default) to derive one third of the lease, or to a whole-second value from `1` through
`floor(lease / 3)`. Commit and deterministic rejection freeze renewal and use the latest successor
fingerprint; heartbeat failure or service shutdown returns
`409 RG.TEST.DURABLE_CREATE_LEASE_LOST` and discards staged state. This is lease liveness, not forced
in-process cancellation: an uncooperative operator still requires a killable worker boundary.
Complete wire semantics and failure codes are in the
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md#42d-create-one-durable-graph-execution).

### Create a durable operator test

Discover the exact operator binding and publish a stored fixture revision first. The operator
contract is versioned separately from graph creation:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/operators/creditScore \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableOperatorTestExecutionCreateRequest.v1",
    "clientRequestId": "create-credit-score-20260717-01",
    "target": {
      "kind": "OPERATOR",
      "id": "creditScore",
      "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    "executionPurpose": "OPERATOR_UNIT_TEST",
    "input": {"customerId": "C-42", "annualIncome": 180000},
    "fixtureBundleRef": {
      "fixtureBundleId": "credit-score-fixture",
      "revision": 4,
      "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
  }'
```

Resource Gateway validates and converts the formal input, then persists a canonical two-node graph:
the server-owned `durable-operator-start` gate followed by the exact `subject` binding. Revision zero
commits while the gate is suspended, before the business operator is invoked. Claim the run and send
a terminal-recovery signal to that gate to execute the subject exactly once from the frozen input,
fixture cursor, provider state, and authorization closure. Signal data is ignored by the gate and
cannot replace business input. Responses, commands, and audits remain payload-free.

Graph and operator creation share the same idempotency namespace, four-dimensional admission,
database-time preparation lease, staged four-store aggregate, atomic checkpoint/audit commit, query,
owner claim, heartbeat, and terminal recovery. The internal start gate also occupies a conservative
operator admission slot. Worker polling, multi-boundary orchestration, hard cancellation, and
complete pre-checkpoint trace evidence remain outside this increment. Full semantics are in the
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md#42e-create-one-durable-operator-execution).

### Inspect a durable test execution

Read the latest integrity-verified control fence before deciding whether to claim or diagnose a
durable run:

```bash
curl -sS \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001 \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

The `bloge.durableTestExecutionView.v1` response contains the current status, exact owner/epoch/
revision fence, lease deadline, graph/operator and fixture references, plan/provider/fixture-ledger
fingerprints, payload-free engine boundary, and aggregate checkpoint fingerprint. It never contains
business context, fixture values, replay payloads, provider cursors, credentials, or BLOGE checkpoint
bodies. `recoverable` is true only for a current v2 resumable state with restorable providers;
historical v1 rows remain visible as operational facts but return no target and set
`migrationRequired=true`. The view is not a dispatch, authorization token, or proof that the lease
is still live after the response was produced.

Missing and cross-organization/project runs both return `404`; malformed run ids return `400`; a
store outage or any sealed-JSON/index/fingerprint inconsistency returns a payload-free `503`.

### Pull one durable worker assignment

A recovery worker that does not already know a `runId` can ask for at most one assignment in its
verified tenant, organization, project, and environment scope:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/worker-acquisitions \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestWorkerAcquisitionRequest.v1",
    "clientRequestId": "worker-poll-20260717-001"
  }'
```

The caller cannot send a run selector, queue scope, owner, lease, priority, or candidate limit. The
server scans expired candidates from a persisted cyclic keyset position within the authenticated
scope, up to
`RG_TEST_DURABLE_WORKER_CANDIDATE_LIMIT` (default `32`, valid `1..1000`), re-authorizes each exact
dependency closure, and atomically commits either `ACQUIRED` or `NO_WORK`. `ACQUIRED` contains the
same payload-free owner/epoch/revision/checkpoint fence used by heartbeat and terminal recovery;
the authorization-bound dispatch remains internal. `NO_WORK` means that the bounded scan produced
no claimable assignment, not that a global queue is empty.

Both outcomes are immutable under `clientRequestId`, including `NO_WORK`. Retry a lost response with
the same key; use a new key for a later poll. Lease CAS, hidden dispatch, command result, and semantic
audit share one transaction with progress through the last examined candidate. The database cursor
wraps from the ordered tail to the head, so a full ineligible prefix cannot permanently hide later
work; cursor state and scope projections are fingerprint-verified, and stale concurrent progress
cannot move it backward.

Legacy checkpoints and exact authorization `403`/`409` outcomes create a database-timed negative
scheduling cache for that checkpoint fingerprint. An active record skips the authority call but not
scan progress; a due repeat doubles from `RG_TEST_DURABLE_WORKER_INITIAL_BACKOFF_SECONDS` (default
`5`) to `RG_TEST_DURABLE_WORKER_MAXIMUM_BACKOFF_SECONDS` (default `300`). Infrastructure failures do
not create a deferral or advance the cursor. After
`RG_TEST_DURABLE_WORKER_QUARANTINE_THRESHOLD` consecutive same-reason failures (default `32`), the
winning cursor transaction converts the exact checkpoint from temporary backoff to permanent worker
quarantine. Quarantined candidates still advance the cyclic scan but are never re-authorized or
claimed by worker pull merely because time passed. Checkpoint replacement or an explicit successful
state transition clears the old fingerprint's scheduling state.

This is non-blocking pull control with automatic exact-checkpoint isolation, not runtime-state
delivery or a complete scheduler: it does not transfer BLOGE runtime state to the caller, reserve an
execution-capacity permit while idle, or provide tenant weighting/priority/aging. A separate
`TEST_RUNTIME_MAINTENANCE` protocol now provides scoped, payload-free quarantine list/history,
server-fenced claims, idempotent `RELEASE`, and database-authoritative two-person `DISCARD`. A
separate approver group creates a token-free, short-lived approval for the exact live maker claim;
the maker then proves its secret fence and atomically consumes that approval. New direct legacy
`DISCARD` commands are rejected. Exact claim-response replay is encrypted with a rotation-aware
AES-256-GCM key ring, while the live control keeps only a domain-separated HMAC-SHA-256 verifier;
`staging` requires that claim-token root-key ring to be injected explicitly. A database-leased,
bounded retention loop later replaces detailed claim/resolution/approval/discard replay rows with
request-key tombstones that contain neither the raw request ID nor claim token. New tombstones use an
independent, domain-separated HMAC-SHA-256 request index with bounded online key rotation; live rows
whose key is unavailable block readiness, old-key/legacy rows lazily re-key on exact access, and
expired rows remain purgeable. A three-stage legacy/dual/keyed-only write-mode protocol now keeps
mixed N/N-1 deployment on old-readable rows until the deployment authority proves every serving
instance is N; each replica publishes its exact mode and rejects incompatible live generations at
readiness. An authenticated rollout endpoint also issues a short-lived Ed25519 proof binding an
external challenge to this process start, deployment-supplied instance and artifact identities,
exact mode, protocol version, and DB-clock live-generation inventory. This is a per-process fact,
not service discovery: the deployment platform must still supply and exhaustively verify the exact
serving inventory. The loop independently purges token-free history and permits request-ID
reuse only after the tombstone window. Exact semantics are documented in
[Stage 4 worker candidate backoff verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-candidate-backoff-verification.md),
the [worker quarantine maintenance verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-maintenance-verification.md),
the [two-person discard verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-two-person-discard-verification.md),
the [claim-token protection verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-claim-token-protection-verification.md),
the [bounded retention verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-retention-verification.md),
the [request-index protection verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-protection-verification.md),
the [request-index rolling-upgrade verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-upgrade-verification.md),
and the [signed replica-proof verification](../docs/resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-replica-proof-verification.md).

### Claim an expired durable test lease

The public owner-claim command is available only under `test` or `staging`. Obtain the current
payload-free checkpoint fence from the durable-run query above, then
submit that exact observation with a caller-stable idempotency key:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/owner-claims \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestOwnerClaimRequest.v1",
    "clientRequestId": "recover-run-20260716-001-attempt-1",
    "expectedFence": {
      "ownerId": "expired-worker-a",
      "leaseEpoch": 3,
      "revision": 7
    },
    "expectedCheckpointFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }'
```

The server, never the caller, chooses the new owner id and lease duration. Configure them with
`RG_TEST_DURABLE_OWNER_INSTANCE_ID` and `RG_TEST_DURABLE_OWNER_LEASE_SECONDS` (default `120`, valid
range `1..3600`). A successful response is `bloge.durableTestOwnerClaimResponse.v1` and contains only
the resulting fence, expiry, checkpoint fingerprint, target locator, and `idempotentReplay` flag.
Retries must reuse the same `clientRequestId` and byte-equivalent intent; reusing the key for another
intent fails closed.

Before mutation, Resource Gateway revalidates the authenticated scope, exact graph/operator target,
fixture revision, replay closure, current identity authority, side-effect policy, execution-service
state, and recompiled effective plan. The authorization audit and fresh lease claim commit in the
same local test-runtime transaction. `404` hides cross-project existence, `409` reports a stale fence,
active lease, migration requirement, or unavailable exact dependency closure, and `503` reports an
authority/store/audit outage. This endpoint moves the checkpoint to `RESUMING`; the claim itself does
not execute BLOGE. Its exact returned fence may be renewed or supplied to the terminal-only recovery
endpoint below.

### Renew a claimed durable recovery lease

The owner-claim response supplies the exact `ownerId`, `leaseEpoch`, `revision`, and
`checkpointFingerprint` required by the heartbeat. Keep those values as one indivisible fence and
send a new caller-stable idempotency key for each renewal:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/heartbeats \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestRecoveryHeartbeatRequest.v1",
    "clientRequestId": "heartbeat-run-20260716-001-1",
    "expectedFence": {
      "ownerId": "server-issued-owner",
      "leaseEpoch": 4,
      "revision": 8
    },
    "expectedCheckpointFingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }'
```

The service resolves the hidden, committed recovery dispatch from that exact fence; callers never
send a dispatch, authorization receipt, owner choice, or lease duration. It requires the same
authenticated tenant, organization, project, environment, region, actor, delegation, purpose,
clearance, and groups that obtained the claim. Correlation id may change across a lost-response
retry. Configure the server-owned renewal with `RG_TEST_DURABLE_HEARTBEAT_LEASE_SECONDS` (default
`120`, valid range `3..3600` while synchronous terminal recovery is enabled). The terminal-recovery
worker derives a one-third heartbeat interval by default. Override it with
`RG_TEST_DURABLE_RECOVERY_HEARTBEAT_INTERVAL_SECONDS`; it must be at least one second and no greater
than one third of the lease.

A successful `bloge.durableTestRecoveryHeartbeatResponse.v1` returns the successor revision,
database-authority expiry, checkpoint fingerprint, and `idempotentReplay`; use that successor fence
for the next heartbeat. Retry an ambiguous response with the same key and identical intent. Reusing
an old fence under a new key, changing authority, using an expired lease, or reusing a key for a
different intent fails closed. The authorization audit and first heartbeat commit atomically. This
endpoint renews ownership only: it does not poll work, run BLOGE, cancel execution, or produce
terminal evidence.

### Advance one durable recovery step

For a graph that can suspend more than once, use the latest exact claim/heartbeat fence with the
one-step protocol:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/recovery-steps \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestRecoveryStepRequest.v1",
    "clientRequestId": "step-run-20260716-001-1",
    "expectedFence": {
      "ownerId": "server-issued-owner",
      "leaseEpoch": 4,
      "revision": 9
    },
    "expectedCheckpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "signal": {"nodeId": "approval-wait", "data": {"approved": true}}
  }'
```

The response is payload-free and has `outcome=SUSPENDED` with `terminal: null`, or one of five
terminal outcomes with a promotion-blocking receipt projection. A suspended commit releases the
consumed lease at database time. Acquire the new checkpoint again before sending the next signal;
the old dispatch is intentionally unusable. Reuse the same key and identical intent only to recover
an ambiguous response. Caller-owned outcome, engine/provider/fixture state, lease, evidence, or
dispatch fields are rejected.

### Advance a bounded recovery sequence

When the complete ordered signal fixture is already known, use the sequence protocol to avoid
manually claim/step chaining every suspension:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/recovery-sequences \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestRecoverySequenceRequest.v1",
    "clientRequestId": "sequence-run-20260716-001-1",
    "expectedFence": {
      "ownerId": "server-issued-owner",
      "leaseEpoch": 4,
      "revision": 9
    },
    "expectedCheckpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "signals": [
      {"nodeId": "risk-approval", "data": {"approved": true}},
      {"nodeId": "finance-approval", "data": {"approved": true}}
    ]
  }'
```

The server reserves a fingerprint of the complete sequence before the first signal runs, then
derives stable child keys and performs a fresh authorized owner claim after every committed
suspension. A retry of the unchanged outer request replays the committed prefix and continues at
the first unfinished step. The response reports ordered payload-free `steps`, consumed/provided
counts, and either `stopReason=TERMINAL` or `SIGNALS_EXHAUSTED`. Limits are 16 signals, 256 KiB per
signal, and 1 MiB for the sequence.

Detailed outer and server-derived child commands remain exactly replayable for 30 days from the
first reservation by default. A request accepted before that absolute deadline advances an
integrity-protected database activity fence for one more command window, so retention cannot race
its in-flight child writes; the fence does not extend the replay deadline. A database-leased,
bounded retention page then verifies every row, atomically replaces the outer request with a scoped
keyed-HMAC tombstone, and erases its derived steps, intermediate claims, and automatic heartbeats.
After the absolute deadline and during the default 365-day tombstone window, an exact retry returns
`409 RG.TEST.DURABLE_RECOVERY_SEQUENCE_REPLAY_WINDOW_EXPIRED`; changed intent under the same key
remains an idempotency conflict. Only tombstone expiry permits key reuse. Staging deployments must
set `RG_TEST_DURABLE_RECOVERY_SEQUENCE_RETENTION_INSTANCE_ID`,
`RG_TEST_DURABLE_RECOVERY_SEQUENCE_REQUEST_KEY_ACTIVE_ID`, and
`RG_TEST_DURABLE_RECOVERY_SEQUENCE_REQUEST_KEY_RING`. Roll out a new verification key to every
replica before making it active, and keep an old key until all tombstones written with it have
expired; a restarted replica fails closed when a referenced generation is missing.

The `test` and `staging` profiles also install a fail-closed Actuator health component for this
lifecycle. Every 30 seconds by default it reads one repeatable-read, database-clock snapshot and
checks last successful retention age, ready-to-delete sequence count/oldest eligible age, and
expired tombstone count/oldest expiry age. Store failure is `DOWN`, policy violation is
`OUT_OF_SERVICE`, and first-start grace is `UNKNOWN`; health details contain only stable violation
codes, aggregate counts, and ages. Capability discovery exposes
`durableRecoverySequenceRetentionSloHealth`. Tune the `RG_TEST_DURABLE_RECOVERY_SEQUENCE_SLO_*`
limits before using the health aggregate as a deployment readiness gate.

This synchronous helper is not a background queue, remote runtime-state dispatcher, cross-process
supervisor, hard-cancellation mechanism, backup-erasure protocol, or external evidence archive.

### Complete one terminal recovery

Use the latest indivisible owner/epoch/revision/checkpoint fence from owner claim or heartbeat to
apply one signal to the exact persisted suspension. The caller supplies intent only; it cannot
provide an outcome, engine state, fixture cursor, provider state, receipt, or evidence label:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/durable-executions/run-20260716-001/terminal-recoveries \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "bloge.durableTestTerminalRecoveryRequest.v1",
    "clientRequestId": "terminal-run-20260716-001-1",
    "expectedFence": {
      "ownerId": "server-issued-owner",
      "leaseEpoch": 4,
      "revision": 9
    },
    "expectedCheckpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "signal": {"nodeId": "approval-wait", "data": {"approved": true}}
  }'
```

The signal is canonicalized under a `256 KiB` limit and exists only in the isolated in-memory
invocation. It is not copied into audit, response, checkpoint, or terminal receipt. Before execution,
the service re-resolves the hidden issued dispatch, requires the original authenticated principal,
loads its exact live `RESUMING` checkpoint, and reproduces the complete authorization receipt. The
process-local recovery coordinator synchronously rotates that dispatch before BLOGE starts, keeps
rotating exact successors while the staged recovery runs, then freezes renewal and supplies the
latest successor to the terminal CAS. Any heartbeat conflict, store failure, or coordinator shutdown
closes the stage without committing and returns payload-free
`RG.TEST.DURABLE_RECOVERY_LEASE_LOST`. The runtime restores the same fixture cursor and deterministic
provider state and signals one cold recovery.

Success requires BLOGE to reach `COMPLETED`, `FAILED`, `FAILED_RECOVERY`, `CANCELLED`, or
`TERMINATED`. The staged BLOGE mutation, terminal checkpoint, payload-free receipt, idempotency
record, and semantic audit commit in one local transaction. If the graph suspends again, the stage is
discarded and the endpoint returns `409`; it does not silently orchestrate another signal. Retry a
lost response with the same key and identical signal to receive the immutable result without running
the engine again. The v1 response is always `EVIDENCE_INCOMPLETE` with explicit
`PRE_CHECKPOINT_TRACE_UNAVAILABLE` and `RECOVERY_SIGNAL_PAYLOAD_OMITTED` gaps, so it blocks promotion
and is not a signed correctness-evidence bundle.

Batch-migrate existing `.bloge` files after the service is running:

```bash
./scripts/bloge-dsl-batch-import.sh report \
  --base-url http://localhost:8080 \
  --operator-library risk-policy \
  --dsl-dir resource-gateway-examples/src/main/resources/bloge/gateway \
  --out target/dsl-batch-report.json
```

Use `commit --commit-policy renderable|fully-projected|rewrite-allowed` with the
same inputs to save eligible projections as governed visual drafts. The script
does not write source `.bloge` files; source replacement still belongs behind
rewrite gate evidence and a reviewed VCS/source-writer flow.

## Notice The Product Loop

1. **Import or use a resource contract**: seeded resources such as
   `user-service.getProfile`, `order-service.listOrders`, and
   `credit-provider.primary` all run through `HttpResourceOperator`.
2. **Compose the business flow**: connect descriptors, transforms, decisions,
   subgraphs, and design-only operators under JSON Schema constraints.
3. **Prove and promote it**: validate, simulate with mock or real evidence,
   run real-DAG graph contract suites and operator schema-contract suites, publish reusable graph products, and
   protect them with golden cases.

The showcase covers dashboard aggregation, product enrichment, enriched orders,
credit fallback, loan policy, and SSE search. The full endpoint catalog lives in
[REFERENCE.md](REFERENCE.md).

## Architecture At A Glance

![BLOGE visual canvas architecture](../docs/assets/bloge-visual-canvas-architecture.svg)

| Layer | Responsibility |
| --- | --- |
| Serving | Spring MVC gateway APIs, visual APIs, admin APIs, browser entry points |
| Orchestration | BLOGE DSL graphs schedule dependencies, fan-out, joins, transforms, decisions, and streams |
| Provider | `HttpResourceOperator` resolves descriptors, maps parameters, calls upstreams, validates response protocol, and extracts payload |
| Visual product surface | Catalog import/export, schema checks, draft validation, simulation, publication, golden cases, and run history |
| Controls | Durable run-control state, owner lease/epoch fencing, pre-run evidence reservation, side-effect claim/reconciliation, crash recovery sweeper/outbox, response cache, tenant rate limiting, circuit breaking, and tenant context |

Managed runs reserve `100 ms` by default for terminal-state and evidence finalization. BLOGE propagates the resulting
work budget through `OperatorContext`, scheduler admission, resilience timeout/retry, common HTTP calls, Resource Gateway
resources, and remote-worker envelopes. Override the reserve with
`resource-gateway.run-control.finalization-reserve-ms`; size it from measured evidence-finalization latency rather than
treating it as an operator timeout.

External-write operators declare `bloge.sideEffectProtocol.v1` and call `OperatorContext.beginSideEffect(...)` before
crossing the provider boundary. Missing contracts are DESIGN-only; a managed WRITE that returns without a journal attempt
is rejected. Descriptor-backed POST/PUT/PATCH/DELETE calls additionally require
`resourceGateway.externalWriteContract.v1`, an idempotency key, an evidence-safe lookup reference, and a provider receipt.
The Author palette shows `managed write` or `write protocol required` before a node is used. Provider-specific status
adapters implement `SideEffectReconciler`; Resource Gateway keeps original evidence immutable and appends a separately
signed reconciliation record. See the [product guide](../docs/bloge-visual-canvas-product-and-system-guide.md),
[conformance chain](../docs/assets/resource-gateway-side-effect-conformance-chain.svg), and
[reconciliation lifecycle](../docs/assets/resource-gateway-side-effect-reconciliation-lifecycle.svg).

Enterprise integration credentials can be verified from a live JWKS and versioned revocation feed. Resource Gateway
publishes key and revocation changes as one immutable snapshot, throttles unknown-`kid` refreshes, exposes refresh health
and propagation SLO through `/api/integration/capabilities`, and records organization/delegation facts without storing raw
tokens or group names. Authority outages return retryable 503; deterministically invalid credentials return 401. See the
[dynamic trust lifecycle](../docs/assets/resource-gateway-dynamic-jwks-trust-lifecycle.svg) and
[identity setup guide](../docs/bloge-visual-canvas-product-and-system-guide.md#31-调用-integration-api-前先建立受信身份).

Production evidence signatures can use a private-network KMS/HSM sidecar instead of the demo H2 key store. Resource
Gateway sends only the canonical evidence fingerprint and expected key version, rejects private material in provider
responses, and verifies the returned Ed25519 signature locally before persistence. Key discovery retains `VERIFY_ONLY`
history while distinguishing `DISABLED` and `REVOKED`; malformed trust material fails immediately, and transport outages
can use cached public keys only until the authority-declared expiry. Enable it with
`RG_EVIDENCE_SIGNING_MANAGED_ENABLED=true` and `RG_EVIDENCE_SIGNING_MANAGED_BASE_URI=https://...`. See the
[custody lifecycle](../docs/assets/resource-gateway-managed-evidence-signing-custody.svg) and
[managed signing setup](../docs/bloge-visual-canvas-product-and-system-guide.md#32-为运行证据启用-kmshsm-托管签名).

Offline release gates can now fetch one signed atomic policy from `GET /api/integration/evidence-keys`
instead of racing individual key reads. Managed key discovery v2 carries `notBefore/notAfter`,
`COMPLETE/CURRENT_STATE_ONLY`, and ordered activation/retirement/disable/revocation/compromise facts.
The independent test-kit requires an externally supplied snapshot fingerprint, validates the key-set
attestation and lifecycle invariants, and applies prospective or retroactive revocation at the evidence
signing time. The fingerprint returned in that same response is not a trust root. See the
[key lifecycle verification record](../docs/resource-gateway-execution-data-control-plane-stage3-key-lifecycle-verification.md),
[public key-set schema](../docs/schemas/tool-studio-resource-gateway/evidence-verification-key-set-v1.schema.json),
and [managed v2 schema](../docs/schemas/tool-studio-resource-gateway/managed-evidence-signing-keys-v2.schema.json).

Tool Studio draft export now reads one relevant-only dependency snapshot containing operator library revision,
runtime binding and activation state, contract-suite revision, schema fingerprints, and a normalized readiness result.
The service checks the draft and dependency fingerprint again after assembly. A concurrent relevant change returns
`409 RG.INTEGRATION.DRAFT_SNAPSHOT_CHANGED` instead of publishing a half-old, half-new bundle; unchanged revisions remain
byte-stable for idempotent consumers. Capability discovery exposes `graphDraftConsistentDependencySnapshot` and
`graphDraftStructuredDependencyRefs`. Scope-mismatched operators export only the draft-owned historical snapshot, never
the current restricted schema, owner, binding, activation, or suite. See the
[snapshot protocol](../docs/assets/resource-gateway-graph-draft-consistent-dependency-snapshot.svg),
[profile v2 schema](../docs/schemas/tool-studio-resource-gateway/graph-draft-dependency-profile-v2.schema.json), and
[product usage guide](../docs/bloge-visual-canvas-product-and-system-guide.md#33-导出可系统化导入的-graphdraft-一致依赖快照).

Replay payloads now have a lifecycle independent from immutable run evidence. New run records contain only shape facts,
a versioned policy descriptor, payload reference, and digest; sanitized values live in an expirable vault. Reads require
tenant/environment scope, purpose, classification clearance, and every policy-required group. `RESTRICTED` defaults to
no retention, expired or purged payloads return 410, legal hold freezes deletion, and each hold/release/purge transition
extends a signed hash chain. See the [lifecycle diagram](../docs/assets/resource-gateway-governed-payload-lifecycle.svg),
[payload replay v2 schema](../docs/schemas/tool-studio-resource-gateway/payload-replay-bundle-v2.schema.json), and
[usage guide](../docs/bloge-visual-canvas-product-and-system-guide.md#35-用-recorded-replay-重算正确性断言).

ANEKE workbook integration has two non-interchangeable protocols. `CorrectnessWorkbookBundle.v1` remains the frozen
draft/dependency projection for visual operator-contract tables. The exact-suite endpoint exports
`SemanticCorrectnessWorkbookBundle.v1` only for `bloge.testSuite.v2`, carrying typed semantic requirements and verified
terminal `bloge.testSuiteRunEvidence.v2` refs without case input, fixture payload, or free-text diagnostics. Structural v1
is rejected instead of being presented as empty semantic coverage; verification-authority outage is distinct from no
retained evidence. ANEKE must fetch each referenced portable bundle and verify it against an independently pinned key set.

Reviewed schema-boundary plans can now be materialized as immutable `bloge.testSuite.v3` assets and executed without
calling the graph or operator. A successful run emits generation-matched
`testSuiteExecutionResponse.v4` / `testSuiteRunEvidence.v3` / `testSuiteRunAttestation.v3`, with typed validator
observations, exact plan/schema/generator provenance, and a signed empty business-child closure. Structural coverage stays
`NOT_EVALUATED` and promotion stays `BLOCKED`; this proves schema admission, not business correctness. The capability probe
advertises `schemaAdmissionSuiteExecution` only with the isolated testing runtime. See the
[testing API guide](../docs/resource-gateway-testing-control-plane-api.md#418-execute-and-verify-a-schema-admission-suite)
and [standalone test-kit guide](../resource-gateway-test-kit/README.md).

Property testing has a separate, deliberately bounded lifecycle. A seeded
`bloge.testPropertyCasePlan.v1` can be materialized as `bloge.testSuite.v4` only after the service
regenerates and matches the target, input-schema, and plan fingerprints. V4 freezes every root and
shrink input in order, binds one existing assertion-bearing fixture revision, and keeps
`BOUNDED_SAMPLED` plus `exhaustive=false` as canonical facts. Raw V4 registration and `PROPERTY`
cases in V1-V3 are rejected. The exact V4 revision is executable when the isolated testing runtime
is enabled. Each root and its frozen shrink path runs through the ordinary authorized child runner;
the aggregate emits generation-matched `testSuiteExecutionResponse.v5`,
`testSuiteRunEvidence.v4`, `testSuiteRunAttestation.v4`, and `testSuiteEvidenceBundle.v4`.
`FAIL_FAST` finishes the already-started root's shrink path before stopping, so the observed
counterexample is reproducible. It is minimal only within that precomputed path and always carries
`globallyMinimal=false`. Durable checkpoints, idempotent replay, lease loss, terminal persistence
failure, and abandoned-run reconciliation remain fail-closed without re-executing business input.
Capability discovery reports both `propertySuiteMaterialization=true` and
`propertySuiteExecution=true` only when the execution endpoint is available. See the
[property materialization API](../docs/resource-gateway-testing-control-plane-api.md#415-materialize-a-reviewed-property-plan)
and [property execution verification](../docs/resource-gateway-execution-data-control-plane-stage5-property-execution-verification.md).

Pure-DSL mutation testing has a bounded, evidence-bearing lifecycle. Planning validates recoverable
`bloge-dsl.ast.v1` against the current graph and frozen dependencies, and returns only independently
compiling orchestration mutations with content fingerprints. V5 materialization then regenerates the
reviewed plan, binds exact baseline/source/artifact/target fingerprints, an executable V1/V2/V4 oracle
suite and every fixture, and freezes at most 16 mutants x 16 cases under one score policy. Callers
cannot upload mutated source or trim the matrix.

The dedicated mutation runner executes the full baseline first, regenerates each mutant server-side,
and reuses the baseline-bound inputs and fixtures in the isolated test engine. Only signed assertion
failure kills a mutant; runtime, fixture, timeout, control, target, or evidence failures remain
inconclusive. `STOP_AFTER_KILL` stops only the current mutant and never skips later mutants. Durable
idempotency, lease checkpoints, V5 terminal signatures, portable bundles, and abandoned-run
reconciliation remain fail closed without re-executing completed children. Capability discovery
advertises planning, materialization, execution, and score evidence independently and enables all four
only with the isolated testing runtime. See the
[mutation lifecycle API](../docs/resource-gateway-testing-control-plane-api.md#419-materialize-an-exact-mutation-matrix)
and [Stage 5 mutation execution verification](../docs/resource-gateway-execution-data-control-plane-stage5-mutation-execution-verification.md).

ANEKE remains the workbook and publish-gate authority. Historical `GovernanceGateResult.v2` stays readable, while a
semantic `PASSED` decision uses `GovernanceGateResult.v3`: it records the exact suite target, reconstructable ordered
evidence closure, semantic bundle manifest facts, policy version, and every required check. Resource Gateway rebuilds the
original bundle from exact run ids, recompiles the exact GraphDraft to bind graph target fingerprints, and requires at least
one gate-ready graph suite. Stale or incomplete bases fail closed; a temporarily unavailable verification authority is shown
as `UNVERIFIABLE`; accepted gate results and their change events commit atomically. See the
[gate v3 schema](../docs/schemas/tool-studio-resource-gateway/governance-gate-result-v3.schema.json),
[evidence loop](../docs/assets/resource-gateway-workbook-gate-evidence-loop.svg),
[workbook schema](../docs/schemas/tool-studio-resource-gateway/correctness-workbook-bundle-v1.schema.json),
[semantic workbook schema](../docs/schemas/tool-studio-resource-gateway/semantic-correctness-workbook-bundle-v1.schema.json),
[semantic workbook verification](../docs/resource-gateway-execution-data-control-plane-stage3-aneke-semantic-workbook-verification.md),
[semantic gate verification](../docs/resource-gateway-execution-data-control-plane-stage3-semantic-gate-basis-verification.md), and
[usage guide](../docs/bloge-visual-canvas-product-and-system-guide.md#351-把-contract-suite-和-run-evidence-交给-aneke-workbook).

## Extend It

To add an external API:

1. Register a `ResourceDescriptor` through bootstrap config or the admin API.
2. Define parameter mapping, response protocol, and payload schema.
3. Use it as a `resource:<resourceId>` visual operator or lower it to
   `httpResource`.
4. Compose it in a `.bloge` graph or on the visual canvas.

When you add a new built-in `.bloge` graph under `src/main/resources/bloge/gateway`,
also add a `GatewayGraphContract` entry. This is not optional: `GatewayGraphService`
fails startup when a loaded graph has no contract, and
`GatewayGraphContractCatalogTest` scans every gateway `.bloge` file to catch
schema drift in CI. Runtime execution validates each context against the graph
`inputSchema`, and public gateway endpoints resolve terminal output through the
contract `outputNodes` before validating it against `outputSchema`;
`/api/gateway/graphs/contracts` exposes both schemas; and
`/api/gateway/examples/scenarios` mirrors the same schemas into showcase
metadata so the browser can show each example's Graph Contract;
`/api/gateway/graphs/contracts/tests/draft` generates editable mock rows from
the graph input schema and resource response schemas before
`/api/gateway/graphs/contracts/tests/run` executes table-driven suites against
the real graph through a fresh, run-scoped test engine with downstream APIs
replaced by deterministic, schema-gated fixture rules. The adapter now delegates
to the shared Execution Data Control Plane kernel: selector preflight rejects
zero-match and ambiguous plans, external effects fail closed, required fixtures
must be consumed, and node/edge/assertion evidence is captured without sharing
application interceptors, caches, quotas, circuit breakers, or durable stores.
Stored suites are available under
`/api/gateway/graphs/contracts/tests/suites`; each suite can carry a coverage
policy so batch runs fail when they lack enough cases, schema validations,
mocked calls, assertions, or required output-node coverage.
The built-in catalog covers all seven example graphs with 14 cases, 28
controlled resource-call observations, and 37 business assertions. Resource
rows use explicit F3 transport fixtures, so request mapping, URL rendering,
descriptor response protocol, and payload extraction stay real. Retry cases
declare `minUses/maxUses`, making credit-provider, wallet, and notification
attempt counts part of the pass/fail result. The testing kernel now recursively
freezes synchronous nested graphs and uses BLOGE run-scoped operator resolution
to control foreach, loop, subgraph, and compensation sites. Node evidence is
addressed by structural invocation site, runtime correlation, site occurrence,
and containing-graph occurrence, with retries retained as attempt facts. Fixture rules use the same
one-based attempt/occurrence coordinates to script retry recovery or nested graph re-entry; edge
evidence carries the same graph coordinates. `enrichOrderList` now certifies a
two-item parallel foreach case with independently controlled shipping and
invoice calls. Streaming/suspendable nested execution remains fail closed for
certification.

For the detailed contract-test design, request format, verification evidence,
and remaining industrialization gaps, see
[Resource Graph Schema Mock Table Testing](../docs/bloge-resource-graph-schema-mock-table-testing.md).
The industrial direction is defined by the
[Execution Data Control Plane and testability evolution plan](../docs/resource-gateway-industrial-testability-evolution-plan.md).
Stage 1 of its
[Execution Data Control Plane v1 blueprint](../docs/resource-gateway-industrial-testability-evolution-plan-1.0.md)
is implemented: it separates schema-only operator checks from executable micro-graph
tests, supplies `REAL/RETURN/THROW/DENY/SPY`, supports F2 protocol-derived and F3
transport-level HTTP fixtures, and records fingerprinted evidence. All seven built-in
graphs now dogfood that adapter under `clean verify`; the Spring integration proof points
descriptors at an unreachable address and still requires every suite to pass, catching any
root resource call that escapes its fixture. The generic public
testing API, persistent test-run store, independent JUnit test kit,
production-profile endpoint isolation, deterministic `DELAY/TIMEOUT`, and
synchronous nested invocation control are now available as Stage 2 increments.
Public operator execution, occurrence-level nested evidence, the Author Canvas executable
operator-suite adapter, and idempotent immutable-suite execution are available. The suite runner
supports graph/operator cases, `COLLECT_ALL`/`FAIL_FAST`, durable per-case checkpoints, structural
node/edge coverage, typed branch/decision/retry/fallback/timeout/compensation semantic coverage,
promotion eligibility, signed child-run evidence, and signed suite
checkpoint/terminal attestations at `POST /api/testing/suites/{suiteId}/executions`. Terminal runs
can be exported as payload-free generation-matched `bloge.testSuiteEvidenceBundle.v1/v2` values and independently verified
with the Java test-kit against a signed, externally pinned Ed25519 key-set. Governed exact-reference
REPLAY and dynamic attempt/occurrence selectors are available. Evidence key-set trust publications
now provide externally authorized M-of-N pin distribution, bounded append-only consistency pages,
durable consumer checkpoints, and rollback/fork/revoked-pin detection. Streaming/suspendable control,
real ANEKE N/N-1 conformance, independent witness gossip, and physical test-runtime deployment
isolation remain in progress and are not advertised as complete. Configuration and consumer flow are
documented in [Stage 3 evidence trust transparency verification](../docs/resource-gateway-execution-data-control-plane-stage3-evidence-trust-transparency-verification.md).

Suite checkpoint and terminal persistence now uses the exact canonical v1-v5 aggregate returned by
the signing boundary. The store and service independently bind its signature, aggregate fingerprint,
tenant/organization/project/environment/actor/classification metadata, record envelope, lookup key,
and indexed columns; create/update receipts and abandoned-run candidates from replaceable repository
adapters are verified before use. Altered JSON, forged `VERIFIED` labels, cross-scope substitutions,
and mutable caller aliases fail closed without echoing business payloads. The invariant and remaining
database/WORM trust assumptions are documented in
[Stage 3 suite-run storage integrity verification](../docs/resource-gateway-execution-data-control-plane-stage3-suite-run-storage-integrity-verification.md).

Stage 4 now also provides a profile-gated, content-addressed durable-test checkpoint repository in
the isolated test-runtime database. It binds the exact plan and fixture revision, fixture-consumption
cursors, deterministic provider state, engine-state closure, and owner/lease/revision fence. Current
`bloge.durableTestExecutionCheckpoint.v2` additionally binds the exact graph/operator kind, stable id,
and target fingerprint needed for recovery-time target resolution. Its database projections are
cross-checked against the sealed JSON. Historical v1 rows remain canonically readable with nullable
target columns but are not eligible for future public recovery until an independently verified target
mapping is migrated; the runtime never guesses from a fingerprint. Engine
store writes can join the same local transaction, and a losing CAS rolls them back. Staged BLOGE
`ExecutionStore`, `ExecutionCheckpointStore`, `WaitStore`, and `WorkItemStore` implementations are
combined under the `bloge.testDurableStateMutation.v3` aggregate fingerprint, so lifecycle/lease
state, node/loop/sequential-foreach checkpoints, signal/timer/task/retry waits, and
queued/claimed/retried/dead-lettered work commit or roll back together. Global timer/correlation and
ready/expired-work scans expose committed rows only; the active execution retains read-your-writes.
Ready work, expired work-item claims, and expired execution leases are selected, ordered, and bounded
in SQL through global and tenant-scoped recovery indexes before authoritative JSON is decoded. Each
returned candidate's tenant, namespace, type/status, shard, priority, lease times, and stable identity
projection is then compared with that JSON; drift fails closed. A scan defaults to 100 rows and is
capped at 10,000, preventing an accidental unbounded worker poll. This is a persistence primitive,
not a public dispatcher or cross-process worker supervisor.
An independent profile-gated anti-entropy loop walks both authority tables by primary-key cursor,
so status, shard, time, or even tenant projection corruption cannot hide a row from inspection. It
defaults to 100 rows per table every 60 seconds and `REPAIR_DERIVED`: safe derived columns are rebuilt
from the authoritative JSON with an identity/scope/payload compare-and-set. Primary key, work-item
execution ownership, tenant, and namespace drift and unreadable JSON are reported but never moved or
guessed. Configure
`gateway.testing.durable.projection-reconciliation-mode=AUDIT_ONLY` for observation-only rollout,
`gateway.testing.durable.projection-reconciliation-page-size` for a `1..1000` page, and
`gateway.testing.durable.projection-reconciliation-interval-ms` for the fixed delay. Set
`gateway.testing.durable.projection-reconciliation.instance-id` to a stable replica identity and
`gateway.testing.durable.projection-reconciliation.lease-duration-seconds` to the database-clock
sweep lease (`1..3600`, default `120`). A generated process identity is used when no instance ID is
configured.

The two keyset cursors, sweep lease/epoch/token, and payload-free finding lifecycle are durable.
Only one replica may sweep a page. Projection repair, finding upsert/consistent-recheck resolution,
and cursor checkpointing commit together in one test-runtime database transaction. A crash rolls that page back,
while the separately committed lease eventually expires for takeover. Unrepairable or raced findings
enter an internal owner queue. Claims use a server-minted token, version, owner, and database-clock
lease; manual resolution rejects stale, forged, and expired fences. Stored findings contain internal
row IDs, column names, classifications, and counters, never authority JSON or business values. Logs
still contain aggregate counts only.

The owner queue now has a profile-gated authenticated operations protocol:

```bash
RG_INTEGRATION_GROUPS=resource-gateway-test-runtime-operators \
RG_INTEGRATION_CLEARANCE=RESTRICTED \
./scripts/start-visual-canvas-demo.sh --profile test

curl -sS 'http://localhost:8080/api/testing/durable-state/projection-findings?actionableOnly=true&limit=100' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_RUNTIME_MAINTENANCE'
```

`POST .../claims` derives owner from verified identity and returns the server-minted token only in
the successful claim response. `POST .../resolutions` accepts the exact key/token/version/deadline
fence and only `MANUALLY_REPAIRED` or `QUARANTINED`. Both commands require a caller-stable
`clientRequestId`; an exact retry returns the original receipt, while same-key fact drift returns
409. First claim/resolution state and its token-free semantic action event commit in one
test-runtime transaction. Rejected and replay attempts are appended separately. Configure the
deployment-owned role with `RG_TEST_PROJECTION_FINDING_REQUIRED_GROUP` and minimum clearance with
`RG_TEST_PROJECTION_FINDING_REQUIRED_CLEARANCE`; defaults are
`resource-gateway-test-runtime-operators` and `RESTRICTED`. The endpoint is vetoed by `production`.

Resolved finding lifecycles now move through a second database-leased control loop. The active queue
retains a resolved row for 30 days by default; one bounded transaction then copies its token-free
classification, timestamps, counters, resolution, source revision, and canonical fingerprint into
`rg_test_bloge_projection_finding_archive` and deletes the exact source row. Claim owner/token,
idempotency request IDs/fingerprints, resolution owner, authority JSON, and field values are never
copied. Archive reads recompute the whole-record fingerprint and fail closed on drift. An independent
365-day archive retention deletes at most one configured page per tick. Archive insert, exact source
delete, archive purge, and cumulative counters commit or roll back together; a database-clock lease
prevents multiple replicas from applying the page concurrently.

Configure `RG_TEST_PROJECTION_FINDING_RESOLVED_RETENTION_DAYS` (`1` to `3650`, default `30`),
`RG_TEST_PROJECTION_FINDING_ARCHIVE_RETENTION_DAYS` (`1` to `3650`, default `365`),
`RG_TEST_PROJECTION_FINDING_RETENTION_PAGE_SIZE` (`1..1000`, default `100`), and
`RG_TEST_PROJECTION_FINDING_RETENTION_INTERVAL_MS` (default one hour). The resolved retention API
also supports a one-hour lower bound, while day-based Spring configuration deliberately rounds the
operator-facing policy to whole days. Retention uses the same stable
`gateway.testing.durable.projection-reconciliation.instance-id` identity and
`gateway.testing.durable.projection-reconciliation.lease-duration-seconds` database-clock lease as projection
anti-entropy, but owns an independent lease row and cannot block the scan cursor. After active retention elapses, the ordinary finding endpoint
no longer returns that historical lifecycle; the archive is currently an internal persistence and
readiness surface, not a public evidence API. Its unkeyed fingerprint catches ordinary row drift but
does not prove external immutability.

The test/staging profile also installs a database-clock SLO health indicator and fixed-cardinality
Micrometer meters for these two control loops. One transactionally consistent snapshot measures
open/live-claimed/expired-claim/resolved findings, overdue active/archive retention, and both durable
last-success timestamps without exporting row IDs, tokens, exception text, or payloads. Health is
`UP`, `UNKNOWN`, `OUT_OF_SERVICE`, or `DOWN` for `HEALTHY`, startup `INITIALIZING`, stable SLO
violations, or store unavailability. Defaults allow 180 seconds for startup and reconciliation,
three hours for retention, one hour for the oldest unresolved finding, and zero unresolved or
overdue rows. Configure these under `gateway.testing.durable.projection-slo.*` or the corresponding
`RG_TEST_PROJECTION_SLO_*` variables. `/actuator/health` is the only default Actuator web exposure;
metric export remains a deployment-owned registry/exporter decision, and health details stay hidden
unless management security explicitly permits them.

The same isolated profile now installs a second, global `testRuntimeSloMonitor`. In one read-only,
repeatable-read transaction it uses the database clock to observe recent child/suite evidence
completeness, running suite ownership, pending durable creation, resumable durable execution,
dispatchable/expired-claim work, and expired or terminal storage backlog. Business assertion,
negative-case, and product-under-test failures remain outcome metrics and never make the runtime
unhealthy. Only incomplete evidence, excessive/old queues, expired ownership, retention backlog, or
store unavailability produce stable SLO violations. Fixed-vocabulary meters live under
`resource.gateway.test.runtime.*`; tags are limited to closed `status`, `queue`, `scope`, and `kind`
values. Deterministic worker deferrals add only the closed `reason` tag plus untagged retry-due,
maximum-failure, and oldest-age gauges. Worker quarantines add the same closed `reason` vocabulary,
fixed `AVAILABLE`/`CLAIMED` maintenance-state gauges, and untagged maximum-failure, oldest-age,
expired-claim, retained-history, live/expired discard-approval, and approved-discard-history gauges.
Expired claims and expired unconsumed approvals have separate stable health violations.
Configure thresholds through
`gateway.testing.runtime-slo.*` / `RG_TEST_RUNTIME_SLO_*`; the full table is in the testing
control-plane guide. Lifecycle/time indexes support these aggregate reads, and the outcome lookback
is hard-capped at 365 days.

The isolated profile now also enforces database-authoritative admission before any test engine starts.
Tenant, suite, recursively reachable operator, and conservative external-dependency claims are acquired
all-or-nothing with database-clock renewable leases. Direct graph/operator runs and sequential batch
children acquire their own permits; an immutable suite acquires its complete closure once so its child
cases cannot self-deadlock; fresh durable creation and terminal recovery hold capacity through their
first committed boundary. Saturation returns stable `429` problems plus `Retry-After`, policy drift and
store loss fail closed, raw subject names are hashed before persistence, expired leases are reclaimed in
bounded pages, and application shutdown releases local permits. Configure limits and lease behavior
under `gateway.testing.admission.*` / `RG_TEST_ADMISSION_*`; the full table and rollout rules are in the
[testing control-plane guide](../docs/resource-gateway-testing-control-plane-api.md#4212-database-authoritative-runtime-admission).

This is immediate admission backpressure, not a queued scheduler. Priority/fairness queues,
runtime-state delivery to a remote worker, cross-process supervision, adaptive autoscaling, hard
cancellation and wall-clock worker deadlines, external alert routing, non-H2 dialect certification,
tamper-evident external anchoring, and production-load certification remain future work.

Wait identity must match the lifecycle identity, and committed wait/work-item ids cannot migrate to
another execution. Work-item batches validate atomically, and claim, retry, terminal, and dead-letter
transitions reuse BLOGE's reference state machine. An internal database-clock lease claim can fence an
expired `ACTIVE`, `SUSPENDED`, or `RESUMING` owner by exact scope, old owner/epoch/revision, and
checkpoint fingerprint. It increments epoch and revision, enters `RESUMING`, and cannot alter the
recovery closure. A profile-gated durable command repository atomically binds a scoped caller key and
complete claim intent to that lease CAS and an immutable result snapshot, so an ambiguous retry returns
the original result while same-key different intent and stored-result tampering fail closed. A separate
command-record fingerprint detects indexed intent drift before classifying caller conflict. The
independent durable session attaches only this aggregate. The caller assigns
the engine execution id outside business context and supplies the complete frozen `ExecutionOptions`,
so operator fixture resolution and
deterministic providers survive unchanged. Missing stages, cross-execution writes, engine-state/id
mismatches, another datasource, post-close mutation, or checkpoint failures all fail closed; a
transient transaction rollback can replay the same content-addressed mutation. The run-scoped
`InvocationRecorder` now captures and restores rule-use and hashed site/graph occurrence cursors
only at a quiescent invocation boundary, and atomically enforces fixture `maxUses`. Cursor hashes omit
raw correlation values but are pseudonymous identifiers, not a confidentiality boundary. BLOGE's
fresh execution-to-durable-boundary API now feeds a strict initial-boundary policy: creation v1 may
prepare only one persisted `WAIT_SIGNAL` under a `SUSPENDED` execution. The session captures that
boundary, the fixture cursor, and the four-store aggregate as one immutable `PreparedRun`; terminal,
paused, timer/work-item/stream, and parallel multi-suspension outcomes discard the stage. A database-time
`bloge.durableTestCreationCommandRecord.v1` reservation now supplies scoped caller idempotency,
server-minted run/engine identities, owner fencing, lease-expiry takeover, immutable rejection/result
replay, and one atomic commit decision for the initial control checkpoint, four-store mutation, and
local audit. It stores fingerprints and payload-free locators only. The authenticated public creator
re-authorizes the exact graph, fixture, replay/authority/provider closure and plan, executes in that
stage, and returns or replays only the payload-free initial suspended view. BLOGE's synchronous
cold-start signal API is consumed by an internal `RecoverySession`: only a current
v2 `RESUMING` checkpoint with exact target and restorable provider state can restore fixture cursors,
open the staged aggregate, signal a real committed suspension, and prepare its next terminal or
single-suspension boundary for the same fenced transaction. Closing without prepare restores the
original committed wait and lifecycle; no detached recovery thread remains active. The public
owner-claim endpoint now binds exact dependency re-authorization to its `RESUMING` fence and a
payload-free worker dispatch. The authenticated public heartbeat resolves that hidden issued
dispatch from an exact predecessor fence, requires the original authorization principal, and rotates
the live revision/lease/successor dispatch atomically. The public terminal-recovery adapter consumes
one still-live dispatch, reconstructs the same executable authorization closure, runs one cold signal,
and commits the server-derived final BLOGE mutation, terminal checkpoint, immutable result, audit,
and payload-free receipt in one transaction; retries never reapply the engine mutation. Because durable
state still lacks complete pre-checkpoint node/edge/attempt trace, receipt v1 is always
`EVIDENCE_INCOMPLETE`, requires explicit gap codes, and blocks promotion. BLOGE streaming
offset/checkpoint state, complete historical evidence, runtime-state worker dispatch and
multi-boundary orchestration, dispatcher consumption, cross-process worker
supervision, and a killable worker deadline are not wired yet. These internal primitives are
not a product claim that durable test
resume is complete. See
[Stage 4 durable checkpoint verification](../docs/resource-gateway-execution-data-control-plane-stage4-durable-checkpoint-verification.md).

Create a provider-specific Java operator only when the provider behavior cannot
be expressed cleanly as a descriptor-backed resource.

For the fastest library definition path, start from
[`bloge.visualLibraryAuthoring.v1`](../docs/bloge-visual-library-authoring-guide.md)
and preview one of the runnable examples:

```bash
curl --fail-with-body \
  -H 'Content-Type: application/yaml' \
  --data-binary @docs/examples/customer-service-library-authoring.yaml \
  http://localhost:8080/admin/visual-operator-library-authoring/preview
```

The stateless endpoint safely parses compact types and function signatures, expands
operator archetypes, returns canonical `bloge.visualOperatorLibrary.v1`, source-map
diagnostics, readiness, callable conflicts, and target registry diff. For recoverable
authoring, use `/admin/visual-operator-library-authoring/drafts/{draftId}` with
`If-Match`, Bearer authentication, and an exact purpose: `TEST_SUITE_READ` for reads,
`TEST_SUITE_WRITE` for mutations, and `TEST_SCENARIO_PUBLISH` for commit. The server derives
the five-dimensional enterprise scope and actor from the authenticated principal; a body
`actor` cannot override attribution. Scoped current/history tables permit the same draft id
in different enterprise scopes. A database-unique ownership record prevents another scope
from committing the same canonical library id. Existing catalog revisions without ownership
fail closed until an explicit migration; legacy unscoped draft tables are not silently claimed.
Preview and commit reject stale draft, compiler, catalog, canonical, and target-library
revisions before importing a design catalog revision.
The revision-fenced `POST .../infer/samples` endpoint derives payload-free observed
facts and conservative candidates from at most 100 JSON samples. It never mutates the
draft or retains raw samples. After review, `POST .../infer/samples/apply` replays the
same bounded request, verifies its evidence fingerprint and every explicit confirmation,
then atomically stores the declared port, payload-free evidence, and decisions as one new
draft revision. Editing that declared port later invalidates its attached evidence.
The `/libraries/` Workbench exposes this protocol through Input/Output target selection,
candidate/fact review, an explicit confirmation queue, responsive review layout, and
lossless nested-object feedback after apply.
Its **Discover Existing Assets** path normalizes Capability Catalog, AsyncAPI, OpenAPI, BLOGE DSL,
and process-local runtime inventory into `bloge.visualAuthoringFactProjection.v1`. Every response
separates declared/observed facts from runtime parity, carries source/projection fingerprints, and
returns a structured authoring document only when one can be produced conservatively. Preview then
binds the candidate to an exact `runtimeInventoryFingerprint`; only an exact authoritative
operator/function contract can reach `BOUND` and `RUNTIME_BOUND`. Existing source-specific
endpoints remain compatible. See the
[fact projection protocol](../docs/resource-gateway-authoring-fact-projection-protocol.md).
See `/catalogs` for exact limits and feature flags.

For Canonical Advanced import:

1. Open `/author/`.
2. Paste a `bloge.visualOperatorLibrary.v1` JSON or YAML document that follows
   the [operator library schema guide](../docs/bloge-visual-operator-library-schema.md)
   and [machine schema](../docs/schemas/bloge-visual-operator-library.schema.json).
3. Validate and import it.
   A library may contribute operators, built-in expression functions, or both;
   at least one of `operators` and `builtInFunctions` must be non-empty.
   Function calls are resolved by `name`, not provenance `namespace`.
   Validate/import therefore rejects an incompatible callable contract already
   supplied by the default catalog or another library, while the registry and
   effective catalog enforce the same invariant for non-HTTP and legacy paths.
4. Use `/api/visual/operators/tests/draft` to generate editable operator mock
   rows from each operator's input/config/output schemas, then save or batch-run
   schema-contract checks through `/api/visual/operators/tests/suites`. This
   current mode validates fixture and schema consistency; it does not execute a
   real operator runtime binding. Results expose `mode=SCHEMA_CONTRACT`.
5. Double-click a canvas node and use `Executable Operator Suite` to run one case or the whole
   table through the isolated testing control plane. `Run Case` / `Run Exploratory` use inline
   fixtures for fast `EXPLORATORY` feedback. For governed evidence, choose a
   `Golden`/`Negative`/`Boundary`/`Regression` intent and use `Publish Case + Run` or
   `Publish Suite + Run`. The canvas registers content-addressed immutable fixtures with
   `TEST_FIXTURE_WRITE`, publishes one dependency-closed `bloge.testSuite.v1` revision with
   `TEST_SUITE_WRITE`, validates the complete returned identity, then executes that exact revision
   through `/api/testing/suites/{suiteId}/executions` with `TEST_EXECUTION`. It displays payload-free
   child run links plus aggregate execution, coverage, and promotion eligibility only after the
   stored suite, child evidence, assertions, coverage, promotion, and aggregate status pass
   fail-closed consistency checks. The table is read-only in flight; a later exploratory run clears
   the stale publication banner. Native operators
   run real code under a `SPY`; resource-backed visual operators
   lower to `httpResource` and replace only transport I/O, using the editable `Transport response`
   as the raw protocol fixture. Unsupported and `OPAQUE_RUNTIME` targets fail closed before
   execution. Stored provenance is necessary but not sufficient for `CERTIFIABLE` evidence: target
   composability, strict schema checks and fixture fidelity must also qualify. A
   stateless/read-only declaration is not enough for certification: non-resource bindings also need
   a versioned, fingerprinted `OperatorComposabilityManifestProvider` declaration.
   `httpResource` can earn `EXECUTABLE_UNIT` only with a transport-boundary fixture, so
   request mapping, URL rendering, response protocol, and payload extraction really execute.
6. Drag operators, wire schemas, simulate, and export.

The `/author/` built-in canvas examples also carry their own graph-level
input/output schemas, and exported drafts include the current `inputSchema` so
the design can be integrated instead of remaining a diagram-only artifact.

### Try The Task-Oriented Author Workspace

Open `http://localhost:8080/author/`, load a built-in example, and use the fixed
`Compose / Contract / Scenarios / Evidence` task modes. Author Workspace v2 is
the default page. Each mode owns exactly one central surface; Contract,
Scenarios, and Evidence no longer open a second full-screen modal or repeat the
same task tabs. `Scenarios` is the only formal Graph/Operator testing
workspace: legacy graph and operator table rows are projected into the same
Scenario model, while unprojectable source remains visible under Advanced for
manual migration. The URL keeps the selected target, workspace view, Scenario,
run, and node so a browser refresh or integration deep link restores the same
working context. Old `authorMode=test|review` bookmarks remain readable and map
to `scenarios|evidence`; legacy `operatorRef` links migrate to the canonical
`target=operator:<ref>` coordinate.

Author, Libraries, and Rehearsals now share a versioned enterprise task coordinate.
Their compact context bar shows the exact asset/revision, tenant, namespace,
environment, role, command scope, and owner before a command runs. Read-only roles
and cross-tenant mutations fail closed. Destructive commands in `production` require
an explicit dialog that names the environment and target and stays disabled until
the author types `PRODUCTION`; this authoring safeguard is not a substitute for a
server publish gate or two-person approval. Scenario Matrix exposes exactly one
direct primary run action: `Run all` with no selection or `Run selected` with a
selection; failed/changed/affected scopes live in one menu. Evidence deep links use
a bounded same-origin return coordinate and restore the originating scroll/focus
once. See the
[S2 implementation note](../docs/resource-gateway-ux-round3-s2-enterprise-task-coordinate-implementation.md).

The right inspector has stable `Config / Data / Scenarios / Contract / Advanced`
tabs. Edit the graph input/output interface in **Contract**. In **Data**, the
**Effective data contract** keeps declared, inferred, bound, and observed facts
separate. Its source rows show every target field and its edge/context/constant
origin; use the arrow to focus the upstream node. Transform assignments and
Decision Table outputs are projected with explicit confidence, while runtime
observations remain non-authoritative. The full Contract tab can explicitly
accept inferred output fields into an open Graph Output Contract; it never
infers requiredness.

Below the projection, fill the schema-generated **Run Input Values** and use
**Bind** to connect a Graph Input field to the selected node without typing a
`ctx.*` path. Low-level direct bindings are available under **Edit direct
bindings**. A bind replaces any existing source for the same target port/path,
so export cannot retain two competing sources. Effective Contract also
deduplicates the edge plus its canonical `nodePath` projection, while reporting
genuinely different sources or incompatible types in shared Diagnostics.

Run Input and optional Context Extras are transient simulation data; exported
GraphDrafts retain only the input binding semantics. Raw runtime JSON is an
explicit **Advanced** takeover and is off by default. Fields declared with
`writeOnly`, password format, `x-sensitive`, or restricted/confidential
classification are masked in the generated input form.

For a complex graph, run **Auto Layout**, then use the canvas navigator's three
reading tasks: **Overview** hides field labels and shows the whole stage shape,
**Focus** keeps the selected node's complete upstream/downstream closure, and
**Inspect** restores exact field semantics. Parallel data edges are bundled by
source without losing their exact coordinates. The Readability verdict includes
effective text size, density, collision, and viewport bounds; **Fit** never
enlarges beyond 100%. Auto Layout now compares the current and candidate geometry,
fit zoom, title size, perceptual verdict, and graph area before enabling **Apply**.
A regressive candidate keeps Apply disabled and recommends retaining the current
layout; **Advanced > Apply anyway** is an explicit, payload-free audited override.
For compact layered graphs, generation and acceptance now share the same 24 px
two-sided node clearance, long edges use the semantic bus lane, and visible edge
labels keep an additional 8 px model-space safety band for localized wrapping and
fractional zoom. The bundled Loan example is browser-verified at 80% fit, a 12 px
effective title, and zero node overlap, label overlap, or viewport clipping.
On compact viewports, start in Overview and select a node before entering Inspect.
**Undo layout** restores the exact pre-layout positions until a node is added,
removed, or manually moved.

The temporary Start and Operator dialogs share one keyboard contract: focus
enters the current task, Tab stays inside the modal, Escape closes it, and focus
returns to the opener. Contract/Scenario/Evidence are central regions, not
dialogs. The old raw Test Suite dialog is available only in Legacy Workspace;
v2 opens schema-driven Scenarios and moves directly to Run Evidence after
`Run & Compare`. On compact viewports the topology context becomes an optional
drawer whose open/close state does not mutate the authoring URL. At 390 x 844
the Author shell remains viewport-bound, its central region scrolls
independently, and Diagnostics cannot cover the Scenario run action.

Workspace v2 dispatches payload-free `bloge:author-task` browser events using the
`bloge.authorTaskEvent.v1` envelope. A host shell may consume counts, modes,
status, duration, and first-success timing; context, fixture, payload, schema,
DSL, config, input/output, and credential-like metadata are rejected. The UI
does not transmit these events itself. Use the visible **Legacy** link or
`?authorWorkspace=legacy` for immediate UI rollback; `v1` remains a compatibility
alias for existing bookmarks. GraphDraft and Scenario assets are shared and
remain untouched.

### Try Contract And Scenario Authoring

The Agent-facing solution authoring path is separate from the legacy graph editor. The
`solution-authoring` reference topic teaches an isolated MCP client the bounded Feature, Scenario,
Instruction, and Solution fragments; a business user never needs to supply BLOGE DSL. At
`rg.solution.compose`, the server resolves the complete scenario tree, rejects cycles, excessive
depth, missing exits, and incomplete instruction bindings, and lowers an accepted solution through
the production DSL importer into exactly `scenarioCall -> instructionCall`. Scenario evaluation is
pure. During simulation, WRITE instructions return a contract-shaped stub and cannot reach the
dispatch channel; the integration contract fixes `realExternalCalls` at zero. Feature collection
and trusted runtime invocation remain outside the pure graph. `rg.solution.getContract` returns the
collection plan. Platform-owned values are produced by a uniquely resolved
`FeatureEvaluationAdapter`, then protected by a five-minute HS256 token bound to Feature ref,
input/value fingerprints, and the exact tenant/project/environment scope. Interactive values must
carry `source=USER`. `rg.solution.invoke` requires a business-action `idempotencyKey`, verifies every
envelope and a still-current immutable publication, durably reserves dispatch, and then executes the
publication's frozen Solution/Scenario/Instruction snapshot. WRITE is mediated by an internal
PLATFORM identity; callers never receive `AGENT_TDD_WRITE_EXEC`. Exact retries replay the stored
response, conflicting key reuse fails closed, and an ambiguous downstream outcome requires operator
recovery instead of automatic retry. Its MCP annotations therefore honestly report idempotent,
open-world, potentially destructive execution. Local demos use one process-local key; replicated
or restart-stable environments must set
`RG_FEATURE_TOKEN_ACTIVE_KEY_ID` and `RG_FEATURE_TOKEN_KEY_RING` from a secret manager. The key-ring
format is comma-separated `keyId=base64(32-or-more-byte-secret)` and may retain verify-only keys
during rotation.

Solution test cases reuse the governed case-set and human Oracle approval line. Set `toolRef` to the
Solution ref when calling `rg.scenario.upsertCases`; no synthetic legacy Tool draft is required.
`rg.scenario.test` asserts pure rule outlets from pinned Feature values. `rg.solution.baseline`
accepts only ACTIVE GOLDEN rows, locks both the case-set and Solution revisions for the complete
evidence transaction, stubs WRITE results from their declared output contracts, and persists the
layer summary plus business backlog with `realExternalCalls=0`. A passing GREEN row advances to
READY in that same transaction; an unapproved, stale, or concurrently changed line fails closed.

1. Start the demo and open `http://localhost:8080/author/`.
2. Load one of the built-in complex canvas examples. Its graph Contract and table cases are
   projected automatically.
3. Click the **Contract** rail above the canvas.
4. Use **Interface** to inspect every graph input/output field and its required/type constraints.
   Declare effect, idempotency, streaming/durability, compatibility, stable errors, and Contract
   invariants in the structured **Contract semantics** editor. A WRITE Contract also exposes its
   reconciliation protocol and compensation declaration. These edits become part of the Graph
   fingerprint, so save the Graph and explicitly rebase stale Scenarios afterward.
5. Open **Scenarios**. Fill **Given** with schema-driven controls. For every dependency choose
   a Canvas node, Operator, Resource, or built-in function target, then choose **Real**, **Return**,
   **Error**, **Delay**, **Timeout**, **Replay**, **Observe**, or **Deny**. Expand **Selector,
   matching & consumption** only for graph path, correlation, attempt/occurrence, matching input,
   or bounded use. Existing cases list only calls they actually control; omitted nodes run normally,
   and complete dependency cards start collapsed.
6. Under **Then**, add graph output, node output, node status, edge transfer, or dependency-use
   assertions. Pick a schema-projected result field; expected values are generated with the
   relevant field type. Use **Custom path** only for schema-external coordinates.
7. Click **Run & Compare** for behavior representable by the transient simulation path. **Run
   Evidence** starts with the verdict and a shared remediation queue. Each action states root cause,
   business impact, owner, required role, and an exact Author or external-governance handoff.
   Execution, Assertions, Contract, and Governance remain separate. Failed assertions show
   Expected / Actual / bounded path-level Diff; fingerprints and request coordinates are collapsed
   under technical details. The surface reports **Ready for promotion** only when every dimension
   passes without warnings. Missing governance handoffs show the accountable owner and do not render
   a fake action.
8. To retain and govern the work, use **Save Graph**, review and rebase to the returned server
   Contract, then **Save Scenario**, and finally **Publish**. Load/Save/Publish remain disabled until
   their exact prerequisites exist.
9. In **Matrix**, use **Run all** to create the complete server baseline. Read Execution and
   Assertions separately in each row, watch the Server batch closure/progress, and use **Cancel** or
   **Retry failed** when offered. After editing a case, use the counted **Run changed (N)** or
   **Run affected (N)** action; zero-count actions are disabled and a partial run cannot satisfy the
   full-suite promotion gate.
10. After the graph or Contract changes, review **Compatibility** and explicitly rebase the Scenario
   set. Stale coordinates cannot run silently.
11. Use **Export Workspace** to download one verified, secret-safe Graph + Contract + Scenario
    bundle for offline or VS Code work. Use **Import Workspace** to restore it without re-layout;
    import rechecks the Contract fingerprint, Graph target fingerprint, enterprise scope, operator
    index, classification, nested protocol shape, and raw-secret policy before changing the canvas.

For a single Operator, double-click its node and choose **Contract & Scenarios**. The same workspace
projects the authoritative catalog port Contract, builds schema-driven Given values, persists
operator-target Scenario revisions, and publishes them through an independently discovered
`OPERATOR` runtime target. Catalog-derived Contract semantics are read-only in this view; update the
operator library to change them. Graph-only node/edge selectors and the Graph workspace bundle are
intentionally unavailable for an Operator target. Opening a stored target automatically resumes its
latest Scenario revision; **Load Scenario** remains an explicit refresh. Before revision 1 exists,
the workspace keeps its schema-generated Happy path, including Given input and an expected-output
assertion, and reports the 404 as the normal “not saved yet” state with a **Save Scenario** action
instead of exposing a request error. A resource-backed business
Operator such as `resource:user-service.getProfile` keeps that ref as its design target while the
governed compiler lowers Given `{params: ...}` into `{resourceId, params}` for the independently
fingerprinted `httpResource` runtime target. Publication lineage retains both coordinates.
Operator dependencies can be added and removed in the graphical Scenario editor. Their durable
asset ids include the complete digest of the exact operator ref, and loaded assets are accepted only
after the target kind/id is reverified, so normalized-name collisions cannot cross-load Scenarios.

Normal authoring does not require JSON. **Advanced Contract JSON** and **Advanced Scenario JSON**
remain available as lossless expert paths. The exploratory browser compiler keeps behavior that
cannot be represented by transient `NodeFixture` fail-closed. The server-side governed compiler maps
the complete set to content-addressed FixtureBundle and TestSuite registration requests; durable
publication remains a separate permission and recoverable transaction.

Publication verifies every registry read at its own trust boundary using canonical JSON
fingerprints. It does not depend on Java object equality after a database round-trip, and Scenario
HTTP conflicts/errors use the stable integration problem response instead of generic HTTP 500.

When a saved Graph or Operator Contract changes, choose **Review compatibility** instead of
rebasing from the banner. The Compatibility view loads the retained revision's immutable Contract
baseline and shows field findings, affected Scenarios, and a migration plan. **Apply safe
migrations** only performs explicit defaults, removals, declared renames, and assertion rebinds.
Breaking or opaque changes require acknowledgement before **Record review & rebase**. The rebased
draft must then be saved and rerun; no compatibility action creates passing evidence.

```text
GET /api/visual/scenario-draft-sets/{id}/compatibility?revision={revision}
```

The response is `bloge.contractCompatibilityReport.v1`. Unknown Schema semantics and legacy
revisions without a captured baseline are `REVIEW_REQUIRED`, never silently compatible.

The portable format is `bloge.visualAuthoringWorkspaceBundle.v1`. It contains publication
references, not fixture or test payload duplication, and permits secret references but rejects raw
credentials. Its authoritative schema is
[`bloge-visual-authoring-workspace-bundle-v1.schema.json`](../docs/schemas/bloge-visual-authoring-workspace-bundle-v1.schema.json).

In `test` and `staging`, authenticated hosts can persist Scenario authoring assets without granting
publication or execution authority:

```text
POST /api/visual/scenario-draft-sets/validate
PUT  /api/visual/scenario-draft-sets/{id}?expectedRevision=0
GET  /api/visual/scenario-draft-sets/{id}
GET  /api/visual/scenario-draft-sets/{id}/revisions
POST /api/visual/scenario-draft-sets/{id}/matrix/query
POST /api/visual/scenario-draft-sets/{id}/matrix/bulk-edits
GET  /api/visual/scenario-draft-sets/targets/graphs/{draftId}/contract
GET  /api/visual/scenario-draft-sets/targets/operators/{operatorRef}/contract
POST /api/visual/scenario-draft-sets/{id}/publications?revision=1
GET  /api/visual/scenario-draft-sets/publications/{publicationId}
GET  /api/visual/scenario-draft-sets/publications/{publicationId}/history
```

Use `X-Purpose: TEST_SUITE_WRITE` for validate/save and `X-Purpose: TEST_SUITE_READ` for reads.
The body enterprise scope must match the verified credential. Every save rechecks the current graph
and Contract, rejects raw secrets, retains immutable history, and fails with a revision conflict
instead of overwriting concurrent edits.

Publication requires the separate `X-Purpose: TEST_SCENARIO_PUBLISH`. It resolves the runtime target
from the testing control plane, compiles content-addressed fixtures and suite, independently re-reads
every registry write, and retains an optimistic `IN_PROGRESS` / `PARTIAL` / `FAILED` / `PUBLISHED`
transition history. The receipt contains fingerprints and registry references only, never Scenario
inputs, dependency outputs, or expected values. Retrying an exact partial publication converges on
the same immutable assets. The normal demo start script already uses the required `test` profile;
the publication service and routes are absent from `production`.

The implementation contract and current residual gap are documented in
[Contract & Scenario Authoring Protocol](../docs/resource-gateway-contract-scenario-authoring-protocol.md)
and [Implementation Status](../docs/resource-gateway-contract-scenario-authoring-implementation-status.md).

## Build And Verify

This is a standalone Maven project. BLOGE artifacts must already exist in the
local Maven repository.

```bash
mvn -f resource-gateway-examples/pom.xml \
    -Dtest=GatewayGraphContractTestServiceTest,ExecutionControlCompilerTest,TestRunServiceTest,ResourceFixtureRuntimeTest,OperatorMicroGraphRunnerTest,GraphArtifactFingerprintTest test
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
mvn -f resource-gateway-examples/pom.xml -Pfrontend package
mvn -f resource-gateway-examples/pom.xml spring-boot:run
```

Run the fixed Capability Studio release-candidate browser matrix from the repository root:

```bash
./scripts/run-capability-studio-browser-matrix.sh
```

The command builds the candidate, executes `GP-01..GP-10` in both languages at
`1440×900`, `1024×768`, and `390×844`, writes 60 fingerprinted screenshots, and asks the
independent Test Kit CLI to revalidate the result. A clean formal run first performs a fail-closed
preflight for root filesystem capacity, free inodes, the artifact root, both Maven `target`
directories, and the actual `TMPDIR` write path. The contract minimums are `4194304` KiB (4 GiB)
and `20000` free inodes; both `CAPABILITY_STUDIO_MIN_FREE_KIB` and
`CAPABILITY_STUDIO_MIN_FREE_INODES` must be non-negative integers. Formal runs may raise these
values, but lowering either one fails before Maven with
`RG.CAPABILITY_STUDIO.BROWSER_PREFLIGHT_FORMAL_THRESHOLD_BELOW_MINIMUM`. A local observation on
2026-08-19 measured about `0.6 GiB` available, under which the formal script stops at preflight;
every run uses its own preflight measurement rather than treating that observation as a product
constant.

Successful clean runs use a unique
`resource-gateway-examples/target/acceptance/runs/<commit-short>-<utc>-<pid>/` root. The normal
result, anomaly result, `browser-matrix-evidence/`, `browser-anomaly-evidence/`, and
`capability-studio-browser-evidence-bundle-manifest-v1.json` all live under that root. In clean
mode the artifact root must be fresh before any producer starts: after its own write probe is
removed, any file, directory, or symlink causes
`RG.CAPABILITY_STUDIO.BROWSER_PREFLIGHT_ARTIFACT_ROOT_NOT_FRESH` before Maven. This also applies
to an explicitly selected shared parent; dirty `--allow-dirty` diagnosis may still reuse its
explicit existing base. The two
matrix CLIs must first print `VALID status=COMPLETE`; the Test Kit
`CapabilityStudioBrowserEvidenceBundleCli` must then emit exactly one line matching
`VALID status=COMPLETE expectedCount=438 persistedCount=438 manifestFingerprint=sha256:<64 lowercase hex>`
and exit zero before the wrapper prints `COMPLETE: 186/186` and `EVIDENCE_MANIFEST`. The 438-file
denominator is `60` normal `.png` screenshots plus, for each of 126 anomaly obligations, one exact
same-prefix `-error.png`, `-recovered.png`, and `-trigger.json`, or `60 + 126 × 3 = 438`.
Arbitrary triples, missing roles, cross-obligation references, and non-PNG normal evidence fail
closed with `RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_EVIDENCE_ROLE_MISMATCH`. A dirty or filtered run never
creates the formal bundle manifest and remains explicitly `DEVELOPMENT_VERIFIED`. `--allow-dirty`
forces development diagnosis even when the source tree is clean: it never invokes the formal bundle
gate and can never print `COMPLETE: 186/186`. A dirty-tree diagnosis must provide explicit existing
result paths. The script does not replace external Candidate,
Environment, Authority, or Owner sign-off.

If BLOGE core artifacts are missing, install them from the main BLOGE repo:

```bash
mvn -pl bloge-core,bloge-dsl,bloge-common-operators,bloge-spring,bloge-test \
    -am install -DskipTests -Dspotbugs.skip=true
```

Java 25 preview flags are already configured.

## Know The Boundary

- Runtime visual execution is request-response; streaming and durable operators
  are visible in catalog readiness but blocked from direct request-response runs.
- Remote worker, AI tool, event source, message handler, and webhook operators
  can be modeled; this example does not ship their production runtime plane.
- Multi-user collaboration and the complete enterprise IAM policy lifecycle remain outside this example. Dynamic
  JWKS/revocation, group/clearance/delegation claims, purpose authorization and tenant isolation are implemented; customer
  IdP certification, customer policy-engine conformance, group lifecycle/orphan ownership and emergency-access governance
  still require deployment-specific integration. The built-in payload policy is fail-closed and replaceable, not a
  substitute for the customer's authoritative classification registry.
- Managed evidence signing is implemented as a vendor-neutral KMS/HSM sidecar protocol and provider SPI. Production
  deployments still need a customer-specific provider identity/policy, authoritative key-use audit export, historical
  public-key retention, multi-region disaster recovery, and vendor conformance evidence; the default H2 signer remains
  demo-only.
- The visual core still lives inside `resource-gateway-examples`; it is shaped
  for future extraction, but is not yet a standalone artifact.

For full endpoint catalogs, implementation notes, tests, and historical detail,
use [REFERENCE.md](REFERENCE.md). For the visual canvas product guide, use
[docs/bloge-visual-canvas-product-and-system-guide.md](../docs/bloge-visual-canvas-product-and-system-guide.md).
For non-production HA, partition, upgrade, restore, rotation, and write-escape certification, use the
[Runtime Certification guide](../docs/resource-gateway-runtime-certification-guide.md). The normal
demo intentionally advertises protocol support while leaving destructive execution unavailable.
