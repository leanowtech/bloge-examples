package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only seam for resolving non-secret Connection metadata during projection.
 *
 * <p>The authoritative API Resource stores only a Connection id.  Implementations
 * must return a safe base URL, defaults and timeout; credentials are deliberately
 * not part of this DTO and must remain in the Connection/Secret runtime.</p>
 */
@FunctionalInterface
public interface ApiResourceConnectionProjectionResolver {
    /**
     * Resolves metadata for an exact authoring scope and connection id.
     *
     * @param scope authoring scope
     * @param connectionId connection identity from the resource authority
     * @return metadata, or empty when the connection is not projection-ready
     */
    Optional<ConnectionMetadata> resolve(AuthoringScope scope, String connectionId);

    /** Non-secret Connection metadata needed by a runtime descriptor. */
    record ConnectionMetadata(String baseUrl, Map<String, String> defaultHeaders, Duration timeout) {
        private static final Set<String> CREDENTIAL_HEADERS = Set.of(
                "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key");
        /** Validates that metadata cannot smuggle credentials or an unbounded URL. */
        public ConnectionMetadata {
            if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
                    || baseUrl.contains("@") || baseUrl.length() > 2048) {
                throw new IllegalArgumentException("connection baseUrl is invalid");
            }
            defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
            if (defaultHeaders.keySet().stream().anyMatch(key -> key == null || key.isBlank()
                    || CREDENTIAL_HEADERS.contains(key.toLowerCase(Locale.ROOT)))) {
                throw new IllegalArgumentException("connection defaults must not contain credentials");
            }
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("connection timeout must be positive");
        }
    }
}
