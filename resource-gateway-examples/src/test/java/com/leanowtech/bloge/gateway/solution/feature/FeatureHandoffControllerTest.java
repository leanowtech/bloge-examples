package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that Feature fulfillment is a separately authenticated non-MCP boundary. */
class FeatureHandoffControllerTest {
    @Test
    void authenticatesWithFeatureEngineeringPurposeBeforeFulfilment() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
        IntegrationRequestContext author = identity("WORKLOAD", "AGENT_TDD_AUTHORING");
        IntegrationRequestContext engineer = identity("USER", "AGENT_TDD_FEATURE_ENG");
        registry.upsertFeature(AgentTddMutationService.scopeKey(author), new FeatureContract(
                "cancel.withinFree", mapper.valueToTree(Map.of("type", "boolean")),
                FeatureContract.EvaluationKind.DAG, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "", "", "",
                "Whether cancellation is within the free window."));
        FeatureHandoffService service = new FeatureHandoffService(states, registry,
                (feature, inputs, identity) -> mapper.valueToTree(true), mapper);
        service.submit("cancel.withinFree", author);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.AGENT_TDD_FEATURE_ENG))
                .thenReturn(engineer);
        FeatureHandoffController controller = new FeatureHandoffController(authenticator, service);

        Map<String, Object> response = controller.fulfil("cancel.withinFree",
                new FeatureHandoffController.FulfilRequest(
                        "graph:cancel-window-v1", mapper.valueToTree(Map.of("orderId", "O-1"))),
                headers);

        assertThat(response).containsEntry("status", "VERIFIED")
                .containsEntry("state", "READY");
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_FEATURE_ENG);
    }

    private static IntegrationRequestContext identity(String actorType, String purpose) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                actorType, actorType.toLowerCase() + "-1", "", purpose, "corr-1");
    }
}
