package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneHttpTransport;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict pinned-mTLS HTTP authority for one regional online baseline TEE sidecar.
 */
public final class HttpOnlineReadOnlyShadowBaselineAuthority
        implements OnlineReadOnlyShadowBaselineAuthority {
    private static final Pattern PATH_IDENTIFIER =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9@._:-]{0,511}");
    private static final Pattern HEADER_NAME =
            Pattern.compile(
                    "[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Set<String> RESERVED_HEADERS =
            Set.of(
                    "accept",
                    "content-type",
                    "host",
                    "content-length",
                    "connection",
                    OnlineReadOnlyShadowBaselineProtocol
                            .VERSION_HEADER
                            .toLowerCase(Locale.ROOT),
                    OnlineReadOnlyShadowBaselineProtocol
                            .EXECUTION_ID_HEADER
                            .toLowerCase(Locale.ROOT));

    private final ObjectMapper mapper;
    private final Clock clock;
    private final Settings settings;
    private final HttpClient client;
    private final RequestHeadersProvider requestHeaders;

    /**
     * Creates one strict sidecar authority behind a dedicated workload identity.
     *
     * @param mapper canonical protocol mapper
     * @param clock trusted consumer clock
     * @param transport exact role-separated private PKIX, pinning, and mTLS policy
     * @param settings bounded endpoint and body policy
     * @param requestHeaders fresh application authorization for each request
     */
    public HttpOnlineReadOnlyShadowBaselineAuthority(
            ObjectMapper mapper,
            Clock clock,
            OnlineReadOnlyShadowBaselineTransport transport,
            Settings settings,
            RequestHeadersProvider requestHeaders) {
        this.mapper = Objects.requireNonNull(
                mapper, "mapper").copy()
                .enable(
                        JsonParser.Feature
                                .STRICT_DUPLICATE_DETECTION)
                .enable(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(
                        DeserializationFeature
                                .FAIL_ON_TRAILING_TOKENS);
        this.clock = Objects.requireNonNull(
                clock, "clock");
        this.settings = Objects.requireNonNull(
                settings, "settings").validated();
        OnlineReadOnlyShadowBaselineTransport exact =
                Objects.requireNonNull(
                        transport, "transport");
        ControlPlaneHttpTransport.Descriptor security =
                exact.descriptor();
        if (security.systemTrustStore()
                || !security.privateTrustStore()
                || !security.serverSpkiPinned()
                || !security.mutualTls()
                || !security.certificateIdentityBound()) {
            throw new IllegalArgumentException(
                    "online baseline authority requires private pinned mTLS workload identity");
        }
        this.client = Objects.requireNonNull(
                exact.client(
                        this.settings.requestTimeout()),
                "online baseline client");
        this.requestHeaders = Objects.requireNonNull(
                requestHeaders, "requestHeaders");
    }

    @Override
    public boolean ready() {
        try {
            HttpRequest.Builder request =
                    request(endpoint(
                            "/api/mirror/shadow/online-baseline/capabilities",
                            Map.of()), settings.requestTimeout())
                            .GET();
            authorize(
                    request,
                    IntegrationOperation
                            .MIRROR_SHADOW_ONLINE_BASELINE_CAPABILITY,
                    request.build().uri());
            OnlineReadOnlyShadowBaselineProtocol.Capability
                    capability = exchange(
                    request.build(),
                    OnlineReadOnlyShadowBaselineProtocol
                            .Capability.class);
            return capability.ready(clock);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public OnlineReadOnlyShadowBaselineObservation observe(
            OnlineReadOnlyShadowBaselineCommand command) {
        OnlineReadOnlyShadowBaselineCommand exact =
                Objects.requireNonNull(
                        command, "command");
        Duration timeout = requestTimeout(
                exact.deadlineAt());
        URI uri = endpoint(
                "/api/mirror/shadow/online-baseline/observations",
                Map.of());
        byte[] body = encode(exact);
        HttpRequest.Builder request =
                request(uri, timeout)
                        .header(
                                "Content-Type",
                                OnlineReadOnlyShadowBaselineProtocol
                                        .MEDIA_TYPE)
                        .header(
                                OnlineReadOnlyShadowBaselineProtocol
                                        .EXECUTION_ID_HEADER,
                                exact.executionId())
                        .POST(
                                HttpRequest.BodyPublishers
                                        .ofByteArray(body));
        authorize(
                request,
                IntegrationOperation
                        .MIRROR_SHADOW_ONLINE_BASELINE_EXECUTE,
                uri);
        return exchange(
                request.build(),
                OnlineReadOnlyShadowBaselineObservation
                        .class);
    }

    @Override
    public OnlineReadOnlyShadowBaselineObservation resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef observationRef) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        MirrorArtifactRef ref = Objects.requireNonNull(
                observationRef, "observationRef");
        if (!OnlineReadOnlyShadowBaselineObservation
                .ARTIFACT_KIND.equals(ref.kind())) {
            throw rejected(
                    "ONLINE_BASELINE_OBSERVATION_REFERENCE_INVALID");
        }
        URI uri = endpoint(
                "/api/mirror/shadow/online-baseline/observations/"
                        + path(ref.id())
                        + "/revisions/" + ref.revision(),
                scopeQuery(exactScope, ref.fingerprint()));
        HttpRequest.Builder request =
                request(uri, settings.requestTimeout())
                        .GET();
        authorize(
                request,
                IntegrationOperation
                        .MIRROR_SHADOW_ONLINE_BASELINE_READ,
                uri);
        return exchange(
                request.build(),
                OnlineReadOnlyShadowBaselineObservation
                        .class);
    }

    private HttpRequest.Builder request(
            URI uri,
            Duration timeout) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header(
                        "Accept",
                        OnlineReadOnlyShadowBaselineProtocol
                                .MEDIA_TYPE)
                .header(
                        OnlineReadOnlyShadowBaselineProtocol
                                .VERSION_HEADER,
                        OnlineReadOnlyShadowBaselineProtocol
                                .VERSION);
    }

    private <T> T exchange(
            HttpRequest request,
            Class<T> type) {
        try {
            HttpResponse<InputStream> response =
                    client.send(
                            request,
                            HttpResponse.BodyHandlers
                                    .ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new AuthorityException(
                            failure(response.statusCode()),
                            "ONLINE_BASELINE_HTTP_STATUS");
                }
                if (!exactProtocol(response)) {
                    throw rejected(
                            "ONLINE_BASELINE_PROTOCOL_DOWNGRADE");
                }
                long declared = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1L);
                if (declared > settings
                        .maximumResponseBytes()) {
                    throw rejected(
                            "ONLINE_BASELINE_BODY_TOO_LARGE");
                }
                return decode(
                        bounded(body), type);
            }
        } catch (AuthorityException classified) {
            throw classified;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable(
                    "ONLINE_BASELINE_INTERRUPTED",
                    interrupted);
        } catch (IOException unavailable) {
            throw unavailable(
                    "ONLINE_BASELINE_UNAVAILABLE",
                    unavailable);
        } catch (RuntimeException invalid) {
            throw rejected(
                    "ONLINE_BASELINE_RESPONSE_INVALID",
                    invalid);
        }
    }

    private <T> T decode(
            byte[] bytes,
            Class<T> type) {
        try {
            return mapper.readValue(bytes, type);
        } catch (IOException | RuntimeException invalid) {
            throw rejected(
                    "ONLINE_BASELINE_RESPONSE_INVALID",
                    invalid);
        }
    }

    private byte[] encode(
            OnlineReadOnlyShadowBaselineCommand command) {
        try {
            byte[] body = mapper.writeValueAsBytes(
                    command);
            if (body.length == 0
                    || body.length
                    > OnlineReadOnlyShadowBaselineCommand
                    .MAXIMUM_CANONICAL_BYTES) {
                throw rejected(
                        "ONLINE_BASELINE_COMMAND_TOO_LARGE");
            }
            return body;
        } catch (AuthorityException rejected) {
            throw rejected;
        } catch (RuntimeException invalid) {
            throw rejected(
                    "ONLINE_BASELINE_COMMAND_INVALID",
                    invalid);
        } catch (IOException invalid) {
            throw rejected(
                    "ONLINE_BASELINE_COMMAND_INVALID",
                    invalid);
        }
    }

    private void authorize(
            HttpRequest.Builder request,
            IntegrationOperation operation,
            URI uri) {
        Map<String, String> supplied =
                requestHeaders.headers(operation, uri);
        if (supplied == null
                || supplied.size() > 16) {
            throw rejected(
                    "ONLINE_BASELINE_AUTHORIZATION_HEADERS_INVALID");
        }
        Map<String, String> accepted =
                new LinkedHashMap<>();
        int bytes = 0;
        for (Map.Entry<String, String> entry
                : supplied.entrySet()) {
            String name = Objects.requireNonNullElse(
                    entry.getKey(), "").trim();
            String value = Objects.requireNonNullElse(
                    entry.getValue(), "").trim();
            bytes += name.length() + value.length();
            if (!HEADER_NAME.matcher(name).matches()
                    || value.isBlank()
                    || value.length() > 4096
                    || value.chars().anyMatch(
                    Character::isISOControl)
                    || RESERVED_HEADERS.contains(
                    name.toLowerCase(Locale.ROOT))
                    || accepted.keySet().stream()
                    .anyMatch(
                            existing -> existing
                                    .equalsIgnoreCase(name))) {
                throw rejected(
                        "ONLINE_BASELINE_AUTHORIZATION_HEADERS_INVALID");
            }
            accepted.put(name, value);
        }
        if (bytes > 16 * 1024) {
            throw rejected(
                    "ONLINE_BASELINE_AUTHORIZATION_HEADERS_INVALID");
        }
        accepted.forEach(request::header);
    }

    private Duration requestTimeout(
            java.time.Instant deadlineAt) {
        Duration remaining = Duration.between(
                clock.instant(),
                Objects.requireNonNull(
                        deadlineAt, "deadlineAt"));
        if (remaining.isZero()
                || remaining.isNegative()) {
            throw rejected(
                    "ONLINE_BASELINE_DEADLINE_EXCEEDED");
        }
        return remaining.compareTo(
                settings.requestTimeout()) < 0
                ? remaining : settings.requestTimeout();
    }

    private byte[] bounded(
            InputStream input) throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        Math.min(
                                settings.maximumResponseBytes(),
                                16 * 1024));
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > settings
                    .maximumResponseBytes()) {
                throw rejected(
                        "ONLINE_BASELINE_BODY_TOO_LARGE");
            }
            output.write(buffer, 0, read);
        }
        if (total == 0) {
            throw rejected(
                    "ONLINE_BASELINE_BODY_EMPTY");
        }
        return output.toByteArray();
    }

    private URI endpoint(
            String suffix,
            Map<String, String> query) {
        StringBuilder value = new StringBuilder(
                settings.baseUri().toString())
                .append(suffix);
        query.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> value
                        .append(value.indexOf("?") < 0
                                ? '?' : '&')
                        .append(encoded(entry.getKey()))
                        .append('=')
                        .append(encoded(entry.getValue())));
        return URI.create(value.toString());
    }

    private static Map<String, String> scopeQuery(
            CapabilitySnapshot.Scope scope,
            String fingerprint) {
        Map<String, String> values =
                new LinkedHashMap<>();
        values.put("tenantId", scope.tenantId());
        values.put(
                "organizationId",
                scope.organizationId());
        values.put("projectId", scope.projectId());
        values.put(
                "environmentId",
                scope.environmentId());
        values.put("region", scope.region());
        values.put("fingerprint", fingerprint);
        return Map.copyOf(values);
    }

    private static boolean exactProtocol(
            HttpResponse<?> response) {
        List<String> contentTypes = response.headers()
                .allValues("Content-Type");
        List<String> versions = response.headers()
                .allValues(
                        OnlineReadOnlyShadowBaselineProtocol
                                .VERSION_HEADER);
        if (contentTypes.size() != 1
                || versions.size() != 1
                || !OnlineReadOnlyShadowBaselineProtocol
                .VERSION.equals(versions.getFirst().trim())) {
            return false;
        }
        String actual = contentTypes.getFirst()
                .trim().toLowerCase(Locale.ROOT);
        String expected =
                OnlineReadOnlyShadowBaselineProtocol
                        .MEDIA_TYPE;
        return actual.equals(expected)
                || actual.startsWith(expected + ";");
    }

    private static Failure failure(int status) {
        if (status == 404) {
            return Failure.NOT_FOUND;
        }
        if (status == 408
                || status == 425
                || status == 429
                || status >= 500) {
            return Failure.UNAVAILABLE;
        }
        return Failure.REJECTED;
    }

    private static String path(String value) {
        if (value == null
                || !PATH_IDENTIFIER.matcher(value).matches()) {
            throw rejected(
                    "ONLINE_BASELINE_PATH_IDENTIFIER_INVALID");
        }
        return encoded(value);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(
                Objects.requireNonNullElse(value, ""),
                StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static AuthorityException rejected(
            String reason) {
        return new AuthorityException(
                Failure.REJECTED, reason);
    }

    private static AuthorityException rejected(
            String reason,
            Throwable cause) {
        return new AuthorityException(
                Failure.REJECTED, reason, cause);
    }

    private static AuthorityException unavailable(
            String reason,
            Throwable cause) {
        return new AuthorityException(
                Failure.UNAVAILABLE, reason, cause);
    }

    /**
     * Supplies fresh application-level workload authorization for each exact request.
     */
    @FunctionalInterface
    public interface RequestHeadersProvider {
        /**
         * Produces authorization headers without transport-owned names.
         *
         * @param operation exact integration operation
         * @param uri exact request target
         * @return bounded headers; secrets must never be cached by this adapter
         */
        Map<String, String> headers(
                IntegrationOperation operation,
                URI uri);
    }

    /**
     * Bounded sidecar endpoint and resource policy.
     *
     * @param baseUri HTTPS sidecar origin with optional regional path prefix
     * @param requestTimeout finite connect and request upper bound
     * @param maximumResponseBytes hard body bound before JSON parsing
     * @param allowInsecureLoopback permits HTTP only on loopback for protocol tests
     */
    public record Settings(
            URI baseUri,
            Duration requestTimeout,
            int maximumResponseBytes,
            boolean allowInsecureLoopback
    ) {
        /** Validates endpoint structure, transport deadline, and memory bound. */
        public Settings {
            requestTimeout = Objects.requireNonNull(
                    requestTimeout, "requestTimeout");
            if (!validBase(
                    baseUri, allowInsecureLoopback)
                    || requestTimeout.compareTo(
                    Duration.ofMillis(100)) < 0
                    || requestTimeout.compareTo(
                    Duration.ofSeconds(30)) > 0
                    || maximumResponseBytes < 1024
                    || maximumResponseBytes
                    > OnlineReadOnlyShadowBaselineObservation
                    .MAXIMUM_CANONICAL_BYTES) {
                throw new IllegalArgumentException(
                        "online baseline authority settings are invalid");
            }
            String exact = baseUri.toString();
            while (exact.endsWith("/")) {
                exact = exact.substring(
                        0, exact.length() - 1);
            }
            baseUri = URI.create(exact);
        }

        private Settings validated() {
            return this;
        }

        private static boolean validBase(
                URI uri,
                boolean allowInsecureLoopback) {
            if (uri == null
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return false;
            }
            if ("https".equalsIgnoreCase(
                    uri.getScheme())) {
                return true;
            }
            return allowInsecureLoopback
                    && "http".equalsIgnoreCase(
                    uri.getScheme())
                    && ("localhost".equalsIgnoreCase(
                    uri.getHost())
                    || "127.0.0.1".equals(
                    uri.getHost())
                    || "::1".equals(
                    uri.getHost()));
        }
    }
}
