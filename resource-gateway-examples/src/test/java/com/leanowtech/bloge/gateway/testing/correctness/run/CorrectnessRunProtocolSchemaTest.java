package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectnessRunProtocolSchemaTest {

    private static final Path SCHEMAS = Path.of("..", "docs", "schemas");
    private static final List<String> ROOTS = List.of(
            "bloge-correctness-run-protocol-v1.schema.json",
            "bloge-correctness-preflight-request-v1.schema.json",
            "bloge-correctness-preflight-report-v1.schema.json",
            "bloge-correctness-run-request-v1.schema.json",
            "bloge-correctness-run-response-v1.schema.json",
            "bloge-correctness-evidence-companion-v1.schema.json",
            "bloge-stored-correctness-evidence-companion-v1.schema.json");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesClosedResolvableRootsForEveryGovernedRunProtocolObject() throws Exception {
        for (String root : ROOTS) {
            JsonNode schema = read(SCHEMAS.resolve(root));
            assertThat(schema.path("$schema").asText())
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertReferencesResolve(SCHEMAS.resolve(root), schema);
        }

        JsonNode definitions = read(SCHEMAS.resolve(ROOTS.getFirst())).path("$defs");
        assertThat(definitions.path("preflightRequest").path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.path("preflightReport").path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.path("runRequest").path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(definitions.path("evidenceCompanion")
                .path("additionalProperties").asBoolean()).isFalse();
        assertThat(definitions.path("storedEvidenceCompanion")
                .path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void schemaTracksCanonicalJavaFieldSetsAndKeepsEvidencePayloadFree() throws Exception {
        JsonNode definitions = read(SCHEMAS.resolve(ROOTS.getFirst())).path("$defs");
        CorrectnessRunRequest request = request();
        CorrectnessPreflightReport report = report(request);
        CorrectnessPreflightRequest preflightRequest = new CorrectnessPreflightRequest(
                "", request.publicationRef(),
                new CorrectnessPreflightRequest.SelectionIntent(
                        CorrectnessRunRequest.Selection.Mode.SELECTED,
                        List.of("case-1"), ""));

        assertThat(fieldNames(mapper.valueToTree(preflightRequest)))
                .containsExactlyInAnyOrderElementsOf(fieldNames(
                        definitions.path("preflightRequest").path("properties")));
        assertThat(fieldNames(mapper.valueToTree(request)))
                .containsExactlyInAnyOrderElementsOf(fieldNames(
                        definitions.path("runRequest").path("properties")));
        assertThat(fieldNames(mapper.valueToTree(report)))
                .containsExactlyInAnyOrderElementsOf(fieldNames(
                        definitions.path("preflightReport").path("properties")));

        Set<String> evidenceFields = fieldNames(
                definitions.path("evidenceCompanion").path("properties"));
        assertThat(evidenceFields).doesNotContain(
                "input", "output", "request", "response", "payload",
                "material", "fixtureMaterial", "secret");
        assertThat(evidenceFields).contains(
                "publicationRef", "caseRefs", "caseExecutions", "sourceMap",
                "verdict", "attestation");
    }

    private void assertReferencesResolve(Path schemaPath, JsonNode node) throws Exception {
        if (node.isObject()) {
            if (node.has("$ref")) {
                String reference = node.path("$ref").asText();
                String[] parts = reference.split("#", 2);
                Path targetPath = parts[0].isEmpty()
                        ? schemaPath : schemaPath.getParent().resolve(parts[0]).normalize();
                assertThat(Files.isRegularFile(targetPath))
                        .as("schema reference file %s", reference).isTrue();
                JsonNode target = read(targetPath);
                if (parts.length == 2 && !parts[1].isEmpty()) {
                    assertThat(target.at(parts[1]))
                            .as("schema reference fragment %s", reference)
                            .isNotEqualTo(com.fasterxml.jackson.databind.node.MissingNode.getInstance());
                }
            }
            for (JsonNode child : node) assertReferencesResolve(schemaPath, child);
        } else if (node.isArray()) {
            for (JsonNode child : node) assertReferencesResolve(schemaPath, child);
        }
    }

    private JsonNode read(Path path) throws IOException {
        return mapper.readTree(path.toFile());
    }

    private static Set<String> fieldNames(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static CorrectnessRunRequest request() {
        return new CorrectnessRunRequest("",
                new CorrectnessRunRequest.PublicationRef("publication-1", 1, fp('a')),
                new CorrectnessRunRequest.Selection(
                        CorrectnessRunRequest.Selection.Mode.SELECTED,
                        List.of("case-1"), fp('b')),
                fp('c'), "request-1", CorrectnessRunRequest.Strategy.COLLECT_ALL);
    }

    private static CorrectnessPreflightReport report(CorrectnessRunRequest request) {
        return new CorrectnessPreflightReport("", request.publicationRef(),
                new ExactTargetRef(TargetKind.GRAPH, "graph-1", 1, fp('d')),
                new ExactAssetRef("TEST_SUITE", "suite-1", 1, fp('e')),
                request.selection(), CorrectnessPreflightReport.ProofLevel.STRUCTURAL,
                List.of(), new CorrectnessPreflightReport.RiskSummary(
                0, 0, 0, 0, 0, 0, 0, 0, 0, false, List.of()),
                List.of(), fp('f'));
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
