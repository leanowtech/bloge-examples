package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for graph draft JSON patch application.
 */
class GraphDraftPatchServiceTest {

    private final GraphDraftPatchService service = new GraphDraftPatchService(new ObjectMapper());

    @Test
    void addAppendsNodeToDraft() {
        GraphDraft patched = service.apply(simpleDraft(List.of(), List.of()), new GraphDraftPatchRequest(1,
                List.of(new GraphDraftPatchRequest.PatchOperation("add", "/nodes/-", Map.of(
                        "id", "eligibility",
                        "operatorRef", "risk:eligibility"
                )))));

        assertThat(patched.nodes()).hasSize(1);
        assertThat(patched.nodes().getFirst().id()).isEqualTo("eligibility");
        assertThat(patched.nodes().getFirst().operatorRef()).isEqualTo("risk:eligibility");
    }

    @Test
    void removeDeletesEdgeByIndex() {
        GraphDraft draft = simpleDraft(
                List.of(
                        new GraphDraft.DraftNode("source", "risk:numericPass", "", Map.of(), Map.of(), null),
                        new GraphDraft.DraftNode("target", "risk:numericPass", "", Map.of(), Map.of(), null)
                ),
                List.of(new GraphDraft.DraftEdge("edge-1", "data",
                        new GraphDraft.Endpoint("source", "output", "value"),
                        new GraphDraft.Endpoint("target", "inputs", "value")))
        );

        GraphDraft patched = service.apply(draft, new GraphDraftPatchRequest(1,
                List.of(new GraphDraftPatchRequest.PatchOperation("remove", "/edges/0", null))));

        assertThat(patched.edges()).isEmpty();
    }

    @Test
    void rejectsUnsupportedPatchOperation() {
        GraphDraft draft = simpleDraft(List.of(), List.of());

        assertThatThrownBy(() -> service.apply(draft, new GraphDraftPatchRequest(1,
                List.of(new GraphDraftPatchRequest.PatchOperation("move", "/nodes/0", null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported patch operation");
    }

    private static GraphDraft simpleDraft(List<GraphDraft.DraftNode> nodes, List<GraphDraft.DraftEdge> edges) {
        return new GraphDraft(
                "",
                "draft-1",
                1,
                "patchGraph",
                "",
                "",
                "",
                "",
                null,
                nodes,
                edges,
                Map.of(),
                GraphDraft.OutputSelection.empty()
        );
    }
}
