package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAdmissionRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAssessmentRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationChunk;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadRequest;
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
 * Strict bounded post-authentication decoder for selected-population commands.
 *
 * <p>Duplicate, unknown, missing, trailing, oversized, or structurally unbounded JSON is rejected
 * before Jackson constructs a protocol record. The initial complete-population route is capped at
 * 64 MiB. Resumable uploads decode only the bounded root or one independently bounded chunk.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class
AuthoritativeOutcomeSelectedPopulationRequestDecoder {
    /** Largest complete-population command admitted by the first-generation route. */
    public static final int MAXIMUM_POPULATION_REQUEST_BYTES =
            64 * 1024 * 1024;
    /** Largest legal-disposition command. */
    public static final int MAXIMUM_DISPOSITION_REQUEST_BYTES =
            512 * 1024;
    /** Largest completeness-assessment command. */
    public static final int MAXIMUM_ASSESSMENT_REQUEST_BYTES =
            64 * 1024;
    /** Largest staged-upload root intent. */
    public static final int MAXIMUM_UPLOAD_REQUEST_BYTES =
            AuthoritativeOutcomeSelectedPopulationUploadRequest
                    .MAXIMUM_CANONICAL_BYTES;
    /** Largest encoded staged chunk, including modest JSON overhead. */
    public static final int MAXIMUM_UPLOAD_CHUNK_REQUEST_BYTES =
            AuthoritativeOutcomeSelectedPopulationChunk
                    .MAXIMUM_CANONICAL_BYTES
                    + 64 * 1024;
    /** Deepest admitted JSON structure. */
    public static final int MAXIMUM_DEPTH = 64;
    /** Largest admitted node count for one buffered command. */
    public static final int MAXIMUM_NODES = 1_000_000;
    private static final Set<String> POPULATION_FIELDS =
            Set.of(
                    "schemaVersion",
                    "expectedPredecessorFingerprint",
                    "manifest",
                    "chunks");
    private static final Set<String> DISPOSITION_FIELDS =
            Set.of(
                    "schemaVersion",
                    "expectedPredecessorFingerprint",
                    "disposition");
    private static final Set<String> ASSESSMENT_FIELDS =
            Set.of(
                    "schemaVersion",
                    "populationRevision",
                    "assessmentId",
                    "assessmentRevision",
                    "expectedPredecessorFingerprint");
    private static final Set<String> UPLOAD_FIELDS =
            Set.of(
                    "schemaVersion",
                    "uploadId",
                    "expectedPredecessorFingerprint",
                    "manifest");
    private static final Set<String> CHUNK_FIELDS =
            Set.of(
                    "schemaVersion",
                    "chunkId",
                    "chunkFingerprint",
                    "populationId",
                    "populationRevision",
                    "scope",
                    "inventoryRef",
                    "cohortRef",
                    "samplingFrameRef",
                    "selectedAt",
                    "chunkIndex",
                    "firstGlobalOrdinal",
                    "members");

    private final ObjectMapper strictMapper;

    /** Creates one isolated recursive strict mapper. */
    public AuthoritativeOutcomeSelectedPopulationRequestDecoder(
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

    /** Decodes one complete selected-population admission after authentication. */
    public AuthoritativeOutcomeSelectedPopulationAdmissionRequest
    decodePopulation(
            byte[] value,
            IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                MAXIMUM_POPULATION_REQUEST_BYTES,
                POPULATION_FIELDS,
                AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                        .SCHEMA_VERSION,
                AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                        .class);
    }

    /** Decodes one legal-disposition admission after authentication. */
    public AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
    decodeDisposition(
            byte[] value,
            IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                MAXIMUM_DISPOSITION_REQUEST_BYTES,
                DISPOSITION_FIELDS,
                AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                        .SCHEMA_VERSION,
                AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                        .class);
    }

    /** Decodes one completeness-assessment command after authentication. */
    public AuthoritativeOutcomeSelectedPopulationAssessmentRequest
    decodeAssessment(
            byte[] value,
            IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                MAXIMUM_ASSESSMENT_REQUEST_BYTES,
                ASSESSMENT_FIELDS,
                AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                        .SCHEMA_VERSION,
                AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                        .class);
    }

    /** Decodes one resumable-upload root intent after authentication. */
    public AuthoritativeOutcomeSelectedPopulationUploadRequest
    decodeUpload(
            byte[] value,
            IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                MAXIMUM_UPLOAD_REQUEST_BYTES,
                UPLOAD_FIELDS,
                AuthoritativeOutcomeSelectedPopulationUploadRequest
                        .SCHEMA_VERSION,
                AuthoritativeOutcomeSelectedPopulationUploadRequest
                        .class);
    }

    /** Decodes one independently bounded content-addressed chunk after authentication. */
    public AuthoritativeOutcomeSelectedPopulationChunk
    decodeUploadChunk(
            byte[] value,
            IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                MAXIMUM_UPLOAD_CHUNK_REQUEST_BYTES,
                CHUNK_FIELDS,
                AuthoritativeOutcomeSelectedPopulationChunk
                        .SCHEMA_VERSION,
                AuthoritativeOutcomeSelectedPopulationChunk
                        .class);
    }

    private <T> T decode(
            byte[] value,
            IntegrationRequestContext identity,
            int maximumBytes,
            Set<String> fields,
            String schemaVersion,
            Class<T> type) {
        IntegrationRequestContext exactIdentity =
                Objects.requireNonNull(
                        identity, "identity");
        if (value == null
                || value.length == 0
                || value.length > maximumBytes) {
            throw invalid(
                    exactIdentity, maximumBytes);
        }
        try {
            JsonNode tree = strictMapper.readTree(value);
            if (tree == null || !tree.isObject()) {
                throw invalid(
                        exactIdentity, maximumBytes);
            }
            HashSet<String> actual = new HashSet<>();
            tree.fieldNames()
                    .forEachRemaining(actual::add);
            if (!actual.equals(fields)
                    || !schemaVersion.equals(
                    tree.path("schemaVersion")
                            .textValue())) {
                throw invalid(
                        exactIdentity, maximumBytes);
            }
            requireBounds(
                    tree, exactIdentity, maximumBytes);
            if (strictMapper.writeValueAsBytes(tree)
                    .length > maximumBytes) {
                throw invalid(
                        exactIdentity, maximumBytes);
            }
            return strictMapper.treeToValue(
                    tree, type);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(
                    exactIdentity, maximumBytes);
        }
    }

    private static void requireBounds(
            JsonNode root,
            IntegrationRequestContext identity,
            int maximumBytes) {
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
                throw invalid(
                        identity, maximumBytes);
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
            IntegrationRequestContext identity,
            int maximumBytes) {
        return new IntegrationProblemException(
                IntegrationProblem.badRequest(
                        "RG.MIRROR.OUTCOME.POPULATION_REQUEST_MALFORMED",
                        "The selected-population command does not match the bounded public protocol.",
                        identity.correlationId(),
                        Map.of(
                                "maximumBytes",
                                maximumBytes,
                                "maximumDepth",
                                MAXIMUM_DEPTH,
                                "maximumNodes",
                                MAXIMUM_NODES)));
    }

    private record NodeDepth(
            JsonNode node,
            int depth
    ) {
    }
}
