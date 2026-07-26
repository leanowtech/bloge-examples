package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeSelectedPopulationUploadCleanupSchedulerTest {

    @Test
    void runsOneBoundedCleanupPage() {
        AuthoritativeOutcomeSelectedPopulationUploadService service =
                mock(AuthoritativeOutcomeSelectedPopulationUploadService.class);
        when(service.cleanup(73)).thenReturn(4);
        AuthoritativeOutcomeSelectedPopulationUploadCleanupScheduler
                scheduler =
                new
                        AuthoritativeOutcomeSelectedPopulationUploadCleanupScheduler(
                        service, 73);

        scheduler.sweep();

        verify(service).cleanup(73);
    }

    @Test
    void leavesFailedCleanupForTheNextScheduledTurn() {
        AuthoritativeOutcomeSelectedPopulationUploadService service =
                mock(AuthoritativeOutcomeSelectedPopulationUploadService.class);
        when(service.cleanup(100))
                .thenThrow(new IllegalStateException("database unavailable"));
        AuthoritativeOutcomeSelectedPopulationUploadCleanupScheduler
                scheduler =
                new
                        AuthoritativeOutcomeSelectedPopulationUploadCleanupScheduler(
                        service, 100);

        assertThatCode(scheduler::sweep)
                .doesNotThrowAnyException();
        verify(service).cleanup(100);
    }
}
