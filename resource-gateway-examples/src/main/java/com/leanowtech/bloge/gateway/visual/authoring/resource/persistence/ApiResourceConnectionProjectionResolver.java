package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceTransportSafetyPolicy;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only seam for resolving exact Connection authority and non-secret metadata.
 *
 * <p>The authoritative API Resource stores only a Connection id.  Implementations
 * must return the exact committed revision and metadata fingerprint together
 * with a safe base URL, defaults and timeout. Credentials are deliberately not
 * part of this DTO and must remain in the Connection/Secret runtime.</p>
 */
@FunctionalInterface
public interface ApiResourceConnectionProjectionResolver {
    /**
     * Resolves one committed snapshot for an exact scope and connection id.
     *
     * @param scope authoring scope
     * @param connectionId connection identity from the resource authority
     * @return authority and metadata, or empty when the connection is not projection-ready
     */
    Optional<ResolvedConnection> resolve(AuthoringScope scope, String connectionId);

    /**
     * Resolves the exact Connection snapshot owned by the same compound save.
     * Existing-only adapters may retain the committed lookup behavior.
     */
    default Optional<ResolvedConnection> resolveForStage(AuthoringScope scope, String connectionId,
                                                          CommandLease lease) {
        return resolve(scope, connectionId);
    }

    /** Exact authority plus the non-secret metadata consumed by projection compilation. */
    record ResolvedConnection(ApiResourceConnectionSnapshot snapshot, ConnectionMetadata metadata) {
        /** Ensures both halves are present before compilation. */
        public ResolvedConnection {
            if (snapshot == null || metadata == null) {
                throw new IllegalArgumentException("resolved connection is incomplete");
            }
        }
    }

    /** Non-secret Connection metadata needed by a runtime descriptor. */
    record ConnectionMetadata(String baseUrl, Map<String, String> defaultHeaders, Duration timeout,
                              String apiKeyHeader) {
        /** Backward-compatible metadata constructor without an API-key header. */
        public ConnectionMetadata(String baseUrl, Map<String, String> defaultHeaders, Duration timeout) {
            this(baseUrl, defaultHeaders, timeout, "");
        }

        /** Validates that metadata cannot smuggle credentials or an unbounded URL. */
        public ConnectionMetadata {
            validateBaseUrl(baseUrl);
            ApiResourceTransportSafetyPolicy.requireSafeApiKeyHeader(apiKeyHeader);
            apiKeyHeader = apiKeyHeader == null ? "" : apiKeyHeader.trim();
            defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
            ApiResourceTransportSafetyPolicy.requireSafeDefaults(defaultHeaders, apiKeyHeader);
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("connection timeout must be positive");
            }
        }

        private static void validateBaseUrl(String baseUrl) {
            if (baseUrl == null || baseUrl.length() > 2048
                    || baseUrl.chars().anyMatch(character -> Character.isISOControl(character)
                    || Character.isWhitespace(character))) {
                throw new IllegalArgumentException("connection baseUrl is invalid");
            }
            try {
                URI uri = new URI(baseUrl);
                String scheme = uri.getScheme();
                if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null || uri.isOpaque()) {
                    throw new IllegalArgumentException("connection baseUrl is invalid");
                }
            } catch (URISyntaxException exception) {
                throw new IllegalArgumentException("connection baseUrl is invalid", exception);
            }
        }
    }
}
