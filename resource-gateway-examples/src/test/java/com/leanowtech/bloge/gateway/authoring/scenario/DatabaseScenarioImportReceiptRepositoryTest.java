package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseScenarioImportReceiptRepositoryTest {

    @Test
    void storesOnePayloadFreeResultPerScopedPlanFingerprint() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        try {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            JdbcTemplate jdbc = new JdbcTemplate(database);
            var repository = new DatabaseScenarioImportReceiptRepository(jdbc, mapper);
            ScenarioDraftSet.EnterpriseScope scope = new ScenarioDraftSet.EnterpriseScope(
                    "tenant-a", "org-a", "project-a", "test", "sg");
            String fingerprint = "sha256:" + "a".repeat(64);
            ScenarioImportMaterializationResult first = result(mapper, scope, "receipt-1");
            ScenarioImportMaterializationResult concurrent = result(mapper, scope, "receipt-2");

            assertThat(repository.saveIfAbsent(scope, fingerprint, first)).isEqualTo(first);
            assertThat(repository.saveIfAbsent(scope, fingerprint, concurrent)).isEqualTo(first);
            assertThat(repository.find(scope, fingerprint)).contains(first);
            assertThat(repository.find(new ScenarioDraftSet.EnterpriseScope(
                    "tenant-b", "org-a", "project-a", "test", "sg"), fingerprint)).isEmpty();

            String storedJson = jdbc.queryForObject(
                    "SELECT result_json FROM visual_scenario_import_receipts", String.class);
            assertThat(storedJson).contains("receipt-1")
                    .doesNotContain("sourceText", "customer-secret");
        } finally {
            database.shutdown();
        }
    }

    private ScenarioImportMaterializationResult result(
            ObjectMapper mapper,
            ScenarioDraftSet.EnterpriseScope scope,
            String receiptId) {
        ScenarioDraftSet draftSet = new ScenarioDraftSet(
                "", "suite", 1, scope,
                new com.leanowtech.bloge.gateway.visual.contract.ContractDraft.Target(
                        com.leanowtech.bloge.gateway.visual.contract.ContractDraft.TargetKind.GRAPH,
                        "graph", 1, "sha256:" + "b".repeat(64)),
                "sha256:" + "c".repeat(64), List.of(),
                new ScenarioDraftSet.Metadata("author", "INTERNAL", null, null, Map.of()));
        ObjectNode receipt = mapper.createObjectNode();
        receipt.put("schemaVersion", "bloge.scenarioMaterializationReceipt.v1");
        receipt.put("receiptId", receiptId);
        return new ScenarioImportMaterializationResult("", draftSet, receipt);
    }
}
