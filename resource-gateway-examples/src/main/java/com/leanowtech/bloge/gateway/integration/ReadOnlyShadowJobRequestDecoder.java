package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobRequest;
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
 * Strict bounded post-authentication decoder for durable read-only Shadow submissions.
 *
 * <p>Callers must authenticate before invoking this decoder. Duplicate, unknown, missing, trailing,
 * oversized, or structurally unbounded JSON is rejected before Jackson constructs the protocol
 * record.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class ReadOnlyShadowJobRequestDecoder {
    /** Largest admitted raw or canonical command. */
    public static final int MAXIMUM_REQUEST_BYTES =
            ReadOnlyShadowJobIntegrity.MAXIMUM_REQUEST_BYTES;
    /** Deepest admitted JSON structure. */
    public static final int MAXIMUM_DEPTH = 64;
    /** Largest admitted object, array, and scalar node count. */
    public static final int MAXIMUM_NODES = 10_000;
    private static final Set<String> V1_FIELDS = Set.of(
            "schemaVersion",
            "requestId",
            "scope",
            "inventoryRef",
            "unitId",
            "scenarioCaseRef",
            "targetCapabilityRef",
            "candidatePlanRef",
            "baselineBindingRef",
            "comparisonPolicyRef",
            "accessGrant",
            "deadlineAt");
    private static final Set<String> V2_FIELDS = Set.of(
            "schemaVersion",
            "requestId",
            "scope",
            "inventoryRef",
            "unitId",
            "scenarioCaseRef",
            "targetCapabilityRef",
            "candidatePlanRef",
            "baselineBindingRef",
            "comparisonPolicyRef",
            "sourceMode",
            "sourceBindingRef",
            "accessGrant",
            "deadlineAt");

    private final ObjectMapper strictMapper;

    /** Creates one isolated recursive strict mapper. */
    public ReadOnlyShadowJobRequestDecoder(
            ObjectMapper mapper) {
        strictMapper = Objects.requireNonNull(
                mapper, "mapper").copy()
                .enable(
                        JsonParser.Feature
                                .STRICT_DUPLICATE_DETECTION)
                .enable(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(
                        DeserializationFeature
                                .FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Decodes one exact payload-free durable Shadow command.
     *
     * @param value untrusted raw request bytes
     * @param identity already authenticated request context
     * @return strict immutable command
     */
    public ReadOnlyShadowJobRequest decode(
            byte[] value,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                Objects.requireNonNull(
                        identity, "identity");
        if (value == null
                || value.length == 0
                || value.length
                > MAXIMUM_REQUEST_BYTES) {
            throw invalid(exactIdentity);
        }
        try {
            JsonNode tree = strictMapper.readTree(value);
            if (tree == null || !tree.isObject()) {
                throw invalid(exactIdentity);
            }
            HashSet<String> actual = new HashSet<>();
            tree.fieldNames().forEachRemaining(
                    actual::add);
            String version =
                    tree.path("schemaVersion").textValue();
            Set<String> expected =
                    ReadOnlyShadowJobRequest.SCHEMA_VERSION
                            .equals(version)
                            ? V1_FIELDS
                            : ReadOnlyShadowJobRequest
                            .V2_SCHEMA_VERSION.equals(version)
                            ? V2_FIELDS : Set.of();
            if (!actual.equals(expected)) {
                throw invalid(exactIdentity);
            }
            requireBounds(tree, exactIdentity);
            if (strictMapper.writeValueAsBytes(tree)
                    .length > MAXIMUM_REQUEST_BYTES) {
                throw invalid(exactIdentity);
            }
            return strictMapper.treeToValue(
                    tree,
                    ReadOnlyShadowJobRequest.class);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException
                 | IllegalArgumentException malformed) {
            throw invalid(exactIdentity);
        }
    }

    private static void requireBounds(
            JsonNode root,
            IntegrationRequestContext identity) {
        ArrayDeque<NodeDepth> pending =
                new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int count = 0;
        while (!pending.isEmpty()) {
            NodeDepth current =
                    pending.removeLast();
            count++;
            if (count > MAXIMUM_NODES
                    || current.depth()
                    > MAXIMUM_DEPTH) {
                throw invalid(identity);
            }
            current.node().elements()
                    .forEachRemaining(child ->
                            pending.add(
                                    new NodeDepth(
                                            child,
                                            current.depth()
                                                    + 1)));
        }
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.badRequest(
                        "RG.MIRROR.SHADOW.REQUEST_MALFORMED",
                        "The read-only Shadow command does not match the bounded public protocol.",
                        identity.correlationId(),
                        Map.of(
                                "currentSchemaVersion",
                                ReadOnlyShadowJobRequest
                                        .V2_SCHEMA_VERSION,
                                "legacySchemaVersion",
                                ReadOnlyShadowJobRequest
                                        .SCHEMA_VERSION,
                                "maximumBytes",
                                MAXIMUM_REQUEST_BYTES,
                                "maximumDepth",
                                MAXIMUM_DEPTH,
                                "maximumNodes",
                                MAXIMUM_NODES)));
    }

    private record NodeDepth(
            JsonNode node,
            int depth) {
    }
}
