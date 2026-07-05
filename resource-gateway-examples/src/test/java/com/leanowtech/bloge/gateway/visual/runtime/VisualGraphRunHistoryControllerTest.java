package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        VisualGraphRunRecord designRun = repository.create(record("draft-1",
                VisualGraphRunRecord.SOURCE_PUBLICATION, "publication-design", "DESIGN", true, false, 1));
        VisualGraphRunHistoryController controller = new VisualGraphRunHistoryController(repository);

        assertThat(controller.list("stored_draft", "draft-1", null, "visualPolicy", true, 1))
                .containsExactly(matching);
        assertThat(controller.list("PUBLICATION", null, "publication-1", null, true, null))
                .extracting(VisualGraphRunRecord::publicationId)
                .containsExactly("publication-1");
        assertThat(controller.list("PUBLICATION", null, null, "DESIGN", null, null, null))
                .containsExactly(designRun);
    }

    @Test
    void statsSummarizeFilteredRunRecords() {
        InMemoryVisualGraphRunRepository repository = new InMemoryVisualGraphRunRepository();
        repository.create(record("draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true, true, 10));
        repository.create(record("draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", true, false, 40));
        repository.create(record("draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", false, false, 0));
        repository.create(record("draft-2", VisualGraphRunRecord.SOURCE_PUBLICATION, "publication-1",
                true, true, 80));
        repository.create(record("draft-2", VisualGraphRunRecord.SOURCE_PUBLICATION, "publication-design",
                "DESIGN", false, false, 0));
        VisualGraphRunHistoryController controller = new VisualGraphRunHistoryController(repository);

        VisualGraphRunStats stats = controller.stats("stored_draft", "draft-1", null, "visualPolicy",
                null, null);
        VisualGraphRunStats designStats = controller.stats("PUBLICATION", null, null, "DESIGN",
                null, null, null);

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
        assertThat(designStats.totalRuns()).isEqualTo(1);
        assertThat(designStats.blockedRuns()).isEqualTo(1);
        assertThat(designStats.bySourceArtifactKind()).containsEntry("DESIGN", 1);
    }

    @Test
    void nodeStatsSummarizeFilteredRunRecords() {
        InMemoryVisualGraphRunRepository repository = new InMemoryVisualGraphRunRepository();
        repository.create(traceRecord(true, 25,
                Map.of("loanPolicy", "COMPLETED", "response", "COMPLETED"),
                Map.of("loanPolicy", 12L, "response", 4L),
                List.of(
                        VisualDiagnostic.warning("bloge.dsl", "Policy expression uses a legacy form.",
                                "/nodes/loanPolicy/expression"),
                        VisualDiagnostic.error("visual.operator.fingerprintMissing",
                                "Response operator fingerprint is missing.", "/nodes/1/operatorRef")
                )));
        repository.create(traceRecord(false, 75,
                Map.of("loanPolicy", "FAILED", "response", "SKIPPED"),
                Map.of("loanPolicy", 66L, "response", 1L),
                List.of(VisualDiagnostic.error("visual.runtime.nodeFailed",
                        "Loan policy failed.", "/draft/nodes/0/runtime"))));
        repository.create(record("draft-2", VisualGraphRunRecord.SOURCE_PUBLICATION, "publication-1",
                true, true, 5));
        VisualGraphRunHistoryController controller = new VisualGraphRunHistoryController(repository);

        VisualGraphRunNodeStats stats = controller.nodeStats("stored_draft", "draft-1", null,
                "visualPolicy", null, null);

        assertThat(stats.schemaVersion()).isEqualTo(VisualGraphRunNodeStats.SCHEMA_VERSION);
        assertThat(stats.totalRuns()).isEqualTo(2);
        assertThat(stats.nodes())
                .extracting(VisualGraphRunNodeStats.NodeStats::nodeId)
                .containsExactly("loanPolicy", "response");
        VisualGraphRunNodeStats.NodeStats loanPolicy = stats.nodes().stream()
                .filter(node -> node.nodeId().equals("loanPolicy"))
                .findFirst()
                .orElseThrow();
        assertThat(loanPolicy.nodeIndex()).isZero();
        assertThat(loanPolicy.operatorRef()).isEqualTo("risk:loanPolicy");
        assertThat(loanPolicy.label()).isEqualTo("Loan Policy");
        assertThat(loanPolicy.runCount()).isEqualTo(2);
        assertThat(loanPolicy.successfulRuns()).isEqualTo(1);
        assertThat(loanPolicy.failedRuns()).isEqualTo(1);
        assertThat(loanPolicy.statusKnownRuns()).isEqualTo(2);
        assertThat(loanPolicy.resultKnownRuns()).isEqualTo(2);
        assertThat(loanPolicy.timingKnownRuns()).isEqualTo(2);
        assertThat(loanPolicy.outputSelectedRuns()).isZero();
        assertThat(loanPolicy.diagnosticCount()).isEqualTo(2);
        assertThat(loanPolicy.errorCount()).isEqualTo(1);
        assertThat(loanPolicy.p50NodeElapsedMs()).isEqualTo(12);
        assertThat(loanPolicy.p95NodeElapsedMs()).isEqualTo(66);
        assertThat(loanPolicy.maxNodeElapsedMs()).isEqualTo(66);
        assertThat(loanPolicy.p50ObservedElapsedMs()).isEqualTo(25);
        assertThat(loanPolicy.p95ObservedElapsedMs()).isEqualTo(75);
        assertThat(loanPolicy.maxObservedElapsedMs()).isEqualTo(75);
        assertThat(loanPolicy.statusCounts())
                .containsEntry("COMPLETED", 1)
                .containsEntry("FAILED", 1);
        assertThat(loanPolicy.firstSeenAt()).isNotNull();
        assertThat(loanPolicy.latestSeenAt()).isNotNull();

        VisualGraphRunNodeStats.NodeStats response = stats.nodes().stream()
                .filter(node -> node.nodeId().equals("response"))
                .findFirst()
                .orElseThrow();
        assertThat(response.nodeIndex()).isEqualTo(1);
        assertThat(response.timingKnownRuns()).isEqualTo(2);
        assertThat(response.p95NodeElapsedMs()).isEqualTo(4);
        assertThat(response.outputSelectedRuns()).isEqualTo(2);
        assertThat(response.diagnosticCount()).isEqualTo(1);
        assertThat(response.errorCount()).isEqualTo(1);
        assertThat(response.statusCounts())
                .containsEntry("COMPLETED", 1)
                .containsEntry("SKIPPED", 1);
    }

    @Test
    void trendSummarizesOutcomeTransitions() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        VisualGraphRunHistoryController controller = new VisualGraphRunHistoryController(new FixedRunRepository(List.of(
                recordAt("run-4", false, false, 0, base.plusSeconds(4)),
                recordAt("run-3", true, true, 30, base.plusSeconds(3)),
                recordAt("run-2", true, false, 40, base.plusSeconds(2)),
                recordAt("run-1", true, true, 10, base.plusSeconds(1))
        )));

        VisualGraphRunTrend trend = controller.trend("stored_draft", "draft-1", null,
                "visualPolicy", null, null);

        assertThat(trend.schemaVersion()).isEqualTo(VisualGraphRunTrend.SCHEMA_VERSION);
        assertThat(trend.totalRuns()).isEqualTo(4);
        assertThat(trend.successfulRuns()).isEqualTo(2);
        assertThat(trend.failedRuns()).isEqualTo(2);
        assertThat(trend.blockedRuns()).isEqualTo(1);
        assertThat(trend.executionFailedRuns()).isEqualTo(1);
        assertThat(trend.successRate()).isEqualTo(0.5D);
        assertThat(trend.latestOutcome()).isEqualTo("BLOCKED");
        assertThat(trend.successToFailureTransitions()).isEqualTo(2);
        assertThat(trend.failureToSuccessTransitions()).isEqualTo(1);
        assertThat(trend.latestFailureStreak()).isEqualTo(1);
        assertThat(trend.latestSuccessStreak()).isZero();
        assertThat(trend.latestRunRegressed()).isTrue();
        assertThat(trend.latestElapsedMs()).isZero();
        assertThat(trend.previousSuccessfulElapsedMs()).isEqualTo(30);
        assertThat(trend.latestLatencyDeltaMs()).isZero();
        assertThat(trend.points())
                .extracting(VisualGraphRunTrend.Point::index)
                .containsExactly(0, 1, 2, 3);
        assertThat(trend.points())
                .extracting(VisualGraphRunTrend.Point::runId)
                .containsExactly("run-4", "run-3", "run-2", "run-1");
        assertThat(trend.points())
                .extracting(VisualGraphRunTrend.Point::outcome)
                .containsExactly("BLOCKED", "SUCCESS", "FAILED", "SUCCESS");
    }

    @Test
    void traceReturnsShapeOnlyNodeReplayView() {
        InMemoryVisualGraphRunRepository repository = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord stored = repository.create(traceRecord());
        VisualGraphRunHistoryController controller = new VisualGraphRunHistoryController(repository);

        VisualGraphRunTrace trace = controller.trace(stored.runId()).getBody();

        assertThat(trace).isNotNull();
        assertThat(trace.schemaVersion()).isEqualTo(VisualGraphRunTrace.SCHEMA_VERSION);
        assertThat(trace.runId()).isEqualTo(stored.runId());
        assertThat(trace.sourceArtifactKind()).isBlank();
        assertThat(trace.graphName()).isEqualTo("visualPolicy");
        assertThat(trace.outputNode()).isEqualTo("response");
        assertThat(trace.contextSummary()).containsKey("score");
        assertThat(trace.outputSummary()).containsEntry("type", "object");
        assertThat(trace.nodes())
                .extracting(VisualGraphRunTrace.NodeTrace::nodeId)
                .containsExactlyInAnyOrder("loanPolicy", "response");
        VisualGraphRunTrace.NodeTrace decisionTrace = trace.nodes().stream()
                .filter(node -> node.nodeId().equals("loanPolicy"))
                .findFirst()
                .orElseThrow();
        assertThat(decisionTrace.nodeIndex()).isEqualTo(0);
        assertThat(decisionTrace.operatorRef()).isEqualTo("risk:loanPolicy");
        assertThat(decisionTrace.label()).isEqualTo("Loan Policy");
        assertThat(decisionTrace.status()).isEqualTo("COMPLETED");
        assertThat(decisionTrace.elapsedMs()).isEqualTo(12);
        assertThat(decisionTrace.timingKnown()).isTrue();
        assertThat(decisionTrace.outputSelected()).isFalse();
        assertThat(decisionTrace.statusKnown()).isTrue();
        assertThat(decisionTrace.resultKnown()).isTrue();
        assertThat(decisionTrace.resultSummary()).containsEntry("type", "object");
        assertThat(decisionTrace.diagnosticCount()).isEqualTo(1);
        assertThat(decisionTrace.errorCount()).isZero();
        assertThat(decisionTrace.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.target()).isEqualTo("/nodes/loanPolicy/expression"));
        VisualGraphRunTrace.NodeTrace responseTrace = trace.nodes().stream()
                .filter(node -> node.nodeId().equals("response"))
                .findFirst()
                .orElseThrow();
        assertThat(responseTrace.nodeIndex()).isEqualTo(1);
        assertThat(responseTrace.operatorRef()).isEqualTo("transform:response");
        assertThat(responseTrace.elapsedMs()).isEqualTo(4);
        assertThat(responseTrace.timingKnown()).isTrue();
        assertThat(responseTrace.outputSelected()).isTrue();
        assertThat(responseTrace.diagnosticCount()).isEqualTo(1);
        assertThat(responseTrace.errorCount()).isEqualTo(1);
        assertThat(responseTrace.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.target()).isEqualTo("/nodes/1/operatorRef"));
        assertThat(trace.generatedDsl()).contains("graph visualPolicy");
    }

    @Test
    void getReturnsNotFoundForUnknownRun() {
        VisualGraphRunHistoryController controller =
                new VisualGraphRunHistoryController(new InMemoryVisualGraphRunRepository());

        assertThat(controller.get("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.trace("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
        return record(draftId, sourceKind, publicationId, "EXECUTABLE", compiled, success, elapsedMs);
    }

    private static VisualGraphRunRecord record(String draftId,
                                               String sourceKind,
                                               String publicationId,
                                               String sourceArtifactKind,
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
                    sourceArtifactKind, response.graphName(), "", "", "", response.outputNode(), null, response.validated(),
                    response.compiled(), response.success(), response.elapsedMs(), response.statusMap(),
                    response.diagnostics(), response.errors(), Map.of("score", Map.of("type", "integer")),
                    Map.of("type", "object"), Map.of(), response.generatedDsl());
        }
        return VisualGraphRunRecord.storedDraft(draft, Map.of("score", 720), response);
    }

    private static VisualGraphRunRecord recordAt(String runId,
                                                 boolean compiled,
                                                 boolean success,
                                                 long elapsedMs,
                                                 Instant createdAt) {
        return record("draft-1", VisualGraphRunRecord.SOURCE_STORED_DRAFT, "", compiled, success, elapsedMs)
                .withIdentity(runId, createdAt);
    }

    private static VisualGraphRunRecord traceRecord() {
        return traceRecord(true, 25,
                Map.of("loanPolicy", "COMPLETED", "response", "COMPLETED"),
                Map.of("loanPolicy", 12L, "response", 4L),
                List.of(
                        VisualDiagnostic.warning("bloge.dsl", "Policy expression uses a legacy form.",
                                "/nodes/loanPolicy/expression"),
                        VisualDiagnostic.error("visual.operator.fingerprintMissing",
                                "Response operator fingerprint is missing.", "/nodes/1/operatorRef")
                ));
    }

    private static VisualGraphRunRecord traceRecord(boolean success,
                                                    long elapsedMs,
                                                    Map<String, String> statusMap,
                                                    Map<String, Long> nodeElapsedMs,
                                                    List<VisualDiagnostic> diagnostics) {
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
                List.of(
                        new GraphDraft.DraftNode("loanPolicy", "risk:loanPolicy", "Loan Policy",
                                Map.of(), Map.of(), null),
                        new GraphDraft.DraftNode("response", "transform:response", "Response",
                                Map.of(), Map.of(), null)
                ),
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
                Map.of("approved", true),
                Map.of(
                        "loanPolicy", Map.of("decision", "approved", "rate", 3.5),
                        "response", Map.of("approved", true, "policy", Map.of("decision", "approved"))
                ),
                statusMap,
                elapsedMs,
                nodeElapsedMs,
                diagnostics,
                diagnostics.stream().filter(VisualDiagnostic::error)
                        .map(VisualDiagnostic::message)
                        .toList(),
                null,
                null,
                "graph visualPolicy {}"
        );
        return VisualGraphRunRecord.storedDraft(draft, Map.of("score", 720), response);
    }

    private record FixedRunRepository(Collection<VisualGraphRunRecord> records) implements VisualGraphRunRepository {

        @Override
        public Collection<VisualGraphRunRecord> all() {
            return records;
        }

        @Override
        public Optional<VisualGraphRunRecord> find(String runId) {
            return records.stream()
                    .filter(record -> record.runId().equals(runId))
                    .findFirst();
        }

        @Override
        public VisualGraphRunRecord create(VisualGraphRunRecord record) {
            throw new UnsupportedOperationException("Fixed run repository is read-only.");
        }
    }
}
