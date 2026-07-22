package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanCreateRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded strict JSON decoder for the protected mirror plan command.
 *
 * <p>The application ObjectMapper intentionally supports older broad APIs that ignore unknown
 * fields. Mirror protocol schemas promise recursive field closure, so this adapter uses an
 * isolated mapper with {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} and rejects an
 * oversized tree before constructing any control-plane model.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class MirrorPlanRequestDecoder {
    /** Maximum canonical request tree admitted by the Stage 1 planning endpoint. */
    public static final int MAXIMUM_REQUEST_BYTES = 16 * 1024 * 1024;
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "schemaVersion", "planId", "graphName", "expectedGraphArtifactFingerprint",
            "capabilityClosure", "fixtureBundleRef", "maximumInvocations", "timeout",
            "certificationRequired", "expiresAt");

    private final ObjectMapper strictMapper;

    /** Creates an isolated recursive strict decoder from the application protocol mapper. */
    public MirrorPlanRequestDecoder(ObjectMapper mapper) {
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Converts one authenticated JSON tree to the exact v1 command.
     *
     * @param value untrusted request tree
     * @param identity authenticated identity used only for the stable problem correlation id
     * @return strict typed command
     */
    public MirrorPlanCreateRequest decode(
            JsonNode value, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        try {
            if (value == null || value.isNull()
                    || strictMapper.writeValueAsBytes(value).length > MAXIMUM_REQUEST_BYTES) {
                throw invalid(identity, "Mirror plan request is absent or exceeds its size limit.");
            }
            if (!value.isObject()) {
                throw invalid(identity, "Mirror plan request must be a JSON object.");
            }
            java.util.HashSet<String> fields = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(fields::add);
            if (!fields.containsAll(REQUIRED_FIELDS)
                    || REQUIRED_FIELDS.stream().anyMatch(field -> value.path(field).isNull())) {
                throw invalid(identity,
                        "Mirror plan request omits one or more required protocol fields.");
            }
            return strictMapper.treeToValue(value, MirrorPlanCreateRequest.class);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw invalid(identity,
                    "Mirror plan request does not match the strict public protocol.");
        }
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.MIRROR.PLAN_REQUEST_MALFORMED", title,
                identity.correlationId(), Map.of(
                        "schemaVersion", MirrorPlanCreateRequest.SCHEMA_VERSION,
                        "maximumBytes", MAXIMUM_REQUEST_BYTES)));
    }
}
