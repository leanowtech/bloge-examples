package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringFactProjectionMachineSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void schemaAcceptsACompleteProjectionAndMatchesTheRootContract() throws Exception {
        Map<String, Object> schema = schema();
        AuthoringFactProjection projection = projection();

        assertThat(schema.get("required")).asList().contains(
                "schemaVersion",
                "sourceKind",
                "sourceFingerprint",
                "projectionFingerprint",
                "facts",
                "runtimeParity");
        assertThat(validate(schema, mapper.convertValue(projection, Map.class))).isEmpty();
    }

    @Test
    void schemaRejectsUnknownParityStatesAndUndeclaredRootProperties() throws Exception {
        Map<String, Object> schema = schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> value = mapper.convertValue(projection(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parity = (List<Map<String, Object>>) value.get("runtimeParity");
        parity.getFirst().put("state", "TRUST_ME");

        assertThat(validate(schema, value))
                .as("unknown parity states must be rejected")
                .isNotEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> undeclared = mapper.convertValue(projection(), Map.class);
        undeclared.put("runtimeReadyWithoutProof", true);
        assertThat(validate(schema, undeclared))
                .extracting(com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic::target)
                .anyMatch(target -> target.contains("runtimeReadyWithoutProof"));
    }

    private AuthoringFactProjection projection() {
        String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of("source", "test"));
        return new AuthoringFactProjection(
                AuthoringFactProjection.SCHEMA_VERSION,
                "DSL",
                "support.bloge",
                fingerprint,
                fingerprint,
                true,
                new AuthoringFactProjection.Summary(1, 0, 1, 0, 0, 1, false),
                List.of(
                        new AuthoringFactProjection.Fact(
                                "DSL:OPERATOR:support:classify:USAGE",
                                "OPERATOR",
                                "support:classify",
                                "USAGE",
                                "OBSERVED",
                                "",
                                "/draft/nodes",
                                1,
                                List.of(),
                                Map.of("nodeIds", List.of("classify"))),
                        new AuthoringFactProjection.Fact(
                                "DSL:GRAPH:support:TOPOLOGY",
                                "GRAPH",
                                "support",
                                "TOPOLOGY",
                                "DECLARED",
                                fingerprint,
                                "/draft",
                                1,
                                List.of(),
                                Map.of("nodeCount", 1))),
                List.of(new AuthoringFactProjection.RuntimeParity(
                        "OPERATOR",
                        "support:classify",
                        "",
                        "DOCUMENTED_ONLY",
                        false,
                        "",
                        "",
                        "RG.AUTHORING.RUNTIME_OPERATOR_MISSING",
                        "No exact operator was found.")),
                List.of(new AuthoringFactProjection.ReviewItem(
                        "RG.AUTHORING.DISCOVERY_DSL_TOPOLOGY_ONLY",
                        "WARNING",
                        "GRAPH",
                        "support",
                        "Contracts require review.",
                        "Enrich the reusable contract.")),
                List.of(VisualDiagnostic.warning(
                        "visual.dslImport.operatorMissing",
                        "The topology remains renderable.",
                        "/draft/nodes/0")),
                null);
    }

    private List<com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic> validate(
            Map<String, Object> schema,
            Object value) {
        return VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                value,
                "/projection");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema() throws Exception {
        return mapper.readValue(Files.readString(Path.of(
                "..",
                "docs",
                "schemas",
                "bloge-visual-authoring-fact-projection-v1.schema.json")), LinkedHashMap.class);
    }
}
