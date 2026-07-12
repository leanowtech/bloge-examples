package com.leanowtech.bloge.gateway.resource;

import com.leanowtech.bloge.operators.http.HttpRequestInput.HttpAuth;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Immutable descriptor for an external HTTP resource (API endpoint).
 *
 * <p>A descriptor is registered once in the {@link ResourceRegistry} and then looked up
 * by {@code resourceId} at call time. It captures everything the gateway needs to construct,
 * send, and interpret an HTTP request — URL template, method, headers, authentication,
 * timeout, parameter mapping, response protocol, and payload extraction path.
 *
 * @param resourceId       unique logical identifier (e.g. "user-service.getProfile")
 * @param urlTemplate      URL with {@code {placeholder}} path variables
 *                         (e.g. "https://{host}/users/{userId}/profile")
 * @param method           HTTP method — GET, POST, PUT, or DELETE
 * @param defaultHeaders   headers to include on every request (may be overridden per-call)
 * @param authStrategy     authentication strategy (Bearer, Basic, or API key), or {@code null}
 * @param defaultTimeout   default request timeout; may be overridden per-call
 * @param parameterMapping expressions mapping operator input to path/query/header/cookie/body parameters
 * @param responseProtocol how to determine success or failure from the HTTP response
 * @param payloadPath      dot-notation JSON path to extract the payload from the response body
 *                         (e.g. "data" or "result.items"); {@code null} means use the full body
 * @param externalWriteContract managed side-effect protocol for unsafe HTTP methods
 */
public record ResourceDescriptor(
    String resourceId,
    String urlTemplate,
    String method,
    Map<String, String> defaultHeaders,
    HttpAuth authStrategy,
    Duration defaultTimeout,
    ParameterMapping parameterMapping,
    ResponseProtocol responseProtocol,
    String payloadPath,
    ExternalWriteContract externalWriteContract
) {
    /** Backward-compatible constructor for descriptors created before managed external writes. */
    public ResourceDescriptor(String resourceId,
                              String urlTemplate,
                              String method,
                              Map<String, String> defaultHeaders,
                              HttpAuth authStrategy,
                              Duration defaultTimeout,
                              ParameterMapping parameterMapping,
                              ResponseProtocol responseProtocol,
                              String payloadPath) {
        this(resourceId, urlTemplate, method, defaultHeaders, authStrategy, defaultTimeout,
                parameterMapping, responseProtocol, payloadPath, null);
    }

    public ResourceDescriptor {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
        if (urlTemplate == null || urlTemplate.isBlank()) {
            throw new IllegalArgumentException("urlTemplate must not be blank");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        method = method.toUpperCase();
        defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        if (defaultTimeout == null) {
            defaultTimeout = Duration.ofSeconds(30);
        }
        if (parameterMapping == null) {
            parameterMapping = ParameterMapping.empty();
        }
        if (responseProtocol == null) {
            responseProtocol = new ResponseProtocol.HttpStatus();
        }
    }

    /** Whether this descriptor crosses an HTTP mutation boundary. */
    public boolean externalWrite() {
        return !Set.of("GET", "HEAD", "OPTIONS").contains(method);
    }

    /**
     * Descriptor-owned mapping from operator input/provider response to the BLOGE side-effect protocol.
     * Lookup references must be generated before the request and must be safe to persist as evidence.
     */
    public record ExternalWriteContract(
            String schemaVersion,
            String idempotencyKeyParam,
            String idempotencyHeader,
            String reconciliationLookupParam,
            String reconcilerRef,
            String receiptIdHeader,
            String transactionRefHeader,
            String provider,
            String proofReferenceHeader,
            String proofFingerprintHeader,
            boolean failureResponseNotCommitted
    ) {
        public static final String SCHEMA_VERSION = "resourceGateway.externalWriteContract.v1";

        public ExternalWriteContract {
            schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
            idempotencyKeyParam = normalized(idempotencyKeyParam);
            idempotencyHeader = normalized(idempotencyHeader);
            reconciliationLookupParam = normalized(reconciliationLookupParam);
            reconcilerRef = normalized(reconcilerRef);
            receiptIdHeader = normalized(receiptIdHeader);
            transactionRefHeader = normalized(transactionRefHeader);
            provider = normalized(provider);
            proofReferenceHeader = normalized(proofReferenceHeader);
            proofFingerprintHeader = normalized(proofFingerprintHeader);
        }

        /** Whether this contract can safely admit an HTTP mutation. */
        public boolean conformant() {
            return SCHEMA_VERSION.equals(schemaVersion)
                    && !idempotencyKeyParam.isBlank()
                    && validHeader(idempotencyHeader)
                    && !reconciliationLookupParam.isBlank()
                    && !reconcilerRef.isBlank()
                    && validHeader(receiptIdHeader)
                    && !provider.isBlank();
        }

        private static boolean validHeader(String value) {
            return value != null && value.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+");
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
