package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchFinalizationStatusTest {
    private static final Instant NOW =
            Instant.parse("2026-07-25T03:00:00Z");
    private static final String JOB_ID =
            "scenario-batch-" + "a".repeat(64);

    @Test
    void projectionOmitsWorkerAndSignerInternals() {
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                snapshot =
                new ScenarioRehearsalBatchRepository
                        .FinalizationSnapshot(
                        ScenarioRehearsalBatchRepository
                                .FinalizationState.SIGNING,
                        JOB_ID,
                        "sha256:" + "b".repeat(64),
                        2,
                        NOW,
                        "kms-worker-private",
                        7,
                        NOW.plusSeconds(30),
                        NOW.minusSeconds(10),
                        "",
                        "",
                        NOW.minusSeconds(20),
                        NOW,
                        null);

        ScenarioRehearsalBatchFinalizationStatus status =
                ScenarioRehearsalBatchFinalizationStatus.from(
                        snapshot);
        var json = new ObjectMapper()
                .findAndRegisterModules()
                .valueToTree(status);

        assertThat(status.state()).isEqualTo(
                ScenarioRehearsalBatchFinalizationStatus.State
                        .SIGNING);
        assertThat(json.has("leaseOwner")).isFalse();
        assertThat(json.has("leaseEpoch")).isFalse();
        assertThat(json.has("intentFingerprint")).isFalse();
        assertThat(json.toString()).doesNotContain(
                "kms-worker-private");
    }

    @Test
    void rejectsLeaseOrEvidenceCoordinatesInTheWrongState() {
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationStatus(
                        "",
                        JOB_ID,
                        ScenarioRehearsalBatchFinalizationStatus
                                .State.RETRY_WAIT,
                        1,
                        NOW.plusSeconds(5),
                        NOW.plusSeconds(30),
                        NOW,
                        "RG.MIRROR.KMS.UNAVAILABLE",
                        "",
                        NOW.minusSeconds(10),
                        NOW,
                        null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationStatus(
                        "",
                        JOB_ID,
                        ScenarioRehearsalBatchFinalizationStatus
                                .State.FINALIZED,
                        1,
                        NOW,
                        Instant.EPOCH,
                        NOW,
                        "",
                        "",
                        NOW.minusSeconds(10),
                        NOW,
                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
