package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual graph run history API.
 */
class VisualGraphRunHistoryControllerTest {

    @Test
    void listAndGetRunRecords() {
        InMemoryVisualGraphRunRepository repository = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord stored = repository.create(record());
        VisualGraphRunHistoryController controller = new VisualGraphRunHistoryController(repository);

        assertThat(controller.list(null, null, null, null, null, null)).containsExactly(stored);
        assertThat(controller.get(stored.runId()))
                .extracting(response -> response.getBody())
                .isEqualTo(stored);
    }

    @Test
    void listFiltersRunRecords() {
        InMemoryVisualGraphRunRepository repository = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord matching = repository.create(record("draft-1",
                VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true));
        repository.create(record("draft-2", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", false));
        repository.create(record("draft-1", VisualGraphRunRecord.SOURCE_PUBLICATION, "publication-1", true));
        VisualGraphRunHistoryController controller = new VisualGraphRunHistoryController(repository);

        assertThat(controller.list("stored_draft", "draft-1", null, "visualPolicy", true, 1))
                .containsExactly(matching);
        assertThat(controller.list("PUBLICATION", null, "publication-1", null, true, null))
                .extracting(VisualGraphRunRecord::publicationId)
                .containsExactly("publication-1");
    }

    @Test
    void getReturnsNotFoundForUnknownRun() {
        VisualGraphRunHistoryController controller =
                new VisualGraphRunHistoryController(new InMemoryVisualGraphRunRepository());

        assertThat(controller.get("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static VisualGraphRunRecord record() {
        return record("draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true);
    }

    private static VisualGraphRunRecord record(String draftId,
                                               String sourceKind,
                                               String publicationId,
                                               boolean success) {
        GraphDraft draft = new GraphDraft(
                "",
                draftId,
                1,
                "visualPolicy",
                "",
                "",
                "",
                "",
                SchemaEnvelope.opaque(),
                List.of(),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("response", "")
        );
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true,
                true,
                success,
                "visualPolicy",
                "response",
                Map.of("ok", true),
                Map.of(),
                Map.of(),
                1,
                List.of(),
                List.of(),
                null,
                null,
                "graph visualPolicy {}"
        );
        if (VisualGraphRunRecord.SOURCE_PUBLICATION.equals(sourceKind)) {
            return new VisualGraphRunRecord("", "", sourceKind, draftId, 1, publicationId,
                    response.graphName(), "", "", "", response.outputNode(), null, response.validated(),
                    response.compiled(), response.success(), response.elapsedMs(), response.statusMap(),
                    response.diagnostics(), response.errors(), Map.of("score", Map.of("type", "integer")),
                    Map.of("type", "object"), Map.of(), response.generatedDsl());
        }
        return VisualGraphRunRecord.storedDraft(draft, Map.of("score", 720), response);
    }
}
