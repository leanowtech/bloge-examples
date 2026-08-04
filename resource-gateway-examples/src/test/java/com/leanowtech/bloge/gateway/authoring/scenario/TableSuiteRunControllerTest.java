package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TableSuiteRunControllerTest {

    @Test
    void authenticatesEveryMutationAndReadWithItsNarrowPurpose() {
        TableSuiteRunService service = mock(TableSuiteRunService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "author", "", "TEST_EXECUTION", "corr-1");
        HttpHeaders headers = new HttpHeaders();
        TableSuiteRunCommand command = mock(TableSuiteRunCommand.class);
        TableSuiteRunBatch batch = mock(TableSuiteRunBatch.class);
        TableSuiteRunBatch.Delta delta = mock(TableSuiteRunBatch.Delta.class);
        when(authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_EXECUTION))
                .thenReturn(identity);
        when(authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_STABILITY_JOB_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_STABILITY_JOB_CANCEL))
                .thenReturn(identity);
        when(service.submit(command, identity)).thenReturn(batch);
        when(service.find("batch-a", identity)).thenReturn(batch);
        when(service.delta("batch-a", 7, identity)).thenReturn(delta);
        when(service.cancel("batch-a", identity)).thenReturn(batch);
        when(service.retryFailed("batch-a", identity)).thenReturn(batch);
        TableSuiteRunController controller = new TableSuiteRunController(service, authenticator);

        assertThat(controller.submit(command, headers)).isSameAs(batch);
        assertThat(controller.find("batch-a", headers)).isSameAs(batch);
        assertThat(controller.events("batch-a", 7, headers)).isSameAs(delta);
        assertThat(controller.cancel("batch-a", headers)).isSameAs(batch);
        assertThat(controller.retryFailed("batch-a", headers)).isSameAs(batch);

        verify(authenticator, org.mockito.Mockito.times(2))
                .authenticate(headers, IntegrationOperation.TEST_SUITE_EXECUTION);
        verify(authenticator, org.mockito.Mockito.times(2))
                .authenticate(headers, IntegrationOperation.TEST_SUITE_STABILITY_JOB_READ);
        verify(authenticator)
                .authenticate(headers, IntegrationOperation.TEST_SUITE_STABILITY_JOB_CANCEL);
    }
}
