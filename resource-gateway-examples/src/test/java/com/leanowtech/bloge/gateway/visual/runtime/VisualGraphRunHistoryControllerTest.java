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

        assertThat(controller.list()).containsExactly(stored);
        assertThat(controller.get(stored.runId()))
                .extracting(response -> response.getBody())
                .isEqualTo(stored);
    }

    @Test
    void getReturnsNotFoundForUnknownRun() {
        VisualGraphRunHistoryController controller =
                new VisualGraphRunHistoryController(new InMemoryVisualGraphRunRepository());

        assertThat(controller.get("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static VisualGraphRunRecord record() {
        GraphDraft draft = new GraphDraft(
                "",
                "draft-1",
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
                true,
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
        return VisualGraphRunRecord.storedDraft(draft, Map.of("score", 720), response);
    }
}
