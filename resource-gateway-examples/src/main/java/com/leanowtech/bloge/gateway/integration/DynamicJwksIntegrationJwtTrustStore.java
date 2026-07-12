package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Atomically refreshes signing keys and revocations from a remote JWKS authority.
 * Requests either observe one complete snapshot or fail closed; partially refreshed material is never published.
 */
public final class DynamicJwksIntegrationJwtTrustStore implements IntegrationJwtTrustStore {
    public static final String REVOCATION_SCHEMA_VERSION = "resourceGateway.integrationJwtRevocations.v1";
    private static final int MAX_DOCUMENT_BYTES = 256 * 1024;
    private static final int MAX_KEYS = 32;
    private static final int MAX_REVOCATIONS = 100_000;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(1);
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final Clock clock;
    private final DocumentFetcher fetcher;
    private final Object refreshLock = new Object();

    private volatile State state;
    private volatile Instant nextUnknownKeyRefreshAt = Instant.MIN;

    public DynamicJwksIntegrationJwtTrustStore(ObjectMapper objectMapper, Settings settings) {
        this(objectMapper, settings, Clock.systemUTC(), null);
    }

    DynamicJwksIntegrationJwtTrustStore(ObjectMapper objectMapper,
                                        Settings settings,
                                        Clock clock,
                                        DocumentFetcher fetcher) {
        this.objectMapper = (objectMapper == null ? new ObjectMapper() : objectMapper).copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.settings = settings == null ? null : settings.validated();
        if (this.settings == null) {
            throw new IllegalArgumentException("Dynamic JWKS settings are required");
        }
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.fetcher = fetcher == null ? new HttpDocumentFetcher(this.settings) : fetcher;
        this.state = State.empty(this.clock.instant());
        refresh(true, false);
        requireUsable(state);
    }

    @Override
    public Optional<VerificationKey> find(String keyId) {
        String normalizedKeyId = normalize(keyId);
        State observed = usableState();
        VerificationKey key = observed.keys().get(normalizedKeyId);
        if (key == null && !normalizedKeyId.isBlank()) {
            refresh(true, true);
            observed = usableStateWithoutRefresh();
            key = observed.keys().get(normalizedKeyId);
        }
        return Optional.ofNullable(key);
    }

    @Override
    public boolean isTokenRevoked(String tokenId) {
        return usableState().revokedTokenIds().contains(normalize(tokenId));
    }

    @Override
    public Snapshot snapshot() {
        refresh(false, false);
        State observed = state;
        Instant now = clock.instant();
        int active = (int) observed.keys().values().stream().filter(key -> key.activeAt(now)).count();
        int revoked = (int) observed.keys().values().stream().filter(VerificationKey::revoked).count();
        return new Snapshot(observed.keys().size(), active, revoked, observed.revokedTokenIds().size(),
                "DYNAMIC_JWKS", observed.refreshState().name(), observed.lastSuccessfulRefreshAt(),
                observed.nextRefreshAt(), observed.refreshSuccessCount(), observed.refreshFailureCount(),
                observed.lastFailureCode(), settings.refreshInterval().toSeconds(),
                settings.refreshInterval().plus(settings.requestTimeout()).toSeconds(),
                true, settings.revocationsUri() != null,
                settings.outagePolicy() == OutagePolicy.FAIL_CLOSED,
                observed.refreshState() == RefreshState.STALE);
    }

    private State usableState() {
        refresh(false, false);
        return usableStateWithoutRefresh();
    }

    private State usableStateWithoutRefresh() {
        State observed = state;
        requireUsable(observed);
        return observed;
    }

    private static void requireUsable(State observed) {
        if (observed.refreshState() == RefreshState.UNAVAILABLE
                || observed.refreshState() == RefreshState.EXPIRED
                || observed.lastSuccessfulRefreshAt() == null) {
            throw new IntegrationIdentityProviderUnavailableException(
                    "The dynamic integration identity trust snapshot is unavailable"
                            + (observed.lastFailureCode().isBlank() ? "" : ": " + observed.lastFailureCode()));
        }
    }

    private void refresh(boolean force, boolean unknownKey) {
        Instant now = clock.instant();
        State observed = state;
        if (!force && now.isBefore(observed.nextRefreshAt())) {
            expireStaleSnapshotIfNeeded(now, observed);
            return;
        }
        synchronized (refreshLock) {
            now = clock.instant();
            observed = state;
            if (!force && now.isBefore(observed.nextRefreshAt())) {
                expireStaleSnapshotIfNeeded(now, observed);
                return;
            }
            if (unknownKey && now.isBefore(nextUnknownKeyRefreshAt)) {
                return;
            }
            if (unknownKey) {
                nextUnknownKeyRefreshAt = now.plus(settings.unknownKeyRefreshInterval());
            }
            try {
                State refreshed = loadSnapshot(now, observed);
                state = refreshed;
            } catch (RuntimeException failure) {
                state = failedState(now, observed, failure);
            }
        }
    }

    private void expireStaleSnapshotIfNeeded(Instant now, State observed) {
        if (observed.refreshState() != RefreshState.STALE
                || observed.lastSuccessfulRefreshAt() == null
                || !now.isAfter(observed.lastSuccessfulRefreshAt().plus(settings.maximumStale()))) {
            return;
        }
        synchronized (refreshLock) {
            State current = state;
            if (current == observed) {
                state = current.withRefreshState(RefreshState.EXPIRED, current.nextRefreshAt());
            }
        }
    }

    private State loadSnapshot(Instant now, State previous) {
        FetchedDocument jwks = fetcher.fetch(settings.jwksUri(), previous.jwksEtag(), settings.requestTimeout());
        Map<String, VerificationKey> declaredKeys = jwks.notModified()
                ? requireExisting(previous.declaredKeys(), "JWKS")
                : parseJwks(jwks.body());

        Set<String> revokedKeyIds = Set.of();
        Set<String> revokedTokenIds = Set.of();
        Instant revocationsExpiresAt = Instant.MAX;
        String revocationsEtag = "";
        if (settings.revocationsUri() != null) {
            FetchedDocument revocations = fetcher.fetch(settings.revocationsUri(), previous.revocationsEtag(),
                    settings.requestTimeout());
            if (revocations.notModified()) {
                if (previous.lastSuccessfulRefreshAt() == null) {
                    throw new IllegalArgumentException("Revocation authority returned 304 before bootstrap");
                }
                revokedKeyIds = previous.revokedKeyIds();
                revokedTokenIds = previous.revokedTokenIds();
                revocationsExpiresAt = previous.revocationsExpiresAt();
                revocationsEtag = revocations.etag().isBlank()
                        ? previous.revocationsEtag() : revocations.etag();
            } else {
                RevocationDocument parsed = parseRevocations(revocations.body(), now);
                revokedKeyIds = parsed.revokedKeyIds();
                revokedTokenIds = parsed.revokedTokenIds();
                revocationsExpiresAt = parsed.expiresAt();
                revocationsEtag = revocations.etag();
            }
            if (!revocationsExpiresAt.isAfter(now)) {
                throw new IllegalArgumentException("Integration JWT revocation document has expired");
            }
        }

        Map<String, VerificationKey> effectiveKeys = new LinkedHashMap<>();
        for (VerificationKey key : declaredKeys.values()) {
            effectiveKeys.put(key.keyId(), new VerificationKey(key.keyId(), key.algorithm(), key.publicKey(),
                    key.notBefore(), key.expiresAt(), key.enabled(), key.revoked() || revokedKeyIds.contains(key.keyId())));
        }
        String effectiveJwksEtag = jwks.etag().isBlank() ? previous.jwksEtag() : jwks.etag();
        return new State(Map.copyOf(declaredKeys), Map.copyOf(effectiveKeys), Set.copyOf(revokedKeyIds),
                Set.copyOf(revokedTokenIds), revocationsExpiresAt, effectiveJwksEtag,
                revocationsEtag, RefreshState.HEALTHY, now, now.plus(settings.refreshInterval()),
                previous.refreshSuccessCount() + 1, previous.refreshFailureCount(), "");
    }

    private State failedState(Instant now, State previous, RuntimeException failure) {
        boolean hasSnapshot = previous.lastSuccessfulRefreshAt() != null;
        boolean withinStaleWindow = hasSnapshot
                && !now.isAfter(previous.lastSuccessfulRefreshAt().plus(settings.maximumStale()));
        boolean staleEligible = failure instanceof IntegrationIdentityProviderUnavailableException;
        RefreshState refreshState = settings.outagePolicy() == OutagePolicy.BOUNDED_STALE
                && staleEligible && withinStaleWindow
                ? RefreshState.STALE
                : (hasSnapshot ? RefreshState.EXPIRED : RefreshState.UNAVAILABLE);
        Duration retryDelay = settings.refreshInterval().compareTo(Duration.ofSeconds(5)) > 0
                ? Duration.ofSeconds(5) : settings.refreshInterval();
        return new State(previous.declaredKeys(), previous.keys(), previous.revokedKeyIds(),
                previous.revokedTokenIds(), previous.revocationsExpiresAt(), previous.jwksEtag(),
                previous.revocationsEtag(), refreshState, previous.lastSuccessfulRefreshAt(), now.plus(retryDelay),
                previous.refreshSuccessCount(), previous.refreshFailureCount() + 1, failureCode(failure));
    }

    private Map<String, VerificationKey> parseJwks(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode keys = root == null ? null : root.path("keys");
            if (root == null || !root.isObject() || keys == null || !keys.isArray()
                    || keys.isEmpty() || keys.size() > MAX_KEYS) {
                throw new IllegalArgumentException("JWKS must contain a bounded non-empty keys array");
            }
            Map<String, VerificationKey> parsed = new LinkedHashMap<>();
            for (JsonNode item : keys) {
                VerificationKey key = parseJwk(item);
                if (parsed.putIfAbsent(key.keyId(), key) != null) {
                    throw new IllegalArgumentException("Duplicate JWKS key id: " + key.keyId());
                }
            }
            return Map.copyOf(parsed);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Invalid integration JWKS JSON", failure);
        }
    }

    private VerificationKey parseJwk(JsonNode item) {
        if (item == null || !item.isObject() || containsPrivateKeyMaterial(item)) {
            throw new IllegalArgumentException("JWKS entries must be public key objects");
        }
        String keyId = requiredText(item, "kid", 128);
        String keyType = requiredText(item, "kty", 16);
        String algorithm = requiredText(item, "alg", 16);
        if (item.has("use") && !"sig".equals(item.path("use").textValue())) {
            throw new IllegalArgumentException("JWKS key use must be sig");
        }
        if (item.has("key_ops")) {
            JsonNode operations = item.path("key_ops");
            if (!operations.isArray() || operations.isEmpty() || operations.size() > 8) {
                throw new IllegalArgumentException("JWKS key_ops must be a bounded array");
            }
            boolean verify = false;
            for (JsonNode operation : operations) {
                if (!operation.isTextual() || !operation.textValue().equals("verify")) {
                    throw new IllegalArgumentException("JWKS key_ops may only grant verify");
                }
                verify = true;
            }
            if (!verify) {
                throw new IllegalArgumentException("JWKS key must grant verify");
            }
        }
        PublicKey publicKey = switch (keyType) {
            case "RSA" -> rsaPublicKey(item, algorithm);
            case "OKP" -> ed25519PublicKey(item, algorithm);
            default -> throw new IllegalArgumentException("Only RSA and OKP JWKS keys are supported");
        };
        Instant notBefore = numericInstant(item, "nbf", Instant.MIN);
        Instant expiresAt = numericInstant(item, "exp", Instant.MAX);
        if (!expiresAt.isAfter(notBefore)) {
            throw new IllegalArgumentException("JWKS key validity window is invalid");
        }
        if (item.has("enabled") && !item.path("enabled").isBoolean()) {
            throw new IllegalArgumentException("JWKS key enabled must be boolean");
        }
        boolean enabled = !item.has("enabled") || item.path("enabled").booleanValue();
        return new VerificationKey(keyId, algorithm, publicKey, notBefore, expiresAt, enabled, false);
    }

    private static PublicKey rsaPublicKey(JsonNode item, String algorithm) {
        if (!"RS256".equals(algorithm)) {
            throw new IllegalArgumentException("RSA JWKS keys must use RS256");
        }
        try {
            BigInteger modulus = new BigInteger(1, decodeBase64Url(requiredText(item, "n", 16_384)));
            BigInteger exponent = new BigInteger(1, decodeBase64Url(requiredText(item, "e", 32)));
            if (exponent.compareTo(BigInteger.valueOf(65_537)) < 0 || !exponent.testBit(0)) {
                throw new IllegalArgumentException("RSA JWKS public exponent is invalid");
            }
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalArgumentException("Invalid RSA JWKS key", failure);
        }
    }

    private static PublicKey ed25519PublicKey(JsonNode item, String algorithm) {
        if (!"EdDSA".equalsIgnoreCase(algorithm) || !"Ed25519".equals(requiredText(item, "crv", 32))) {
            throw new IllegalArgumentException("OKP JWKS keys must use Ed25519/EdDSA");
        }
        byte[] coordinate = decodeBase64Url(requiredText(item, "x", 128));
        if (coordinate.length != 32) {
            throw new IllegalArgumentException("Ed25519 JWKS x coordinate must be 32 bytes");
        }
        byte[] encoded = new byte[ED25519_X509_PREFIX.length + coordinate.length];
        System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
        System.arraycopy(coordinate, 0, encoded, ED25519_X509_PREFIX.length, coordinate.length);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalArgumentException("Invalid Ed25519 JWKS key", failure);
        }
    }

    private RevocationDocument parseRevocations(byte[] body, Instant now) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()
                    || !REVOCATION_SCHEMA_VERSION.equals(requiredText(root, "schemaVersion", 128))) {
                throw new IllegalArgumentException("Unsupported integration JWT revocation document");
            }
            Instant generatedAt = Instant.parse(requiredText(root, "generatedAt", 64));
            Instant expiresAt = Instant.parse(requiredText(root, "expiresAt", 64));
            if (generatedAt.isAfter(now.plus(MAX_CLOCK_SKEW)) || !expiresAt.isAfter(generatedAt)
                    || !expiresAt.isAfter(now)) {
                throw new IllegalArgumentException("Integration JWT revocation document time window is invalid");
            }
            return new RevocationDocument(stringSet(root.path("revokedKeyIds"), "revokedKeyIds"),
                    stringSet(root.path("revokedTokenIds"), "revokedTokenIds"), expiresAt);
        } catch (java.io.IOException | java.time.DateTimeException failure) {
            throw new IllegalArgumentException("Invalid integration JWT revocation JSON", failure);
        }
    }

    private static Set<String> stringSet(JsonNode value, String label) {
        if (!value.isArray() || value.size() > MAX_REVOCATIONS) {
            throw new IllegalArgumentException(label + " must be a bounded array");
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(label + " entries must be strings");
            }
            String normalized = normalize(item.textValue());
            if (normalized.isBlank() || normalized.length() > 160 || !result.add(normalized)) {
                throw new IllegalArgumentException(label + " contains an invalid or duplicate entry");
            }
        }
        return Set.copyOf(result);
    }

    private static boolean containsPrivateKeyMaterial(JsonNode item) {
        return List.of("d", "p", "q", "dp", "dq", "qi", "oth", "k")
                .stream().anyMatch(item::has);
    }

    private static Instant numericInstant(JsonNode item, String field, Instant fallback) {
        if (!item.has(field)) {
            return fallback;
        }
        if (!item.path(field).isIntegralNumber() || !item.path(field).canConvertToLong()) {
            throw new IllegalArgumentException("JWKS key time must be an epoch second: " + field);
        }
        return Instant.ofEpochSecond(item.path(field).longValue());
    }

    private static String requiredText(JsonNode item, String field, int maximumLength) {
        JsonNode value = item.path(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Missing JWKS field: " + field);
        }
        String result = normalize(value.textValue());
        if (result.isBlank() || result.length() > maximumLength
                || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid JWKS field: " + field);
        }
        return result;
    }

    private static byte[] decodeBase64Url(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid base64url JWKS field", failure);
        }
    }

    private static <K, V> Map<K, V> requireExisting(Map<K, V> values, String label) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(label + " authority returned 304 before bootstrap");
        }
        return values;
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof IntegrationIdentityProviderUnavailableException) {
            return "REMOTE_AUTHORITY_UNAVAILABLE";
        }
        if (failure instanceof IllegalArgumentException) {
            return "REMOTE_DOCUMENT_INVALID";
        }
        return "REMOTE_REFRESH_FAILED";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public enum OutagePolicy {
        FAIL_CLOSED,
        BOUNDED_STALE;

        public static OutagePolicy parse(String value) {
            try {
                return value == null || value.isBlank()
                        ? FAIL_CLOSED : valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Unsupported dynamic JWKS outage policy: " + value, failure);
            }
        }
    }

    enum RefreshState {
        BOOTSTRAPPING,
        HEALTHY,
        STALE,
        EXPIRED,
        UNAVAILABLE
    }

    public record Settings(URI jwksUri,
                           URI revocationsUri,
                           Duration refreshInterval,
                           Duration unknownKeyRefreshInterval,
                           Duration requestTimeout,
                           OutagePolicy outagePolicy,
                           Duration maximumStale,
                           boolean allowInsecureLoopback) {
        public Settings validated() {
            validateUri(jwksUri, allowInsecureLoopback, "JWKS");
            if (revocationsUri != null) {
                validateUri(revocationsUri, allowInsecureLoopback, "revocation");
            }
            Duration refresh = bounded(refreshInterval, Duration.ofSeconds(30), Duration.ofSeconds(1),
                    Duration.ofHours(1), "refresh interval");
            Duration unknown = bounded(unknownKeyRefreshInterval, Duration.ofSeconds(5), Duration.ofSeconds(1),
                    Duration.ofMinutes(5), "unknown-key refresh interval");
            Duration timeout = bounded(requestTimeout, Duration.ofSeconds(3), Duration.ofMillis(100),
                    Duration.ofSeconds(30), "request timeout");
            OutagePolicy policy = outagePolicy == null ? OutagePolicy.FAIL_CLOSED : outagePolicy;
            Duration stale = maximumStale == null ? Duration.ZERO : maximumStale;
            if (stale.isNegative() || stale.compareTo(Duration.ofHours(24)) > 0
                    || policy == OutagePolicy.BOUNDED_STALE && stale.isZero()
                    || policy == OutagePolicy.FAIL_CLOSED && !stale.isZero()) {
                throw new IllegalArgumentException("Invalid dynamic JWKS maximum stale duration");
            }
            return new Settings(jwksUri, revocationsUri, refresh, unknown, timeout, policy, stale,
                    allowInsecureLoopback);
        }

        private static Duration bounded(Duration value,
                                        Duration fallback,
                                        Duration minimum,
                                        Duration maximum,
                                        String label) {
            Duration resolved = value == null ? fallback : value;
            if (resolved.compareTo(minimum) < 0 || resolved.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("Invalid dynamic JWKS " + label);
            }
            return resolved;
        }

        private static void validateUri(URI uri, boolean allowInsecureLoopback, String label) {
            if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("A valid " + label + " authority URI is required");
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
            if (!allowInsecureLoopback || !loopback || !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException(label + " authority must use HTTPS");
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
            etag = normalize(etag);
            if (etag.length() > 512 || etag.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Remote identity document ETag is invalid");
            }
            if (!notModified && (body.length == 0 || body.length > MAX_DOCUMENT_BYTES)) {
                throw new IllegalArgumentException("Remote identity document has an invalid size");
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
            this.client = HttpClient.newBuilder()
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
                        .header("Accept", "application/json, application/jwk-set+json");
                if (etag != null && !etag.isBlank()) {
                    request.header("If-None-Match", etag);
                }
                HttpResponse<java.io.InputStream> response = client.send(
                        request.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 304) {
                    response.body().close();
                    return FetchedDocument.notModified(response.headers().firstValue("ETag").orElse(etag));
                }
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IntegrationIdentityProviderUnavailableException(
                            "Remote identity authority returned HTTP " + response.statusCode());
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("")
                        .toLowerCase(Locale.ROOT);
                if (!contentType.startsWith("application/json")
                        && !contentType.startsWith("application/jwk-set+json")) {
                    response.body().close();
                    throw new IllegalArgumentException("Remote identity authority returned a non-JSON document");
                }
                long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declaredLength > MAX_DOCUMENT_BYTES) {
                    response.body().close();
                    throw new IllegalArgumentException("Remote identity document exceeds its size limit");
                }
                byte[] body;
                try (java.io.InputStream input = response.body()) {
                    body = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
                }
                return FetchedDocument.modified(body, response.headers().firstValue("ETag").orElse(""));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IntegrationIdentityProviderUnavailableException(
                        "Remote identity authority request was interrupted", failure);
            } catch (java.io.IOException failure) {
                throw new IntegrationIdentityProviderUnavailableException(
                        "Remote identity authority request failed", failure);
            }
        }
    }

    private record RevocationDocument(Set<String> revokedKeyIds,
                                      Set<String> revokedTokenIds,
                                      Instant expiresAt) {
    }

    private record State(Map<String, VerificationKey> declaredKeys,
                         Map<String, VerificationKey> keys,
                         Set<String> revokedKeyIds,
                         Set<String> revokedTokenIds,
                         Instant revocationsExpiresAt,
                         String jwksEtag,
                         String revocationsEtag,
                         RefreshState refreshState,
                         Instant lastSuccessfulRefreshAt,
                         Instant nextRefreshAt,
                         long refreshSuccessCount,
                         long refreshFailureCount,
                         String lastFailureCode) {
        private State {
            declaredKeys = declaredKeys == null ? Map.of() : Map.copyOf(declaredKeys);
            keys = keys == null ? Map.of() : Map.copyOf(keys);
            revokedKeyIds = revokedKeyIds == null ? Set.of() : Set.copyOf(revokedKeyIds);
            revokedTokenIds = revokedTokenIds == null ? Set.of() : Set.copyOf(revokedTokenIds);
            revocationsExpiresAt = revocationsExpiresAt == null ? Instant.MAX : revocationsExpiresAt;
            jwksEtag = normalize(jwksEtag);
            revocationsEtag = normalize(revocationsEtag);
            refreshState = refreshState == null ? RefreshState.UNAVAILABLE : refreshState;
            nextRefreshAt = nextRefreshAt == null ? Instant.MIN : nextRefreshAt;
            refreshSuccessCount = Math.max(0, refreshSuccessCount);
            refreshFailureCount = Math.max(0, refreshFailureCount);
            lastFailureCode = normalize(lastFailureCode);
        }

        static State empty(Instant now) {
            return new State(Map.of(), Map.of(), Set.of(), Set.of(), Instant.MAX, "", "",
                    RefreshState.BOOTSTRAPPING, null, now, 0, 0, "");
        }

        State withRefreshState(RefreshState value, Instant nextRefresh) {
            return new State(declaredKeys, keys, revokedKeyIds, revokedTokenIds, revocationsExpiresAt,
                    jwksEtag, revocationsEtag, value, lastSuccessfulRefreshAt, nextRefresh,
                    refreshSuccessCount, refreshFailureCount, lastFailureCode);
        }
    }
}
