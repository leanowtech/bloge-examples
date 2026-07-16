package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.List;

import static com.leanowtech.bloge.gateway.integration.EvidenceKeySetTrustTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseEvidenceKeySetTrustPublicationRepositoryTest {
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private Authority signer;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        signer = new Authority("security-a", keyPair());
    }

    @Test
    void survivesRestartAndReturnsBoundedContiguousPages() {
        DatabaseEvidenceKeySetTrustPublicationRepository first = repository();
        EvidenceKeySetTrustPublication genesis = publication(mapper, 1, "", 0,
                PUBLISHED_AT, List.of(active(SNAPSHOT_A)), List.of(signer));
        EvidenceKeySetTrustPublication rotated = publication(mapper, 2,
                genesis.publicationFingerprint(), 0, PUBLISHED_AT.plusSeconds(1),
                List.of(overlap(SNAPSHOT_A), active(SNAPSHOT_B)), List.of(signer));
        first.append(genesis);
        first.append(rotated);

        DatabaseEvidenceKeySetTrustPublicationRepository restarted = repository();

        assertThat(restarted.highWaterSequence(LOG_ID)).isEqualTo(2);
        assertThat(restarted.latest(LOG_ID)).contains(rotated);
        assertThat(restarted.readAfter(LOG_ID, 0, 1)).containsExactly(genesis);
        assertThat(restarted.readAfter(LOG_ID, 1, 10)).containsExactly(rotated);
        assertThat(restarted.append(rotated).publicationFingerprint())
                .isEqualTo(rotated.publicationFingerprint());
    }

    @Test
    void durableRevocationIndexRejectsResurrectionAfterRestart() {
        DatabaseEvidenceKeySetTrustPublicationRepository first = repository();
        EvidenceKeySetTrustPublication genesis = publication(mapper, 1, "", 0,
                PUBLISHED_AT, List.of(active(SNAPSHOT_A), overlap(SNAPSHOT_B)), List.of(signer));
        first.append(genesis);
        EvidenceKeySetTrustPublication recovered = publication(mapper, 2,
                genesis.publicationFingerprint(), 1, PUBLISHED_AT.plusSeconds(1),
                List.of(active(SNAPSHOT_A), revoked(SNAPSHOT_B, PUBLISHED_AT.plusSeconds(1))),
                List.of(signer));
        first.append(recovered);

        DatabaseEvidenceKeySetTrustPublicationRepository restarted = repository();
        EvidenceKeySetTrustPublication resurrection = publication(mapper, 3,
                recovered.publicationFingerprint(), 1, PUBLISHED_AT.plusSeconds(2),
                List.of(active(SNAPSHOT_B), overlap(SNAPSHOT_A)), List.of(signer));

        assertThatThrownBy(() -> restarted.append(resurrection))
                .isInstanceOfSatisfying(EvidenceKeySetTrustChain.ChainViolation.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                EvidenceKeySetTrustChain.Reason.REVOKED_PIN_REACTIVATED));
    }

    @Test
    void persistedMaterialTamperFailsClosedInsteadOfBecomingAHead() {
        DatabaseEvidenceKeySetTrustPublicationRepository repository = repository();
        EvidenceKeySetTrustPublication genesis = publication(mapper, 1, "", 0,
                PUBLISHED_AT, List.of(active(SNAPSHOT_A)), List.of(signer));
        repository.append(genesis);
        jdbc.update("""
                UPDATE evidence_key_set_trust_publications SET publication_json = ?
                WHERE log_id = ? AND publication_sequence = 1
                """, mapper.valueToTree(genesis).deepCopy().toString().replace(SNAPSHOT_A, SNAPSHOT_B),
                LOG_ID);

        assertThatThrownBy(() -> repository.latest(LOG_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint is invalid");
    }

    private DatabaseEvidenceKeySetTrustPublicationRepository repository() {
        DatabaseEvidenceKeySetTrustPublicationRepository repository =
                new DatabaseEvidenceKeySetTrustPublicationRepository(jdbc, mapper, transactionManager);
        repository.init();
        return repository;
    }
}
