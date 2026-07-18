# Stage 5 suite-stability current-authority verification

> Current-state note (2026-07-19): the static-trust increment documented below is now complemented
> by the built-in atomic background JWKS refresh path in
> [suite-stability dynamic authority trust verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-dynamic-authority-trust-verification.md).

## Scope

This increment closes the product implementation gap between the durable stability worker and
current enterprise IAM/delegation state. It adds an opt-in HTTPS policy-decision-point adapter,
strict versioned request/response types, bounded static Ed25519 trust, startup validation, honest
capability projection and negative protocol tests.

It does not make submission-time identity perpetual authority. It also does not add a bearer token
to a durable job. The background worker asks a current external authority to decide the exact
credential-free job immediately before any engine execution.

## Authority boundary

The private endpoint is:

```text
POST <base-uri>/v1/stability-job-authorizations
```

`bloge.testSuiteStabilityAuthorityRequest.v1` contains:

1. a unique request id and fresh 256-bit base64url challenge;
2. request time and the fixed `EXECUTE_SUITE_STABILITY_JOB` action;
3. exact job id, submitted request fingerprint and immutable suite revision/fingerprint;
4. frozen classification and absolute job deadline;
5. tenant/organization/project/environment/region, actor/delegation, purpose, sorted governance
   groups, clearance and delegation grant id;
6. independent principal and whole-request SHA-256 fingerprints.

It deliberately excludes submission correlation id, bearer/API credentials, headers, execution
metadata, fixtures, graph context, replay/business payloads, source run ids, node/edge results and
lease fences. The JSON Schema and Java tests compare the exact serialized property inventory and
reject these fields.

`bloge.testSuiteStabilityAuthorityResponse.v1` echoes request id, challenge, job id, request
fingerprint and principal fingerprint. It adds only `AUTHORIZED` or `REVOKED`, a stable failure code
for revocation, authority/policy/decision identities, issue/expiry times, a canonical material
fingerprint and a detached Ed25519 signature over that fingerprint.

## Trust invariants

The worker consumes a decision only when all of the following are true:

1. strict JSON decoding found no duplicate, unknown or trailing fields;
2. every echoed request binding exactly matches the locally created request;
3. request principal and request fingerprints recompute exactly;
4. response material fingerprint recomputes exactly;
5. the response authority equals the deployment-owned expected authority;
6. issue time is within configured clock skew of request/observation time;
7. total decision lifetime is bounded and enough validity remains;
8. the selected Ed25519 public key is enabled, not revoked, and active at issue time, local
   verification time and through response expiry;
9. the detached signature verifies over the exact material fingerprint.

Only then does `AUTHORIZED` start the engine or `REVOKED` become a definitive current-policy
failure. HTTP 4xx/5xx, redirects, timeout/interruption, transport error, non-JSON, empty/oversized
response, malformed/duplicate/unknown JSON, challenge replay, binding drift, stale/future decision,
unknown/expired/revoked key, fingerprint mismatch, bad signature or trust-store exception becomes
`UNAVAILABLE`. The worker's existing durable retry policy owns retries; the HTTP adapter performs no
automatic retry and never creates an unaccounted retry loop.

## Configuration and lifecycle

Both `test` and `staging` profiles map these environment variables:

| Variable | Bound | Purpose |
| --- | ---: | --- |
| `RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_ENABLED` | boolean | opt in; default false |
| `RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_BASE_URI` | HTTPS URI | PDP base URI |
| `RG_TEST_STABILITY_JOB_AUTHORITY_ID` | 1..255 chars | exact response authority |
| `RG_TEST_STABILITY_JOB_AUTHORITY_TIMEOUT_MS` | 100..30000 ms | connect/request timeout |
| `RG_TEST_STABILITY_JOB_AUTHORITY_MAX_LIFETIME_SECONDS` | 1..300 s | maximum signed lifetime |
| `RG_TEST_STABILITY_JOB_AUTHORITY_CLOCK_SKEW_SECONDS` | 0..300 s | tolerated clock skew |
| `RG_TEST_STABILITY_JOB_AUTHORITY_MIN_REMAINING_MS` | 0..<lifetime | minimum remaining validity |
| `RG_TEST_STABILITY_JOB_AUTHORITY_KEYS_JSON` | 1..64 keys | public Ed25519 key ring |
| `RG_TEST_STABILITY_JOB_AUTHORITY_ALLOW_INSECURE_LOOPBACK` | boolean | local-test-only HTTP escape hatch |

The worker still requires exactly one `TestSuiteStabilityJobAuthorizer`. It now also requires that
provider's key-free descriptor to report ready. Missing trust, unsafe URI, zero/multiple providers,
an undeclared custom provider or invalid time/key configuration fails Spring startup. Production
profiles contain none of the test runtime, authority trust or HTTP adapter beans.

For planned rotation, publish the new public key to every Resource Gateway replica before signing
with it, retain the old key beyond the maximum possible live decision, then remove it. For
compromise, set `revoked=true` fleet-wide immediately; signed decisions from that key fail closed.
Static JSON does not prove fleet rollout convergence. A deployment requiring online rotation should
replace `TestSuiteStabilityAuthorityTrustStore` with a dynamic trust adapter and supply its own fleet
rollout/readiness controls.

## Capability truth

The unauthenticated capability projection now includes the key-free authorizer descriptor and four
authority protocol object versions. The flags mean:

| Flag | Meaning |
| --- | --- |
| `asyncSuiteStabilityJobSubmission` | worker lifecycle and one ready authority provider are assembled |
| `suiteStabilityCurrentAuthorityRevalidation` | every claimed job crosses the ready current-authority seam |
| `signedChallengeBoundSuiteStabilityAuthority` | the active provider declares signed, challenge-bound decisions |

Descriptors use a closed non-secret property vocabulary. URI, credentials, key material and
arbitrary extension values cannot be projected through this capability channel.

## Verification evidence

Focused tests cover:

1. minimal request construction, deterministic sorted groups and nested fingerprint tamper;
2. authorized and revoked signatures;
3. challenge/binding/authority/key/signature/time/key-lifecycle failures;
4. strict public-key parsing, duplicate/unknown/private-key-shaped fields and algorithm drift;
5. real HTTP authorized/revoked exchange, unsigned 403, redirect, timeout, non-JSON,
   duplicate/unknown JSON and oversized response;
6. HTTPS-only settings with explicit local-loopback exception;
7. Spring default-off, production isolation, complete built-in assembly, missing trust and provider
   ambiguity;
8. exact capability versions and signed/challenge-bound truth;
9. strict machine-readable Schema property parity and forbidden business/security fields;
10. runtime trust-readiness loss closes fresh admission and capability truth without a network
    probe, while retained exact replay bypasses mutable readiness and remains idempotent;
11. provider enumeration/descriptor failure and provider ambiguity converge to unavailable rather
    than escaping through the capability endpoint.

The combined focused authority, service, profile and capability gate executes 65 tests with zero
failures, errors or skips before the full project gate. Of these, 35 directly cover this authority
increment; the remainder protect adjacent job-service and integration-projection behavior from
regression.

The final Resource Gateway `clean verify` executes 2650 tests with zero failures or errors and 34
existing conditional skips. It also rebuilds the executable Spring Boot JAR successfully.

## Deliberately unclaimed guarantees

This increment does not provide dynamic JWKS polling, certificate-transparency/witness proof,
fleet-membership completeness, external authority HA certification, non-H2 queue certification,
soak/chaos/DR evidence, hard process cancellation, physical attempt isolation, poison-job repair,
legal hold/backup erasure or external WORM evidence. JVM TLS configuration may provide mTLS, but this
repository does not provision or rotate client certificates. Those remain separate industrial
readiness work, not properties inferred from one successful signed decision.
