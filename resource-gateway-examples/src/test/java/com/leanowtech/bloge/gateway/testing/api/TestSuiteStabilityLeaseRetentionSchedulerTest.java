package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityLeaseRetentionSchedulerTest {

    @Test
    void sweepUsesOneBoundedBatch() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        TestSuiteStabilityLeaseRetentionScheduler scheduler =
                new TestSuiteStabilityLeaseRetentionScheduler(repository, 20_000);

        scheduler.purgeExpired();

        verify(repository).purgeExpiredLeases(10_000);
    }

    @Test
    void storeFailureIsContainedForTheNextScheduledRetry() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        when(repository.purgeExpiredLeases(100)).thenThrow(new IllegalStateException("offline"));
        TestSuiteStabilityLeaseRetentionScheduler scheduler =
                new TestSuiteStabilityLeaseRetentionScheduler(repository, 100);

        assertThatCode(scheduler::purgeExpired).doesNotThrowAnyException();
        verify(repository).purgeExpiredLeases(100);
    }
}
