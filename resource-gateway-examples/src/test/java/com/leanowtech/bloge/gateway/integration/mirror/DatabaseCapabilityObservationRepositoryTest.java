package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCapabilityObservationRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityObservationIntegrity observationIntegrity =
            new CapabilityObservationIntegrity(mapper);
    private final CapabilityObservationAdmissionIntegrity admissionIntegrity =
            new CapabilityObservationAdmissionIntegrity(mapper);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseCapabilityObservationRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseCapabilityObservationRepository(
                jdbc, mapper, observationIntegrity, admissionIntegrity);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsAtomicReceiptAcrossRestartAndRecoversExactRetry() {
        CapabilityObservationRepository.StoredObservation candidate =
                candidate("org-a", "observation-a");

        assertThat(repository.append(candidate)).isEqualTo(candidate);
        assertThat(repository.append(candidate)).isEqualTo(candidate);
        DatabaseCapabilityObservationRepository restarted =
                new DatabaseCapabilityObservationRepository(
                        jdbc, mapper, observationIntegrity, admissionIntegrity);
        restarted.init();

        assertThat(restarted.find(
                candidate.envelope().material().scope(), "observation-a"))
                .contains(candidate);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_capability_observations",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void isolatesSameObservationIdByCompleteEnterpriseScope() {
        CapabilityObservationRepository.StoredObservation orgA =
                candidate("org-a", "shared-observation");
        CapabilityObservationRepository.StoredObservation orgB =
                candidate("org-b", "shared-observation");

        repository.append(orgA);
        repository.append(orgB);

        assertThat(repository.find(
                orgA.envelope().material().scope(), "shared-observation"))
                .contains(orgA);
        assertThat(repository.find(
                orgB.envelope().material().scope(), "shared-observation"))
                .contains(orgB);
        assertThat(repository.find(
                new CapabilitySnapshot.Scope(
                        "tenant-a", "org-a", "other", "test", "sg"),
                "shared-observation")).isEmpty();
    }

    @Test
    void rejectsDifferentContentAtExistingObservationId() {
        CapabilityObservationRepository.StoredObservation first =
                candidate("org-a", "observation-conflict");
        CapabilitySnapshot capability = CapabilityObservationTestFixtures.capability(
                mapper, first.envelope().material().scope());
        InMemoryVisualEvidenceSigner otherSigner =
                new InMemoryVisualEvidenceSigner();
        CapabilityObservationEnvelope different =
                CapabilityObservationTestFixtures.envelope(
                        mapper, otherSigner, capability, "observation-conflict");
        Instant decidedAt = different.seal().signedAt().plusSeconds(1);
        CapabilityObservationAdmission differentAdmission =
                admissionIntegrity.admitted(
                        different,
                        CapabilityObservationTestFixtures.ref(
                                "OBSERVATION_ADMISSION_POLICY", "support-policy", 3, 'f'),
                        CapabilityObservationTestFixtures.authorityKey(
                                different,
                                otherSigner,
                                CapabilityObservationIntegrity.KeyState.ACTIVE)
                                .keyRef(),
                        decidedAt,
                        decidedAt.plus(Duration.ofDays(10)));
        repository.append(first);

        assertThatThrownBy(() -> repository.append(
                new CapabilityObservationRepository.StoredObservation(
                        different, differentAdmission)))
                .isInstanceOf(CapabilityObservationRepository.Violation.class)
                .extracting(failure ->
                        ((CapabilityObservationRepository.Violation) failure).reason())
                .isEqualTo(
                        CapabilityObservationRepository.Reason
                                .OBSERVATION_ID_CONFLICT);
    }

    @Test
    void refusesTamperedIndexAndCanonicalJson() {
        CapabilityObservationRepository.StoredObservation candidate =
                candidate("org-a", "observation-tamper");
        repository.append(candidate);
        CapabilitySnapshot.Scope scope = candidate.envelope().material().scope();

        jdbc.update("""
                UPDATE mirror_capability_observations
                SET admission_state = 'QUARANTINED'
                WHERE observation_id = ?
                """, "observation-tamper");
        assertThatThrownBy(() -> repository.find(scope, "observation-tamper"))
                .isInstanceOf(CapabilityObservationRepository.Violation.class)
                .extracting(failure ->
                        ((CapabilityObservationRepository.Violation) failure).reason())
                .isEqualTo(
                        CapabilityObservationRepository.Reason.STORED_STATE_CORRUPT);

        jdbc.update("""
                UPDATE mirror_capability_observations
                SET envelope_json = '{}', admission_state = 'ADMITTED'
                WHERE observation_id = ?
                """, "observation-tamper");
        assertThatThrownBy(() -> repository.find(scope, "observation-tamper"))
                .isInstanceOf(CapabilityObservationRepository.Violation.class)
                .extracting(failure ->
                        ((CapabilityObservationRepository.Violation) failure).reason())
                .isEqualTo(
                        CapabilityObservationRepository.Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void tableCannotRepresentRawRequestResponseOrBusinessKeyPayloads() {
        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_CAPABILITY_OBSERVATIONS'
                ORDER BY ORDINAL_POSITION
                """, String.class);

        assertThat(columns).noneMatch(column ->
                column.equals("REQUEST")
                        || column.equals("RESPONSE")
                        || column.contains("BUSINESS_KEY")
                        || column.contains("RAW_PAYLOAD")
                        || column.contains("SECRET"));
        assertThat(columns).contains(
                "OBSERVATION_FINGERPRINT",
                "ADMISSION_STATE",
                "ADMISSION_REASON",
                "ENVELOPE_JSON",
                "ADMISSION_JSON");
    }

    private CapabilityObservationRepository.StoredObservation candidate(
            String organization, String observationId) {
        CapabilitySnapshot capability = CapabilityObservationTestFixtures.capability(
                mapper, CapabilityObservationTestFixtures.scope(organization));
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        CapabilityObservationEnvelope envelope =
                CapabilityObservationTestFixtures.envelope(
                        mapper, signer, capability, observationId);
        Instant decidedAt = envelope.seal().signedAt().plusSeconds(1);
        CapabilityObservationAdmission admission = admissionIntegrity.admitted(
                envelope,
                CapabilityObservationTestFixtures.ref(
                        "OBSERVATION_ADMISSION_POLICY", "support-policy", 3, 'f'),
                CapabilityObservationTestFixtures.authorityKey(
                        envelope,
                        signer,
                        CapabilityObservationIntegrity.KeyState.ACTIVE).keyRef(),
                decidedAt,
                decidedAt.plus(Duration.ofDays(10)));
        return new CapabilityObservationRepository.StoredObservation(
                envelope, admission);
    }
}
