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
                VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true, true, 1));
        repository.create(record("draft-2", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true, false, 1));
        repository.create(record("draft-1", VisualGraphRunRecord.SOURCE_PUBLICATION, "publication-1", true, true, 1));
        VisualGraphRunHistoryController controller = new VisualGraphRunHistoryController(repository);

        assertThat(controller.list("stored_draft", "draft-1", null, "visualPolicy", true, 1))
                .containsExactly(matching);
        assertThat(controller.list("PUBLICATION", null, "publication-1", null, true, null))
                .extracting(VisualGraphRunRecord::publicationId)
                .containsExactly("publication-1");
    }

    @Test
    void statsSummarizeFilteredRunRecords() {
        InMemoryVisualGraphRunRepository repository = new InMemoryVisualGraphRunRepository();
        repository.create(record("draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true, true, 10));
        repository.create(record("draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true, false, 40));
        repository.create(record("draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", false, false, 0));
        repository.create(record("draft-2", VisualGraphRunRecord.SOURCE_PUBLICATION, "publication-1",
                true, true, 80));
        VisualGraphRunHistoryController controller = new VisualGraphRunHistoryController(repository);

        VisualGraphRunStats stats = controller.stats("stored_draft", "draft-1", null, "visualPolicy",
                null, null);

        assertThat(stats.totalRuns()).isEqualTo(3);
        assertThat(stats.successfulRuns()).isEqualTo(1);
        assertThat(stats.failedRuns()).isEqualTo(2);
        assertThat(stats.blockedRuns()).isEqualTo(1);
        assertThat(stats.executionFailedRuns()).isEqualTo(1);
        assertThat(stats.successRate()).isEqualTo(1 / 3.0D);
        assertThat(stats.p50ElapsedMs()).isEqualTo(10);
        assertThat(stats.p95ElapsedMs()).isEqualTo(40);
        assertThat(stats.maxElapsedMs()).isEqualTo(40);
        assertThat(stats.bySourceKind()).containsEntry(VisualGraphRunRecord.SOURCE_STORED_DRAFT, 3);
        assertThat(stats.byGraphName()).containsEntry("visualPolicy", 3);
        assertThat(stats.firstRunAt()).isNotNull();
        assertThat(stats.latestRunAt()).isNotNull();
    }

    @Test
    void getReturnsNotFoundForUnknownRun() {
        VisualGraphRunHistoryController controller =
                new VisualGraphRunHistoryController(new InMemoryVisualGraphRunRepository());

        assertThat(controller.get("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static VisualGraphRunRecord record() {
        return record("draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true, true, 1);
    }

    private static VisualGraphRunRecord record(String draftId,
                                               String sourceKind,
                                               String publicationId,
                                               boolean compiled,
                                               boolean success,
                                               long elapsedMs) {
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
                compiled,
                success,
                "visualPolicy",
                "response",
                Map.of("ok", true),
                Map.of(),
                Map.of(),
                elapsedMs,
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
