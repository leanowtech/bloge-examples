package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Atomically refreshes test-secret authority verification keys from a bounded HTTPS JWKS.
 *
 * <p>Construction performs a mandatory bootstrap before the store becomes injectable. Background
 * refresh then runs on one daemon lane with a randomized half-to-full-interval phase. Every fetch
 * publishes either one complete validated key generation or an unavailable state; an ambiguous
 * refresh never leaves the previous generation usable. This immediate fail-closed transition is
 * required because stale trust could release plaintext test credentials after a key revocation.</p>
 *
 * <p>An unknown response key id may trigger one synchronous refresh under a process-wide cooldown.
 * The refresh lock rechecks the current generation before I/O, so concurrent rotation traffic
 * performs at most one fetch and callers observe only complete immutable generations. Health and
 * capability reads use local state and never become hidden network probes.</p>
 */
public final class DynamicJwksTestSecretAuthorityTrustStore
        implements TestSecretAuthorityTrustStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            DynamicJwksTestSecretAuthorityTrustStore.class);
    private static final int MAXIMUM_DOCUMENT_BYTES = 256 * 1024;
    private static final int MAXIMUM_KEYS = 64;
    private static final byte[] ED25519_X509_PREFIX =
            HexFormat.of().parseHex("302a300506032b6570032100");
    private static final Set<String> ROOT_FIELDS = Set.of("keys");
    private static final Set<String> KEY_FIELDS = Set.of(
            "kid", "kty", "crv", "x", "alg", "use", "key_ops",
            "nbf", "exp", "enabled", "revoked");
    private static final Set<String> PRIVATE_KEY_FIELDS = Set.of(
            "d", "p", "q", "dp", "dq", "qi", "oth", "k");

    private final ObjectMapper objectMapper;
    private final String expectedAuthorityId;
    private final Duration maximumResponseLifetime;
    private final Duration clockSkew;
    private final Duration minimumRemainingValidity;
    private final Settings settings;
    private final Clock clock;
    private final DocumentFetcher fetcher;
    private final Object refreshLock = new Object();
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    private volatile State state;
    private volatile Instant nextUnknownKeyRefreshAt = Instant.MIN;
    private volatile boolean closed;

    /**
     * Bootstraps the remote public-key set and starts bounded automatic refresh.
     *
     * @param objectMapper application JSON mapper
     * @param expectedAuthorityId exact accepted test-secret authority
     * @param maximumResponseLifetime maximum lifetime of one signed response
     * @param clockSkew maximum tolerated caller/authority clock skew
     * @param minimumRemainingValidity minimum response validity at verification
     * @param settings HTTPS JWKS refresh policy
     */
    public DynamicJwksTestSecretAuthorityTrustStore(
            ObjectMapper objectMapper,
            String expectedAuthorityId,
            Duration maximumResponseLifetime,
            Duration clockSkew,
            Duration minimumRemainingValidity,
            Settings settings) {
        this(objectMapper, expectedAuthorityId, maximumResponseLifetime, clockSkew,
                minimumRemainingValidity, settings, Clock.systemUTC(), null, true);
    }

    DynamicJwksTestSecretAuthorityTrustStore(
            ObjectMapper objectMapper,
            String expectedAuthorityId,
            Duration maximumResponseLifetime,
            Duration clockSkew,
            Duration minimumRemainingValidity,
            Settings settings,
            Clock clock,
            DocumentFetcher fetcher,
            boolean startScheduler) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.expectedAuthorityId = normalized(expectedAuthorityId);
        this.maximumResponseLifetime = bounded(maximumResponseLifetime,
                Duration.ofSeconds(1), Duration.ofMinutes(5),
                "maximum response lifetime");
        this.clockSkew = bounded(clockSkew, Duration.ZERO, Duration.ofMinutes(5),
                "clock skew");
        this.minimumRemainingValidity = bounded(minimumRemainingValidity,
                Duration.ZERO, Duration.ofSeconds(30), "minimum remaining validity");
        if (this.expectedAuthorityId.isBlank()
                || this.minimumRemainingValidity.compareTo(
                this.maximumResponseLifetime) >= 0) {
            throw new IllegalArgumentException(
                    "Invalid dynamic test-secret authority trust policy");
        }
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.fetcher = fetcher == null ? new HttpDocumentFetcher(this.settings) : fetcher;
        this.state = State.empty();
        if (!refresh() || !bootstrapUsable()) {
            throw new IllegalStateException(
                    "Dynamic test-secret authority JWKS bootstrap is unavailable");
        }
        this.scheduler = startScheduler ? scheduler() : null;
    }

    @Override
    public Verification verify(
            TestSecretAuthorityResponse response,
            TestSecretAuthorityRequest request,
            Instant observedAt) {
        Instant now = observedAt == null ? clock.instant() : observedAt;
        State observed = state;
        if (response != null && usable(observed, now)
                && !observed.keys().containsKey(response.signature().keyId())) {
            refreshUnknownKey(response.signature().keyId());
            observed = state;
        }
        if (!usable(observed, now)) {
            return new Verification(VerificationStatus.KEY_UNAVAILABLE,
                    "RG.TEST.SECRET_AUTHORITY_KEY_UNAVAILABLE");
        }
        return observed.verifier().verify(response, request, now);
    }

    /**
     * Returns key-free local readiness without performing network I/O.
     *
     * @return current dynamic trust descriptor
     */
    @Override
    public Descriptor descriptor() {
        State observed = state;
        Instant now = clock.instant();
        long activeKeys = activeKeyCount(observed, now);
        String refreshState = effectiveRefreshState(observed, now);
        boolean available = !closed && "HEALTHY".equals(refreshState)
                && activeKeys > 0;
        return new Descriptor("", available, "DYNAMIC_JWKS_ED25519",
                expectedAuthorityId, observed.keys().size(), Map.ofEntries(
                Map.entry("algorithm", "Ed25519"),
                Map.entry("signedResponses", true),
                Map.entry("challengeBound", true),
                Map.entry("privateMaterialPresent", false),
                Map.entry("activeKeyCount", activeKeys),
                Map.entry("maximumResponseLifetimeSeconds",
                        maximumResponseLifetime.toSeconds()),
                Map.entry("clockSkewSeconds", clockSkew.toSeconds()),
                Map.entry("minimumRemainingValidityMillis",
                        minimumRemainingValidity.toMillis()),
                Map.entry("refreshMode", "BACKGROUND_AND_UNKNOWN_KEY"),
                Map.entry("refreshState", refreshState),
                Map.entry("refreshIntervalSeconds",
                        settings.refreshInterval().toSeconds()),
                Map.entry("maximumSnapshotAgeSeconds",
                        settings.maximumSnapshotAge().toSeconds()),
                Map.entry("unknownKeyRefreshIntervalSeconds",
                        settings.unknownKeyRefreshInterval().toSeconds()),
                Map.entry("failClosedOnRefreshFailure", true),
                Map.entry("conditionalRequests", true),
                Map.entry("automaticRefresh", scheduler != null && !closed)));
    }

    /**
     * Returns a fixed-cardinality operational projection without URI, ETag, key ids or material.
     *
     * @return local refresh health for Actuator and alerting
     */
    public RefreshSnapshot snapshot() {
        State observed = state;
        Instant now = clock.instant();
        long activeKeys = activeKeyCount(observed, now);
        String refreshState = effectiveRefreshState(observed, now);
        return new RefreshSnapshot(RefreshSnapshot.SCHEMA_VERSION,
                !closed && "HEALTHY".equals(refreshState) && activeKeys > 0,
                refreshState, observed.keys().size(), activeKeys,
                observed.lastSuccessfulRefreshAt(), observed.refreshSuccessCount(),
                observed.refreshFailureCount(), observed.lastFailureCode(),
                settings.refreshInterval().toSeconds(),
                settings.maximumSnapshotAge().toSeconds());
    }

    /**
     * Returns the private local generation identity required by a later exact-cohort gate.
     *
     * <p>The fingerprint covers authority identity, public keys and lifecycle flags. It is never
     * exposed by descriptors, health details or logs.</p>
     *
     * @return immutable payload-free trust-generation observation
     */
    public CohortObservation cohortObservation() {
        State observed = state;
        Instant now = clock.instant();
        long activeKeys = activeKeyCount(observed, now);
        String refreshState = effectiveRefreshState(observed, now);
        return new CohortObservation(CohortObservation.SCHEMA_VERSION,
                !closed && "HEALTHY".equals(refreshState) && activeKeys > 0,
                refreshState, observed.snapshotFingerprint(), activeKeys,
                observed.lastSuccessfulRefreshAt());
    }

    /** Stops automatic refresh and immediately makes this trust source unavailable. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(
                        Math.min(1_000L, settings.requestTimeout().toMillis()),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean refreshNow() {
        return refresh();
    }

    private void refreshUnknownKey(String keyId) {
        Instant now = clock.instant();
        synchronized (refreshLock) {
            State observed = state;
            if (closed || observed.keys().containsKey(keyId)
                    || now.isBefore(nextUnknownKeyRefreshAt)) {
                return;
            }
            nextUnknownKeyRefreshAt = now.plus(
                    settings.unknownKeyRefreshInterval());
            refreshLocked(now, observed);
        }
    }

    private boolean refresh() {
        synchronized (refreshLock) {
            if (closed) {
                return false;
            }
            return refreshLocked(clock.instant(), state);
        }
    }

    private boolean refreshLocked(Instant now, State previous) {
        try {
            FetchedDocument document = fetcher.fetch(
                    settings.jwksUri(), previous.etag(), settings.requestTimeout());
            Map<String, ConfiguredTestSecretAuthorityTrustStore.AuthorityKey> keys;
            if (document.notModified()) {
                if (previous.keys().isEmpty()) {
                    throw new IllegalArgumentException(
                            "JWKS authority returned 304 before bootstrap");
                }
                keys = previous.keys();
            } else {
                keys = parseJwks(document.body());
            }
            ConfiguredTestSecretAuthorityTrustStore verifier =
                    new ConfiguredTestSecretAuthorityTrustStore(
                            objectMapper, expectedAuthorityId, maximumResponseLifetime,
                            clockSkew, minimumRemainingValidity,
                            new ArrayList<>(keys.values()));
            String etag = document.etag().isBlank()
                    ? previous.etag() : document.etag();
            String fingerprint = document.notModified()
                    ? previous.snapshotFingerprint() : snapshotFingerprint(keys);
            state = new State(keys, verifier, etag, fingerprint,
                    RefreshState.HEALTHY, now,
                    previous.refreshSuccessCount() + 1,
                    previous.refreshFailureCount(), "");
            refreshFailureLogged.set(false);
            return true;
        } catch (RuntimeException unavailable) {
            state = previous.failed(failureCode(unavailable));
            if (refreshFailureLogged.compareAndSet(false, true)) {
                log.warn("Dynamic test-secret authority JWKS refresh failed; "
                        + "secret resolution is now fail-closed");
            }
            return false;
        }
    }

    private Map<String, ConfiguredTestSecretAuthorityTrustStore.AuthorityKey>
            parseJwks(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            requireExactFields(root, ROOT_FIELDS, "JWKS document");
            JsonNode items = root.path("keys");
            if (!items.isArray() || items.isEmpty() || items.size() > MAXIMUM_KEYS) {
                throw new IllegalArgumentException(
                        "JWKS must contain one through 64 public keys");
            }
            LinkedHashMap<String,
                    ConfiguredTestSecretAuthorityTrustStore.AuthorityKey> parsed =
                    new LinkedHashMap<>();
            for (JsonNode item : items) {
                ConfiguredTestSecretAuthorityTrustStore.AuthorityKey key = parseJwk(item);
                if (parsed.putIfAbsent(key.keyId(), key) != null) {
                    throw new IllegalArgumentException("JWKS key ids must be unique");
                }
            }
            return Map.copyOf(parsed);
        } catch (IOException invalid) {
            throw new IllegalArgumentException(
                    "Invalid test-secret authority JWKS JSON", invalid);
        }
    }

    private ConfiguredTestSecretAuthorityTrustStore.AuthorityKey parseJwk(
            JsonNode item) {
        if (item != null && item.isObject()
                && PRIVATE_KEY_FIELDS.stream().anyMatch(item::has)) {
            throw new IllegalArgumentException(
                    "Authority JWKS must not contain private key material");
        }
        requireExactFields(item, KEY_FIELDS, "JWKS key");
        if (!"OKP".equals(requiredText(item, "kty", 16))
                || !"Ed25519".equals(requiredText(item, "crv", 32))
                || !"EdDSA".equals(requiredText(item, "alg", 16))) {
            throw new IllegalArgumentException(
                    "Authority JWKS keys must be public Ed25519 verification keys");
        }
        if (item.has("use") && !"sig".equals(requiredText(item, "use", 16))) {
            throw new IllegalArgumentException("Authority JWKS key use must be sig");
        }
        if (item.has("key_ops")) {
            JsonNode operations = item.path("key_ops");
            if (!operations.isArray() || operations.size() != 1
                    || !operations.get(0).isTextual()
                    || !"verify".equals(operations.get(0).textValue())) {
                throw new IllegalArgumentException(
                        "Authority JWKS key_ops must contain only verify");
            }
        }
        byte[] coordinate;
        try {
            coordinate = Base64.getUrlDecoder().decode(
                    requiredText(item, "x", 128));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Authority JWKS x is invalid", invalid);
        }
        if (coordinate.length != 32) {
            throw new IllegalArgumentException("Authority JWKS x must be 32 bytes");
        }
        byte[] encoded = new byte[ED25519_X509_PREFIX.length + coordinate.length];
        System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0,
                ED25519_X509_PREFIX.length);
        System.arraycopy(coordinate, 0, encoded, ED25519_X509_PREFIX.length,
                coordinate.length);
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(encoded));
            return new ConfiguredTestSecretAuthorityTrustStore.AuthorityKey(
                    requiredText(item, "kid", 255), publicKey,
                    epochSecond(item, "nbf", Instant.MIN),
                    epochSecond(item, "exp", Instant.MAX),
                    !item.has("enabled") || booleanValue(item, "enabled"),
                    item.has("revoked") && booleanValue(item, "revoked"));
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException(
                    "Authority JWKS public key is invalid", invalid);
        }
    }

    private String snapshotFingerprint(
            Map<String, ConfiguredTestSecretAuthorityTrustStore.AuthorityKey> keys) {
        List<Map<String, Object>> material = new TreeMap<>(keys).values().stream()
                .map(key -> Map.<String, Object>ofEntries(
                        Map.entry("keyId", key.keyId()),
                        Map.entry("algorithm", key.publicKey().getAlgorithm()),
                        Map.entry("publicKey", Base64.getEncoder().encodeToString(
                                key.publicKey().getEncoded())),
                        Map.entry("notBefore", key.notBefore().toString()),
                        Map.entry("expiresAt", key.expiresAt().toString()),
                        Map.entry("enabled", key.enabled()),
                        Map.entry("revoked", key.revoked())))
                .toList();
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testSecretAuthorityTrustGeneration.v1",
                "authorityId", expectedAuthorityId,
                "keys", material));
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-test-secret-authority-jwks-refresh");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        long intervalMillis = settings.refreshInterval().toMillis();
        long initialDelayMillis = ThreadLocalRandom.current().nextLong(
                Math.max(1L, intervalMillis / 2L), intervalMillis + 1L);
        executor.scheduleWithFixedDelay(this::refreshSafely,
                initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return executor;
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException unavailable) {
            synchronized (refreshLock) {
                state = state.failed("REFRESH_TASK_FAILED");
            }
        }
    }

    private long activeKeyCount(State candidate, Instant now) {
        return candidate.keys().values().stream()
                .filter(key -> key.activeAt(now)).count();
    }

    private boolean usable(State candidate, Instant now) {
        return !closed && candidate.refreshState() == RefreshState.HEALTHY
                && candidate.lastSuccessfulRefreshAt() != null
                && now.isBefore(candidate.lastSuccessfulRefreshAt()
                .plus(settings.maximumSnapshotAge()));
    }

    private boolean bootstrapUsable() {
        Instant now = clock.instant();
        return usable(state, now) && activeKeyCount(state, now) > 0;
    }

    private String effectiveRefreshState(State candidate, Instant now) {
        if (closed) {
            return "CLOSED";
        }
        if (candidate.refreshState() == RefreshState.HEALTHY
                && !usable(candidate, now)) {
            return "EXPIRED";
        }
        return candidate.refreshState().name();
    }

    private static void requireExactFields(
            JsonNode value, Set<String> allowed, String label) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        Set<String> fields = new HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        if (!allowed.containsAll(fields)) {
            throw new IllegalArgumentException(label + " contains an unknown field");
        }
    }

    private static String requiredText(
            JsonNode item, String field, int maximumLength) {
        JsonNode value = item.path(field);
        String result = value.isTextual() ? normalized(value.textValue()) : "";
        if (result.isBlank() || result.length() > maximumLength
                || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid JWKS field: " + field);
        }
        return result;
    }

    private static boolean booleanValue(JsonNode item, String field) {
        if (!item.path(field).isBoolean()) {
            throw new IllegalArgumentException("Invalid JWKS flag: " + field);
        }
        return item.path(field).booleanValue();
    }

    private static Instant epochSecond(
            JsonNode item, String field, Instant fallback) {
        if (!item.has(field)) {
            return fallback;
        }
        JsonNode value = item.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "Invalid JWKS epoch second: " + field);
        }
        try {
            return Instant.ofEpochSecond(value.longValue());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "JWKS epoch second is out of range: " + field, invalid);
        }
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof RemoteAuthorityUnavailableException) {
            return "REMOTE_AUTHORITY_UNAVAILABLE";
        }
        if (failure instanceof IllegalArgumentException) {
            return "REMOTE_DOCUMENT_INVALID";
        }
        return "REMOTE_REFRESH_FAILED";
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String label) {
        Duration result = Objects.requireNonNull(value, label);
        if (result.compareTo(minimum) < 0 || result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Invalid dynamic test-secret authority " + label);
        }
        return result;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private enum RefreshState {
        BOOTSTRAPPING,
        HEALTHY,
        UNAVAILABLE
    }

    /**
     * Bounded remote-refresh policy. Maximum snapshot age is a hard local validity fence and must
     * cover at least one refresh interval plus one request timeout.
     *
     * @param jwksUri HTTPS public-key authority
     * @param refreshInterval fixed-delay background refresh interval
     * @param unknownKeyRefreshInterval global unknown-key refresh cooldown
     * @param requestTimeout per-fetch connect and request timeout
     * @param maximumSnapshotAge hard age after which trust expires locally
     * @param allowInsecureLoopback local-test-only HTTP loopback escape hatch
     */
    public record Settings(
            URI jwksUri,
            Duration refreshInterval,
            Duration unknownKeyRefreshInterval,
            Duration requestTimeout,
            Duration maximumSnapshotAge,
            boolean allowInsecureLoopback) {

        /** @return validated immutable settings */
        public Settings validated() {
            validateUri(jwksUri, allowInsecureLoopback);
            Duration refresh = bounded(refreshInterval, Duration.ofSeconds(1),
                    Duration.ofHours(1), "JWKS refresh interval");
            Duration unknown = bounded(unknownKeyRefreshInterval,
                    Duration.ofSeconds(1), Duration.ofMinutes(5),
                    "unknown-key refresh interval");
            Duration timeout = bounded(requestTimeout, Duration.ofMillis(100),
                    Duration.ofSeconds(30), "JWKS request timeout");
            Duration maximumAge = bounded(maximumSnapshotAge,
                    Duration.ofSeconds(2), Duration.ofHours(24),
                    "maximum snapshot age");
            if (maximumAge.compareTo(refresh.plus(timeout)) < 0) {
                throw new IllegalArgumentException(
                        "Dynamic test-secret authority snapshot age must cover "
                                + "refresh plus request timeout");
            }
            return new Settings(jwksUri, refresh, unknown, timeout, maximumAge,
                    allowInsecureLoopback);
        }

        private static void validateUri(
                URI uri, boolean allowInsecureLoopback) {
            if (uri == null || !uri.isAbsolute() || uri.isOpaque()
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null
                    || !uri.normalize().equals(uri)) {
                throw new IllegalArgumentException(
                        "A valid test-secret authority JWKS URI is required");
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("localhost")
                    || host.equals("127.0.0.1") || host.equals("::1");
            if (!allowInsecureLoopback || !loopback
                    || !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException(
                        "Test-secret authority JWKS must use HTTPS");
            }
        }
    }

    @FunctionalInterface
    interface DocumentFetcher {
        FetchedDocument fetch(URI uri, String etag, Duration timeout);
    }

    record FetchedDocument(byte[] body, String etag, boolean notModified) {
        FetchedDocument {
            body = body == null ? new byte[0] : body.clone();
            etag = normalized(etag);
            if (etag.length() > 512
                    || etag.chars().anyMatch(Character::isISOControl)
                    || !notModified && (body.length == 0
                    || body.length > MAXIMUM_DOCUMENT_BYTES)) {
                throw new IllegalArgumentException(
                        "Invalid test-secret authority JWKS response");
            }
        }

        static FetchedDocument modified(byte[] body, String etag) {
            return new FetchedDocument(body, etag, false);
        }

        static FetchedDocument notModified(String etag) {
            return new FetchedDocument(new byte[0], etag, true);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static final class HttpDocumentFetcher implements DocumentFetcher {
        private final HttpClient client;

        private HttpDocumentFetcher(Settings settings) {
            client = HttpClient.newBuilder()
                    .connectTimeout(settings.requestTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        @Override
        public FetchedDocument fetch(URI uri, String etag, Duration timeout) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .GET()
                        .timeout(timeout)
                        .header("Accept",
                                "application/jwk-set+json, application/json");
                if (etag != null && !etag.isBlank()) {
                    request.header("If-None-Match", etag);
                }
                HttpResponse<InputStream> response = client.send(
                        request.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 304) {
                    response.body().close();
                    return FetchedDocument.notModified(
                            response.headers().firstValue("ETag").orElse(etag));
                }
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new RemoteAuthorityUnavailableException(
                            "Test-secret authority JWKS returned a non-success status");
                }
                String contentType = response.headers()
                        .firstValue("Content-Type").orElse("")
                        .toLowerCase(Locale.ROOT);
                if (!contentType.startsWith("application/json")
                        && !contentType.startsWith("application/jwk-set+json")) {
                    response.body().close();
                    throw new IllegalArgumentException(
                            "Test-secret authority JWKS returned a non-JSON document");
                }
                long declaredLength = response.headers()
                        .firstValueAsLong("Content-Length").orElse(-1);
                if (declaredLength > MAXIMUM_DOCUMENT_BYTES) {
                    response.body().close();
                    throw new IllegalArgumentException(
                            "Test-secret authority JWKS is too large");
                }
                byte[] body;
                try (InputStream input = response.body()) {
                    body = input.readNBytes(MAXIMUM_DOCUMENT_BYTES + 1);
                }
                return FetchedDocument.modified(body,
                        response.headers().firstValue("ETag").orElse(""));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RemoteAuthorityUnavailableException(
                        "Test-secret authority JWKS request was interrupted",
                        interrupted);
            } catch (IOException unavailable) {
                throw new RemoteAuthorityUnavailableException(
                        "Test-secret authority JWKS request failed", unavailable);
            }
        }
    }

    private static final class RemoteAuthorityUnavailableException
            extends RuntimeException {
        private RemoteAuthorityUnavailableException(String message) {
            super(message);
        }

        private RemoteAuthorityUnavailableException(
                String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record State(
            Map<String, ConfiguredTestSecretAuthorityTrustStore.AuthorityKey> keys,
            ConfiguredTestSecretAuthorityTrustStore verifier,
            String etag,
            String snapshotFingerprint,
            RefreshState refreshState,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode) {

        private State {
            keys = keys == null ? Map.of() : Map.copyOf(keys);
            etag = normalized(etag);
            snapshotFingerprint = normalized(snapshotFingerprint);
            refreshState = refreshState == null
                    ? RefreshState.UNAVAILABLE : refreshState;
            lastFailureCode = normalized(lastFailureCode);
        }

        private static State empty() {
            return new State(Map.of(), null, "", "",
                    RefreshState.BOOTSTRAPPING, null, 0, 0, "");
        }

        private State failed(String failureCode) {
            return new State(keys, verifier, etag, snapshotFingerprint,
                    RefreshState.UNAVAILABLE, lastSuccessfulRefreshAt,
                    refreshSuccessCount, refreshFailureCount + 1, failureCode);
        }
    }

    /**
     * Key-free local refresh truth for health and fixed-cardinality telemetry.
     *
     * @param schemaVersion snapshot protocol generation
     * @param available whether a fresh snapshot has an active verification key
     * @param refreshState closed local refresh state
     * @param trustedKeyCount bounded public-key inventory cardinality
     * @param activeKeyCount currently active verification-key cardinality
     * @param lastSuccessfulRefreshAt last complete atomic publication time
     * @param refreshSuccessCount process-local successful refresh count
     * @param refreshFailureCount process-local failed refresh count
     * @param lastFailureCode stable payload-free failure family
     * @param refreshIntervalSeconds configured background refresh interval
     * @param maximumSnapshotAgeSeconds hard local snapshot validity fence
     */
    public record RefreshSnapshot(
            String schemaVersion,
            boolean available,
            String refreshState,
            int trustedKeyCount,
            long activeKeyCount,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode,
            long refreshIntervalSeconds,
            long maximumSnapshotAgeSeconds) {

        /** Current payload-free refresh snapshot version. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityTrustRefreshSnapshot.v1";

        /** Normalizes and validates the bounded operational projection. */
        public RefreshSnapshot {
            schemaVersion = normalized(schemaVersion);
            refreshState = normalized(refreshState);
            lastFailureCode = normalized(lastFailureCode);
            if (!SCHEMA_VERSION.equals(schemaVersion) || refreshState.isBlank()
                    || trustedKeyCount < 0 || trustedKeyCount > MAXIMUM_KEYS
                    || activeKeyCount < 0 || activeKeyCount > trustedKeyCount
                    || refreshSuccessCount < 0 || refreshFailureCount < 0
                    || refreshIntervalSeconds < 1
                    || maximumSnapshotAgeSeconds < 2) {
                throw new IllegalArgumentException(
                        "Invalid test-secret authority trust refresh snapshot");
            }
        }
    }

    /**
     * Private local observation for later exact trust-generation convergence.
     *
     * @param schemaVersion observation protocol generation
     * @param available whether this process can currently verify responses
     * @param refreshState closed local refresh state
     * @param snapshotFingerprint SHA-256 identity of the complete public generation
     * @param activeKeyCount currently active verification-key cardinality
     * @param lastSuccessfulRefreshAt last complete local snapshot publication
     */
    public record CohortObservation(
            String schemaVersion,
            boolean available,
            String refreshState,
            String snapshotFingerprint,
            long activeKeyCount,
            Instant lastSuccessfulRefreshAt) {

        /** Current private cohort-observation version. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityTrustCohortObservation.v1";

        /** Validates the bounded private generation heartbeat. */
        public CohortObservation {
            schemaVersion = normalized(schemaVersion);
            refreshState = normalized(refreshState);
            snapshotFingerprint = normalized(snapshotFingerprint);
            if (!SCHEMA_VERSION.equals(schemaVersion) || refreshState.isBlank()
                    || activeKeyCount < 0 || activeKeyCount > MAXIMUM_KEYS
                    || available && (!"HEALTHY".equals(refreshState)
                    || !snapshotFingerprint.matches("sha256:[a-f0-9]{64}")
                    || activeKeyCount == 0 || lastSuccessfulRefreshAt == null)) {
                throw new IllegalArgumentException(
                        "Invalid test-secret authority trust cohort observation");
            }
        }
    }
}
