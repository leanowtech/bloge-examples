package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.mirror.HttpOnlineReadOnlyShadowBaselineAuthority;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Strict process-local configuration for the regional online baseline sidecar adapter.
 *
 * @param enabled explicit online baseline connector activation
 * @param baseUri exact regional sidecar HTTPS origin
 * @param requestTimeoutMillis finite connect and request upper bound
 * @param maximumResponseBytes hard response-body bound before parsing
 * @param allowInsecureLoopback permits HTTP only for loopback protocol tests
 */
@ConfigurationProperties(
        prefix = OnlineReadOnlyShadowBaselineProperties.PREFIX,
        ignoreUnknownFields = false)
public record OnlineReadOnlyShadowBaselineProperties(
        Boolean enabled,
        String baseUri,
        Long requestTimeoutMillis,
        Integer maximumResponseBytes,
        Boolean allowInsecureLoopback
) {
    /** Configuration prefix shared by runtime and operating documentation. */
    public static final String PREFIX =
            "gateway.testing.mirror.read-only-shadow.online-baseline";

    /** Applies conservative finite defaults and validates enabled settings. */
    public OnlineReadOnlyShadowBaselineProperties {
        enabled = Boolean.TRUE.equals(enabled);
        baseUri = baseUri == null
                ? "" : baseUri.trim();
        requestTimeoutMillis =
                requestTimeoutMillis == null
                        ? 5_000L
                        : requestTimeoutMillis;
        maximumResponseBytes =
                maximumResponseBytes == null
                        ? 512 * 1024
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
    public HttpOnlineReadOnlyShadowBaselineAuthority.Settings
    settings() {
        if (!enabled) {
            throw new IllegalStateException(
                    "online baseline settings are disabled");
        }
        return settings(
                baseUri,
                requestTimeoutMillis,
                maximumResponseBytes,
                allowInsecureLoopback);
    }

    private static HttpOnlineReadOnlyShadowBaselineAuthority.Settings
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
                    "online baseline baseUri is invalid",
                    invalid);
        }
        return new HttpOnlineReadOnlyShadowBaselineAuthority
                .Settings(
                uri,
                Duration.ofMillis(
                        requestTimeoutMillis),
                maximumResponseBytes,
                allowInsecureLoopback);
    }
}
