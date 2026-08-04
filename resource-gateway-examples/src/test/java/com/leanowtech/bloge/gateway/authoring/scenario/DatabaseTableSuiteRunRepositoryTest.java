package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTableSuiteRunRepositoryTest {

    @Test
    void isolatesScopesMakesRequestCreationIdempotentAndUsesRevisionCas() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        try {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            JdbcTemplate jdbc = new JdbcTemplate(database);
            DatabaseTableSuiteRunRepository repository =
                    new DatabaseTableSuiteRunRepository(jdbc, mapper);
            ScenarioDraftSet.EnterpriseScope scope = scope("tenant-a");
            TableSuiteRunBatch admitted = admitted(scope, "batch-1", "request-1");
            TableSuiteRunBatch duplicate = admitted(scope, "batch-2", "request-1");

            assertThat(repository.create(admitted)).isEqualTo(admitted);
            assertThat(repository.create(duplicate)).isEqualTo(admitted);
            assertThat(repository.find(scope, "batch-1")).contains(admitted);
            assertThat(repository.findByRequest(scope, "request-1")).contains(admitted);
            assertThat(repository.find(scope("tenant-b"), "batch-1")).isEmpty();

            TableSuiteRunBatch running = admitted.running(List.of("case-a"),
                    Instant.parse("2026-08-04T10:00:01Z"), false);
            assertThat(repository.replace(running, admitted.revision())).isTrue();
            assertThat(repository.replace(running, admitted.revision())).isFalse();
            assertThat(repository.find(scope, "batch-1")).contains(running);

            String stored = jdbc.queryForObject(
                    "SELECT batch_json FROM visual_table_suite_runs", String.class);
            assertThat(stored)
                    .doesNotContain("business-secret", "graphDraft", "draftSet", "expectedInput")
                    .contains("scenarioDraftSetFingerprint", "requestFingerprint");
        } finally {
            database.shutdown();
        }
    }

    private static TableSuiteRunBatch admitted(
            ScenarioDraftSet.EnterpriseScope scope,
            String batchId,
            String requestId) {
        String fingerprint = "sha256:" + "a".repeat(64);
        ContractDraft.Target target = new ContractDraft.Target(
                ContractDraft.TargetKind.GRAPH, "graph-a", 3, fingerprint);
        ScenarioDraftSet.ScenarioDraft scenario = new ScenarioDraftSet.ScenarioDraft(
                "case-a", "Case A", "", ScenarioDraftSet.CaseType.GOLDEN,
                List.of(), new ScenarioDraftSet.Given(
                Map.of("secret", "business-secret"), ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(), new ScenarioDraftSet.Then(List.of()));
        ScenarioDraftSet draftSet = new ScenarioDraftSet(
                "", "suite-a", 2, scope, target, fingerprint, List.of(scenario),
                new ScenarioDraftSet.Metadata("owner", "INTERNAL", null, null, Map.of()));
        TableSuiteRunBatch.RowEvidence row = new TableSuiteRunBatch.RowEvidence(
                "case-a", fingerprint, TableSuiteRunBatch.RowStatus.QUEUED,
                List.of(), false, TableSuiteRunBatch.BaselineComparison.none());
        return TableSuiteRunBatch.admitted(batchId, requestId, fingerprint, draftSet,
                fingerprint, new TableSuiteRunBatch.SelectionClosure(
                        TableSuiteRunCommand.SelectionMode.ALL, List.of("case-a"), fingerprint, true),
                new TableSuiteRunCommand.Preflight("test",
                        TableSuiteRunCommand.DependencyMode.SIMULATED,
                        TableSuiteRunCommand.EffectProfile.SIDE_EFFECT_FREE,
                        500, 10, 1, 10_000), "", List.of(row),
                Instant.parse("2026-08-04T10:00:00Z"));
    }

    private static ScenarioDraftSet.EnterpriseScope scope(String tenantId) {
        return new ScenarioDraftSet.EnterpriseScope(
                tenantId, "org-a", "project-a", "test", "sg");
    }
}
