package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusCandidateRequest;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusPublishRequest;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationReviewRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict bounded decoder for corpus-governance commands after authentication. */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class CapabilityCorpusGovernanceDecoder {
    /** Maximum buffered governance command size. */
    public static final int MAXIMUM_BYTES =
            CapabilityCorpusIntegrity.MAXIMUM_CANONICAL_BYTES;
    /** Maximum nested JSON depth before typed mapping. */
    public static final int MAXIMUM_DEPTH = 32;
    /** Maximum object, array, and scalar node count before typed mapping. */
    public static final int MAXIMUM_NODES = 20_000;

    private static final Set<String> REVIEW_FIELDS = Set.of(
            "schemaVersion", "observationRef", "admissionRef",
            "disposition", "reviewTicketRef", "reasonCode");
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "schemaVersion", "corpusId", "revision", "expectedPredecessorRef",
            "capabilityRef", "sources");
    private static final Set<String> PUBLISH_FIELDS = Set.of(
            "schemaVersion", "corpusId", "publicationRevision",
            "expectedPublicationRef", "corpusRevisionRef",
            "reviewTicketRef", "reasonCode");

    private final ObjectMapper strictMapper;

    /**
     * Creates a detached strict mapper from immutable application configuration.
     *
     * @param mapper application protocol mapper
     */
    public CapabilityCorpusGovernanceDecoder(ObjectMapper mapper) {
        strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Decodes one quarantine-review command.
     *
     * @param value untrusted buffered JSON
     * @param identity authenticated identity used only for problem correlation
     * @return exact closed review command
     */
    public CapabilityObservationReviewRequest decodeReview(
            byte[] value, IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                REVIEW_FIELDS,
                CapabilityObservationReviewRequest.SCHEMA_VERSION,
                CapabilityObservationReviewRequest.class);
    }

    /**
     * Decodes one corpus-candidate command.
     *
     * @param value untrusted buffered JSON
     * @param identity authenticated identity used only for problem correlation
     * @return exact closed candidate command
     */
    public CapabilityCorpusCandidateRequest decodeCandidate(
            byte[] value, IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                CANDIDATE_FIELDS,
                CapabilityCorpusCandidateRequest.SCHEMA_VERSION,
                CapabilityCorpusCandidateRequest.class);
    }

    /**
     * Decodes one corpus-publication command.
     *
     * @param value untrusted buffered JSON
     * @param identity authenticated identity used only for problem correlation
     * @return exact closed publication command
     */
    public CapabilityCorpusPublishRequest decodePublication(
            byte[] value, IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                PUBLISH_FIELDS,
                CapabilityCorpusPublishRequest.SCHEMA_VERSION,
                CapabilityCorpusPublishRequest.class);
    }

    private <T> T decode(
            byte[] value,
            IntegrationRequestContext identity,
            Set<String> fields,
            String schemaVersion,
            Class<T> type) {
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
            if (!fields.equals(actual)
                    || fields.stream().filter(field ->
                    !field.startsWith("expected")).anyMatch(
                    field -> root.path(field).isNull())
                    || !schemaVersion.equals(
                    root.path("schemaVersion").textValue())) {
                throw invalid(identity);
            }
            requireBounds(root, identity);
            return strictMapper.treeToValue(root, type);
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
                "RG.MIRROR.CORPUS_REQUEST_MALFORMED",
                "Corpus governance input must be bounded, unambiguous, closed v1 JSON.",
                identity.correlationId(),
                Map.of(
                        "maximumBytes", MAXIMUM_BYTES,
                        "maximumDepth", MAXIMUM_DEPTH,
                        "maximumNodes", MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
