package com.leanowtech.bloge.gateway.visual.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualAuthoringJsonValue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractDraftTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ContractDraftProjectionService projector = new ContractDraftProjectionService();

    @Test
    void projectsGraphSchemasWithoutInventingExecutionSemantics() {
        ContractDraft contract = projector.project(graphDraft(), fingerprint('a'));

        assertThat(contract.schemaVersion()).isEqualTo(ContractDraft.SCHEMA_VERSION);
        assertThat(contract.target()).isEqualTo(new ContractDraft.Target(
                ContractDraft.TargetKind.GRAPH, "draft-a", 4, fingerprint('a')));
        assertThat(contract.inputSchema().required()).containsExactly("applicantId");
        assertThat(contract.outputSchema().properties()).containsKey("decision");
        assertThat(contract.executionSemantics()).isEqualTo(ContractDraft.ExecutionSemantics.unknown());
        assertThat(contract.confidence()).isEqualTo(ContractDraft.Confidence.EXACT);
    }

    @Test
    void projectsCompleteAuthoredSemanticsFromTheVersionedGraphContractLayout() {
        GraphDraft graph = graphDraft();
        Map<String, Object> semantics = Map.of(
                "schemaVersion", "bloge.graphContractSemantics.v1",
                "errorContract", List.of(Map.of(
                        "code", "CRM_UNAVAILABLE",
                        "type", "DEPENDENCY",
                        "description", "CRM could not be reached.",
                        "retryable", true)),
                "executionSemantics", Map.of(
                        "effect", "WRITE",
                        "idempotency", "IDEMPOTENCY_KEY:/requestId",
                        "streaming", false,
                        "durable", true,
                        "sideEffectProtocol", Map.of(
                                "protocol", "bloge.sideEffectProtocol.v1",
                                "reconcilerRef", "crm.reconcile",
                                "reversible", true,
                                "metadata", Map.of("owner", "customer-platform"))),
                "invariants", List.of(Map.of(
                        "invariantId", "request-id-required",
                        "phase", "PRECONDITION",
                        "expression", "exists(ctx.requestId)",
                        "description", "Every write has an idempotency coordinate.",
                        "severity", "ERROR")),
                "compatibilityPolicy", Map.of(
                        "mode", "BACKWARD",
                        "unknownBlocksAutomaticMigration", true),
                "fieldMetadata", Map.of());
        GraphDraft withSemantics = new GraphDraft(
                graph.schemaVersion(), graph.draftId(), graph.revision(), graph.graphName(),
                graph.tenantId(), graph.namespace(), graph.environment(), graph.status(),
                graph.inputSchema(), graph.outputSchema(), graph.nodes(), graph.edges(),
                Map.of("graphContract", Map.of("contractSemantics", semantics)),
                graph.nodeFixtures(), graph.output(), graph.operatorFingerprints(),
                graph.operatorSnapshots(), graph.revisionMetadata());

        ContractDraft projected = projector.project(withSemantics, fingerprint('a'));

        assertThat(projected.errorContract()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("CRM_UNAVAILABLE");
            assertThat(error.retryable()).isTrue();
        });
        assertThat(projected.executionSemantics().effect()).isEqualTo(ContractDraft.Effect.WRITE);
        assertThat(projected.executionSemantics().sideEffectProtocol().reconcilerRef())
                .isEqualTo("crm.reconcile");
        assertThat(projected.invariants()).singleElement()
                .extracting(ContractDraft.ContractInvariant::invariantId)
                .isEqualTo("request-id-required");
        assertThat(projected.compatibilityPolicy().mode()).isEqualTo("BACKWARD");
    }

    @Test
    void rejectsIncompleteOrWronglyTypedEmbeddedSemanticsInsteadOfDefaultingThem() {
        Map<String, Object> incomplete = Map.of(
                "schemaVersion", GraphContractSemantics.SCHEMA_VERSION,
                "errorContract", List.of(),
                "executionSemantics", Map.of(
                        "effect", "READ",
                        "idempotency", "REQUEST_KEY",
                        "streaming", false,
                        "durable", true),
                "invariants", List.of(),
                "compatibilityPolicy", Map.of(
                        "mode", "STRICT",
                        "unknownBlocksAutomaticMigration", true));
        Map<String, Object> wrongType = new LinkedHashMap<>(incomplete);
        wrongType.put("fieldMetadata", Map.of());
        wrongType.put("executionSemantics", Map.of(
                "effect", "READ",
                "idempotency", 42,
                "streaming", false,
                "durable", true));

        assertThatThrownBy(() -> GraphContractSemantics.fromVisualLayout(
                Map.of("graphContract", Map.of("contractSemantics", incomplete))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required fields");
        assertThatThrownBy(() -> GraphContractSemantics.fromVisualLayout(
                Map.of("graphContract", Map.of("contractSemantics", wrongType))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotency must be a string");
    }

    @Test
    void canonicalFingerprintIsStableAndChangesWithContractSemantics() {
        ContractDraft original = projector.project(graphDraft(), fingerprint('a'));
        ContractDraft equivalent = projector.project(graphDraft(), fingerprint('a'));
        ContractDraft changed = new ContractDraft(
                original.schemaVersion(),
                original.target(),
                original.inputSchema(),
                original.outputSchema(),
                original.errorContract(),
                new ContractDraft.ExecutionSemantics(ContractDraft.Effect.READ, "REQUEST_KEY", false, true, null),
                original.invariants(),
                original.compatibilityPolicy(),
                original.fieldMetadata(),
                original.source(),
                original.confidence()
        );

        assertThat(original.fingerprint(objectMapper)).isEqualTo(equivalent.fingerprint(objectMapper));
        assertThat(changed.fingerprint(objectMapper)).isNotEqualTo(original.fingerprint(objectMapper));
    }

    @Test
    void fieldMetadataAndExtensionsAreDefensivelyFrozen() {
        Map<String, Object> nested = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>(List.of("identity"));
        nested.put("labels", labels);
        ContractDraft.FieldMetadata metadata = new ContractDraft.FieldMetadata(
                "Applicant",
                "Stable applicant identity.",
                "CONFIDENTIAL",
                ContractDraft.Source.AUTHORED,
                ContractDraft.Confidence.EXACT,
                nested
        );
        ContractDraft contract = new ContractDraft(
                "",
                ContractDraft.Target.unknown(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                Map.of("/applicantId", metadata),
                null,
                null
        );

        labels.add("mutated");

        assertThat(contract.fieldMetadata().get("/applicantId").extensions())
                .containsEntry("labels", List.of("identity"));
        assertThatThrownBy(() -> contract.fieldMetadata().put("/new", metadata))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void cyclicAuthoringValuesFailClosed() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);

        assertThatThrownBy(() -> VisualAuthoringJsonValue.freeze(cyclic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain cycles");
    }

    private static GraphDraft graphDraft() {
        SchemaEnvelope input = SchemaEnvelope.object(
                Map.of("applicantId", Map.of("type", "string")),
                List.of("applicantId")
        );
        SchemaEnvelope output = SchemaEnvelope.object(
                Map.of("decision", Map.of("type", "string")),
                List.of("decision")
        );
        return new GraphDraft(
                "",
                "draft-a",
                4,
                "loanPolicy",
                "tenant-a",
                "local",
                "test",
                "",
                input,
                output,
                List.of(new GraphDraft.DraftNode(
                        "decision",
                        "bloge:transform",
                        "Decision",
                        Map.of(),
                        Map.of(),
                        new GraphDraft.Position(0, 0)
                )),
                List.of(),
                Map.of(),
                Map.of(),
                new GraphDraft.OutputSelection("decision", ""),
                Map.of("decision", fingerprint('d')),
                Map.of(),
                GraphDraft.RevisionMetadata.empty()
        );
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
