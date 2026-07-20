package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException;

/**
 * Challenge-bound Byzantine-quorum HTTP sequence anchor.
 *
 * <p>Every configured notary receives the same fresh request concurrently. Responses are accepted
 * only with exact HTTP/media/protocol semantics, strict JSON, an echoed request fingerprint, a
 * short current validity window, exact configured authority/failure-domain identity, and a valid
 * Ed25519 signature from independently configured public keys. A threshold follows the
 * {@code 3f+1 / 2f+1} fault model. Any authenticated conflict is safety-significant and rejects the
 * candidate even when another quorum claims acceptance; unavailable or malformed minority
 * responses may be tolerated.</p>
 *
 * <p>Health and capability reads are process-local and never contact a notary. Response bodies,
 * endpoint identities, challenges, stream ids, fingerprints, authority ids, and key ids are never
 * included in exceptions or aggregate state.</p>
 */
public final class HttpTestSuiteStabilityExternalSequenceAnchor
        implements TestSuiteStabilityExternalSequenceAnchor {

    /** Exact request and response media type. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.suite-stability-external-sequence-checkpoint.v1+json";
    /** Explicit wire-version response header. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-External-Sequence-Checkpoint-Protocol";

    private static final int MAXIMUM_RESPONSE_BYTES = 128 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Set<String> ENDPOINT_FIELDS =
            Set.of("authorityId", "failureDomain", "uri");

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final String trustDomain;
    private final String anchorSetId;
    private final int signatureThreshold;
    private final int maximumFaults;
    private final ExternalSequenceAnchorReceiptTrustStore trustStore;
    private final List<Endpoint> endpoints;
    private final Set<String> endpointAuthorityIds;
    private final Settings settings;
    private final HttpClient httpClient;
    private volatile RuntimeState state = RuntimeState.initial();

    /**
     * Strictly parses independent public keys and notary endpoints.
     *
     * @param objectMapper canonical JSON baseline
     * @param trustDomain expected notary trust domain
     * @param anchorSetId stable notary-set identity
     * @param signatureThreshold required accepted receipt quorum
     * @param maximumFaults declared Byzantine fault bound
     * @param authorityKeysJson bounded public Ed25519 keys
     * @param endpointsJson one endpoint and failure domain per authority
     * @param settings transport and receipt freshness policy
     * @return configured external sequence anchor
     */
    public static HttpTestSuiteStabilityExternalSequenceAnchor fromJson(
            ObjectMapper objectMapper,
            String trustDomain,
            String anchorSetId,
            int signatureThreshold,
            int maximumFaults,
            String authorityKeysJson,
            String endpointsJson,
            Settings settings) {
        try {
            ObjectMapper strict = strict(Objects.requireNonNull(objectMapper, "objectMapper"));
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys =
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                            strict, authorityKeysJson);
            return new HttpTestSuiteStabilityExternalSequenceAnchor(
                    strict, Clock.systemUTC(), new SecureRandom(), trustDomain, anchorSetId,
                    signatureThreshold, maximumFaults, keys,
                    parseEndpoints(strict, endpointsJson), settings, defaultClient());
        } catch (RuntimeException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "External sequence-anchor configuration is invalid", invalid);
        }
    }

    HttpTestSuiteStabilityExternalSequenceAnchor(
            ObjectMapper objectMapper,
            Clock clock,
            SecureRandom secureRandom,
            String trustDomain,
            String anchorSetId,
            int signatureThreshold,
            int maximumFaults,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> authorityKeys,
            List<Endpoint> endpoints,
            Settings settings,
            HttpClient httpClient) {
        this(objectMapper, clock, secureRandom, trustDomain, anchorSetId,
                signatureThreshold, maximumFaults,
                new StaticExternalSequenceAnchorReceiptTrustStore(clock, authorityKeys),
                endpoints, settings, httpClient);
    }

    HttpTestSuiteStabilityExternalSequenceAnchor(
            ObjectMapper objectMapper,
            Clock clock,
            SecureRandom secureRandom,
            String trustDomain,
            String anchorSetId,
            int signatureThreshold,
            int maximumFaults,
            ExternalSequenceAnchorReceiptTrustStore trustStore,
            List<Endpoint> endpoints,
            Settings settings,
            HttpClient httpClient) {
        this.objectMapper = strict(Objects.requireNonNull(objectMapper, "objectMapper"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.trustDomain = normalized(trustDomain);
        this.anchorSetId = normalized(anchorSetId);
        this.signatureThreshold = signatureThreshold;
        this.maximumFaults = maximumFaults;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoints = validateEndpoints(endpoints, this.settings.allowInsecureLoopback());
        this.endpointAuthorityIds = this.endpoints.stream()
                .map(Endpoint::authorityId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        validatePolicy();
    }

    /**
     * Creates an anchor backed by an already bootstrapped atomic managed trust store.
     *
     * @param objectMapper canonical JSON baseline
     * @param trustDomain exact receipt signer trust domain
     * @param anchorSetId stable notary-set identity
     * @param signatureThreshold required accepted receipt quorum
     * @param maximumFaults declared Byzantine fault bound
     * @param trustStore managed or static atomic receipt trust snapshot
     * @param endpointsJson one endpoint and failure domain per authority
     * @param settings transport and receipt freshness policy
     * @return configured external sequence anchor
     */
    public static HttpTestSuiteStabilityExternalSequenceAnchor fromTrustStore(
            ObjectMapper objectMapper,
            String trustDomain,
            String anchorSetId,
            int signatureThreshold,
            int maximumFaults,
            ExternalSequenceAnchorReceiptTrustStore trustStore,
            String endpointsJson,
            Settings settings) {
        try {
            ObjectMapper strict = strict(Objects.requireNonNull(objectMapper, "objectMapper"));
            return new HttpTestSuiteStabilityExternalSequenceAnchor(
                    strict, Clock.systemUTC(), new SecureRandom(), trustDomain, anchorSetId,
                    signatureThreshold, maximumFaults, trustStore,
                    parseEndpoints(strict, endpointsJson), settings, defaultClient());
        } catch (RuntimeException invalid) {
            try {
                Objects.requireNonNull(trustStore, "trustStore").close();
            } catch (RuntimeException ignored) {
                // Preserve the original bounded configuration failure.
            }
            throw invalid;
        } catch (Exception invalid) {
            try {
                Objects.requireNonNull(trustStore, "trustStore").close();
            } catch (RuntimeException ignored) {
                // Preserve the original bounded configuration failure.
            }
            throw new IllegalArgumentException(
                    "External sequence-anchor configuration is invalid", invalid);
        }
    }

    /**
     * Sends one fresh challenge to every notary and requires an accepted Byzantine quorum.
     *
     * <p>The method is synchronized so publication and trust-root streams cannot race state
     * counters or create unnecessary concurrent safety decisions inside one process. HTTP requests
     * themselves are dispatched concurrently.</p>
     */
    @Override
    public synchronized void accept(Head head) {
        Objects.requireNonNull(head, "head");
        ExternalSequenceAnchorReceiptTrustStore.Descriptor trust = trustStore.descriptor();
        if (!trust.available() || trust.activeAuthorityCount() < signatureThreshold
                || !trustStore.coversAuthorities(endpointAuthorityIds)) {
            state = state.failed();
            throw new ExternalAnchorException(ExternalAnchorException.Reason.UNAVAILABLE);
        }
        Instant requestedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String challenge = challenge();
        TestSuiteStabilityExternalSequenceCheckpointRequest request =
                TestSuiteStabilityExternalSequenceCheckpointRequest.create(
                        objectMapper, trustDomain, anchorSetId, head, challenge,
                        requestedAt, requestedAt.plus(settings.maximumReceiptLifetime()));
        byte[] body = write(request);
        List<CompletableFuture<Observation>> pending = endpoints.stream()
                .map(endpoint -> observeAsync(endpoint, request, body))
                .toList();
        Set<String> acceptedAuthorities = new HashSet<>();
        boolean authenticatedConflict = false;
        for (CompletableFuture<Observation> result : pending) {
            try {
                Observation observed = result.join();
                if (observed.decision()
                        == TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.CONFLICT) {
                    authenticatedConflict = true;
                } else {
                    acceptedAuthorities.add(observed.authorityId());
                }
            } catch (CompletionException unavailable) {
                // A malformed or unavailable minority is tolerated only by the configured quorum.
            }
        }
        Instant completedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (authenticatedConflict) {
            state = state.conflict();
            throw new ExternalAnchorException(
                    ExternalAnchorException.Reason.AUTHENTICATED_CONFLICT);
        }
        if (acceptedAuthorities.size() < signatureThreshold) {
            state = state.failed();
            throw new ExternalAnchorException(
                    ExternalAnchorException.Reason.QUORUM_NOT_MET);
        }
        state = state.succeeded(completedAt);
    }

    /** Returns static aggregate capability facts without remote I/O. */
    @Override
    public Descriptor descriptor() {
        ExternalSequenceAnchorReceiptTrustStore.Descriptor trust = trustStore.descriptor();
        boolean available = trust.available()
                && trust.activeAuthorityCount() >= signatureThreshold
                && trustStore.coversAuthorities(endpointAuthorityIds);
        return new Descriptor(Descriptor.SCHEMA_VERSION, available, true, true,
                available && maximumFaults > 0, endpoints.size(), signatureThreshold, maximumFaults,
                endpoints.size(), Map.of(
                "sourceType", trust.managedPublication()
                        ? "HTTPS_SIGNED_MULTI_NOTARY_MANAGED_TRUST"
                        : "HTTPS_SIGNED_MULTI_NOTARY_STATIC_TRUST",
                "externalFirstCommit", true,
                "authenticatedConflictFatal", true,
                "concurrentNotaryRequests", true,
                "managedTrustPublication", trust.managedPublication(),
                "restartFreeNotaryKeyRotation", trust.restartFreeRotation(),
                "durableTrustPublicationFloor", trust.durableFloor()));
    }

    /** Returns aggregate process-local operation state without remote I/O. */
    @Override
    public Snapshot snapshot() {
        RuntimeState observed = state;
        boolean trustAvailable = trustStore.snapshot().available();
        return new Snapshot(Snapshot.SCHEMA_VERSION,
                observed.available() && trustAvailable,
                trustAvailable ? observed.status() : "TRUST_UNAVAILABLE",
                observed.lastSuccessfulAnchorAt(), observed.successCount(),
                observed.failureCount(), observed.conflictCount(), endpoints.size(),
                signatureThreshold, maximumFaults, endpoints.size());
    }

    /** Returns aggregate local receipt-trust refresh state without remote I/O. */
    @Override
    public ExternalSequenceAnchorReceiptTrustStore.Snapshot trustSnapshot() {
        return trustStore.snapshot();
    }

    /** Returns aggregate bootstrap-root capability without remote I/O. */
    @Override
    public ExternalSequenceAnchorBootstrapRootTrustStore.Descriptor
            bootstrapRootDescriptor() {
        return trustStore.bootstrapRootDescriptor();
    }

    /** Returns aggregate bootstrap-root chain state without remote I/O. */
    @Override
    public ExternalSequenceAnchorBootstrapRootTrustStore.Snapshot bootstrapRootSnapshot() {
        return trustStore.bootstrapRootSnapshot();
    }

    private CompletableFuture<Observation> observeAsync(
            Endpoint endpoint,
            TestSuiteStabilityExternalSequenceCheckpointRequest request,
            byte[] body) {
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint.uri())
                .timeout(settings.requestTimeout())
                .header("Accept", MEDIA_TYPE)
                .header("Content-Type", MEDIA_TYPE)
                .header(PROTOCOL_HEADER,
                        TestSuiteStabilityExternalSequenceCheckpointRequest.SCHEMA_VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> observe(endpoint, request, response));
    }

    private Observation observe(
            Endpoint endpoint,
            TestSuiteStabilityExternalSequenceCheckpointRequest request,
            HttpResponse<InputStream> response) {
        int status = response.statusCode();
        if (status != 200 && status != 409
                || !MEDIA_TYPE.equalsIgnoreCase(response.headers()
                .firstValue("Content-Type").orElse(""))
                || !TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION.equals(
                response.headers().firstValue(PROTOCOL_HEADER).orElse(""))) {
            closeQuietly(response.body());
            throw new CompletionException(new IllegalArgumentException(
                    "External checkpoint response protocol is invalid"));
        }
        TestSuiteStabilityExternalSequenceCheckpointReceipt receipt;
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
            if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalArgumentException(
                        "External checkpoint response is oversized");
            }
            receipt = objectMapper.readValue(
                    bytes, TestSuiteStabilityExternalSequenceCheckpointReceipt.class);
        } catch (IOException | RuntimeException invalid) {
            throw new CompletionException(new IllegalArgumentException(
                    "External checkpoint response is invalid", invalid));
        }
        verifyReceipt(endpoint, request, receipt, status);
        return new Observation(receipt.authorityId(), receipt.decision());
    }

    private void verifyReceipt(
            Endpoint endpoint,
            TestSuiteStabilityExternalSequenceCheckpointRequest request,
            TestSuiteStabilityExternalSequenceCheckpointReceipt receipt,
            int status) {
        Instant now = clock.instant();
        boolean statusMatches = (status == 200
                && receipt.decision()
                == TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED)
                || (status == 409
                && receipt.decision()
                == TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.CONFLICT);
        if (!statusMatches || !receipt.fingerprintVerified(objectMapper)
                || !request.requestFingerprint().equals(receipt.requestFingerprint())
                || !trustDomain.equals(receipt.trustDomain())
                || !anchorSetId.equals(receipt.anchorSetId())
                || !endpoint.authorityId().equals(receipt.authorityId())
                || !endpoint.failureDomain().equals(receipt.failureDomain())
                || receipt.candidateSequence() != request.head().sequence()
                || !receipt.candidateHeadFingerprint().equals(
                request.head().headFingerprint())
                || receipt.issuedAt().isBefore(
                request.requestedAt().minus(settings.clockSkew()))
                || receipt.issuedAt().isAfter(now.plus(settings.clockSkew()))
                || !now.isBefore(request.expiresAt())
                || !now.isBefore(receipt.expiresAt())
                || receipt.expiresAt().isAfter(request.expiresAt())
                || Duration.between(receipt.issuedAt(), receipt.expiresAt())
                .compareTo(settings.maximumReceiptLifetime()) > 0
                || receipt.decision()
                == TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.CONFLICT
                && !meaningfulConflict(request.head(), receipt)) {
            throw new IllegalArgumentException(
                    "External checkpoint receipt binding is invalid");
        }
        trustStore.verify(receipt, now);
    }

    private static boolean meaningfulConflict(
            Head head,
            TestSuiteStabilityExternalSequenceCheckpointReceipt receipt) {
        if (receipt.observedSequence() > head.sequence()) {
            return true;
        }
        if (receipt.observedSequence() == head.sequence()) {
            return !receipt.observedHeadFingerprint().equals(head.headFingerprint());
        }
        return receipt.observedSequence() == head.sequence() - 1
                && !receipt.observedHeadFingerprint().equals(
                head.previousHeadFingerprint());
    }

    private void validatePolicy() {
        if (!IDENTIFIER.matcher(trustDomain).matches()
                || !IDENTIFIER.matcher(anchorSetId).matches()
                || maximumFaults < 0 || maximumFaults > 10
                || endpoints.size() < 3 * maximumFaults + 1
                || signatureThreshold < 2 * maximumFaults + 1
                || signatureThreshold > endpoints.size()) {
            throw new IllegalArgumentException(
                    "External sequence-anchor quorum policy is invalid");
        }
        if (!trustStore.coversAuthorities(endpointAuthorityIds)) {
            throw new IllegalArgumentException(
                    "Every external notary requires independently configured keys and endpoint");
        }
    }

    /** Stops any managed trust refresh lane owned by this anchor. */
    @Override
    public void close() {
        trustStore.close();
    }

    private static List<Endpoint> validateEndpoints(
            List<Endpoint> values, boolean allowInsecureLoopback) {
        List<Endpoint> result = values == null ? List.of() : List.copyOf(values);
        if (result.isEmpty() || result.size() > 32) {
            throw new IllegalArgumentException(
                    "External notary endpoints must be non-empty and bounded");
        }
        Set<String> authorities = new HashSet<>();
        Set<String> domains = new HashSet<>();
        Set<URI> uris = new HashSet<>();
        for (Endpoint endpoint : result) {
            if (endpoint == null || !authorities.add(endpoint.authorityId())
                    || !domains.add(endpoint.failureDomain()) || !uris.add(endpoint.uri())) {
                throw new IllegalArgumentException(
                        "External notary authorities, failure domains, and endpoints must be unique");
            }
            validateUri(endpoint.uri(), allowInsecureLoopback);
        }
        return result;
    }

    private static List<Endpoint> parseEndpoints(ObjectMapper objectMapper, String value)
            throws IOException {
        JsonNode root = objectMapper.readTree(normalized(value));
        if (root == null || !root.isArray() || root.isEmpty() || root.size() > 32) {
            throw new IllegalArgumentException(
                    "External notary endpoint configuration must be a bounded array");
        }
        List<Endpoint> endpoints = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(
                        "External notary endpoint must be an object");
            }
            Set<String> fields = new HashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            if (!ENDPOINT_FIELDS.equals(fields)) {
                throw new IllegalArgumentException(
                        "External notary endpoint fields are invalid");
            }
            try {
                endpoints.add(new Endpoint(required(item, "authorityId"),
                        required(item, "failureDomain"),
                        URI.create(required(item, "uri"))));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "External notary endpoint is invalid", invalid);
            }
        }
        return List.copyOf(endpoints);
    }

    private static String required(JsonNode item, String field) {
        JsonNode value = item.path(field);
        String result = value.isTextual() ? normalized(value.textValue()) : "";
        if (result.isEmpty() || result.length() > 2048) {
            throw new IllegalArgumentException(
                    "External notary endpoint field is invalid");
        }
        return result;
    }

    private static void validateUri(URI uri, boolean allowInsecureLoopback) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("External notary URI is invalid");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
        if (!allowInsecureLoopback || !loopback
                || !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(
                    "External notary URI must use HTTPS");
        }
    }

    private byte[] write(TestSuiteStabilityExternalSequenceCheckpointRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (IOException invalid) {
            throw new IllegalStateException(
                    "External checkpoint request cannot be serialized", invalid);
        }
    }

    private String challenge() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The bounded protocol failure remains authoritative.
        }
    }

    private static ObjectMapper strict(ObjectMapper source) {
        return source.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    /** One externally configured notary and independent failure domain. */
    record Endpoint(String authorityId, String failureDomain, URI uri) {

        Endpoint {
            authorityId = normalized(authorityId);
            failureDomain = normalized(failureDomain);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(failureDomain).matches() || uri == null) {
                throw new IllegalArgumentException("Invalid external notary endpoint");
            }
        }
    }

    /**
     * Transport and signed-receipt freshness bounds.
     *
     * @param requestTimeout per-notary HTTP timeout, 100 ms through 30 seconds
     * @param clockSkew maximum authority clock skew, zero through 30 seconds
     * @param maximumReceiptLifetime request/receipt lifetime, one through 60 seconds
     * @param allowInsecureLoopback explicit local-test HTTP escape hatch
     */
    public record Settings(
            Duration requestTimeout,
            Duration clockSkew,
            Duration maximumReceiptLifetime,
            boolean allowInsecureLoopback) {

        /** Enforces bounded synchronous admission latency and replay windows. */
        public Settings {
            if (requestTimeout == null
                    || requestTimeout.compareTo(Duration.ofMillis(100)) < 0
                    || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || clockSkew == null || clockSkew.isNegative()
                    || clockSkew.compareTo(Duration.ofSeconds(30)) > 0
                    || maximumReceiptLifetime == null
                    || maximumReceiptLifetime.compareTo(Duration.ofSeconds(1)) < 0
                    || maximumReceiptLifetime.compareTo(
                    TestSuiteStabilityExternalSequenceCheckpointRequest.MAXIMUM_LIFETIME) > 0
                    || requestTimeout.compareTo(maximumReceiptLifetime) >= 0) {
                throw new IllegalArgumentException(
                        "External sequence-anchor timing policy is invalid");
            }
        }
    }

    private record Observation(
            String authorityId,
            TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision decision) {
    }

    private record RuntimeState(
            boolean available,
            String status,
            Instant lastSuccessfulAnchorAt,
            long successCount,
            long failureCount,
            long conflictCount) {

        private static RuntimeState initial() {
            return new RuntimeState(false, "UNVERIFIED", null, 0, 0, 0);
        }

        private RuntimeState succeeded(Instant at) {
            return new RuntimeState(true, "HEALTHY", at,
                    successCount + 1, failureCount, conflictCount);
        }

        private RuntimeState failed() {
            return new RuntimeState(false, "QUORUM_UNAVAILABLE", lastSuccessfulAnchorAt,
                    successCount, failureCount + 1, conflictCount);
        }

        private RuntimeState conflict() {
            return new RuntimeState(false, "AUTHENTICATED_CONFLICT", lastSuccessfulAnchorAt,
                    successCount, failureCount, conflictCount + 1);
        }
    }
}
