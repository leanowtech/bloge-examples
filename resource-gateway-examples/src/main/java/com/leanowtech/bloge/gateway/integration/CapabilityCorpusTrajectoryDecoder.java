package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusTrajectoryPublishRequest;
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
 * Strict bounded decoder for owner-reviewed corpus trajectory commands.
 *
 * <p>Authentication precedes this decoder. It rejects duplicate and unknown fields, trailing
 * tokens, schema drift, null mandatory values, and resource exhaustion before typed mapping.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class CapabilityCorpusTrajectoryDecoder {
    /** Maximum buffered command size. */
    public static final int MAXIMUM_BYTES =
            CapabilityCorpusIntegrity.MAXIMUM_CANONICAL_BYTES;
    /** Maximum nested JSON depth. */
    public static final int MAXIMUM_DEPTH = 32;
    /** Maximum object, array, and scalar node count. */
    public static final int MAXIMUM_NODES = 20_000;

    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "trajectoryId", "revision",
            "expectedPredecessorRef", "capabilityRef",
            "corpusPublicationRef", "retryPolicyRef", "attempts",
            "reviewTicketRef", "reasonCode");

    private final ObjectMapper strictMapper;

    /**
     * Creates a detached strict mapper from immutable application configuration.
     *
     * @param mapper application protocol mapper
     */
    public CapabilityCorpusTrajectoryDecoder(ObjectMapper mapper) {
        strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Decodes one closed v1 trajectory publication command.
     *
     * @param value untrusted buffered JSON
     * @param identity authenticated identity used for problem correlation
     * @return exact typed command
     */
    public CapabilityCorpusTrajectoryPublishRequest decode(
            byte[] value,
            IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        if (value == null || value.length == 0 || value.length > MAXIMUM_BYTES) {
            throw invalid(identity);
        }
        try {
            JsonNode root = strictMapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw invalid(identity);
            }
            HashSet<String> actual = new HashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            if (!FIELDS.equals(actual)
                    || FIELDS.stream()
                    .filter(field -> !"expectedPredecessorRef".equals(field))
                    .anyMatch(field -> root.path(field).isNull())
                    || !CapabilityCorpusTrajectoryPublishRequest.SCHEMA_VERSION
                    .equals(root.path("schemaVersion").textValue())) {
                throw invalid(identity);
            }
            requireBounds(root, identity);
            return strictMapper.treeToValue(
                    root, CapabilityCorpusTrajectoryPublishRequest.class);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(identity);
        }
    }

    private static void requireBounds(
            JsonNode root,
            IntegrationRequestContext identity) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeLast();
            if (++nodes > MAXIMUM_NODES
                    || current.depth() > MAXIMUM_DEPTH) {
                throw invalid(identity);
            }
            current.node().elements().forEachRemaining(
                    child -> pending.add(
                            new NodeDepth(child, current.depth() + 1)));
        }
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.MIRROR.CORPUS_TRAJECTORY_REQUEST_MALFORMED",
                "Trajectory input must be bounded, unambiguous, closed v1 JSON.",
                identity.correlationId(),
                Map.of(
                        "maximumBytes", MAXIMUM_BYTES,
                        "maximumDepth", MAXIMUM_DEPTH,
                        "maximumNodes", MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
