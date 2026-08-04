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

class ScenarioDraftSetControllerScaleTest {

    @Test
    void usesReadForPagedQueriesAndWriteForAtomicEdits() {
        ScenarioDraftSetAuthoringService service = mock(ScenarioDraftSetAuthoringService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "author", "", "TEST_SUITE_WRITE", "corr-1");
        HttpHeaders headers = new HttpHeaders();
        ScenarioTablePageQuery query = mock(ScenarioTablePageQuery.class);
        ScenarioTablePage page = mock(ScenarioTablePage.class);
        ScenarioBulkEditCommand command = mock(ScenarioBulkEditCommand.class);
        ScenarioBulkEditResult result = mock(ScenarioBulkEditResult.class);
        when(authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_WRITE))
                .thenReturn(identity);
        when(service.queryPage("suite-a", query, identity)).thenReturn(page);
        when(service.bulkEdit("suite-a", command, identity)).thenReturn(result);
        ScenarioDraftSetController controller = new ScenarioDraftSetController(service, authenticator);

        assertThat(controller.queryMatrix("suite-a", query, headers)).isSameAs(page);
        assertThat(controller.bulkEditMatrix("suite-a", command, headers)).isSameAs(result);

        verify(authenticator).authenticate(headers, IntegrationOperation.TEST_SUITE_READ);
        verify(authenticator).authenticate(headers, IntegrationOperation.TEST_SUITE_WRITE);
    }
}
