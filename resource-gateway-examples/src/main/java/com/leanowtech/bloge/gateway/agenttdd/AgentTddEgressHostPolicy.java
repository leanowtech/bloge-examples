package com.leanowtech.bloge.gateway.agenttdd;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact-host allowlist shared by Agent TDD resource declaration and sandbox attestation. */
@Component
public final class AgentTddEgressHostPolicy {
    private final Set<String> allowedHosts;
    private final HostResolver resolver;

    /**
     * Creates a fail-closed policy from a comma-separated exact hostname list.
     *
     * @param configuredHosts hostnames only; schemes, paths and wildcard suffixes are not accepted
     */
    @Autowired
    public AgentTddEgressHostPolicy(
            @Value("${gateway.agent-tdd.attestation.allowed-hosts:localhost,127.0.0.1}") String configuredHosts) {
        this(configuredHosts, host -> List.of(InetAddress.getAllByName(host)));
    }

    AgentTddEgressHostPolicy(String configuredHosts, HostResolver resolver) {
        allowedHosts = Arrays.stream(configuredHosts == null ? new String[0] : configuredHosts.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(AgentTddEgressHostPolicy::normalizedHost)
                .collect(Collectors.toUnmodifiableSet());
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
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

    /**
     * Resolves an admitted target twice and returns the stable, globally routable address set.
     *
     * <p>An explicit {@code localhost} or {@code 127.0.0.1} allowlist entry remains available for
     * the repository's local sandbox. A hostname that merely resolves to a local/private address
     * is rejected. Mixed answers, empty answers, DNS failure, and answers changing between the two
     * checks fail closed.</p>
     *
     * @param urlTemplate admitted descriptor URL template
     * @return immutable pre-dispatch resolution claim
     */
    public Resolution resolveAllowed(String urlTemplate) {
        String host = requireAllowed(urlTemplate);
        Set<String> first = resolve(host);
        Set<String> second = resolve(host);
        if (first.isEmpty() || !first.equals(second)) throw rejected();
        return new Resolution(host, first);
    }

    /**
     * Rechecks that DNS still names the exact address set admitted during plan construction.
     *
     * @param urlTemplate current immutable descriptor template
     * @param admitted earlier resolution claim
     */
    public void requireUnchanged(String urlTemplate, Resolution admitted) {
        if (admitted == null || !requireAllowed(urlTemplate).equals(admitted.host())
                || !resolve(admitted.host()).equals(admitted.addresses())) {
            throw rejected();
        }
    }

    private Set<String> resolve(String host) {
        try {
            List<InetAddress> answers = resolver.resolve(host);
            if (answers == null || answers.isEmpty()) throw rejected();
            boolean explicitLocalSandbox = "localhost".equals(host) || "127.0.0.1".equals(host);
            for (InetAddress answer : answers) {
                if (answer == null || (!explicitLocalSandbox && forbidden(answer))) throw rejected();
            }
            return answers.stream().map(InetAddress::getHostAddress).sorted()
                    .collect(Collectors.toUnmodifiableSet());
        } catch (UnknownHostException failure) {
            throw rejected();
        }
    }

    private static boolean forbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 0xfe) == 0xfc; // IPv6 unique-local fc00::/7
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return (first == 100 && second >= 64 && second <= 127) // shared address space
                || (first == 198 && (second == 18 || second == 19)) // benchmark network
                || first >= 240; // reserved/limited broadcast space
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

    /** Stable, non-persisted DNS claim used only inside one attestation run. */
    public record Resolution(String host, Set<String> addresses) {
        public Resolution {
            host = host == null ? "" : host;
            addresses = addresses == null ? Set.of() : Set.copyOf(addresses);
        }
    }

    /** Resolves one normalized host for deterministic policy tests and the platform resolver. */
    @FunctionalInterface
    interface HostResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }
}
