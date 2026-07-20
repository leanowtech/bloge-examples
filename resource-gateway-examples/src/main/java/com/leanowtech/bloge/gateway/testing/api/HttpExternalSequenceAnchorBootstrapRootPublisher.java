package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublisher.FailureReason;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublisher.PublisherException;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublisher.ResponseDecision;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublisher.SignedResponse;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * Strict HTTPS and signed-response bootstrap-root publisher adapter.
 *
 * <p>Every request carries the content-addressed publication id as its idempotency key and the
 * exact expected predecessor as an HTTP conditional. Accepted responses require exact status,
 * media type, protocol header, strict bounded JSON, canonical material fingerprint, fresh key
 * lifecycle, exact request binding, and a valid detached Ed25519 signature. Redirects are never
 * followed. Plain HTTP is available only through an explicit loopback test escape hatch.</p>
 *
 * <p>A {@code 409} is safety-significant only after the complete signed response is verified and
 * proves a meaningful remote-head conflict. Unauthenticated or malformed conflicts are classified
 * as invalid responses and cannot quarantine durable work. Exceptions and runtime state retain no
 * URI, key, publication, scope, root-set, bundle, or provider diagnostics.</p>
 */
public final class HttpExternalSequenceAnchorBootstrapRootPublisher
        implements ExternalSequenceAnchorBootstrapRootPublisher {

    /** Exact request and response media type. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.external-sequence-anchor-bootstrap-root-publication.v1+json";

    /** Explicit request and response protocol header. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-External-Sequence-Anchor-Bootstrap-Root-Publication-Protocol";

    /** Current HTTP exchange protocol generation. */
    public static final String PROTOCOL_VERSION =
            "bloge.externalSequenceAnchorBootstrapRootPublicationHttp.v1";

    /** Hard serialized complete-chain request bound. */
    public static final int MAXIMUM_REQUEST_BYTES = 4 * 1024 * 1024;

    private static final int MAXIMUM_RESPONSE_BYTES = 128 * 1024;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String trustDomain;
    private final String publisherId;
    private final String keyId;
    private final PublicKey publicKey;
    private final Instant keyNotBefore;
    private final Instant keyExpiresAt;
    private final URI endpoint;
    private final Settings settings;
    private final HttpClient httpClient;

    private volatile RuntimeState state = RuntimeState.initial();

    /**
     * Creates a strict publisher from one statically pinned Ed25519 response key.
     *
     * @param objectMapper canonical JSON baseline
     * @param trustDomain expected response-signing trust domain
     * @param publisherId exact logical publisher identity
     * @param keyId exact response-signing key identity
     * @param publicKeyBase64 X.509-encoded Ed25519 public key
     * @param keyNotBefore inclusive response-signing activation instant
     * @param keyExpiresAt exclusive response-signing expiry instant
     * @param endpoint exact remote publication endpoint
     * @param settings bounded transport and signed-response freshness policy
     * @return configured strict publisher adapter
     */
    public static HttpExternalSequenceAnchorBootstrapRootPublisher fromBase64(
            ObjectMapper objectMapper,
            String trustDomain,
            String publisherId,
            String keyId,
            String publicKeyBase64,
            Instant keyNotBefore,
            Instant keyExpiresAt,
            URI endpoint,
            Settings settings) {
        try {
            byte[] encoded = Base64.getDecoder().decode(normalized(publicKeyBase64));
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(encoded));
            return new HttpExternalSequenceAnchorBootstrapRootPublisher(
                    objectMapper, Clock.systemUTC(), trustDomain, publisherId, keyId,
                    key, keyNotBefore, keyExpiresAt, endpoint, settings, null);
        } catch (RuntimeException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Bootstrap-root publisher key configuration is invalid", invalid);
        }
    }

    /** Package-visible seam for deterministic clock and HTTP protocol tests. */
    HttpExternalSequenceAnchorBootstrapRootPublisher(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            String publisherId,
            String keyId,
            PublicKey publicKey,
            Instant keyNotBefore,
            Instant keyExpiresAt,
            URI endpoint,
            Settings settings,
            HttpClient httpClient) {
        this.objectMapper = strict(Objects.requireNonNull(objectMapper, "objectMapper"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.trustDomain = identifier(trustDomain, "trustDomain");
        this.publisherId = identifier(publisherId, "publisherId");
        this.keyId = identifier(keyId, "keyId");
        this.publicKey = requireEd25519(publicKey);
        this.keyNotBefore = wholeSecond(keyNotBefore, "keyNotBefore");
        this.keyExpiresAt = wholeSecond(keyExpiresAt, "keyExpiresAt");
        if (!this.keyExpiresAt.isAfter(this.keyNotBefore)) {
            throw new IllegalArgumentException(
                    "Bootstrap-root publisher key lifecycle is invalid");
        }
        this.settings = Objects.requireNonNull(settings, "settings");
        this.endpoint = validateUri(endpoint, settings.allowInsecureLoopback());
        this.httpClient = httpClient == null ? HttpClient.newBuilder()
                .connectTimeout(settings.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build() : httpClient;
    }

    /**
     * Sends one bounded conditional request and locally authenticates the complete response.
     *
     * @param request immutable content-addressed complete-chain request
     * @return stable exact publication receipt
     */
    @Override
    public synchronized ExternalSequenceAnchorBootstrapRootPublicationOutbox
            .PublicationReceipt publish(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request) {
        ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest safeRequest =
                Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        if (!keyUsableAt(now)) {
            state = state.failed("KEY_UNAVAILABLE");
            throw failed(FailureReason.UNAVAILABLE);
        }
        byte[] body = write(safeRequest);
        if (body.length > MAXIMUM_REQUEST_BYTES) {
            state = state.failed("INVALID_REQUEST");
            throw failed(FailureReason.INVALID_RESPONSE);
        }
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, safeRequest);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(settings.requestTimeout())
                .header("Accept", MEDIA_TYPE)
                .header("Content-Type", MEDIA_TYPE)
                .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                .header("Idempotency-Key", safeRequest.publicationId())
                .header("If-Match", etag(
                        safeRequest.expectedPreviousMaterialFingerprint()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            state = state.failed("UNAVAILABLE");
            throw failed(FailureReason.UNAVAILABLE);
        } catch (IOException | RuntimeException unavailable) {
            state = state.failed("UNAVAILABLE");
            throw failed(FailureReason.UNAVAILABLE);
        }
        int status = response.statusCode();
        if (unavailableStatus(status)) {
            closeQuietly(response.body());
            state = state.failed("UNAVAILABLE");
            throw failed(FailureReason.UNAVAILABLE);
        }
        if ((status != 200 && status != 409)
                || !MEDIA_TYPE.equalsIgnoreCase(response.headers()
                .firstValue("Content-Type").orElse(""))
                || !PROTOCOL_VERSION.equals(response.headers()
                .firstValue(PROTOCOL_HEADER).orElse(""))) {
            closeQuietly(response.body());
            state = state.failed("INVALID_RESPONSE");
            throw failed(FailureReason.INVALID_RESPONSE);
        }
        SignedResponse signed;
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
            if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publisher response is oversized");
            }
            signed = objectMapper.readValue(bytes, SignedResponse.class);
            verify(safeRequest, requestFingerprint, signed, status, clock.instant());
        } catch (IOException | RuntimeException invalid) {
            if (invalid instanceof PublisherException publisherFailure) {
                throw publisherFailure;
            }
            state = state.failed("INVALID_RESPONSE");
            throw failed(FailureReason.INVALID_RESPONSE);
        }
        if (signed.material().decision() == ResponseDecision.CONFLICT) {
            state = state.conflict();
            throw failed(FailureReason.AUTHENTICATED_CONFLICT);
        }
        ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt receipt =
                signed.material().toReceipt();
        state = signed.material().decision() == ResponseDecision.PUBLISHED
                ? state.published(receipt.publishedAt())
                : state.replayed(receipt.publishedAt());
        return receipt;
    }

    /** Returns static protocol truth and current key-lifecycle readiness without remote I/O. */
    @Override
    public Descriptor descriptor() {
        return new Descriptor(Descriptor.SCHEMA_VERSION, keyUsableAt(clock.instant()),
                true, true, true, true, true, MAXIMUM_REQUEST_BYTES);
    }

    /** Returns aggregate local adapter outcomes without remote identities. */
    @Override
    public Snapshot snapshot() {
        RuntimeState observed = state;
        boolean keyAvailable = keyUsableAt(clock.instant());
        return new Snapshot(Snapshot.SCHEMA_VERSION,
                keyAvailable && observed.available(),
                keyAvailable ? observed.status() : "KEY_UNAVAILABLE",
                observed.publishedCount(), observed.replayCount(),
                observed.conflictCount(), observed.failureCount(),
                observed.lastSuccessfulPublicationAt());
    }

    private void verify(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request,
            String requestFingerprint,
            SignedResponse signed,
            int status,
            Instant now) {
        var material = signed.material();
        boolean statusMatches = status == 200
                && material.decision() != ResponseDecision.CONFLICT
                || status == 409 && material.decision() == ResponseDecision.CONFLICT;
        Instant headIssuedAt = request.bundle().transitions().getLast().material().issuedAt();
        if (!statusMatches || !signed.fingerprintVerified(objectMapper)
                || !trustDomain.equals(material.trustDomain())
                || !publisherId.equals(material.publisherId())
                || !keyId.equals(material.keyId())
                || !requestFingerprint.equals(material.requestFingerprint())
                || !request.publicationId().equals(material.publicationId())
                || !request.scopeId().equals(material.scopeId())
                || !request.rootSetId().equals(material.rootSetId())
                || request.sequence() != material.sequence()
                || !request.expectedPreviousMaterialFingerprint().equals(
                material.expectedPreviousMaterialFingerprint())
                || !request.bundleFingerprint().equals(material.bundleFingerprint())
                || !request.headMaterialFingerprint().equals(
                material.headMaterialFingerprint())
                || material.signedAt().isBefore(
                now.minus(settings.maximumResponseLifetime()))
                || material.signedAt().isAfter(now.plus(settings.clockSkew()))
                || !now.isBefore(material.expiresAt())
                || material.expiresAt().isAfter(
                material.signedAt().plus(settings.maximumResponseLifetime()))
                || material.signedAt().isBefore(keyNotBefore)
                || !material.signedAt().isBefore(keyExpiresAt)
                || material.expiresAt().isAfter(keyExpiresAt)
                || material.publishedAt() != null
                && material.publishedAt().isBefore(headIssuedAt)
                || !verifySignature(signed)) {
            throw new IllegalArgumentException(
                    "Bootstrap-root publisher response binding is invalid");
        }
    }

    private boolean verifySignature(SignedResponse response) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(response.materialFingerprint().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(response.signatureBase64()));
        } catch (Exception invalid) {
            return false;
        }
    }

    private boolean keyUsableAt(Instant now) {
        return !now.isBefore(keyNotBefore) && now.isBefore(keyExpiresAt);
    }

    private byte[] write(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (IOException invalid) {
            throw new IllegalStateException(
                    "Bootstrap-root publication request cannot be serialized");
        }
    }

    private static PublicKey requireEd25519(PublicKey value) {
        PublicKey required = Objects.requireNonNull(value, "publicKey");
        if (!"EdDSA".equalsIgnoreCase(required.getAlgorithm())
                && !"Ed25519".equalsIgnoreCase(required.getAlgorithm())
                || required.getEncoded() == null || required.getEncoded().length != 44) {
            throw new IllegalArgumentException(
                    "Bootstrap-root publisher response key is invalid");
        }
        return required;
    }

    private static URI validateUri(URI value, boolean allowInsecureLoopback) {
        URI uri = Objects.requireNonNull(value, "endpoint");
        if (uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Bootstrap-root publisher URI is invalid");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
        if (!allowInsecureLoopback || !loopback
                || !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(
                    "Bootstrap-root publisher URI must use HTTPS");
        }
        return uri;
    }

    private static Instant wholeSecond(Instant value, String field) {
        Instant required = Objects.requireNonNull(value, field);
        if (required.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Bootstrap-root publisher key time is invalid");
        }
        return required;
    }

    private static boolean unavailableStatus(int status) {
        return status == 408 || status == 425 || status == 429
                || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static String etag(String fingerprint) {
        return '"' + fingerprint + '"';
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The bounded protocol classification remains authoritative.
        }
    }

    private static ObjectMapper strict(ObjectMapper source) {
        return source.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static PublisherException failed(FailureReason reason) {
        return new PublisherException(reason);
    }

    private static String identifier(String value, String field) {
        String result = normalized(value);
        if (!ExternalSequenceAnchorBootstrapRootPublisher.IDENTIFIER
                .matcher(result).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    /**
     * Bounded transport and signed-response freshness policy.
     *
     * @param requestTimeout complete HTTP call timeout from 100 ms through 30 seconds
     * @param clockSkew maximum accepted publisher clock lead from zero through 30 seconds
     * @param maximumResponseLifetime signed response lifetime from one through 60 seconds
     * @param allowInsecureLoopback explicit local-test HTTP escape hatch
     */
    public record Settings(
            Duration requestTimeout,
            Duration clockSkew,
            Duration maximumResponseLifetime,
            boolean allowInsecureLoopback) {

        /** Enforces finite millisecond-exact transport and replay windows. */
        public Settings {
            requestTimeout = requiredDuration(requestTimeout, Duration.ofMillis(100),
                    Duration.ofSeconds(30), "requestTimeout");
            clockSkew = requiredDuration(clockSkew, Duration.ZERO,
                    Duration.ofSeconds(30), "clockSkew");
            maximumResponseLifetime = requiredDuration(maximumResponseLifetime,
                    Duration.ofSeconds(1), Duration.ofSeconds(60),
                    "maximumResponseLifetime");
            if (requestTimeout.compareTo(maximumResponseLifetime) >= 0) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publisher timing policy is invalid");
            }
        }

        private static Duration requiredDuration(
                Duration value,
                Duration minimum,
                Duration maximum,
                String field) {
            Duration required = Objects.requireNonNull(value, field);
            if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0
                    || !required.equals(Duration.ofMillis(required.toMillis()))) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publisher timing policy is invalid");
            }
            return required;
        }
    }

    private record RuntimeState(
            boolean available,
            String status,
            long publishedCount,
            long replayCount,
            long conflictCount,
            long failureCount,
            Instant lastSuccessfulPublicationAt) {

        private static RuntimeState initial() {
            return new RuntimeState(false, "UNVERIFIED", 0L, 0L, 0L, 0L, null);
        }

        private RuntimeState published(Instant at) {
            return new RuntimeState(true, "HEALTHY", publishedCount + 1L,
                    replayCount, conflictCount, failureCount, at);
        }

        private RuntimeState replayed(Instant at) {
            return new RuntimeState(true, "HEALTHY", publishedCount,
                    replayCount + 1L, conflictCount, failureCount, at);
        }

        private RuntimeState conflict() {
            return new RuntimeState(false, "AUTHENTICATED_CONFLICT", publishedCount,
                    replayCount, conflictCount + 1L, failureCount,
                    lastSuccessfulPublicationAt);
        }

        private RuntimeState failed(String nextStatus) {
            return new RuntimeState(false, nextStatus, publishedCount, replayCount,
                    conflictCount, failureCount + 1L, lastSuccessfulPublicationAt);
        }
    }
}
