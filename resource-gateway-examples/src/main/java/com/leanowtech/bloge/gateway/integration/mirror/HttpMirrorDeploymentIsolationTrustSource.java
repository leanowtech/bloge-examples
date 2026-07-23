package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationEnvelope;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneHttpTransport;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict private-PKI, SPKI-pinned, mutual-TLS source for Mirror deployment trust.
 *
 * <p>The source follows no redirects through the supplied control-plane transport, requests one
 * exact vendor protocol, bounds bytes before parsing, rejects duplicate, unknown, missing, and
 * trailing JSON, and verifies the Tool Studio envelope fingerprint. Dynamic integration
 * authorization headers are supplied per request so nonce-based workload authentication is not
 * accidentally cached.</p>
 */
public final class HttpMirrorDeploymentIsolationTrustSource
        implements MirrorDeploymentIsolationTrustSource {
    private static final Pattern PATH_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,254}");
    private static final Pattern HEADER_NAME =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "protocol", "protocolVersion", "resourceGatewayVersion", "schemaVersion",
            "producedAt", "compatibility", "payloadKind", "payloadSchemaVersion",
            "payloadFingerprint", "payload");
    private static final Duration MAXIMUM_ENVELOPE_AGE = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_CLOCK_SKEW = Duration.ofMinutes(1);

    private final ObjectMapper mapper;
    private final Clock clock;
    private final Settings settings;
    private final HttpClient client;
    private final RequestHeadersProvider requestHeaders;
    private final Descriptor descriptor;

    /**
     * Creates one source behind an independently governed workload-identity transport.
     *
     * @param mapper strict protocol mapper
     * @param clock trusted local protocol clock
     * @param transport exact private PKIX, SPKI, mTLS, and workload-identity policy
     * @param settings bounded endpoint and stream coordinates
     * @param requestHeaders dynamic application-level workload authorization headers
     */
    public HttpMirrorDeploymentIsolationTrustSource(
            ObjectMapper mapper,
            Clock clock,
            ControlPlaneHttpTransport transport,
            Settings settings,
            RequestHeadersProvider requestHeaders) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        ControlPlaneHttpTransport exact = Objects.requireNonNull(transport, "transport");
        ControlPlaneHttpTransport.Descriptor security = exact.descriptor();
        if (security.systemTrustStore() || !security.privateTrustStore()
                || !security.serverSpkiPinned() || !security.mutualTls()
                || !security.certificateIdentityBound()) {
            throw new IllegalArgumentException(
                    "Mirror trust source requires private pinned mTLS workload identity");
        }
        this.client = Objects.requireNonNull(
                exact.client(this.settings.requestTimeout()), "trust source client");
        this.requestHeaders = Objects.requireNonNull(requestHeaders, "requestHeaders");
        this.descriptor = new Descriptor(Descriptor.SCHEMA_VERSION,
                security.privateTrustStore(), security.serverSpkiPinned(), security.mutualTls(),
                security.certificateIdentityBound(), true,
                MirrorDeploymentIsolationTrustDistributionProtocol.VERSION,
                this.settings.requestTimeout().toMillis());
    }

    /** {@inheritDoc} */
    @Override
    public MirrorDeploymentIsolationAttestationBundle latestAttestation() {
        URI uri = endpoint("/api/mirror/trust/deployment-isolation/attestations/"
                + path(settings.attestationId()) + "/latest",
                Map.of("deploymentScopeId", settings.deploymentScopeId(),
                        "keySetId", settings.keySetId()));
        return fetch(uri, IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_READ,
                MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                MirrorDeploymentIsolationAttestationBundle.SCHEMA_VERSION,
                MirrorDeploymentIsolationAttestationBundle.class);
    }

    /** {@inheritDoc} */
    @Override
    public MirrorDeploymentIsolationAuthorityKeySetPublication currentAuthority(
            MirrorArtifactRef authorityRef) {
        MirrorArtifactRef ref = Objects.requireNonNull(authorityRef, "authorityRef");
        if (!MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND.equals(ref.kind())
                || !settings.keySetId().equals(ref.id())) {
            throw new SourceException(SourceFailure.REJECTED,
                    "MIRROR_TRUST_AUTHORITY_REFERENCE_INVALID");
        }
        URI uri = endpoint("/api/mirror/trust/deployment-isolation/authority-key-sets/"
                + path(settings.keySetId()) + "/generations/" + ref.revision(),
                Map.of("deploymentScopeId", settings.deploymentScopeId(),
                        "publicationFingerprint", ref.fingerprint()));
        return fetch(uri, IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_READ,
                MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                MirrorDeploymentIsolationAuthorityKeySetPublication.SCHEMA_VERSION,
                MirrorDeploymentIsolationAuthorityKeySetPublication.class);
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    private <T> T fetch(
            URI uri,
            IntegrationOperation operation,
            String payloadKind,
            String payloadSchemaVersion,
            Class<T> payloadType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(settings.requestTimeout())
                    .header("Accept", MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE)
                    .header(MirrorDeploymentIsolationTrustDistributionProtocol.REQUEST_HEADER,
                            MirrorDeploymentIsolationTrustDistributionProtocol.VERSION)
                    .GET();
            authorizedHeaders(operation, uri).forEach(builder::header);
            HttpResponse<InputStream> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new SourceException(failure(response.statusCode()),
                            "MIRROR_TRUST_SOURCE_HTTP_STATUS");
                }
                if (!exactMediaType(response)) {
                    throw new SourceException(SourceFailure.REJECTED,
                            "MIRROR_TRUST_SOURCE_PROTOCOL_DOWNGRADE");
                }
                long declared = response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1L);
                if (declared > settings.maximumResponseBytes()) {
                    throw new SourceException(SourceFailure.REJECTED,
                            "MIRROR_TRUST_SOURCE_BODY_TOO_LARGE");
                }
                byte[] bytes = bounded(body);
                return decode(bytes, payloadKind, payloadSchemaVersion, payloadType);
            }
        } catch (SourceException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SourceException(SourceFailure.UNAVAILABLE,
                    "MIRROR_TRUST_SOURCE_INTERRUPTED", interrupted);
        } catch (IOException | RuntimeException unavailable) {
            throw new SourceException(SourceFailure.UNAVAILABLE,
                    "MIRROR_TRUST_SOURCE_UNAVAILABLE", unavailable);
        }
    }

    private <T> T decode(
            byte[] bytes,
            String payloadKind,
            String payloadSchemaVersion,
            Class<T> payloadType) {
        try {
            JsonNode tree = mapper.readTree(bytes);
            if (tree == null || !tree.isObject()
                    || !fieldNames(tree).equals(ENVELOPE_FIELDS)) {
                throw rejected("MIRROR_TRUST_ENVELOPE_FIELDS_INVALID");
            }
            JavaType type = mapper.getTypeFactory().constructParametricType(
                    IntegrationEnvelope.class, payloadType);
            IntegrationEnvelope<T> envelope = mapper.readerFor(type).readValue(tree);
            Instant now = clock.instant();
            if (!ToolStudioResourceGatewayProtocol.NAME.equals(envelope.protocol())
                    || !ToolStudioResourceGatewayProtocol.VERSION.equals(
                    envelope.protocolVersion())
                    || !ToolStudioResourceGatewayProtocol.ENVELOPE_SCHEMA_VERSION.equals(
                    envelope.schemaVersion())
                    || envelope.resourceGatewayVersion() == null
                    || envelope.resourceGatewayVersion().isBlank()
                    || envelope.producedAt() == null
                    || envelope.producedAt().isAfter(now.plus(MAXIMUM_CLOCK_SKEW))
                    || envelope.producedAt().isBefore(now.minus(MAXIMUM_ENVELOPE_AGE))
                    || envelope.compatibility() == null
                    || !envelope.compatibility().backwardCompatible()
                    || !envelope.compatibility().breakingChanges().isEmpty()
                    || !payloadKind.equals(envelope.payloadKind())
                    || !payloadSchemaVersion.equals(envelope.payloadSchemaVersion())
                    || envelope.payload() == null) {
                throw rejected("MIRROR_TRUST_ENVELOPE_IDENTITY_INVALID");
            }
            String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                    "payloadKind", envelope.payloadKind(),
                    "payloadSchemaVersion", envelope.payloadSchemaVersion(),
                    "payload", envelope.payload()));
            if (!fingerprint.equals(envelope.payloadFingerprint())) {
                throw rejected("MIRROR_TRUST_ENVELOPE_FINGERPRINT_INVALID");
            }
            return envelope.payload();
        } catch (SourceException rejected) {
            throw rejected;
        } catch (IOException | RuntimeException invalid) {
            throw rejected("MIRROR_TRUST_ENVELOPE_INVALID", invalid);
        }
    }

    private Map<String, String> authorizedHeaders(
            IntegrationOperation operation, URI uri) {
        Map<String, String> supplied = requestHeaders.headers(operation, uri);
        if (supplied == null || supplied.size() > 16) {
            throw rejected("MIRROR_TRUST_AUTHORIZATION_HEADERS_INVALID");
        }
        Map<String, String> accepted = new LinkedHashMap<>();
        int bytes = 0;
        for (Map.Entry<String, String> entry : supplied.entrySet()) {
            String name = Objects.requireNonNullElse(entry.getKey(), "").trim();
            String value = Objects.requireNonNullElse(entry.getValue(), "").trim();
            String lower = name.toLowerCase(Locale.ROOT);
            bytes += name.length() + value.length();
            if (!HEADER_NAME.matcher(name).matches() || value.isBlank()
                    || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl)
                    || Set.of("accept", "host", "content-length", "connection",
                    MirrorDeploymentIsolationTrustDistributionProtocol.REQUEST_HEADER
                            .toLowerCase(Locale.ROOT)).contains(lower)
                    || accepted.keySet().stream().anyMatch(
                    existing -> existing.equalsIgnoreCase(name))) {
                throw rejected("MIRROR_TRUST_AUTHORIZATION_HEADERS_INVALID");
            }
            accepted.put(name, value);
        }
        if (bytes > 16 * 1024) {
            throw rejected("MIRROR_TRUST_AUTHORIZATION_HEADERS_INVALID");
        }
        return Map.copyOf(accepted);
    }

    private byte[] bounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(settings.maximumResponseBytes(), 16 * 1024));
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > settings.maximumResponseBytes()) {
                throw rejected("MIRROR_TRUST_SOURCE_BODY_TOO_LARGE");
            }
            output.write(buffer, 0, read);
        }
        if (total == 0) {
            throw rejected("MIRROR_TRUST_SOURCE_BODY_EMPTY");
        }
        return output.toByteArray();
    }

    private URI endpoint(String suffix, Map<String, String> query) {
        StringBuilder uri = new StringBuilder(settings.baseUri().toString());
        uri.append(suffix).append('?');
        query.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (uri.charAt(uri.length() - 1) != '?') {
                uri.append('&');
            }
            uri.append(encoded(entry.getKey())).append('=').append(encoded(entry.getValue()));
        });
        return URI.create(uri.toString());
    }

    private static Set<String> fieldNames(JsonNode tree) {
        Set<String> fields = new java.util.HashSet<>();
        tree.fieldNames().forEachRemaining(fields::add);
        return Set.copyOf(fields);
    }

    private static boolean exactMediaType(HttpResponse<?> response) {
        List<String> values = response.headers().allValues("Content-Type");
        if (values.size() != 1) {
            return false;
        }
        String value = values.getFirst().trim().toLowerCase(Locale.ROOT);
        String expected = MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE;
        return value.equals(expected) || value.startsWith(expected + ";");
    }

    private static SourceFailure failure(int status) {
        if (status == 404) {
            return SourceFailure.NOT_FOUND;
        }
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return SourceFailure.UNAVAILABLE;
        }
        return SourceFailure.REJECTED;
    }

    private static String path(String value) {
        if (!PATH_IDENTIFIER.matcher(value).matches()) {
            throw rejected("MIRROR_TRUST_PATH_IDENTIFIER_INVALID");
        }
        return encoded(value);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static SourceException rejected(String code) {
        return new SourceException(SourceFailure.REJECTED, code);
    }

    private static SourceException rejected(String code, Throwable cause) {
        return new SourceException(SourceFailure.REJECTED, code, cause);
    }

    /** Supplies fresh application-level workload authorization for each exact request URI. */
    @FunctionalInterface
    public interface RequestHeadersProvider {
        /**
         * Produces application-level authorization for one uncached request.
         *
         * @param operation exact Resource Gateway integration operation
         * @param uri exact method-independent request target
         * @return bounded immutable-style header map without transport-owned headers
         */
        Map<String, String> headers(IntegrationOperation operation, URI uri);
    }

    /**
     * Bounded remote source policy and exact governed stream coordinates.
     *
     * @param baseUri HTTPS Resource Gateway origin with optional deployment path prefix
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact path-safe authority key-set stream
     * @param attestationId exact path-safe attestation stream
     * @param requestTimeout finite connect and request deadline
     * @param maximumResponseBytes hard response-body bound before parsing
     * @param allowInsecureLoopback permits HTTP only on loopback for protocol tests
     */
    public record Settings(
            URI baseUri,
            String deploymentScopeId,
            String keySetId,
            String attestationId,
            Duration requestTimeout,
            int maximumResponseBytes,
            boolean allowInsecureLoopback) {

        /** Validates endpoint, identifiers, and resource bounds. */
        public Settings {
            deploymentScopeId = normalized(deploymentScopeId);
            keySetId = normalized(keySetId);
            attestationId = normalized(attestationId);
            requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (!validBase(baseUri, allowInsecureLoopback)
                    || deploymentScopeId.isBlank() || deploymentScopeId.length() > 512
                    || !PATH_IDENTIFIER.matcher(keySetId).matches()
                    || !PATH_IDENTIFIER.matcher(attestationId).matches()
                    || requestTimeout.compareTo(Duration.ofMillis(100)) < 0
                    || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || maximumResponseBytes < 1024
                    || maximumResponseBytes
                    > MirrorDeploymentIsolationAgentSnapshotIntegrity.MAXIMUM_SNAPSHOT_BYTES) {
                throw new IllegalArgumentException(
                        "Mirror deployment isolation trust source settings are invalid");
            }
            String exact = baseUri.toString();
            while (exact.endsWith("/")) {
                exact = exact.substring(0, exact.length() - 1);
            }
            baseUri = URI.create(exact);
        }

        private Settings validated() {
            return this;
        }

        private static boolean validBase(URI uri, boolean allowInsecureLoopback) {
            if (uri == null || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return false;
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            return allowInsecureLoopback && "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost())
                    || "::1".equals(uri.getHost()));
        }
    }

    /** Stable remote failure family. */
    public enum SourceFailure {
        /** Transport or remote service was transiently unavailable. */
        UNAVAILABLE,
        /** Remote response or local authorization policy was invalid. */
        REJECTED,
        /** Exact current trust coordinates were absent or moved. */
        NOT_FOUND
    }

    /** Payload-free source failure safe for agent health projection. */
    public static final class SourceException extends RuntimeException {
        /** Stable source failure family. */
        private final SourceFailure failure;
        /** Stable payload-free reason code. */
        private final String reasonCode;

        /**
         * Creates one bounded source failure.
         *
         * @param failure stable failure family
         * @param reasonCode stable payload-free reason code
         */
        public SourceException(SourceFailure failure, String reasonCode) {
            this(failure, reasonCode, null);
        }

        /**
         * Creates one bounded source failure with a non-projected cause.
         *
         * @param failure stable failure family
         * @param reasonCode stable payload-free reason code
         * @param cause internal diagnostic cause that must not enter health projections
         */
        public SourceException(SourceFailure failure, String reasonCode, Throwable cause) {
            super(normalized(reasonCode), cause);
            this.failure = Objects.requireNonNull(failure, "failure");
            this.reasonCode = normalized(reasonCode);
            if (!this.reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("source reasonCode is invalid");
            }
        }

        /**
         * Returns the stable source failure family.
         *
         * @return stable source failure family
         */
        public SourceFailure failure() {
            return failure;
        }

        /**
         * Returns the stable payload-free reason code.
         *
         * @return stable payload-free reason code
         */
        public String reasonCode() {
            return reasonCode;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
