package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeContinuousAssessmentSchedulerTest {
    @Test
    void pollsBoundedWorkerLanesAndStopsCleanly()
            throws Exception {
        AuthoritativeOutcomeContinuousAssessmentWorker worker =
                mock(AuthoritativeOutcomeContinuousAssessmentWorker
                        .class);
        when(worker.runOne(
                anyString(),
                anyString(),
                anyString()))
                .thenReturn(
                        AuthoritativeOutcomeContinuousAssessmentRepository
                                .Claim.noWork(
                                        DomainFidelityTestFixtures
                                                .NOW));
        AuthoritativeOutcomeContinuousAssessmentScheduler
                scheduler =
                new AuthoritativeOutcomeContinuousAssessmentScheduler(
                        worker,
                        "sg",
                        "staging",
                        "instance-1",
                        2,
                        Duration.ZERO,
                        Duration.ofMillis(100),
                        Duration.ofSeconds(1));

        try {
            assertThat(scheduler.ready()).isTrue();
            Thread.sleep(250);
            verify(worker, atLeastOnce()).runOne(
                    "sg",
                    "staging",
                    "instance-1/continuous-assessment-lane-1");
        } finally {
            scheduler.close();
        }
        assertThat(scheduler.ready()).isFalse();
        assertThat(scheduler.activePolls()).isZero();
    }

    @Test
    void rejectsProductionPartitionAndUnboundedControls() {
        AuthoritativeOutcomeContinuousAssessmentWorker worker =
                mock(AuthoritativeOutcomeContinuousAssessmentWorker
                        .class);

        assertThatThrownBy(() ->
                new AuthoritativeOutcomeContinuousAssessmentScheduler(
                        worker,
                        "sg",
                        "production",
                        "instance-1",
                        1,
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)))
                .isInstanceOf(
                        IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AuthoritativeOutcomeContinuousAssessmentScheduler(
                        worker,
                        "sg",
                        "staging",
                        "instance-1",
                        65,
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }
}
