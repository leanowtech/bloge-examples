package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityRunRepositoryTest {
    private static final String REQUEST_FINGERPRINT =
            TestSuiteStabilityProtocolFixtures.fingerprint('9');

    private ObjectMapper mapper;
    private DatabaseTestSuiteStabilityRunRepository repository;
    private TestSuiteStabilityAttestationService attestations;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-stability-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        repository = new DatabaseTestSuiteStabilityRunRepository(
                new JdbcTemplate(dataSource), mapper);
        repository.init();
        attestations = new TestSuiteStabilityAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());
    }

    @Test
    void signedTerminalAnalysisRoundTripsOnlyInsideItsScope() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));

        assertThat(repository.create(record)).isEqualTo(record);
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .contains(record);
        assertThat(repository.findByClientRequestId(
                "tenant-a", "test", "stability-request")).contains(record);
        assertThat(repository.find("tenant-b", "test", record.stabilityRunId())).isEmpty();
        assertThat(repository.find("tenant-a", "staging", record.stabilityRunId())).isEmpty();
    }

    @Test
    void statisticalV3AnalysisRoundTripsWithoutLosingItsSignedAssessment() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.statisticalStableEvidence();
        TestSuiteStabilityRunRecord record = record(evidence, "tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));

        repository.create(record);

        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .get().extracting(value -> value.evidence().statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityEvidence.StatisticalStatus.SATISFIED);
    }

    @Test
    void scopedIdempotencyKeyAndDeterministicIdAreImmutableRaceBarriers() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        repository.create(record);

        TestSuiteStabilityRunRecord duplicate = new TestSuiteStabilityRunRecord(
                record.stabilityRunId(), record.clientRequestId(), record.requestFingerprint(),
                record.tenantId(), record.organizationId(), record.projectId(),
                record.environmentId(), "another-actor", record.classification(),
                record.evidenceFingerprint(), record.evidence(), record.attestation(),
                record.createdAt().plusMillis(1), record.expiresAt());

        assertThatThrownBy(() -> repository.create(duplicate))
                .isInstanceOf(TestSuiteStabilityRunConflictException.class);
    }

    @Test
    void rejectsTamperedFingerprintOrUnsignedMaterialBeforePersistence() {
        TestSuiteStabilityRunRecord valid = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityRunRecord tampered = new TestSuiteStabilityRunRecord(
                valid.stabilityRunId(), valid.clientRequestId(), valid.requestFingerprint(),
                valid.tenantId(), valid.organizationId(), valid.projectId(),
                valid.environmentId(), valid.actorId(), valid.classification(),
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), valid.evidence(),
                valid.attestation(), valid.createdAt(), valid.expiresAt());

        assertThatThrownBy(() -> repository.create(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed terminal stability record");
    }

    private TestSuiteStabilityRunRecord record(
            String tenantId,
            String environmentId,
            String clientRequestId,
            Instant expiresAt) {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.stableEvidence();
        return record(evidence, tenantId, environmentId, clientRequestId, expiresAt);
    }

    private TestSuiteStabilityRunRecord record(
            TestSuiteStabilityEvidence evidence,
            String tenantId,
            String environmentId,
            String clientRequestId,
            Instant expiresAt) {
        var seal = attestations.seal(evidence, REQUEST_FINGERPRINT);
        Instant createdAt = Instant.now();
        return new TestSuiteStabilityRunRecord(evidence.stabilityRunId(), clientRequestId,
                REQUEST_FINGERPRINT, tenantId, "org-a", "project-a", environmentId,
                "runner", "INTERNAL", seal.attestation().evidenceFingerprint(), evidence,
                seal.attestation(), createdAt, expiresAt);
    }
}
