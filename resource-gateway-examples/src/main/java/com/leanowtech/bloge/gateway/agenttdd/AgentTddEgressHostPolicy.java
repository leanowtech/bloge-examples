package com.leanowtech.bloge.gateway.agenttdd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact-host allowlist shared by Agent TDD resource declaration and sandbox attestation. */
@Component
public final class AgentTddEgressHostPolicy {
    private final Set<String> allowedHosts;

    /**
     * Creates a fail-closed policy from a comma-separated exact hostname list.
     *
     * @param configuredHosts hostnames only; schemes, paths and wildcard suffixes are not accepted
     */
    public AgentTddEgressHostPolicy(
            @Value("${gateway.agent-tdd.attestation.allowed-hosts:localhost,127.0.0.1}") String configuredHosts) {
        allowedHosts = Arrays.stream(configuredHosts == null ? new String[0] : configuredHosts.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(AgentTddEgressHostPolicy::normalizedHost)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Requires an HTTP URL template to target one exact configured host.
     *
     * <p>Path placeholders are allowed. Authority placeholders, user-info, non-HTTP schemes,
     * malformed URLs, suffix matches, and an empty allowlist all fail closed.</p>
     *
     * @param urlTemplate resource URL template to inspect without resolving DNS
     * @return normalized admitted hostname
     */
    public String requireAllowed(String urlTemplate) {
        try {
            String normalizedTemplate = urlTemplate == null ? "" : urlTemplate.trim();
            URI uri = URI.create(normalizedTemplate.replaceAll("\\{[^/{}]+}", "placeholder"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getRawUserInfo() != null || uri.getHost() == null) {
                throw rejected();
            }
            String host = normalizedHost(uri.getHost());
            if (!allowedHosts.contains(host)) throw rejected();
            return host;
        } catch (AgentTddToolException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw rejected();
        }
    }

    private static String normalizedHost(String host) {
        try {
            String normalized = IDN.toASCII(host == null ? "" : host.trim(), IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
            if (normalized.isBlank() || normalized.contains("*") || normalized.contains("/")) {
                throw rejected();
            }
            return normalized;
        } catch (IllegalArgumentException invalid) {
            throw rejected();
        }
    }

    private static AgentTddToolException rejected() {
        return new AgentTddToolException(
                "EGRESS_NOT_ALLOWED", "The resource target host is not admitted for Agent TDD sandbox egress.");
    }
}
