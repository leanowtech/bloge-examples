package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Strict HTTPS/mTLS source for normalized certificate-status publications.
 *
 * <p>The adapter follows no redirects, sends the exact durable cursor, accepts one versioned media
 * type and protocol header, bounds the body before parsing, rejects duplicate/unknown/trailing JSON,
 * and preflights scope, contiguous sequence, predecessor, canonical fingerprint, and freshness.
 * The database floor still repeats trust, cursor, inventory, and database-time verification.</p>
 */
public final class HttpControlPlaneCertificateStatusSource
        implements ControlPlaneCertificateStatusSource {

    /** Required response media type. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.control-plane-certificate-status-publication.v1+json";
    /** Exact response header preventing generic JSON downgrade. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-Certificate-Status-Protocol";
    /** Exact protocol header value. */
    public static final String PROTOCOL_VERSION = "certificate-status-publication-v1";

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Settings settings;
    private final HttpClient client;
    private final Descriptor descriptor;

    /**
     * Creates one source behind an independently governed workload-identity transport.
     *
     * @param objectMapper application protocol mapper
     * @param clock publication freshness clock
     * @param transport exact PKIX/SPKI/mTLS/workload-identity policy
     * @param settings bounded endpoint and publication policy
     */
    public HttpControlPlaneCertificateStatusSource(
            ObjectMapper objectMapper,
            Clock clock,
            ControlPlaneHttpTransport transport,
            Settings settings) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        ControlPlaneHttpTransport required = Objects.requireNonNull(transport, "transport");
        ControlPlaneHttpTransport.Descriptor security = required.descriptor();
        if (security.systemTrustStore() || !security.privateTrustStore()
                || !security.serverSpkiPinned() || !security.mutualTls()
                || !security.certificateIdentityBound()) {
            throw new IllegalArgumentException(
                    "Certificate status source requires private pinned mTLS identity");
        }
        client = Objects.requireNonNull(required.client(this.settings.requestTimeout()),
                "certificate status client");
        descriptor = new Descriptor(Descriptor.SCHEMA_VERSION, true,
                security.privateTrustStore(), security.serverSpkiPinned(), security.mutualTls(),
                security.certificateIdentityBound(), true);
    }

    /** {@inheritDoc} */
    @Override
    public FetchResult fetch(Cursor cursor) {
        Cursor required = Objects.requireNonNull(cursor, "cursor");
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri(required))
                    .timeout(settings.requestTimeout())
                    .header("Accept", MEDIA_TYPE)
                    .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                    .GET().build();
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 204) {
                    return exactHeader(response, PROTOCOL_HEADER, PROTOCOL_VERSION)
                            && emptyBody(body) ? FetchResult.unchanged()
                            : FetchResult.rejected("CERTIFICATE_STATUS_PROTOCOL_DOWNGRADE");
                }
                if (response.statusCode() != 200) {
                    return transientStatus(response.statusCode())
                            ? FetchResult.unavailable("CERTIFICATE_STATUS_HTTP_UNAVAILABLE")
                            : FetchResult.rejected("CERTIFICATE_STATUS_HTTP_REJECTED");
                }
                if (!exactHeader(response, "Content-Type", MEDIA_TYPE)
                        || !exactHeader(response, PROTOCOL_HEADER, PROTOCOL_VERSION)) {
                    return FetchResult.rejected("CERTIFICATE_STATUS_PROTOCOL_DOWNGRADE");
                }
                long declared = response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1L);
                if (declared > settings.maximumPublicationBytes()) {
                    return FetchResult.rejected("CERTIFICATE_STATUS_BODY_TOO_LARGE");
                }
                try {
                    byte[] bytes = bounded(body, settings.maximumPublicationBytes());
                    ControlPlaneCertificateStatusPublication publication = objectMapper.readValue(
                            bytes, ControlPlaneCertificateStatusPublication.class);
                    return valid(publication, required)
                            ? FetchResult.publication(publication)
                            : FetchResult.rejected("CERTIFICATE_STATUS_PUBLICATION_INVALID");
                } catch (BodyTooLargeException tooLarge) {
                    return FetchResult.rejected("CERTIFICATE_STATUS_BODY_TOO_LARGE");
                } catch (IOException | RuntimeException invalid) {
                    return FetchResult.rejected("CERTIFICATE_STATUS_PUBLICATION_INVALID");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return FetchResult.unavailable("CERTIFICATE_STATUS_SOURCE_INTERRUPTED");
        } catch (IOException | RuntimeException unavailable) {
            return FetchResult.unavailable("CERTIFICATE_STATUS_SOURCE_UNAVAILABLE");
        }
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    private URI requestUri(Cursor cursor) {
        String query = "deploymentScopeId=" + encoded(settings.deploymentScopeId())
                + "&afterSequence=" + cursor.sequence()
                + "&afterPublicationFingerprint=" + encoded(
                cursor.publicationFingerprint());
        return URI.create(settings.endpointUri() + "?" + query);
    }

    private boolean valid(
            ControlPlaneCertificateStatusPublication publication, Cursor cursor) {
        if (publication == null || !publication.fingerprintVerified(objectMapper)) {
            return false;
        }
        ControlPlaneCertificateStatusPublication.Material material = publication.material();
        Instant now = clock.instant();
        String predecessor = cursor.sequence() == 0 ? "" : cursor.publicationFingerprint();
        return material.deploymentScopeId().equals(settings.deploymentScopeId())
                && cursor.sequence() != Long.MAX_VALUE
                && material.sequence() == cursor.sequence() + 1
                && material.previousPublicationFingerprint().equals(predecessor)
                && !material.issuedAt().isAfter(now.plus(settings.clockSkew()))
                && material.expiresAt().isAfter(now)
                && Duration.between(material.issuedAt(), material.expiresAt())
                .compareTo(settings.maximumPublicationLifetime()) <= 0;
    }

    private static boolean exactHeader(
            HttpResponse<?> response, String name, String expected) {
        List<String> values = response.headers().allValues(name);
        return values.size() == 1
                && expected.equals(values.getFirst().trim().toLowerCase(Locale.ROOT));
    }

    private static boolean transientStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static boolean emptyBody(InputStream body) throws IOException {
        return body.read() == -1;
    }

    private static byte[] bounded(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(maximumBytes, 16 * 1024));
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new BodyTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Bounded remote source and publication-validity policy.
     *
     * @param deploymentScopeId exact Resource Gateway deployment scope
     * @param endpointUri HTTPS endpoint without query, fragment, or user info
     * @param requestTimeoutMillis connect and request deadline in milliseconds
     * @param maximumPublicationBytes hard response-body limit
     * @param clockSkewSeconds bounded source/local clock tolerance
     * @param maximumPublicationLifetimeSeconds maximum publication validity duration
     * @param allowInsecureLoopback permits HTTP only for explicit local protocol tests
     */
    public record Settings(
            String deploymentScopeId,
            String endpointUri,
            long requestTimeoutMillis,
            int maximumPublicationBytes,
            long clockSkewSeconds,
            long maximumPublicationLifetimeSeconds,
            boolean allowInsecureLoopback) {

        /** Validates all remote I/O and publication-time bounds. */
        public Settings {
            deploymentScopeId = normalized(deploymentScopeId);
            endpointUri = normalized(endpointUri);
            URI uri;
            try {
                uri = URI.create(endpointUri);
            } catch (RuntimeException invalid) {
                throw invalid();
            }
            boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                    && isLoopback(uri.getHost()) && allowInsecureLoopback;
            if (!deploymentScopeId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                    || !("https".equalsIgnoreCase(uri.getScheme()) || loopback)
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getPath().startsWith("/")
                    || requestTimeoutMillis < 100 || requestTimeoutMillis > 30_000
                    || maximumPublicationBytes < 1_024
                    || maximumPublicationBytes > 2 * 1024 * 1024
                    || clockSkewSeconds < 0 || clockSkewSeconds > 300
                    || maximumPublicationLifetimeSeconds < 1
                    || maximumPublicationLifetimeSeconds > 86_400) {
                throw invalid();
            }
        }

        private Settings validated() {
            return this;
        }

        /** @return finite request timeout */
        public Duration requestTimeout() {
            return Duration.ofMillis(requestTimeoutMillis);
        }

        /** @return finite accepted source clock skew */
        public Duration clockSkew() {
            return Duration.ofSeconds(clockSkewSeconds);
        }

        /** @return finite maximum publication validity duration */
        public Duration maximumPublicationLifetime() {
            return Duration.ofSeconds(maximumPublicationLifetimeSeconds);
        }
    }

    private static boolean isLoopback(String host) {
        return host != null && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "[::1]".equals(host)
                || "::1".equals(host));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Certificate status source settings are invalid");
    }

    private static final class BodyTooLargeException extends IOException {
    }
}
