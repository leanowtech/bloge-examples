package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.mirror.HttpOnlineReadOnlyShadowCandidateAuthority;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Strict process-local configuration for the isolated online candidate sidecar adapter.
 *
 * @param enabled explicit online candidate authority activation
 * @param baseUri exact regional sidecar HTTPS origin
 * @param requestTimeoutMillis finite connect and request upper bound
 * @param maximumResponseBytes hard response-body bound before parsing
 * @param allowInsecureLoopback permits HTTP only for loopback protocol tests
 */
@ConfigurationProperties(
        prefix = OnlineReadOnlyShadowCandidateProperties.PREFIX,
        ignoreUnknownFields = false)
public record OnlineReadOnlyShadowCandidateProperties(
        Boolean enabled,
        String baseUri,
        Long requestTimeoutMillis,
        Integer maximumResponseBytes,
        Boolean allowInsecureLoopback
) {
    /** Configuration prefix shared by runtime and operating documentation. */
    public static final String PREFIX =
            "gateway.testing.mirror.read-only-shadow.online-candidate";

    /** Applies conservative finite defaults and validates enabled settings. */
    public OnlineReadOnlyShadowCandidateProperties {
        enabled = Boolean.TRUE.equals(enabled);
        baseUri = baseUri == null
                ? "" : baseUri.trim();
        requestTimeoutMillis =
                requestTimeoutMillis == null
                        ? 5_000L
                        : requestTimeoutMillis;
        maximumResponseBytes =
                maximumResponseBytes == null
                        ? 8 * 1024 * 1024
                        : maximumResponseBytes;
        allowInsecureLoopback =
                Boolean.TRUE.equals(
                        allowInsecureLoopback);
        if (enabled) {
            settings(
                    baseUri,
                    requestTimeoutMillis,
                    maximumResponseBytes,
                    allowInsecureLoopback);
        }
    }

    /**
     * Builds the validated immutable HTTP authority policy.
     *
     * @return exact bounded sidecar settings
     */
    public HttpOnlineReadOnlyShadowCandidateAuthority.Settings
    settings() {
        if (!enabled) {
            throw new IllegalStateException(
                    "online candidate settings are disabled");
        }
        return settings(
                baseUri,
                requestTimeoutMillis,
                maximumResponseBytes,
                allowInsecureLoopback);
    }

    private static HttpOnlineReadOnlyShadowCandidateAuthority.Settings
    settings(
            String baseUri,
            long requestTimeoutMillis,
            int maximumResponseBytes,
            boolean allowInsecureLoopback) {
        URI uri;
        try {
            uri = URI.create(baseUri);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "online candidate baseUri is invalid",
                    invalid);
        }
        return new HttpOnlineReadOnlyShadowCandidateAuthority
                .Settings(
                uri,
                Duration.ofMillis(
                        requestTimeoutMillis),
                maximumResponseBytes,
                allowInsecureLoopback);
    }
}
