package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * HTTPS adapter for an external current-authority policy decision point.
 *
 * <p>The adapter performs exactly one bounded request and never retries: durable worker retry
 * policy owns backoff and attempt accounting. Redirects, non-JSON responses, oversized bodies,
 * timeouts, protocol drift and trust failures all become {@code UNAVAILABLE}. Only a valid signed
 * {@code REVOKED} response is a definitive revocation; an unsigned HTTP denial can never be
 * promoted to policy truth.</p>
 */
public final class HttpTestSuiteStabilityJobAuthorizer
        implements TestSuiteStabilityJobAuthorizer {

    private static final int MAXIMUM_RESPONSE_BYTES = 64 * 1024;
    private static final String PATH = "v1/stability-job-authorizations";
    private static final String UNAVAILABLE =
            "RG.TEST.STABILITY_JOB_AUTHORITY_UNAVAILABLE";
    private static final String RESPONSE_INVALID =
            "RG.TEST.STABILITY_JOB_AUTHORITY_RESPONSE_INVALID";

    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityAuthorityTrustStore trustStore;
    private final TestSuiteStabilityAuthorityCohortGate cohortGate;
    private final Settings settings;
    private final HttpClient client;
    private final URI authorityUri;
    private final Clock clock;
    private final SecureRandom secureRandom;

    /**
     * Creates a production HTTP adapter using the JVM TLS context and a cryptographic challenge.
     *
     * @param objectMapper application JSON mapper
     * @param trustStore signed-decision verification policy
     * @param settings bounded endpoint and timeout settings
     */
    public HttpTestSuiteStabilityJobAuthorizer(
            ObjectMapper objectMapper,
            TestSuiteStabilityAuthorityTrustStore trustStore,
            Settings settings) {
        this(objectMapper, trustStore, TestSuiteStabilityAuthorityCohortGate.localOnly(),
                settings, Clock.systemUTC(), new SecureRandom(), null);
    }

    /**
     * Creates the HTTP adapter with an exact cross-replica trust-convergence gate.
     *
     * @param objectMapper application JSON mapper
     * @param trustStore signed-decision verification policy
     * @param cohortGate database-authoritative pre-request convergence guard
     * @param settings bounded endpoint and timeout settings
     */
    public HttpTestSuiteStabilityJobAuthorizer(
            ObjectMapper objectMapper,
            TestSuiteStabilityAuthorityTrustStore trustStore,
            TestSuiteStabilityAuthorityCohortGate cohortGate,
            Settings settings) {
        this(objectMapper, trustStore, cohortGate, settings,
                Clock.systemUTC(), new SecureRandom(), null);
    }

    /** Package-visible seam for deterministic protocol and timeout tests. */
    HttpTestSuiteStabilityJobAuthorizer(
            ObjectMapper objectMapper,
            TestSuiteStabilityAuthorityTrustStore trustStore,
            Settings settings,
            Clock clock,
            SecureRandom secureRandom,
            HttpClient client) {
        this(objectMapper, trustStore, TestSuiteStabilityAuthorityCohortGate.localOnly(),
                settings, clock, secureRandom, client);
    }

    HttpTestSuiteStabilityJobAuthorizer(
            ObjectMapper objectMapper,
            TestSuiteStabilityAuthorityTrustStore trustStore,
            TestSuiteStabilityAuthorityCohortGate cohortGate,
            Settings settings,
            Clock clock,
            SecureRandom secureRandom,
            HttpClient client) {
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.cohortGate = Objects.requireNonNull(cohortGate, "cohortGate");
        if (!this.trustStore.descriptor().available()) {
            throw new IllegalArgumentException(
                    "Stability authority trust store must be ready before HTTP authorization");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.client = client == null ? HttpClient.newBuilder()
                .connectTimeout(this.settings.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build() : client;
        this.authorityUri = endpoint(this.settings.baseUri());
    }

    @Override
    public Authorization reauthorize(TestSuiteStabilityJobRecord job) {
        try {
            if (!cohortGate.descriptor().available()) {
                return Authorization.unavailable(
                        "RG.TEST.STABILITY_JOB_AUTHORITY_COHORT_UNAVAILABLE");
            }
        } catch (RuntimeException unavailable) {
            return Authorization.unavailable(
                    "RG.TEST.STABILITY_JOB_AUTHORITY_COHORT_UNAVAILABLE");
        }
        Instant requestedAt = clock.instant();
        byte[] challengeBytes = new byte[32];
        secureRandom.nextBytes(challengeBytes);
        String requestId = "authz-" + UUID.randomUUID();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes);
        TestSuiteStabilityAuthorityRequest request;
        byte[] body;
        try {
            request = TestSuiteStabilityAuthorityRequest.create(
                    objectMapper, Objects.requireNonNull(job, "job"), requestId, challenge,
                    requestedAt);
            body = objectMapper.writeValueAsBytes(request);
        } catch (RuntimeException | JsonProcessingException invalid) {
            return Authorization.unavailable(
                    "RG.TEST.STABILITY_JOB_AUTHORITY_REQUEST_INVALID");
        }

        ExchangeResult exchange = exchange(request, body);
        if (exchange.response() == null) {
            return Authorization.unavailable(exchange.failureCode());
        }
        TestSuiteStabilityAuthorityResponse response = exchange.response();
        TestSuiteStabilityAuthorityTrustStore.Verification verification;
        try {
            verification = trustStore.verify(response, request, clock.instant());
        } catch (RuntimeException unavailable) {
            return Authorization.unavailable(RESPONSE_INVALID);
        }
        if (verification == null || !verification.verified()) {
            String code = verification == null || verification.failureCode() == null
                    || verification.failureCode().isBlank()
                    ? RESPONSE_INVALID : verification.failureCode();
            return Authorization.unavailable(code);
        }
        return response.decision() == TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED
                ? Authorization.authorized()
                : Authorization.revoked(response.failureCode());
    }

    @Override
    public Descriptor descriptor() {
        TestSuiteStabilityAuthorityTrustStore.Descriptor trust = trustStore.descriptor();
        TestSuiteStabilityAuthorityCohortGate.Descriptor cohort;
        try {
            cohort = cohortGate.descriptor();
        } catch (RuntimeException unavailable) {
            cohort = TestSuiteStabilityAuthorityCohortGate.Descriptor.unavailable(
                    0, 0, false);
        }
        return new Descriptor("", trust.available() && cohort.available(), "HTTPS_SIGNED_PDP",
                trust.expectedAuthorityId(), Map.ofEntries(
                Map.entry("protocolVersion", TestSuiteStabilityAuthorityRequest.SCHEMA_VERSION),
                Map.entry("responseProtocolVersion",
                        TestSuiteStabilityAuthorityResponse.SCHEMA_VERSION),
                Map.entry("signedDecisions", true),
                Map.entry("challengeBound", true),
                Map.entry("redirectsFollowed", false),
                Map.entry("automaticRetries", false),
                Map.entry("privateMaterialPresent", false),
                Map.entry("requestTimeoutMillis", settings.requestTimeout().toMillis()),
                Map.entry("trustProviderType", trust.providerType()),
                Map.entry("trustLocalAvailable", trust.available()),
                Map.entry("trustRefreshState",
                        trust.properties().getOrDefault("refreshState", "STATIC")),
                Map.entry("trustRefreshIntervalSeconds",
                        trust.properties().getOrDefault("refreshIntervalSeconds", 0)),
                Map.entry("trustMaximumSnapshotAgeSeconds",
                        trust.properties().getOrDefault("maximumSnapshotAgeSeconds", 0)),
                Map.entry("trustFailClosedOnRefreshFailure",
                        trust.properties().getOrDefault("failClosedOnRefreshFailure", true)),
                Map.entry("trustAutomaticRefresh",
                        trust.properties().getOrDefault("automaticRefresh", false)),
                Map.entry("trustCohortConfigured", cohort.configured()),
                Map.entry("trustCohortConverged", cohort.available()),
                Map.entry("trustCohortStatus", cohort.status()),
                Map.entry("trustCohortExpectedReplicaCount", cohort.expectedReplicaCount()),
                Map.entry("trustCohortLiveReplicaCount", cohort.liveReplicaCount()),
                Map.entry("trustCohortHealthyReplicaCount", cohort.healthyReplicaCount()),
                Map.entry("trustCohortDistinctSnapshotCount",
                        cohort.distinctSnapshotCount()),
                Map.entry("trustCohortDistinctServingInventoryGenerationCount",
                        cohort.distinctServingInventoryGenerationCount()),
                Map.entry("trustCohortLeaseDurationSeconds",
                        cohort.leaseDurationSeconds()),
                Map.entry("trustCohortDatabaseAuthority", cohort.databaseAuthority()),
                Map.entry("trustCohortExactConfiguredInventory",
                        cohort.exactConfiguredInventory()),
                Map.entry("trustCohortExternallyAttestedInventory",
                        cohort.externallyAttestedInventory()),
                Map.entry("trustCohortDynamicallyRefreshedInventory",
                        cohort.dynamicallyRefreshedInventory()),
                Map.entry("trustCohortWitnessedInventoryPublications",
                        cohort.witnessedInventoryPublications())));
    }

    private ExchangeResult exchange(
            TestSuiteStabilityAuthorityRequest request, byte[] body) {
        HttpRequest httpRequest = HttpRequest.newBuilder(authorityUri)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(settings.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", request.requestId())
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                return failed(UNAVAILABLE);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) {
                response.body().close();
                return failed(RESPONSE_INVALID);
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length")
                    .orElse(-1);
            if (declaredLength > MAXIMUM_RESPONSE_BYTES) {
                response.body().close();
                return failed(RESPONSE_INVALID);
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAXIMUM_RESPONSE_BYTES) {
                return failed(RESPONSE_INVALID);
            }
            return new ExchangeResult(objectMapper.readerFor(
                    TestSuiteStabilityAuthorityResponse.class).readValue(bytes), "");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(UNAVAILABLE);
        } catch (JsonProcessingException invalid) {
            return failed(RESPONSE_INVALID);
        } catch (IOException unavailable) {
            return failed(UNAVAILABLE);
        } catch (RuntimeException invalid) {
            return failed(RESPONSE_INVALID);
        }
    }

    private static ExchangeResult failed(String code) {
        return new ExchangeResult(null, code);
    }

    private record ExchangeResult(
            TestSuiteStabilityAuthorityResponse response,
            String failureCode) {
    }

    private static URI endpoint(URI baseUri) {
        String base = baseUri.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + PATH);
    }

    /**
     * Strict HTTP PDP settings.
     *
     * @param baseUri authority service base URI without query or fragment
     * @param requestTimeout connect and end-to-end request timeout
     * @param allowInsecureLoopback explicit local-test-only HTTP escape hatch
     */
    public record Settings(
            URI baseUri,
            Duration requestTimeout,
            boolean allowInsecureLoopback) {

        /** @return normalized settings after HTTPS and timeout validation */
        public Settings validated() {
            validateUri(baseUri, allowInsecureLoopback);
            Duration timeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
            if (timeout.compareTo(Duration.ofMillis(100)) < 0
                    || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException(
                        "Stability authority timeout must be between 100ms and 30s");
            }
            return new Settings(baseUri, timeout, allowInsecureLoopback);
        }

        private static void validateUri(URI uri, boolean allowInsecureLoopback) {
            if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "A valid stability authority base URI is required");
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1");
            if (!allowInsecureLoopback || !loopback
                    || !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Stability authority must use HTTPS");
            }
        }
    }
}
