package com.leanowtech.bloge.gateway.visual.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Proves that simulation lineage is derived from successful server output, never client claims. */
class VisualSimulationCaptureEvidenceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final String OPERATOR_FINGERPRINT = "sha256:" + "1".repeat(64);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void capturesSuccessfulUnpinnedOperatorOutputAndMatchesPinnedRevision() {
        VisualOperatorCatalog catalog = catalog();
        InMemoryVisualSimulationCaptureEvidenceRepository repository = repository();
        GraphDraft draft = draft(3, Map.of());
        Object output = Map.of("orders", List.of(Map.of("id", "order-1")));

        repository.recordSuccessfulSimulation(request(draft, Map.of()), response(output), catalog);

        VisualSimulationCaptureEvidence evidence = repository.find(
                        "tenant-a", "local", "test", "draft-1", "node_1")
                .orElseThrow();
        assertThat(evidence.draftRevision()).isEqualTo(3);
        assertThat(evidence.operatorRef()).isEqualTo("resource:orders");
        assertThat(evidence.outputFingerprint()).startsWith("sha256:");
        assertThat(evidence.matches(
                draft(4, Map.of("node_1", new GraphDraft.NodeFixture(output))),
                "node_1", operator(), output, MAPPER, NOW.plusSeconds(1))).isTrue();
        assertThat(evidence.matches(
                draft(4, Map.of()), "node_1", operator(), Map.of("orders", List.of()),
                MAPPER, NOW.plusSeconds(1))).isFalse();
    }

    @Test
    void ignoresSuccessfulResponsesForClientProvidedFixtures() {
        InMemoryVisualSimulationCaptureEvidenceRepository repository = repository();
        GraphDraft draft = draft(3, Map.of());
        Object output = Map.of("orders", List.of(Map.of("id", "order-1")));

        repository.recordSuccessfulSimulation(
                request(draft, Map.of("node_1", new NodeFixture(
                        output, Map.of(), null, NodeFixture.ResourceFidelity.OUTPUT_LEVEL))),
                response(output), catalog());

        assertThat(repository.find("tenant-a", "local", "test", "draft-1", "node_1"))
                .isEmpty();
    }

    @Test
    void rejectsFailedOrExpiredEvidenceAndKeepsScopeBoundaries() {
        InMemoryVisualSimulationCaptureEvidenceRepository repository = repository();
        GraphDraft draft = draft(3, Map.of());
        Object output = Map.of("orders", List.of(Map.of("id", "order-1")));
        VisualGraphSimulationResponse failed = new VisualGraphSimulationResponse(
                true, false, false, "graph", "node_1", output, Map.of("node_1", output),
                Map.of("node_1", "FAILED"), 1, Map.of(), List.of(), List.of(), false,
                List.of(), List.of("compile failed"), "", Map.of());
        repository.recordSuccessfulSimulation(request(draft, Map.of()), failed, catalog());
        assertThat(repository.find("tenant-a", "local", "test", "draft-1", "node_1"))
                .isEmpty();

        VisualSimulationCaptureEvidence evidence = new VisualSimulationCaptureEvidence(
                VisualSimulationCaptureEvidence.SCHEMA_VERSION, "tenant-a", "local", "test",
                "draft-1", 3, VisualSimulationCaptureEvidence.draftFingerprint(MAPPER, draft),
                "node_1", "resource:orders", OPERATOR_FINGERPRINT,
                VisualSimulationCaptureEvidence.valueFingerprint(MAPPER, output), NOW,
                NOW.plusSeconds(5));
        assertThat(evidence.activeAt(NOW.plusSeconds(5))).isFalse();
        assertThat(evidence.matches(
                draft, "node_1", operator(), output, MAPPER, NOW.plusSeconds(5))).isFalse();
        assertThat(evidence.matches(
                draft, "node_1", operator(), output, MAPPER, NOW.plusSeconds(1))).isTrue();
        assertThat(evidence.matches(
                new GraphDraft("", "draft-1", 3, "graph", "tenant-b", "local", "test", "DRAFT",
                        SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(node()), List.of(),
                        Map.of(), Map.of(), new GraphDraft.OutputSelection("node_1", ""), Map.of(), Map.of(),
                        GraphDraft.RevisionMetadata.empty()),
                "node_1", operator(), output, MAPPER, NOW.plusSeconds(1))).isFalse();
    }

    private static InMemoryVisualSimulationCaptureEvidenceRepository repository() {
        return new InMemoryVisualSimulationCaptureEvidenceRepository(
                MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), 8);
    }

    private static VisualOperatorCatalog catalog() {
        VisualOperatorCatalog catalog = mock(VisualOperatorCatalog.class);
        OperatorDefinition operator = operator();
        when(catalog.find("resource:orders")).thenReturn(Optional.of(operator));
        return catalog;
    }

    private static OperatorDefinition operator() {
        OperatorDefinition operator = mock(OperatorDefinition.class);
        when(operator.operatorRef()).thenReturn("resource:orders");
        when(operator.fingerprint()).thenReturn(OPERATOR_FINGERPRINT);
        return operator;
    }

    private static VisualGraphSimulationRequest request(GraphDraft draft, Map<String, NodeFixture> fixtures) {
        return new VisualGraphSimulationRequest(draft, Map.of(), "node_1", fixtures);
    }

    private static VisualGraphSimulationResponse response(Object output) {
        return new VisualGraphSimulationResponse(
                true, true, true, "graph", "node_1", output, Map.of("node_1", output),
                Map.of("node_1", "COMPLETED"), 1, Map.of("node_1", 1L), List.of("node_1"),
                List.of(), true, List.of(), List.of(), "graph graph {}",
                Map.of("node_1", "OUTPUT_LEVEL"));
    }

    private static GraphDraft draft(long revision, Map<String, GraphDraft.NodeFixture> fixtures) {
        return new GraphDraft(
                "", "draft-1", revision, "graph", "tenant-a", "local", "test", "DRAFT",
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(node()), List.of(), Map.of(), fixtures,
                new GraphDraft.OutputSelection("node_1", ""), Map.of(), Map.of(),
                GraphDraft.RevisionMetadata.empty());
    }

    private static GraphDraft.DraftNode node() {
        return new GraphDraft.DraftNode(
                "node_1", "resource:orders", "Orders", Map.of(), Map.of(), null);
    }
}
