package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorExecutionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict and structurally bounded decoder for protected mirror execution commands.
 *
 * <p>Only the business {@code context} remains open to caller-defined keys. Top-level fields are
 * closed, context must be an object, duplicate keys are rejected before tree materialization, and
 * depth/node/serialized-size limits are checked before a typed command is created. Spring still
 * buffers the request body before this decoder, so connection, total-body, and rate limits remain
 * deployment-owned ingress controls.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class MirrorExecutionRequestDecoder {
    /** Maximum canonical execution command admitted after MVC parsing. */
    public static final int MAXIMUM_REQUEST_BYTES = 16 * 1024 * 1024;
    /** Maximum nested JSON depth, including the top-level request object. */
    public static final int MAXIMUM_DEPTH = 64;
    /** Maximum total object, array, and scalar nodes in one command. */
    public static final int MAXIMUM_NODES = 100_000;
    private static final Set<String> V1_FIELDS = Set.of(
            "schemaVersion", "requestId", "planId", "expectedPlanFingerprint", "context");
    private static final Set<String> V2_FIELDS = Set.of(
            "schemaVersion", "requestId", "planId",
            "expectedPlanFingerprint", "context", "sessionBinding");
    private static final Set<String> SESSION_BINDING_FIELDS = Set.of(
            "sessionId", "expectedStateFingerprint");

    private final ObjectMapper strictMapper;

    /** Creates an isolated recursive strict decoder from the application protocol mapper. */
    public MirrorExecutionRequestDecoder(ObjectMapper mapper) {
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Parses one raw buffered HTTP body with duplicate detection before typed admission.
     *
     * @param value untrusted buffered request bytes
     * @param identity authenticated identity used only for stable problem correlation
     * @return strict detached execution command
     */
    public MirrorExecutionRequest decode(
            byte[] value, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        if (value == null || value.length == 0 || value.length > MAXIMUM_REQUEST_BYTES) {
            throw invalid(identity, "Mirror execution request exceeds its raw size limits.");
        }
        try {
            return decode(strictMapper.readTree(value), identity);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException malformed) {
            throw invalid(identity,
                    "Mirror execution request is not unambiguous strict JSON.");
        }
    }

    /**
     * Converts one authenticated JSON tree to an exact supported execution command.
     *
     * @param value untrusted request tree; transport callers should prefer raw-byte decoding
     * @param identity authenticated identity used only for stable problem correlation
     * @return strict detached execution command
     */
    public MirrorExecutionRequest decode(
            JsonNode value, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        try {
            if (value == null || value.isNull() || !value.isObject()) {
                throw invalid(identity, "Mirror execution request must be a JSON object.");
            }
            HashSet<String> actualFields = new HashSet<>();
            value.fieldNames().forEachRemaining(actualFields::add);
            String schemaVersion = value.path("schemaVersion").textValue();
            Set<String> expectedFields =
                    MirrorExecutionRequest.SCHEMA_VERSION.equals(schemaVersion)
                            ? V1_FIELDS
                            : MirrorExecutionRequest.STATEFUL_SCHEMA_VERSION
                            .equals(schemaVersion) ? V2_FIELDS : Set.of();
            if (expectedFields.isEmpty()
                    || !actualFields.equals(expectedFields)
                    || expectedFields.stream().anyMatch(
                    field -> value.path(field).isNull())
                    || !value.path("context").isObject()
                    || !exactText(value.path("requestId"))
                    || !exactText(value.path("planId"))
                    || !exactText(value.path("expectedPlanFingerprint"))
                    || (expectedFields == V2_FIELDS
                    && !validSessionBinding(
                    value.path("sessionBinding")))) {
                throw invalid(identity,
                        "Mirror execution request must contain only one complete supported field set.");
            }
            requireStructuralBounds(value, identity);
            if (strictMapper.writeValueAsBytes(value).length > MAXIMUM_REQUEST_BYTES) {
                throw invalid(identity, "Mirror execution request exceeds its size limit.");
            }
            return strictMapper.treeToValue(value, MirrorExecutionRequest.class);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw invalid(identity,
                    "Mirror execution request does not match the strict public protocol.");
        }
    }

    private static boolean exactText(JsonNode value) {
        return value.isTextual() && value.textValue().equals(value.textValue().trim());
    }

    private static boolean validSessionBinding(JsonNode value) {
        if (!value.isObject()) {
            return false;
        }
        HashSet<String> fields = new HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        return fields.equals(SESSION_BINDING_FIELDS)
                && SESSION_BINDING_FIELDS.stream().noneMatch(
                field -> value.path(field).isNull())
                && exactText(value.path("sessionId"))
                && exactText(value.path("expectedStateFingerprint"));
    }

    private static void requireStructuralBounds(
            JsonNode root, IntegrationRequestContext identity) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeLast();
            nodes++;
            if (nodes > MAXIMUM_NODES || current.depth() > MAXIMUM_DEPTH) {
                throw invalid(identity,
                        "Mirror execution request exceeds its structural limits.");
            }
            current.node().elements().forEachRemaining(child ->
                    pending.add(new NodeDepth(child, current.depth() + 1)));
        }
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.MIRROR.EXECUTION_REQUEST_MALFORMED", title,
                identity.correlationId(), Map.of(
                        "schemaVersion", MirrorExecutionRequest.SCHEMA_VERSION,
                        "statefulSchemaVersion",
                        MirrorExecutionRequest.STATEFUL_SCHEMA_VERSION,
                        "maximumBytes", MAXIMUM_REQUEST_BYTES,
                        "maximumDepth", MAXIMUM_DEPTH,
                        "maximumNodes", MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
