package com.leanowtech.bloge.gateway.visualadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.simulation.InMemoryVisualSimulationCaptureEvidenceRepository;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationRequest;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationResponse;
import com.leanowtech.bloge.gateway.visual.simulation.VisualSimulationCaptureEvidence;
import com.leanowtech.bloge.gateway.visual.simulation.VisualSimulationCaptureEvidenceRepository;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GovernedFixtureSimulationResolver;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Proves the visual adapter records ordinary server simulation lineage at its trusted boundary. */
class GovernedFixtureSimulationAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final String OPERATOR_FINGERPRINT = "sha256:" + "2".repeat(64);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void ordinarySuccessfulSimulationCreatesServerCaptureReceipt() {
        VisualOperatorCatalog catalog = catalog();
        VisualSimulationCaptureEvidenceRepository captures = repository();
        var simulation = mock(com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService.class);
        GraphDraft draft = draft();
        Object output = Map.of("orders", List.of(Map.of("id", "order-1")));
        VisualGraphSimulationResponse response = response(output);
        when(simulation.simulate(any(), any(), any(), any())).thenReturn(response);
        GovernedFixtureSimulationAdapter adapter = new GovernedFixtureSimulationAdapter(
                simulation, null, mock(GovernedFixtureSimulationResolver.class), catalog, captures);

        assertThat(adapter.simulate(
                new VisualGraphSimulationRequest(draft, Map.of(), "node_1", Map.of()),
                new HttpHeaders())).isSameAs(response);

        VisualSimulationCaptureEvidence evidence = captures.find(
                        "tenant-a", "local", "test", "draft-1", "node_1")
                .orElseThrow();
        assertThat(evidence.matches(draft, "node_1", operator(), output, MAPPER, NOW)).isTrue();
    }

    @Test
    void clientFixtureSimulationDoesNotCreateLineageReceipt() {
        VisualSimulationCaptureEvidenceRepository captures = repository();
        var simulation = mock(com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService.class);
        Object output = Map.of("orders", List.of(Map.of("id", "order-1")));
        when(simulation.simulate(any(), any(), any(), any())).thenReturn(response(output));
        GovernedFixtureSimulationAdapter adapter = new GovernedFixtureSimulationAdapter(
                simulation, null, mock(GovernedFixtureSimulationResolver.class), catalog(), captures);

        adapter.simulate(new VisualGraphSimulationRequest(
                draft(), Map.of(), "node_1", Map.of("node_1", new NodeFixture(
                        output, Map.of(), null, NodeFixture.ResourceFidelity.OUTPUT_LEVEL))),
                new HttpHeaders());

        assertThat(captures.find("tenant-a", "local", "test", "draft-1", "node_1"))
                .isEmpty();
    }

    private static VisualSimulationCaptureEvidenceRepository repository() {
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

    private static GraphDraft draft() {
        return new GraphDraft(
                "", "draft-1", 3, "graph", "tenant-a", "local", "test", "DRAFT",
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(new GraphDraft.DraftNode(
                        "node_1", "resource:orders", "Orders", Map.of(), Map.of(), null)), List.of(),
                Map.of(), Map.of(), new GraphDraft.OutputSelection("node_1", ""), Map.of(), Map.of(),
                GraphDraft.RevisionMetadata.empty());
    }

    private static VisualGraphSimulationResponse response(Object output) {
        return new VisualGraphSimulationResponse(
                true, true, true, "graph", "node_1", output, Map.of("node_1", output),
                Map.of("node_1", "COMPLETED"), 1, Map.of("node_1", 1L), List.of("node_1"),
                List.of(), true, List.of(), List.of(), "graph graph {}",
                Map.of("node_1", "OUTPUT_LEVEL"));
    }
}
