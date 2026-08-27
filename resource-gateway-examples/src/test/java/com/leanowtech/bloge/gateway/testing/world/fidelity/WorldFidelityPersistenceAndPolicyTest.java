package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldFidelityPersistenceAndPolicyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String CONTRACT = fp("contract");
    private static final String IMPLEMENTATION = fp("implementation");
    private static final String WORLD = fp("world");
    private static final String POLICY = fp("policy");

    @Test
    void jdbcHistoryAndHeadSurviveNewRepositoryInstanceWithoutPayload() throws Exception {
        DataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        WorldFidelityReport report = report("jdbc", true);
        DatabaseWorldFidelityDriftRepository first = new DatabaseWorldFidelityDriftRepository(jdbc, MAPPER);
        first.append("tenant-a", report);
        WorldFidelityDriftRepository.DriftAnnotation head = annotation(report,
                WorldFidelityDriftRepository.DriftState.CURRENT);
        assertThat(first.compareAndSet("tenant-a", report.targetFingerprint(), null, head)).isTrue();

        DatabaseWorldFidelityDriftRepository restarted = new DatabaseWorldFidelityDriftRepository(jdbc, MAPPER);
        assertThat(restarted.current("tenant-a", report.targetFingerprint())).contains(head);
        assertThat(restarted.history("tenant-a", report.targetFingerprint())).containsExactly(report);
        String projection = jdbc.queryForObject("SELECT report_projection_json FROM rg_world_fidelity_reports", String.class);
        assertThat(projection).doesNotContain("payload-canary", "request-secret", "response-secret");
        com.fasterxml.jackson.databind.JsonNode projectionJson = MAPPER.readTree(projection);
        assertThat(projectionJson.has("request")).isFalse();
        assertThat(projectionJson.has("response")).isFalse();
        assertThat(projectionJson.has("realResponse")).isFalse();
        assertThat(projectionJson.has("worldResponse")).isFalse();
        assertThat(restarted.current("tenant-b", report.targetFingerprint())).isEmpty();
    }

    @Test
    void jdbcReceiptIsConsumedOnlyByWinningExactTransitionAndTamperingFailsClosed() {
        JdbcTemplate jdbc = new JdbcTemplate(database());
        DatabaseWorldFidelityDriftRepository repository = new DatabaseWorldFidelityDriftRepository(jdbc, MAPPER);
        WorldFidelityReport report = report("receipt", false);
        repository.append("tenant-a", report);
        WorldFidelityDriftRepository.DriftAnnotation confirmed = annotation(report,
                WorldFidelityDriftRepository.DriftState.CONFIRMED);
        assertThat(repository.compareAndSet("tenant-a", report.targetFingerprint(), null, confirmed)).isTrue();
        String receipt = fp("receipt");
        WorldFidelityDriftRepository.DriftAnnotation accepted = annotation(report,
                WorldFidelityDriftRepository.DriftState.ACCEPTED_DIVERGENCE);
        assertThat(repository.compareAndSetAndConsumeReceipt("tenant-a", report.targetFingerprint(),
                WorldFidelityDriftRepository.DriftState.CONFIRMED, accepted, receipt)).isTrue();
        assertThat(repository.compareAndSetAndConsumeReceipt("tenant-a", report.targetFingerprint(),
                WorldFidelityDriftRepository.DriftState.CONFIRMED, accepted, receipt)).isFalse();
        assertThat(repository.current("tenant-a", report.targetFingerprint())).contains(accepted);

        jdbc.update("UPDATE rg_world_fidelity_reports SET report_projection_json=?", "{}");
        assertThatThrownBy(() -> repository.history("tenant-a", report.targetFingerprint()))
                .isInstanceOf(WorldFidelityException.class)
                .extracting(error -> ((WorldFidelityException) error).code())
                .isEqualTo(WorldFidelityException.Code.PERSISTENCE_INVALID);
    }

    @Test
    void migrationAndCrossTenantBoundariesAreExplicit() throws Exception {
        String postgres = new ClassPathResource("db/postgresql/V20260827_003__world_fidelity_drift.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String h2 = new ClassPathResource("db/h2/V20260827_003__world_fidelity_drift.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(postgres).contains("rg_world_fidelity_reports", "rg_world_fidelity_drift_heads",
                "rg_world_fidelity_receipts", "JSONB", "PRIMARY KEY (tenant_id, target_fingerprint)")
                .doesNotContain("canonical_json", "payload_json");
        assertThat(h2).contains("rg_world_fidelity_reports", "rg_world_fidelity_drift_heads",
                "rg_world_fidelity_receipts", "TEXT");

        JdbcTemplate jdbc = new JdbcTemplate(database());
        DatabaseWorldFidelityDriftRepository repository = new DatabaseWorldFidelityDriftRepository(jdbc, MAPPER);
        WorldFidelityReport report = report("tenant", true);
        repository.append("tenant-a", report);
        WorldFidelityDriftRepository.DriftAnnotation head = annotation(report,
                WorldFidelityDriftRepository.DriftState.CURRENT);
        assertThat(repository.compareAndSet("tenant-b", report.targetFingerprint(), null, head)).isTrue();
        assertThat(repository.current("tenant-a", report.targetFingerprint())).isEmpty();
        assertThat(repository.compareAndSet("tenant-a", report.targetFingerprint(), null, head)).isTrue();
    }

    @Test
    void policyAnnotatesHistoricalEvidenceAndNeverMutatesIt() throws Exception {
        InMemoryWorldFidelityDriftRepository repository = new InMemoryWorldFidelityDriftRepository();
        WorldFidelityReport equivalent = report("policy-equivalent", true);
        WorldFidelityDriftService drift = new WorldFidelityDriftService(repository);
        drift.observe("tenant-a", equivalent);
        TestRunEvidence evidence = evidence(equivalent.targetFingerprint());
        String before = MAPPER.writeValueAsString(evidence);
        WorldFidelityPolicyDecision certifiable = new WorldFidelityPolicyService(repository, MAPPER)
                .decide("tenant-a", equivalent.targetFingerprint(), evidence);
        assertThat(certifiable.driftState()).isEqualTo(WorldFidelityDriftRepository.DriftState.CURRENT);
        assertThat(certifiable.evidenceCeiling()).isEqualTo(WorldFidelityDriftService.EvidenceCeiling.CERTIFIABLE);
        assertThat(certifiable.publicationAllowed()).isTrue();
        assertThat(MAPPER.writeValueAsString(evidence)).isEqualTo(before);

        WorldFidelityReport different = report("policy-different", false);
        drift.observe("tenant-a", different);
        WorldFidelityPolicyDecision blocked = new WorldFidelityPolicyService(repository, MAPPER)
                .decide("tenant-a", equivalent.targetFingerprint(), evidence);
        assertThat(blocked.driftState()).isEqualTo(WorldFidelityDriftRepository.DriftState.SUSPECTED);
        assertThat(blocked.evidenceCeiling()).isEqualTo(WorldFidelityDriftService.EvidenceCeiling.EXPLORATORY);
        assertThat(blocked.publicationAllowed()).isFalse();
        assertThat(MAPPER.writeValueAsString(evidence)).isEqualTo(before);

        WorldFidelityPolicyDecision noAnnotation = new WorldFidelityPolicyService(
                new InMemoryWorldFidelityDriftRepository(), MAPPER)
                .decide("tenant-a", equivalent.targetFingerprint(), ProtocolFingerprint.of(MAPPER, evidence));
        assertThat(noAnnotation.driftState()).isNull();
        assertThat(noAnnotation.evidenceCeiling()).isEqualTo(WorldFidelityDriftService.EvidenceCeiling.UNKNOWN);
        assertThat(noAnnotation.publicationAllowed()).isFalse();
    }

    private static DataSource database() {
        DataSource dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/h2/V20260827_003__world_fidelity_drift.sql")).execute(dataSource);
        return dataSource;
    }

    private static WorldFidelityReport report(String id, boolean equal) {
        WorldFidelityRequest request = request(id);
        return new WorldFidelityCalibrationService(MAPPER).calibrate(request, (access, target) ->
                        new WorldFidelityRequest.AuthorizedTarget(access.tenantId(), access.scope(),
                                WorldFidelityRequest.Environment.NON_PRODUCTION, target.contractId(),
                                target.contractFingerprint(), target.implementationFingerprint(),
                                target.worldSliceFingerprint(), target.policyFingerprint(), true, true),
                ignored -> execution(json("result", "payload-canary"), 200),
                ignored -> execution(json("result", equal ? "payload-canary" : "other"), 200));
    }

    private static WorldFidelityRequest request(String id) {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("id", id);
        WorldFidelityRequest.Sample sample = new WorldFidelityRequest.Sample(id, value,
                ProtocolFingerprint.of(MAPPER, value));
        String samples = ProtocolFingerprint.of(MAPPER, List.of(value));
        WorldFidelityRequest.SampleSet set = new WorldFidelityRequest.SampleSet("samples", 1, List.of(sample),
                samples, true, true);
        WorldFidelityRequest.Target target = new WorldFidelityRequest.Target("ride.lookup", CONTRACT,
                IMPLEMENTATION, WORLD, POLICY);
        return new WorldFidelityRequest(new WorldFidelityRequest.Access("tenant-a", "support",
                WorldFidelityCalibrationService.CALIBRATION_PURPOSE,
                WorldFidelityRequest.Environment.NON_PRODUCTION, true), target, set,
                new WorldFidelityRequest.ComparatorSpec("cmp.v1", POLICY, Map.of(), Map.of(), true, true), samples);
    }

    private static TestRunEvidence evidence(String target) {
        return new TestRunEvidence("", "historical-run", TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "CALIBRATION", target, fp("fixture"), fp("plan"),
                Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:01Z"), List.of(),
                List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private static WorldFidelityDriftRepository.DriftAnnotation annotation(WorldFidelityReport report,
                                                                             WorldFidelityDriftRepository.DriftState state) {
        return new WorldFidelityDriftRepository.DriftAnnotation(state, report.reportFingerprint(),
                report.targetFingerprint(), report.contractFingerprint(), report.worldSliceFingerprint(),
                report.implementationFingerprint(), report.sampleSetFingerprint());
    }

    private static WorldFidelityRunner.Execution execution(ObjectNode response, int status) {
        return new WorldFidelityRunner.Execution(response, "", status, false, List.of(), 1);
    }

    private static ObjectNode json(String key, String value) {
        return JsonNodeFactory.instance.objectNode().put(key, value);
    }

    private static String fp(String value) {
        return ProtocolFingerprint.ofText(value);
    }
}
