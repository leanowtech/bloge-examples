package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;

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
 * Strict HTTPS implementation of the external {@link TestSecretAuthority} boundary.
 *
 * <p>Each resolution uses a fresh cryptographic challenge and exactly one bounded request. The
 * adapter never follows redirects or retries, accepts only strict JSON with a bounded body, and
 * treats every unsigned HTTP denial as infrastructure failure. Only an exact short-lived Ed25519
 * response verified by {@link TestSecretAuthorityTrustStore} can release values or establish a
 * definitive policy denial.</p>
 */
public final class HttpTestSecretAuthority implements TestSecretAuthority {

    private static final int MAXIMUM_REQUEST_BYTES = 256 * 1024;
    private static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final String PATH = "v1/test-secret-resolutions";

    private final ObjectMapper objectMapper;
    private final TestSecretAuthorityTrustStore trustStore;
    private final TestSecretAuthorityTrustCohortGate cohortGate;
    private final TestSecretAuthorityServingInventoryAuthority servingInventoryAuthority;
    private final Settings settings;
    private final HttpClient client;
    private final URI authorityUri;
    private final Clock clock;
    private final SecureRandom secureRandom;

    /**
     * Creates a production adapter using the JVM TLS context and cryptographic random source.
     *
     * @param objectMapper application JSON mapper
     * @param trustStore signed-response verification policy
     * @param settings bounded endpoint and timeout settings
     */
    public HttpTestSecretAuthority(
            ObjectMapper objectMapper,
            TestSecretAuthorityTrustStore trustStore,
            Settings settings) {
        this(objectMapper, trustStore, TestSecretAuthorityTrustCohortGate.localOnly(),
                TestSecretAuthorityServingInventoryAuthority.localOnly(), settings,
                Clock.systemUTC(),
                new SecureRandom(), null);
    }

    /**
     * Creates an adapter protected by exact cross-replica trust-generation convergence.
     *
     * @param objectMapper application JSON mapper
     * @param trustStore signed-response verification policy
     * @param cohortGate non-network database-backed convergence gate
     * @param settings bounded endpoint and timeout settings
     */
    public HttpTestSecretAuthority(
            ObjectMapper objectMapper,
            TestSecretAuthorityTrustStore trustStore,
            TestSecretAuthorityTrustCohortGate cohortGate,
            Settings settings) {
        this(objectMapper, trustStore, cohortGate,
                TestSecretAuthorityServingInventoryAuthority.localOnly(), settings,
                Clock.systemUTC(),
                new SecureRandom(), null);
    }

    /**
     * Creates an adapter protected by exact cohort and serving-inventory convergence.
     *
     * @param objectMapper application JSON mapper
     * @param trustStore signed-response verification policy
     * @param cohortGate non-network database-backed convergence gate
     * @param servingInventoryAuthority current local signed-inventory authority
     * @param settings bounded endpoint and timeout settings
     */
    public HttpTestSecretAuthority(
            ObjectMapper objectMapper,
            TestSecretAuthorityTrustStore trustStore,
            TestSecretAuthorityTrustCohortGate cohortGate,
            TestSecretAuthorityServingInventoryAuthority servingInventoryAuthority,
            Settings settings) {
        this(objectMapper, trustStore, cohortGate, servingInventoryAuthority, settings,
                Clock.systemUTC(), new SecureRandom(), null);
    }

    /** Package-visible seam for deterministic transport and timeout tests. */
    HttpTestSecretAuthority(
            ObjectMapper objectMapper,
            TestSecretAuthorityTrustStore trustStore,
            Settings settings,
            Clock clock,
            SecureRandom secureRandom,
            HttpClient client) {
        this(objectMapper, trustStore, TestSecretAuthorityTrustCohortGate.localOnly(),
                TestSecretAuthorityServingInventoryAuthority.localOnly(), settings, clock,
                secureRandom,
                client);
    }

    /** Package-visible seam including the convergence gate for deterministic boundary tests. */
    HttpTestSecretAuthority(
            ObjectMapper objectMapper,
            TestSecretAuthorityTrustStore trustStore,
            TestSecretAuthorityTrustCohortGate cohortGate,
            Settings settings,
            Clock clock,
            SecureRandom secureRandom,
            HttpClient client) {
        this(objectMapper, trustStore, cohortGate,
                TestSecretAuthorityServingInventoryAuthority.localOnly(), settings, clock,
                secureRandom, client);
    }

    /** Package-visible complete seam for deterministic inventory-gate boundary tests. */
    HttpTestSecretAuthority(
            ObjectMapper objectMapper,
            TestSecretAuthorityTrustStore trustStore,
            TestSecretAuthorityTrustCohortGate cohortGate,
            TestSecretAuthorityServingInventoryAuthority servingInventoryAuthority,
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
        this.servingInventoryAuthority = Objects.requireNonNull(
                servingInventoryAuthority, "servingInventoryAuthority");
        if (!this.trustStore.descriptor().available()) {
            throw new IllegalArgumentException(
                    "Test-secret authority trust store must be ready before HTTP resolution");
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
    public ResolvedTestSecrets resolve(TestSecretResolutionContext context) {
        requireReady();
        Instant requestedAt = clock.instant();
        byte[] challengeBytes = new byte[32];
        secureRandom.nextBytes(challengeBytes);
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(challengeBytes);
        TestSecretAuthorityRequest request;
        byte[] body;
        try {
            request = TestSecretAuthorityRequest.create(objectMapper,
                    Objects.requireNonNull(context, "context"),
                    "secret-" + UUID.randomUUID(), challenge, requestedAt);
            body = objectMapper.writeValueAsBytes(request);
            if (body.length == 0 || body.length > MAXIMUM_REQUEST_BYTES) {
                throw new IllegalArgumentException("Request exceeds the private protocol bound");
            }
        } catch (RuntimeException | JsonProcessingException invalid) {
            throw new ResolutionException(Reason.INVALID_RESPONSE);
        }

        ExchangeResult exchange = exchange(request, body);
        if (exchange.response() == null) {
            throw new ResolutionException(exchange.reason());
        }
        TestSecretAuthorityResponse response = exchange.response();
        TestSecretAuthorityTrustStore.Verification verification;
        try {
            verification = trustStore.verify(response, request, clock.instant());
        } catch (RuntimeException unavailable) {
            throw new ResolutionException(Reason.UNAVAILABLE);
        }
        if (verification == null || !verification.verified()) {
            Reason reason = verification != null
                    && verification.status()
                    == TestSecretAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE
                    ? Reason.UNAVAILABLE : Reason.INVALID_RESPONSE;
            throw new ResolutionException(reason);
        }
        requireReady();
        if (response.decision() == TestSecretAuthorityResponse.Decision.DENIED) {
            throw new ResolutionException(Reason.DENIED);
        }
        return response.toResolvedSecrets();
    }

    @Override
    public Descriptor descriptor() {
        TestSecretAuthorityTrustStore.Descriptor trust;
        try {
            trust = trustStore.descriptor();
        } catch (RuntimeException unavailable) {
            trust = TestSecretAuthorityTrustStore.unavailable().descriptor();
        }
        TestSecretAuthorityTrustCohortGate.Descriptor cohort;
        try {
            cohort = cohortGate.descriptor();
        } catch (RuntimeException unavailable) {
            cohort = TestSecretAuthorityTrustCohortGate.Descriptor.unavailable(0, 0);
        }
        TestSecretAuthorityServingInventoryAuthority.Descriptor inventory;
        try {
            inventory = servingInventoryAuthority.descriptor();
        } catch (RuntimeException unavailable) {
            inventory = new TestSecretAuthorityServingInventoryAuthority.Descriptor(
                    TestSecretAuthorityServingInventoryAuthority.Descriptor.SCHEMA_VERSION,
                    true, true, false, "UNAVAILABLE", 1, 1, Map.of());
        }
        return new Descriptor("", trust.available() && cohort.available() && inventory.available(),
                "HTTPS_SIGNED_TEST_SECRET_AUTHORITY",
                trust.expectedAuthorityId(), Map.ofEntries(
                Map.entry("protocolVersion", TestSecretAuthorityRequest.SCHEMA_VERSION),
                Map.entry("responseProtocolVersion",
                        TestSecretAuthorityResponse.SCHEMA_VERSION),
                Map.entry("signedResponses", true),
                Map.entry("challengeBound", true),
                Map.entry("credentialFree", true),
                Map.entry("redirectsFollowed", false),
                Map.entry("automaticRetries", false),
                Map.entry("privateMaterialPresent", false),
                Map.entry("requestTimeoutMillis", settings.requestTimeout().toMillis()),
                Map.entry("trustProviderType", trust.providerType()),
                Map.entry("trustAvailable", trust.available()),
                Map.entry("trustRefreshState",
                        trust.properties().getOrDefault("refreshState", "STATIC")),
                Map.entry("trustAutomaticRefresh",
                        trust.properties().getOrDefault("automaticRefresh", false)),
                Map.entry("trustRefreshIntervalSeconds",
                        trust.properties().getOrDefault("refreshIntervalSeconds", 0L)),
                Map.entry("trustMaximumSnapshotAgeSeconds",
                        trust.properties().getOrDefault("maximumSnapshotAgeSeconds", 0L)),
                Map.entry("trustConditionalRequests",
                        trust.properties().getOrDefault("conditionalRequests", false)),
                Map.entry("trustFailClosedOnRefreshFailure",
                        trust.properties().getOrDefault("failClosedOnRefreshFailure", true)),
                Map.entry("trustCohortConfigured", cohort.configured()),
                Map.entry("trustCohortAvailable", cohort.available()),
                Map.entry("trustCohortStatus", cohort.status()),
                Map.entry("trustCohortExpectedReplicaCount", cohort.expectedReplicaCount()),
                Map.entry("trustCohortLiveReplicaCount", cohort.liveReplicaCount()),
                Map.entry("trustCohortHealthyReplicaCount", cohort.healthyReplicaCount()),
                Map.entry("trustCohortDistinctGenerationCount",
                        cohort.distinctTrustGenerationCount()),
                Map.entry("trustCohortDistinctInventoryGenerationCount",
                        cohort.distinctServingInventoryGenerationCount()),
                Map.entry("trustCohortLeaseDurationSeconds",
                        cohort.leaseDurationSeconds()),
                Map.entry("trustCohortDatabaseAuthority", cohort.databaseAuthority()),
                Map.entry("trustCohortExactConfiguredInventory",
                        cohort.exactConfiguredInventory()),
                Map.entry("trustCohortExternallyAttestedInventory",
                        cohort.externallyAttestedInventory()),
                Map.entry("servingInventorySourceType",
                        inventory.properties().getOrDefault("sourceType", "LOCAL_CONFIGURED")),
                Map.entry("servingInventoryAvailable", inventory.available()),
                Map.entry("servingInventoryStatus", inventory.status()),
                Map.entry("servingInventoryExternallyAttested",
                        inventory.externallyAttested()),
                Map.entry("servingInventoryExpectedReplicaCount",
                        inventory.expectedReplicaCount()),
                Map.entry("servingInventoryRevision", inventory.revision()),
                Map.entry("servingInventoryAutomaticRefresh",
                        inventory.properties().getOrDefault("automaticRefresh", false)),
                Map.entry("servingInventoryRefreshState",
                        inventory.properties().getOrDefault("refreshState", "STATIC")),
                Map.entry("servingInventoryRefreshIntervalSeconds",
                        inventory.properties().getOrDefault("refreshIntervalSeconds", 0L)),
                Map.entry("servingInventoryMaximumSnapshotAgeSeconds",
                        inventory.properties().getOrDefault("maximumSnapshotAgeSeconds", 0L)),
                Map.entry("servingInventoryConditionalRequests",
                        inventory.properties().getOrDefault("conditionalRequests", false)),
                Map.entry("servingInventoryFailClosedOnRefreshFailure",
                        inventory.properties().getOrDefault(
                                "failClosedOnRefreshFailure", true)),
                Map.entry("servingInventorySignedRevocation",
                        inventory.properties().getOrDefault("signedRevocation", false)),
                Map.entry("servingInventoryWitnessedPublications",
                        inventory.properties().getOrDefault("witnessedPublications", false)),
                Map.entry("servingInventoryWitnessSignatureThreshold",
                        inventory.properties().getOrDefault("witnessSignatureThreshold", 0)),
                Map.entry("servingInventoryDurablePublicationFloor",
                        inventory.properties().getOrDefault("durablePublicationFloor", false)),
                Map.entry("servingInventoryManagedTrustRootRefresh",
                        inventory.properties().getOrDefault("managedTrustRootRefresh", false)),
                Map.entry("servingInventoryAtomicDualTrustRootPublication",
                        inventory.properties().getOrDefault(
                                "atomicDualTrustRootPublication", false)),
                Map.entry("servingInventoryDurableTrustRootFloor",
                        inventory.properties().getOrDefault("durableTrustRootFloor", false)),
                Map.entry("servingInventoryExternallyAnchoredTrustRootFloor",
                        inventory.properties().getOrDefault(
                                "externallyAnchoredTrustRootFloor", false))));
    }

    private void requireReady() {
        try {
            if (!trustStore.descriptor().available() || !cohortGate.descriptor().available()
                    || !servingInventoryAuthority.observation().available()) {
                throw new ResolutionException(Reason.UNAVAILABLE);
            }
        } catch (ResolutionException unavailable) {
            throw unavailable;
        } catch (RuntimeException unavailable) {
            throw new ResolutionException(Reason.UNAVAILABLE);
        }
    }

    private ExchangeResult exchange(TestSecretAuthorityRequest request, byte[] body) {
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
                return failed(Reason.UNAVAILABLE);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) {
                response.body().close();
                return failed(Reason.INVALID_RESPONSE);
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length")
                    .orElse(-1);
            if (declaredLength > MAXIMUM_RESPONSE_BYTES) {
                response.body().close();
                return failed(Reason.INVALID_RESPONSE);
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAXIMUM_RESPONSE_BYTES) {
                return failed(Reason.INVALID_RESPONSE);
            }
            return new ExchangeResult(objectMapper.readerFor(
                    TestSecretAuthorityResponse.class).readValue(bytes), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(Reason.UNAVAILABLE);
        } catch (JsonProcessingException invalid) {
            return failed(Reason.INVALID_RESPONSE);
        } catch (IOException unavailable) {
            return failed(Reason.UNAVAILABLE);
        } catch (RuntimeException invalid) {
            return failed(Reason.INVALID_RESPONSE);
        }
    }

    private static ExchangeResult failed(Reason reason) {
        return new ExchangeResult(null, reason);
    }

    private record ExchangeResult(TestSecretAuthorityResponse response, Reason reason) {
    }

    private static URI endpoint(URI baseUri) {
        String base = baseUri.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + PATH);
    }

    /**
     * Strict HTTP authority settings.
     *
     * @param baseUri authority service base URI without user-info, query or fragment
     * @param requestTimeout connect and end-to-end request timeout
     * @param allowInsecureLoopback explicit local-test-only HTTP escape hatch
     */
    public record Settings(
            URI baseUri,
            Duration requestTimeout,
            boolean allowInsecureLoopback) {

        /** @return normalized settings after HTTPS, URI and timeout validation */
        public Settings validated() {
            validateUri(baseUri, allowInsecureLoopback);
            Duration timeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
            if (timeout.compareTo(Duration.ofMillis(100)) < 0
                    || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException(
                        "Test-secret authority timeout must be between 100ms and 30s");
            }
            return new Settings(baseUri, timeout, allowInsecureLoopback);
        }

        private static void validateUri(URI uri, boolean allowInsecureLoopback) {
            if (uri == null || !uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.normalize().equals(uri)) {
                throw new IllegalArgumentException(
                        "A valid test-secret authority base URI is required");
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1");
            if (!allowInsecureLoopback || !loopback
                    || !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Test-secret authority must use HTTPS");
            }
        }
    }
}
