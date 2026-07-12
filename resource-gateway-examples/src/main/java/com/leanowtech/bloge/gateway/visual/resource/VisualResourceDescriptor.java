package com.leanowtech.bloge.gateway.visual.resource;

import java.time.Duration;
import java.util.Map;

/**
 * Visual-owned HTTP resource descriptor shape used by catalog projection and OpenAPI previews.
 *
 * <p>This record mirrors the fields the canvas needs to present a resource-backed operator while
 * avoiding a dependency on the resource-gateway runtime descriptor type. Runtime-specific code
 * performs the final translation through an adapter.</p>
 *
 * @param resourceId unique logical resource identifier
 * @param urlTemplate URL template with path placeholders
 * @param method HTTP method
 * @param defaultHeaders headers suggested for every request
 * @param authStrategy optional visual authentication descriptor
 * @param defaultTimeout default request timeout
 * @param parameterMapping expressions mapping operator input to request parts
 * @param responseProtocol response success/payload interpretation strategy
 * @param payloadPath optional dot-path for extracting the response payload
 * @param externalWriteContract managed side-effect protocol mapping for HTTP mutations
 */
public record VisualResourceDescriptor(
        String resourceId,
        String urlTemplate,
        String method,
        Map<String, String> defaultHeaders,
        VisualResourceAuth authStrategy,
        Duration defaultTimeout,
        VisualResourceParameterMapping parameterMapping,
        VisualResourceResponseProtocol responseProtocol,
        String payloadPath,
        ExternalWriteContract externalWriteContract
) {
    /** Backward-compatible constructor for visual descriptors without managed-write metadata. */
    public VisualResourceDescriptor(String resourceId,
                                    String urlTemplate,
                                    String method,
                                    Map<String, String> defaultHeaders,
                                    VisualResourceAuth authStrategy,
                                    Duration defaultTimeout,
                                    VisualResourceParameterMapping parameterMapping,
                                    VisualResourceResponseProtocol responseProtocol,
                                    String payloadPath) {
        this(resourceId, urlTemplate, method, defaultHeaders, authStrategy, defaultTimeout,
                parameterMapping, responseProtocol, payloadPath, null);
    }

    public VisualResourceDescriptor {
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
        defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(30) : defaultTimeout;
        parameterMapping = parameterMapping == null ? VisualResourceParameterMapping.empty() : parameterMapping;
        responseProtocol = responseProtocol == null ? new VisualResourceResponseProtocol.HttpStatus()
                : responseProtocol;
    }

    /** Visual-owned mirror of the runtime descriptor's managed-write contract. */
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
        public ExternalWriteContract {
            schemaVersion = normalized(schemaVersion);
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

        public boolean conformant() {
            return ResourceDescriptorContract.SCHEMA_VERSION.equals(schemaVersion)
                    && !idempotencyKeyParam.isBlank()
                    && !idempotencyHeader.isBlank()
                    && !reconciliationLookupParam.isBlank()
                    && !reconcilerRef.isBlank()
                    && !receiptIdHeader.isBlank()
                    && !provider.isBlank();
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** Keeps the runtime schema literal out of the visual adapter dependency boundary. */
    public static final class ResourceDescriptorContract {
        public static final String SCHEMA_VERSION = "resourceGateway.externalWriteContract.v1";

        private ResourceDescriptorContract() {
        }
    }
}
