package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphDraftDependencyProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void profileAndSnapshotSchemasMatchEverySerializedProtocolField() throws Exception {
        String fingerprint = "sha256:" + "a".repeat(64);
        GraphDraftDependencyProfile.OperatorLibraryRef library =
                new GraphDraftDependencyProfile.OperatorLibraryRef(
                        "risk-policy", 3, "2.1.0", "risk-team", "ACTIVE", fingerprint, true);
        GraphDraftDependencyProfile.RuntimeBindingRef binding =
                new GraphDraftDependencyProfile.RuntimeBindingRef(
                        "binding-1", 4, "bound", fingerprint, fingerprint,
                        "activation-1", 5, "active", "prod", "healthy", fingerprint, true);
        GraphDraftDependencyProfile.ContractSuiteRef suite =
                new GraphDraftDependencyProfile.ContractSuiteRef(
                        "suite-1", 6, "bloge.visualOperatorContractTestSuite.v1", 7, fingerprint);
        GraphDraftDependencyProfile.RuntimeReadiness readiness =
                new GraphDraftDependencyProfile.RuntimeReadiness(
                        true, true, true, "READ", "risk-team", "p95<100ms", "EXTERNAL_RUNTIME_BOUND");
        GraphDraftDependencyProfile.OperatorDependency dependency =
                new GraphDraftDependencyProfile.OperatorDependency(
                        "node-1", "risk:score", "risk-policy", fingerprint, fingerprint,
                        List.of("binding-1@4"), List.of("suite-1@6"), readiness,
                        library, List.of(binding), List.of(suite));
        GraphDraftDependencyProfile.SnapshotManifest snapshot =
                new GraphDraftDependencyProfile.SnapshotManifest(
                        "", fingerprint, Instant.parse("2026-07-13T00:00:00Z"),
                        "STABLE", 1, 1, 1, 1);
        GraphDraftDependencyProfile profile = new GraphDraftDependencyProfile(
                "", List.of(dependency), new GraphDraftDependencyProfile.GraphContract(fingerprint, fingerprint),
                snapshot, GraphDraftDependencyReport.empty());

        JsonNode profileSchema = schema("graph-draft-dependency-profile-v2.schema.json");
        JsonNode snapshotSchema = schema("graph-draft-dependency-snapshot-v1.schema.json");

        assertProperties(mapper.valueToTree(profile), profileSchema.path("properties"));
        assertProperties(mapper.valueToTree(dependency), profileSchema.at("/$defs/operatorDependency/properties"));
        assertProperties(mapper.valueToTree(profile.graphContract()),
                profileSchema.at("/$defs/graphContract/properties"));
        assertProperties(mapper.valueToTree(snapshot), profileSchema.at("/$defs/snapshot/properties"));
        assertProperties(mapper.valueToTree(snapshot), snapshotSchema.path("properties"));
        assertProperties(mapper.valueToTree(library), profileSchema.at("/$defs/operatorLibrary/properties"));
        assertProperties(mapper.valueToTree(binding), profileSchema.at("/$defs/runtimeBinding/properties"));
        assertProperties(mapper.valueToTree(suite), profileSchema.at("/$defs/contractSuite/properties"));
        assertProperties(mapper.valueToTree(readiness), profileSchema.at("/$defs/readiness/properties"));
        assertThat(profileSchema.at("/$defs/readiness/properties/state/enum"))
                .extracting(JsonNode::asText)
                .contains("RUNTIME_EXECUTABLE", "EXTERNAL_RUNTIME_BOUND", "LIBRARY_MISSING",
                        "LIBRARY_NOT_ACTIVE", "CONTRACT_SUITE_MISSING", "RUNTIME_BINDING_MISSING",
                        "ACTIVATION_MISSING_OR_STALE", "CATALOG_MISSING", "SCOPE_MISMATCH");
    }

    @Test
    void capabilitiesAdvertiseBothProfileGenerationsAndSnapshotContract() {
        IntegrationCapabilities capabilities = IntegrationCapabilities.current();

        assertThat(capabilities.supportedObjects().get("graphDraftDependencyProfile"))
                .containsExactly(GraphDraftDependencyProfile.SCHEMA_VERSION_V1,
                        GraphDraftDependencyProfile.SCHEMA_VERSION);
        assertThat(capabilities.supportedObjects().get("graphDraftDependencySnapshot"))
                .containsExactly(GraphDraftDependencyProfile.SnapshotManifest.SCHEMA_VERSION);
        assertThat(capabilities.features())
                .containsEntry("graphDraftConsistentDependencySnapshot", true)
                .containsEntry("graphDraftStructuredDependencyRefs", true);
        assertThat(capabilities.testability().protocolVersion()).isEqualTo("bloge.testing.v1");
        assertThat(capabilities.testability().enabledEnvironments())
                .containsExactly("test", "staging");
        assertThat(capabilities.testability().schemaContractMode()).isTrue();
        assertThat(capabilities.testability().executionEndpointEnabled()).isFalse();
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "tool-studio-resource-gateway", file)));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
