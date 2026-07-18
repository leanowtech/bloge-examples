# Stage 5 suite-stability dynamic authority trust verification

## Scope

This increment removes application restart from the normal suite-stability authority key-rotation
path. It adds an opt-in Ed25519 JWKS trust source for the existing signed current-authority PDP
protocol. The source bootstraps before worker startup, refreshes complete snapshots in the
background, performs one bounded unknown-key refresh during rotation, exports local-only capability
truth and contributes payload-free Actuator health.

This is verification-key distribution, not policy evaluation. The worker still obtains a fresh,
challenge-bound signed `AUTHORIZED|REVOKED` decision immediately before execution.

## Root cause and decision

Static JSON trust is cryptographically valid but operationally couples IAM key rotation to Resource
Gateway configuration rollout. In a large fleet that creates three failure windows: a PDP can sign
with a key some replicas do not know, a compromised key can remain accepted on stale replicas, and
operators cannot prove which replicas have refreshed.

The built-in solution uses a separate authority JWKS model instead of reusing workload-JWT token
trust. Token revocation, issuer claims and bearer lifecycle are unrelated to detached PDP decisions.
The shared format is only public Ed25519 JWK; policy binding remains in the existing authority
request/response protocol.

## Refresh invariants

1. Dynamic mode is explicit and mutually exclusive with the static key-ring fallback.
2. Startup performs a synchronous bounded bootstrap and requires at least one currently active key.
3. JWKS accepts exactly 1..64 public `OKP/Ed25519/EdDSA` keys with `sig`/`verify` use.
4. Duplicate fields, unknown fields, duplicate key ids, private-key fields, malformed coordinates,
   unsupported algorithms and invalid lifecycle windows reject the whole document.
5. HTTPS is mandatory; HTTP is accepted only for an explicit loopback test escape hatch. User info,
   query and fragment are rejected.
6. Redirects are never followed. Response status, content type, declared length, actual length,
   request timeout and ETag are bounded.
7. Parsing and verifier construction complete before one volatile snapshot publication. Readers
   never observe a partially rotated key set.
8. Any fetch or document failure marks the entire snapshot `UNAVAILABLE`; the previous material is
   retained only for diagnostics/recovery and cannot verify decisions.
9. A local hard maximum snapshot age independently expires a silent or dead refresh lane without
   making a network call.
10. Capability and Actuator reads are local-only. They never turn unauthenticated probes into an
    IAM dependency or amplification path.
11. An unknown response `kid` may trigger one synchronous refresh under a global cooldown. The
    refresh lock rechecks the snapshot, so concurrent requests issue at most one fetch.
12. The background lane has a randomized half-to-full-interval initial phase, preventing replicas
    started together from polling the JWKS endpoint in lockstep.
13. Explicit `revoked=true`, key removal, key expiry and zero-active-key snapshots immediately make
    matching decisions unavailable after atomic publication.
14. Closing the Spring context stops the daemon lane and changes local readiness to `CLOSED`.

There is deliberately no bounded-stale outage mode. Suite-stability jobs are retryable and
non-production; accepting ambiguous revocation state would trade business correctness for test
throughput.

## Configuration

Static trust remains the default. Dynamic trust requires both the signed HTTP authority and JWKS
switches:

| Variable | Default | Bound / meaning |
| --- | ---: | --- |
| `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_ENABLED` | `false` | explicit dynamic source switch |
| `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_URI` | empty | HTTPS JWKS endpoint |
| `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_REFRESH_SECONDS` | `30` | 1..3600 seconds |
| `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_UNKNOWN_KEY_REFRESH_SECONDS` | `5` | 1..300 second global cooldown |
| `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_TIMEOUT_MS` | `3000` | 100..30000 ms |
| `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_MAXIMUM_AGE_SECONDS` | `60` | 2..86400 seconds and at least refresh + timeout |
| `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_ALLOW_INSECURE_LOOPBACK` | `false` | local tests only |

The PDP base URI, expected authority id and decision time policy remain mandatory. Dynamic mode does
not read `RG_TEST_STABILITY_JOB_AUTHORITY_KEYS_JSON`.

For multi-replica deployments, dynamic local refresh is necessary but not sufficient: replicas can
briefly hold different valid key generations. The optional database-clock exact configured cohort
gate closes submission and worker claim until every expected process reports one equivalent trust
snapshot. Its invariants, rollout behavior, configuration, and deliberately unclaimed external
inventory guarantee are specified in
[authority cohort verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-authority-cohort-verification.md).

## Capability and health truth

The authorizer descriptor adds only closed, non-secret trust facts: provider type, refresh state,
refresh interval, hard maximum age, automatic-refresh and fail-closed flags. It never publishes the
JWKS URI, ETag, key id or public material.

Two capability flags are now independently derived:

| Flag | Exact meaning |
| --- | --- |
| `dynamicSuiteStabilityAuthorityTrust` | configured provider is dynamic Ed25519 JWKS with automatic refresh |
| `suiteStabilityAuthorityTrustRefreshSlo` | dynamic provider declares interval, hard age and fail-closed refresh semantics |

`asyncSuiteStabilityJobSubmission` still reflects current local readiness. A refresh outage can
therefore leave dynamic capability configured while truthfully closing admission.

`TestSuiteStabilityAuthorityTrustHealth` is `UP` only for a fresh snapshot with at least one active
key. Its details contain provider/state, aggregate key counts, last successful refresh time,
process-local success/failure counts, stable failure family, interval and maximum age. They contain
no endpoint, ETag, key identity or key material.

## Verification evidence

Focused tests prove:

1. concurrent unknown-key rotation performs one fetch and all readers verify against one complete
   successor snapshot;
2. malformed/private replacement fails closed without partially publishing new keys;
3. old material cannot verify while refresh state is unavailable, and a later valid refresh
   recovers atomically;
4. descriptor reads perform no fetch and hard-expire a silent scheduler locally;
5. ETag `304` extends a successfully confirmed snapshot;
6. explicit revocation immediately removes verification readiness;
7. a real HTTP server observes conditional requests, redirect is not followed and non-JSON
   bootstrap is rejected;
8. the randomized background lane refreshes and lifecycle close yields `CLOSED`;
9. unsafe URI, contradictory timing, unavailable bootstrap and private material fail startup;
10. Spring assembles exactly one dynamic trust bean, one signed authorizer and one health
    contributor while preserving production/default-off isolation;
11. machine-readable descriptor vocabularies exactly match the strict Schema;
12. capability flags distinguish static signed trust from dynamic refresh/SLO semantics.

The focused dynamic-trust and adjacent authority/capability gate executes 57 tests with zero
failures, errors or skips. The full Resource Gateway `clean verify` gate executes 2660 tests with
zero failures, zero errors and 34 existing conditional skips, then successfully repackages the
executable Spring Boot JAR.

## Deliberately unclaimed guarantees

This increment does not prove that the configured JWKS endpoint itself is highly available,
non-equivocating or backed by KMS/HSM custody. It does not provision mTLS, certificate pinning,
signed-JWKS witness/CT evidence, cross-replica serving membership, global refresh convergence,
external alert routing, chaos/DR behavior or cross-region cache consistency. The endpoint URI is
deployment-owned and the JVM TLS context supplies transport identity. Those remaining controls must
not be inferred from one healthy local snapshot.
