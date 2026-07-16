package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadRedactionManifest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectnessWorkbookProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializedWorkbookAndGateFieldsExactlyMatchMachineSchemas() throws Exception {
        CorrectnessWorkbookBundle workbook = workbook();
        GovernanceGateResult gate = gate(workbook);
        JsonNode workbookSchema = schema("correctness-workbook-bundle-v1.schema.json");
        JsonNode gateSchema = schema("governance-gate-result-v2.schema.json");

        assertFields(mapper.valueToTree(workbook), workbookSchema.path("properties"));
        assertFields(mapper.valueToTree(workbook.target()), workbookSchema.at("/$defs/target/properties"));
        assertFields(mapper.valueToTree(workbook.manifest()), workbookSchema.at("/$defs/manifest/properties"));
        assertThat(workbook.fingerprintVerified()).isTrue();

        assertFields(mapper.valueToTree(gate), gateSchema.path("properties"));
        assertFields(mapper.valueToTree(gate.target()), gateSchema.at("/$defs/target/properties"));
        assertFields(mapper.valueToTree(gate.decisionBasis()), gateSchema.at("/$defs/basis/properties"));
        assertFields(mapper.valueToTree(gate.decisionBasis().workbook()),
                gateSchema.at("/$defs/workbook/properties"));
        assertThat(gate.fingerprintVerified()).isTrue();
    }

    @Test
    void capabilitiesAdvertiseWorkbookAndGateV3WithoutDroppingOlderGenerations() {
        IntegrationCapabilities capabilities = IntegrationCapabilities.current(
                new InMemoryVisualEvidenceSigner().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(),
                false, null);

        assertThat(capabilities.supportedObjects().get("correctnessWorkbookBundle"))
                .containsExactly(CorrectnessWorkbookBundle.SCHEMA_VERSION);
        assertThat(capabilities.supportedObjects().get("governanceGateResult"))
                .containsExactly(GovernanceGateResult.SCHEMA_VERSION_V1,
                        GovernanceGateResult.SCHEMA_VERSION_V2, GovernanceGateResult.SCHEMA_VERSION);
        assertThat(capabilities.features()).containsEntry("correctnessWorkbookProjection", true)
                .containsEntry("workbookEvidenceReferences", true);
        assertThat(capabilities.endpoints()).contains(
                new IntegrationCapabilities.Endpoint("GET",
                        "/api/integration/drafts/{draftId}/correctness-workbook"));
    }

    @Test
    void gateV2GoldenFingerprintAndJsonShapeRemainStableAfterV3Evolution() {
        GovernanceGateResult.DecisionBasis basis = new GovernanceGateResult.DecisionBasis(
                new GovernanceGateResult.WorkbookRef("workbook-1", 2, sha("workbook"), sha("source")),
                sha("snapshot"), List.of(), List.of(),
                new GovernanceGateResult.PolicyRef("gate-policy", "2", List.of("WORKBOOK")),
                List.of(new GovernanceGateResult.Check("WORKBOOK", "PASSED", "verified", List.of())));
        GovernanceGateResult gate = new GovernanceGateResult(
                GovernanceGateResult.SCHEMA_VERSION_V2, "gate-1",
                new GovernanceGateResult.Target("GRAPH_DRAFT", "draft-1", 3, sha("draft"),
                        "tenant-a", "knowledge", "prod"),
                "PASSED", List.of(), Instant.parse("2026-07-13T00:00:00Z"), null, "", basis);

        assertThat(gate.resultFingerprint())
                .isEqualTo("sha256:dde9ff6ea32baa0a9510c789efa01e01ad987d812bf5477e8909d3101a007735");
        assertThat(mapper.valueToTree(gate.decisionBasis()).has("semanticWorkbooks")).isFalse();
        assertThat(gate.fingerprintVerified()).isTrue();
    }

    @Test
    void serializedGateV3FieldsExactlyMatchSemanticDecisionBasisSchema() throws Exception {
        GovernanceGateResult.SemanticWorkbookRef semantic =
                new GovernanceGateResult.SemanticWorkbookRef(
                        new GovernanceGateResult.SuiteRef("suite-semantic", 4, sha("suite")),
                        new TestSuite.Target("GRAPH", "riskGraph", sha("target")),
                        sha("semantic-workbook"), "NO_TERMINAL_EVIDENCE", 0, 0, false, List.of());
        GovernanceGateResult.DecisionBasis basis = new GovernanceGateResult.DecisionBasis(
                new GovernanceGateResult.WorkbookRef("", 0, "", ""), sha("snapshot"),
                List.of(), List.of(), new GovernanceGateResult.PolicyRef("", "", List.of()),
                List.of(), List.of(semantic));
        GovernanceGateResult gate = new GovernanceGateResult(
                GovernanceGateResult.SCHEMA_VERSION, "gate-v3",
                new GovernanceGateResult.Target("GRAPH_DRAFT", "draft-1", 3, sha("draft"),
                        "tenant-a", "knowledge", "test"),
                "BLOCKED", List.of(), Instant.parse("2026-07-13T00:00:00Z"), null, "", basis);
        JsonNode schema = schema("governance-gate-result-v3.schema.json");

        assertFields(mapper.valueToTree(gate), schema.path("properties"));
        assertFields(mapper.valueToTree(gate.decisionBasis()), schema.at("/$defs/basis/properties"));
        assertFields(mapper.valueToTree(semantic), schema.at("/$defs/semanticWorkbookRef/properties"));
        assertFields(mapper.valueToTree(semantic.target()), schema.at("/$defs/semanticTarget/properties"));
        assertThat(gate.fingerprintVerified()).isTrue();
    }

    private CorrectnessWorkbookBundle workbook() {
        String fingerprint = sha("draft");
        return new CorrectnessWorkbookBundle("",
                new CorrectnessWorkbookBundle.Target("GRAPH_DRAFT", "draft-1", 3, fingerprint),
                sha("snapshot"), List.of(), List.of(), VisualPayloadRedactionManifest.empty(), null);
    }

    private GovernanceGateResult gate(CorrectnessWorkbookBundle workbook) {
        GovernanceGateResult.DecisionBasis basis = new GovernanceGateResult.DecisionBasis(
                new GovernanceGateResult.WorkbookRef("workbook-1", 2, sha("workbook"),
                        workbook.manifest().bundleFingerprint()), workbook.dependencySnapshotFingerprint(),
                List.of(), List.of(),
                new GovernanceGateResult.PolicyRef("gate-policy", "2", List.of("WORKBOOK")),
                List.of(new GovernanceGateResult.Check("WORKBOOK", "PASSED", "verified", List.of())));
        return new GovernanceGateResult(GovernanceGateResult.SCHEMA_VERSION_V2, "gate-1",
                new GovernanceGateResult.Target("GRAPH_DRAFT", "draft-1", 3, sha("draft"),
                        "tenant-a", "knowledge", "prod"), "PASSED", List.of(),
                Instant.parse("2026-07-13T00:00:00Z"), null, "", basis);
    }

    private JsonNode schema(String name) throws Exception {
        Path path = Path.of("..", "docs", "schemas", "tool-studio-resource-gateway", name);
        if (!Files.exists(path)) {
            path = Path.of("docs", "schemas", "tool-studio-resource-gateway", name);
        }
        return mapper.readTree(path.toFile());
    }

    private static void assertFields(JsonNode value, JsonNode schemaProperties) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        Set<String> expected = new HashSet<>();
        schemaProperties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).isEqualTo(expected);
    }

    private static String sha(String value) {
        return VisualBundleFingerprint.fromMaterial(Map.of("value", value));
    }
}
