package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioImportControllerTest {

    @Test
    void authenticatesMaterializationAsATestSuiteWrite() {
        ScenarioImportMaterializationService service = mock(ScenarioImportMaterializationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "author", "", "TEST_SUITE_WRITE", "corr-1");
        HttpHeaders headers = new HttpHeaders();
        ScenarioImportMaterializationRequest request = new ScenarioImportMaterializationRequest(
                ScenarioImportMaterializationRequest.SCHEMA_VERSION,
                "id,name\nA,Case A",
                new ObjectMapper().createObjectNode(),
                null,
                "");
        ScenarioImportMaterializationResult expected = new ScenarioImportMaterializationResult(
                "", null, new ObjectMapper().createObjectNode());
        when(authenticator.authenticate(headers, IntegrationOperation.TEST_SUITE_WRITE))
                .thenReturn(identity);
        when(service.materialize(request, identity)).thenReturn(expected);
        ScenarioImportController controller = new ScenarioImportController(service, authenticator);

        assertThat(controller.materialize(request, headers)).isSameAs(expected);
        verify(authenticator).authenticate(headers, IntegrationOperation.TEST_SUITE_WRITE);
        verify(service).materialize(request, identity);
    }
}
