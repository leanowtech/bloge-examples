package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the accountable non-MCP boundary for WRITE Instruction implementation. */
class EngineeringHandoffControllerTest {
    @Test
    void authenticatesWithInstructionEngineeringPurposeBeforeBinding() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
        IntegrationRequestContext author = identity("WORKLOAD", "AGENT_TDD_AUTHORING");
        IntegrationRequestContext engineer = identity("USER", "AGENT_TDD_INSTRUCTION_ENG");
        String scope = AgentTddMutationService.scopeKey(author);
        registry.upsertInstruction(scope, new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "object"),
                        "reasoning", "required")), InstructionContract.Effect.WRITE, "",
                new InstructionContract.WriteGovernance(
                        "refund-service", "orderId", "recon:refund-v1"), "全额免除"));
        registry.upsertScenario(scope, new ScenarioContract(
                "scn:root", List.of("approved"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("approved", Map.of("eq", true))),
                        new ScenarioContract.Outlet(
                                ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:refund", Map.of(), ""))),
                new ScenarioContract.Outlet(
                        ScenarioContract.OutletKind.INSTRUCTION, "ins:refund", Map.of(), "")));
        registry.upsertSolution(scope, new SolutionContract(
                "sol:cancel", "处理取消费纠纷", Map.of("approved", "feature:approved"), "scn:root",
                List.of("ins:refund"), "caseSet:cancel"), true);
        EngineeringHandoffService service = new EngineeringHandoffService(states, registry, mapper);
        service.submit("sol:cancel", author);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.AGENT_TDD_INSTRUCTION_ENG))
                .thenReturn(engineer);
        EngineeringHandoffController controller = new EngineeringHandoffController(
                authenticator, service);

        Map<String, Object> response = controller.fulfil(
                "sol:cancel", "ins:refund",
                new EngineeringHandoffController.FulfilRequest("operator:refund-v1"), headers);

        assertThat(response).containsEntry("status", "IMPLEMENTED");
        assertThat(registry.requireInstruction(scope, "ins:refund").bindingRef())
                .isEqualTo("operator:refund-v1");
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_INSTRUCTION_ENG);
    }

    private static IntegrationRequestContext identity(String actorType, String purpose) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                actorType, actorType.toLowerCase() + "-1", "", purpose, "corr-1");
    }
}
