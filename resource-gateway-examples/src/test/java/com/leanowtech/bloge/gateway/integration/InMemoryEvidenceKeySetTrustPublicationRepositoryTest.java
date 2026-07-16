package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.leanowtech.bloge.gateway.integration.EvidenceKeySetTrustTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryEvidenceKeySetTrustPublicationRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Authority signer = new Authority("security-a", keyPair());

    @Test
    void appendsContiguousChainPagesAndIdempotentlyReturnsExactSequence() {
        InMemoryEvidenceKeySetTrustPublicationRepository repository =
                new InMemoryEvidenceKeySetTrustPublicationRepository();
        EvidenceKeySetTrustPublication first = publication(mapper, 1, "", 0,
                PUBLISHED_AT, List.of(active(SNAPSHOT_A)), List.of(signer));
        EvidenceKeySetTrustPublication second = publication(mapper, 2,
                first.publicationFingerprint(), 0, PUBLISHED_AT.plusSeconds(1),
                List.of(overlap(SNAPSHOT_A), active(SNAPSHOT_B)), List.of(signer));

        assertThat(repository.append(first)).isSameAs(first);
        assertThat(repository.append(first).publicationFingerprint())
                .isEqualTo(first.publicationFingerprint());
        repository.append(second);

        assertThat(repository.highWaterSequence(LOG_ID)).isEqualTo(2);
        assertThat(repository.latest(LOG_ID)).contains(second);
        assertThat(repository.readAfter(LOG_ID, 0, 1)).containsExactly(first);
        assertThat(repository.readAfter(LOG_ID, 1, 10)).containsExactly(second);
    }

    @Test
    void rejectsSequenceForkGapPreviousMismatchTimeRollbackAndBadRecoveryEpoch() {
        InMemoryEvidenceKeySetTrustPublicationRepository repository =
                new InMemoryEvidenceKeySetTrustPublicationRepository();
        EvidenceKeySetTrustPublication first = publication(mapper, 1, "", 0,
                PUBLISHED_AT, List.of(active(SNAPSHOT_A)), List.of(signer));
        repository.append(first);

        assertReason(repository, publication(mapper, 1, "", 0, PUBLISHED_AT.plusSeconds(1),
                List.of(active(SNAPSHOT_B)), List.of(signer)),
                EvidenceKeySetTrustChain.Reason.SEQUENCE_FORK);
        assertReason(repository, publication(mapper, 3, first.publicationFingerprint(), 0,
                PUBLISHED_AT.plusSeconds(1), List.of(active(SNAPSHOT_B)), List.of(signer)),
                EvidenceKeySetTrustChain.Reason.SEQUENCE_GAP);
        assertReason(repository, publication(mapper, 2, "sha256:" + "f".repeat(64), 0,
                PUBLISHED_AT.plusSeconds(1), List.of(active(SNAPSHOT_B)), List.of(signer)),
                EvidenceKeySetTrustChain.Reason.PREVIOUS_FINGERPRINT_MISMATCH);
        assertReason(repository, publication(mapper, 2, first.publicationFingerprint(), 0,
                PUBLISHED_AT.minusSeconds(1), List.of(active(SNAPSHOT_B)), List.of(signer)),
                EvidenceKeySetTrustChain.Reason.TIME_ROLLBACK);
        assertReason(repository, publication(mapper, 2, first.publicationFingerprint(), 1,
                PUBLISHED_AT.plusSeconds(1), List.of(active(SNAPSHOT_B)), List.of(signer)),
                EvidenceKeySetTrustChain.Reason.RECOVERY_EPOCH_INVALID);
    }

    @Test
    void explicitRevocationAdvancesRecoveryEpochAndCanNeverBeReactivated() {
        InMemoryEvidenceKeySetTrustPublicationRepository repository =
                new InMemoryEvidenceKeySetTrustPublicationRepository();
        EvidenceKeySetTrustPublication first = publication(mapper, 1, "", 0,
                PUBLISHED_AT, List.of(active(SNAPSHOT_A), overlap(SNAPSHOT_B)), List.of(signer));
        repository.append(first);
        EvidenceKeySetTrustPublication recovered = publication(mapper, 2,
                first.publicationFingerprint(), 1, PUBLISHED_AT.plusSeconds(1),
                List.of(active(SNAPSHOT_A), revoked(SNAPSHOT_B, PUBLISHED_AT.plusSeconds(1))),
                List.of(signer));
        repository.append(recovered);

        EvidenceKeySetTrustPublication resurrection = publication(mapper, 3,
                recovered.publicationFingerprint(), 1, PUBLISHED_AT.plusSeconds(2),
                List.of(active(SNAPSHOT_B), overlap(SNAPSHOT_A)), List.of(signer));
        assertReason(repository, resurrection,
                EvidenceKeySetTrustChain.Reason.REVOKED_PIN_REACTIVATED);
    }

    private static void assertReason(
            InMemoryEvidenceKeySetTrustPublicationRepository repository,
            EvidenceKeySetTrustPublication publication, EvidenceKeySetTrustChain.Reason reason) {
        assertThatThrownBy(() -> repository.append(publication))
                .isInstanceOfSatisfying(EvidenceKeySetTrustChain.ChainViolation.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
