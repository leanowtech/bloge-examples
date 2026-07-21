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
 * Strict HTTPS/mTLS adapter for the certificate-rotation event page protocol.
 *
 * <p>The adapter sends the exact committed sequence and page fingerprint, follows no redirects,
 * accepts only the versioned media type and protocol header, bounds the body before parsing, and
 * verifies scope, contiguous sequence, predecessor, page fingerprint, publication time, expiry,
 * and maximum page lifetime. Network and parsing failures become stable payload-free results.</p>
 */
public final class HttpControlPlaneCertificateRotationEventSource
        implements ControlPlaneCertificateRotationEventSource {

    /** Required response media type. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.control-plane-certificate-rotation-event-page.v1+json";
    /** Exact response header that prevents generic JSON downgrade. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-Certificate-Rotation-Event-Protocol";
    /** Exact protocol header value. */
    public static final String PROTOCOL_VERSION = "certificate-rotation-event-page-v1";

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Settings settings;
    private final HttpClient client;
    private final Descriptor descriptor;

    /**
     * Creates one source behind an independently governed workload-identity transport.
     *
     * @param objectMapper application protocol mapper
     * @param clock page publication and expiry clock
     * @param transport exact source PKIX/SPKI/mTLS/workload-identity policy
     * @param settings bounded endpoint and page policy
     */
    public HttpControlPlaneCertificateRotationEventSource(
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
                    "Certificate rotation event source requires private pinned mTLS identity");
        }
        this.client = Objects.requireNonNull(required.client(this.settings.requestTimeout()),
                "event source client");
        this.descriptor = new Descriptor(Descriptor.SCHEMA_VERSION, true,
                security.privateTrustStore(), security.serverSpkiPinned(), security.mutualTls(),
                security.certificateIdentityBound());
    }

    /** {@inheritDoc} */
    @Override
    public FetchResult fetch(Position position) {
        Position required = Objects.requireNonNull(position, "position");
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri(required))
                    .timeout(settings.requestTimeout())
                    .header("Accept", MEDIA_TYPE)
                    .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 204) {
                    return exactHeader(response, PROTOCOL_HEADER, PROTOCOL_VERSION)
                            && emptyBody(body)
                            ? FetchResult.withoutPage(FetchStatus.NO_CHANGE, "NO_EVENTS")
                            : rejected("EVENT_SOURCE_PROTOCOL_DOWNGRADE");
                }
                if (response.statusCode() != 200) {
                    return transientStatus(response.statusCode())
                            ? unavailable("EVENT_SOURCE_HTTP_UNAVAILABLE")
                            : rejected("EVENT_SOURCE_HTTP_REJECTED");
                }
                if (!exactHeader(response, "Content-Type", MEDIA_TYPE)
                        || !exactHeader(response, PROTOCOL_HEADER, PROTOCOL_VERSION)) {
                    return rejected("EVENT_SOURCE_PROTOCOL_DOWNGRADE");
                }
                long declared = response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1L);
                if (declared > settings.maximumPageBytes()) {
                    return rejected("EVENT_SOURCE_PAGE_TOO_LARGE");
                }
                try {
                    byte[] bytes = bounded(body, settings.maximumPageBytes());
                    ControlPlaneCertificateRotationEventPage page = objectMapper.readValue(
                            bytes, ControlPlaneCertificateRotationEventPage.class);
                    return valid(page, required)
                            ? FetchResult.page(page)
                            : rejected("EVENT_SOURCE_PAGE_INVALID");
                } catch (PageTooLargeException tooLarge) {
                    return rejected("EVENT_SOURCE_PAGE_TOO_LARGE");
                } catch (IOException | RuntimeException invalid) {
                    return rejected("EVENT_SOURCE_PAGE_INVALID");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable("EVENT_SOURCE_INTERRUPTED");
        } catch (IOException | RuntimeException unavailable) {
            return FetchResult.withoutPage(FetchStatus.SOURCE_UNAVAILABLE,
                    "EVENT_SOURCE_UNAVAILABLE");
        }
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    private URI requestUri(Position position) {
        String query = "deploymentScopeId=" + encoded(position.deploymentScopeId())
                + "&afterSequence=" + position.sequence()
                + "&afterPageFingerprint=" + encoded(position.pageFingerprint());
        return URI.create(settings.endpointUri() + "?" + query);
    }

    private boolean valid(
            ControlPlaneCertificateRotationEventPage page,
            Position position) {
        if (page == null || !page.fingerprintVerified(objectMapper)) {
            return false;
        }
        ControlPlaneCertificateRotationEventPage.Material material = page.material();
        Instant now = clock.instant();
        return material.deploymentScopeId().equals(position.deploymentScopeId())
                && position.sequence() != Long.MAX_VALUE
                && material.sequence() == position.sequence() + 1
                && material.previousPageFingerprint().equals(position.pageFingerprint())
                && !material.issuedAt().isAfter(now.plus(settings.clockSkew()))
                && material.expiresAt().isAfter(now)
                && Duration.between(material.issuedAt(), material.expiresAt())
                .compareTo(settings.maximumPageLifetime()) <= 0;
    }

    private static boolean exactHeader(
            HttpResponse<?> response,
            String name,
            String expected) {
        List<String> values = response.headers().allValues(name);
        return values.size() == 1
                && expected.equals(values.getFirst().trim().toLowerCase(Locale.ROOT));
    }

    private static boolean transientStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static FetchResult unavailable(String reason) {
        return FetchResult.withoutPage(FetchStatus.SOURCE_UNAVAILABLE, reason);
    }

    private static FetchResult rejected(String reason) {
        return FetchResult.withoutPage(FetchStatus.PROTOCOL_REJECTED, reason);
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
                throw new PageTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Bounded remote source and page-validity policy.
     *
     * @param endpointUri HTTPS endpoint without query, fragment, or user info
     * @param requestTimeoutMillis connect and request deadline in milliseconds
     * @param maximumPageBytes hard response body limit
     * @param clockSkewSeconds bounded source/local clock tolerance
     * @param maximumPageLifetimeSeconds maximum source page validity duration
     * @param allowInsecureLoopback permits HTTP only for explicit local protocol tests
     */
    public record Settings(
            String endpointUri,
            long requestTimeoutMillis,
            int maximumPageBytes,
            long clockSkewSeconds,
            long maximumPageLifetimeSeconds,
            boolean allowInsecureLoopback) {

        /** Validates all remote I/O and publication-time bounds. */
        public Settings {
            endpointUri = Objects.requireNonNullElse(endpointUri, "").trim();
            URI uri;
            try {
                uri = URI.create(endpointUri);
            } catch (RuntimeException invalid) {
                throw invalid();
            }
            boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                    && isLoopback(uri.getHost()) && allowInsecureLoopback;
            if (!("https".equalsIgnoreCase(uri.getScheme()) || loopback)
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getPath().startsWith("/")
                    || requestTimeoutMillis < 100 || requestTimeoutMillis > 30_000
                    || maximumPageBytes < 1_024 || maximumPageBytes > 512 * 1024
                    || clockSkewSeconds < 0 || clockSkewSeconds > 300
                    || maximumPageLifetimeSeconds < 1
                    || maximumPageLifetimeSeconds > 86_400) {
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

        /** @return finite maximum page validity duration */
        public Duration maximumPageLifetime() {
            return Duration.ofSeconds(maximumPageLifetimeSeconds);
        }
    }

    private static boolean isLoopback(String host) {
        return host != null && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "[::1]".equals(host)
                || "::1".equals(host));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Certificate rotation event source settings are invalid");
    }

    private static final class PageTooLargeException extends IOException {
    }
}
