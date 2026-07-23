package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationEnvelope;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationIntegrity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict bounded decoder for signed capability-observation ingest. */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class CapabilityObservationDecoder {
    /** Maximum buffered body, equal to the canonical envelope maximum. */
    public static final int MAXIMUM_BYTES =
            CapabilityObservationIntegrity.MAXIMUM_ENVELOPE_BYTES;
    /** Maximum nested JSON depth before typed mapping. */
    public static final int MAXIMUM_DEPTH = 32;
    /** Maximum object, array, and scalar node count before typed mapping. */
    public static final int MAXIMUM_NODES = 10_000;

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "observationFingerprint", "material", "seal");

    private final ObjectMapper strictMapper;

    /**
     * Creates a detached strict mapper from immutable application configuration.
     *
     * @param mapper application protocol mapper
     */
    public CapabilityObservationDecoder(ObjectMapper mapper) {
        strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Decodes one untrusted observation after transport authentication.
     *
     * @param value untrusted buffered JSON
     * @param identity authenticated identity used only for problem correlation
     * @return exact closed v1 observation
     */
    public CapabilityObservationEnvelope decode(
            byte[] value, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        if (value == null || value.length == 0 || value.length > MAXIMUM_BYTES) {
            throw invalid(identity);
        }
        try {
            JsonNode root = strictMapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw invalid(identity);
            }
            HashSet<String> fields = new HashSet<>();
            root.fieldNames().forEachRemaining(fields::add);
            if (!ROOT_FIELDS.equals(fields)
                    || ROOT_FIELDS.stream().anyMatch(
                    field -> root.path(field).isNull())
                    || !CapabilityObservationEnvelope.SCHEMA_VERSION.equals(
                    root.path("schemaVersion").textValue())) {
                throw invalid(identity);
            }
            requireBounds(root, identity);
            return strictMapper.treeToValue(
                    root, CapabilityObservationEnvelope.class);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(identity);
        }
    }

    private static void requireBounds(
            JsonNode root, IntegrationRequestContext identity) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeLast();
            if (++nodes > MAXIMUM_NODES || current.depth() > MAXIMUM_DEPTH) {
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
                "RG.MIRROR.OBSERVATION_REQUEST_MALFORMED",
                "Capability observation input must be bounded, unambiguous, closed v1 JSON.",
                identity.correlationId(),
                Map.of(
                        "maximumBytes", MAXIMUM_BYTES,
                        "maximumDepth", MAXIMUM_DEPTH,
                        "maximumNodes", MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
