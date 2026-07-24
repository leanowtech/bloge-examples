package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CaseHandlingAssertion;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointBundle;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioCase;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioPack;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalCompileRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalExecutionRequest;
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
 * Strict structurally bounded decoder for ScenarioPack registration and compilation commands.
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class ScenarioArtifactRequestDecoder {
    /** Maximum raw or canonical bytes admitted for one scenario artifact. */
    public static final int MAXIMUM_REQUEST_BYTES = 8 * 1024 * 1024;
    /** Maximum nested JSON depth. */
    public static final int MAXIMUM_DEPTH = 64;
    /** Maximum object, array, and scalar nodes. */
    public static final int MAXIMUM_NODES = 100_000;

    private static final Set<String> ASSERTION_FIELDS = Set.of(
            "schemaVersion", "assertionId", "revision", "fingerprint",
            "scope", "observation", "selector", "expectation", "severity",
            "governanceCode", "provenance", "lifecycle", "createdAt");
    private static final Set<String> CASE_FIELDS = Set.of(
            "schemaVersion", "caseId", "revision", "fingerprint", "scope",
            "caseType", "targetCapabilityRef", "testSuiteRef", "testCaseId",
            "mirrorPlanRef", "fixtureBundleRef", "sessionCheckpointRef",
            "executionServices", "faultRuleRefs", "assertionRefs",
            "provenance", "lifecycle", "createdAt");
    private static final Set<String> PACK_FIELDS = Set.of(
            "schemaVersion", "packId", "revision", "fingerprint", "scope",
            "targetCapabilityRef", "caseRefs", "assertionRefs",
            "writeEffectRefs", "corpusSnapshotRef", "stateModelRefs",
            "policy", "provenance", "lifecycle", "createdAt");
    private static final Set<String> CHECKPOINT_FIELDS = Set.of(
            "schemaVersion", "bundleFingerprint", "payloadPolicy",
            "checkpoint", "attestation");
    private static final Set<String> COMPILE_FIELDS = Set.of(
            "schemaVersion", "revision", "fingerprint");
    private static final Set<String> EXECUTION_FIELDS = Set.of(
            "schemaVersion", "requestId", "compiledPlanRef");

    private final ObjectMapper strictMapper;

    /** Creates an isolated recursive strict decoder. */
    public ScenarioArtifactRequestDecoder(ObjectMapper mapper) {
        strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Decodes one exact CaseHandlingAssertion. */
    public CaseHandlingAssertion decodeAssertion(
            byte[] value, IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                ASSERTION_FIELDS,
                CaseHandlingAssertion.SCHEMA_VERSION,
                CaseHandlingAssertion.class);
    }

    /** Decodes one exact ScenarioCase. */
    public ScenarioCase decodeCase(
            byte[] value, IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                CASE_FIELDS,
                ScenarioCase.SCHEMA_VERSION,
                ScenarioCase.class);
    }

    /** Decodes one exact ScenarioPack. */
    public ScenarioPack decodePack(
            byte[] value, IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                PACK_FIELDS,
                ScenarioPack.SCHEMA_VERSION,
                ScenarioPack.class);
    }

    /** Decodes one exact signed Session checkpoint bundle. */
    public MirrorSessionCheckpointBundle decodeCheckpoint(
            byte[] value, IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                CHECKPOINT_FIELDS,
                MirrorSessionCheckpointBundle.SCHEMA_VERSION,
                MirrorSessionCheckpointBundle.class);
    }

    /** Decodes one exact ScenarioPack compilation command. */
    public ScenarioRehearsalCompileRequest decodeCompileRequest(
            byte[] value, IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                COMPILE_FIELDS,
                ScenarioRehearsalCompileRequest.SCHEMA_VERSION,
                ScenarioRehearsalCompileRequest.class);
    }

    /** Decodes one exact payload-free Scenario rehearsal execution command. */
    public ScenarioRehearsalExecutionRequest decodeExecutionRequest(
            byte[] value, IntegrationRequestContext identity) {
        return decode(
                value,
                identity,
                EXECUTION_FIELDS,
                ScenarioRehearsalExecutionRequest.SCHEMA_VERSION,
                ScenarioRehearsalExecutionRequest.class);
    }

    private <T> T decode(
            byte[] value,
            IntegrationRequestContext identity,
            Set<String> fields,
            String schemaVersion,
            Class<T> type) {
        Objects.requireNonNull(identity, "identity");
        if (value == null || value.length == 0
                || value.length > MAXIMUM_REQUEST_BYTES) {
            throw invalid(
                    identity, schemaVersion,
                    "Scenario request exceeds its raw size limits.");
        }
        try {
            JsonNode tree = strictMapper.readTree(value);
            if (tree == null || !tree.isObject()) {
                throw invalid(
                        identity, schemaVersion,
                        "Scenario request must be a JSON object.");
            }
            HashSet<String> actual = new HashSet<>();
            tree.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(fields)
                    || fields.stream().anyMatch(
                    field -> tree.path(field).isMissingNode())
                    || !schemaVersion.equals(
                    tree.path("schemaVersion").textValue())) {
                throw invalid(
                        identity, schemaVersion,
                        "Scenario request must contain exactly one supported field set.");
            }
            requireStructuralBounds(tree, identity, schemaVersion);
            if (strictMapper.writeValueAsBytes(tree).length
                    > MAXIMUM_REQUEST_BYTES) {
                throw invalid(
                        identity, schemaVersion,
                        "Scenario request exceeds its canonical size limit.");
            }
            return strictMapper.treeToValue(tree, type);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(
                    identity, schemaVersion,
                    "Scenario request does not match the strict public protocol.");
        }
    }

    private static void requireStructuralBounds(
            JsonNode root,
            IntegrationRequestContext identity,
            String schemaVersion) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeLast();
            nodes++;
            if (nodes > MAXIMUM_NODES
                    || current.depth() > MAXIMUM_DEPTH) {
                throw invalid(
                        identity, schemaVersion,
                        "Scenario request exceeds its structural limits.");
            }
            current.node().elements().forEachRemaining(child ->
                    pending.add(
                            new NodeDepth(
                                    child, current.depth() + 1)));
        }
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity,
            String schemaVersion,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.badRequest(
                        "RG.MIRROR.SCENARIO_REQUEST_MALFORMED",
                        title,
                        identity.correlationId(),
                        Map.of(
                                "schemaVersion", schemaVersion,
                                "maximumBytes", MAXIMUM_REQUEST_BYTES,
                                "maximumDepth", MAXIMUM_DEPTH,
                                "maximumNodes", MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
