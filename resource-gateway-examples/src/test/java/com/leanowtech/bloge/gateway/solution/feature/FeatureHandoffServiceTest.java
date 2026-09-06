package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies the scoped design-to-engineering lifecycle for platform-evaluated Features. */
class FeatureHandoffServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);

    @Test
    void opensACompleteTicketForOneDesignOnlyFeature() {
        register("project-a", "", "Who is responsible for the cancellation fee?");
        FeatureHandoffService service = service((feature, inputs, identity) -> mapper.valueToTree("none"));

        Map<String, Object> ticket = service.submit("responsibility.party", author("project-a"));

        assertThat(ticket).containsEntry("featureName", "responsibility.party")
                .containsEntry("businessSemantics", "Who is responsible for the cancellation fee?")
                .containsEntry("evaluationKind", "API")
                .containsEntry("status", "OPEN")
                .containsEntry("acceptanceRef", "feature-acceptance:responsibility.party")
                .containsEntry("revision", 1L);
        assertThat(ticket.get("ticketId").toString()).startsWith("feature-handoff:");
        assertThat(ticket).containsKeys("requiredOutput", "requiredInputs");
    }

    @Test
    void rejectsBoundAndCrossProjectFeaturesWithoutLeakingReferences() {
        register("project-a", "resource:party-v1", "Responsibility party");
        FeatureHandoffService service = service((feature, inputs, identity) -> mapper.valueToTree("none"));

        assertThatThrownBy(() -> service.submit("responsibility.party", author("project-a")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("GATE_REJECTED");
        assertThatThrownBy(() -> service.submit("responsibility.party", author("project-b")))
                .isInstanceOf(AgentTddToolException.class)
                .hasMessage("A Feature is unavailable.")
                .hasMessageNotContaining("responsibility.party")
                .hasMessageNotContaining("project-a");
    }

    @Test
    void onlyFeatureEngineeringCanFulfilAndVerificationMakesTheFeatureReady() {
        register("project-a", "", "Responsibility party");
        FeatureHandoffService service = service((feature, inputs, identity) -> mapper.valueToTree("driver"));
        service.submit("responsibility.party", author("project-a"));

        assertThatThrownBy(() -> service.fulfil("responsibility.party", "resource:party-v2",
                mapper.valueToTree(Map.of("orderId", "O-1")), author("project-a")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("FORBIDDEN_PURPOSE");

        Map<String, Object> result = service.fulfil("responsibility.party", "resource:party-v2",
                mapper.valueToTree(Map.of("orderId", "O-1")), engineer("project-a"));

        assertThat(result).containsEntry("featureName", "responsibility.party")
                .containsEntry("status", "VERIFIED")
                .containsEntry("state", "READY")
                .containsEntry("verified", true);
        assertThat(registry.requireFeature(scope("project-a"), "responsibility.party").speccing()).isFalse();
    }

    @Test
    void rejectsWorkloadFulfillmentEvenWhenItClaimsTheEngineeringPurpose() {
        register("project-a", "", "Responsibility party");
        FeatureHandoffService service = service(
                (feature, inputs, identity) -> mapper.valueToTree("driver"));
        service.submit("responsibility.party", author("project-a"));

        assertThatThrownBy(() -> service.fulfil("responsibility.party", "resource:party-v2",
                mapper.valueToTree(Map.of("orderId", "O-1")),
                identity("project-a", "WORKLOAD", "AGENT_TDD_FEATURE_ENG")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("GATE_REJECTED");
    }

    @Test
    void failedOutputVerificationKeepsImplementedTicketForRepair() {
        register("project-a", "", "Responsibility party");
        FeatureHandoffService service = service((feature, inputs, identity) -> mapper.valueToTree(42));
        service.submit("responsibility.party", author("project-a"));

        assertThatThrownBy(() -> service.fulfil("responsibility.party", "resource:party-v2",
                mapper.valueToTree(Map.of("orderId", "O-1")), engineer("project-a")))
                .isInstanceOf(AgentTddToolException.class)
                .extracting(failure -> ((AgentTddToolException) failure).code())
                .isEqualTo("FEATURE_OUTPUT_INVALID");

        AgentTddStoredAsset ticket = states.find(scope("project-a"),
                FeatureHandoffService.FEATURE_HANDOFF, "responsibility.party").orElseThrow();
        assertThat(ticket.data().path("status").asText()).isEqualTo("IMPLEMENTED");
        assertThat(ticket.data().path("evaluationRef").asText()).isEqualTo("resource:party-v2");
        assertThat(ticket.data().path("verificationMode").asText()).isEqualTo("LEGACY_SINGLE_FIXTURE");
        assertThat(registry.requireFeature(scope("project-a"), "responsibility.party").speccing()).isTrue();
    }

    @Test
    void defaultRolloutRejectsLegacyFixtureWithoutCallingTheBackend() {
        register("project-a", "", "Responsibility party");
        AtomicInteger calls = new AtomicInteger();
        FeatureControlledSuiteProperties properties = new FeatureControlledSuiteProperties();
        FeatureHandoffService service = new FeatureHandoffService(states, registry,
                (feature, inputs, identity) -> {
                    calls.incrementAndGet();
                    return mapper.valueToTree("driver");
                }, mapper, mock(FeatureControlledSuiteService.class), properties);
        service.submit("responsibility.party", author("project-a"));

        assertThatThrownBy(() -> service.fulfil("responsibility.party", "resource:party-v2",
                mapper.valueToTree(Map.of("orderId", "O-1")), engineer("project-a")))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FEATURE_SUITE_REQUIRED"));
        assertThat(calls).hasValue(0);
        assertThat(registry.requireFeature(scope("project-a"), "responsibility.party").speccing()).isTrue();
    }

    @Test
    void suiteEvidenceFailureNeverBindsTheFeature() {
        register("project-a", "", "Responsibility party");
        FeatureControlledSuiteService suites = mock(FeatureControlledSuiteService.class);
        FeatureHandoffService service = suiteService(suites);
        service.submit("responsibility.party", author("project-a"));
        when(suites.requireCurrentEvidence("responsibility.party", "resource:party-v2",
                "sha256:" + "a".repeat(64), engineer("project-a")))
                .thenThrow(new AgentTddToolException(
                        "FEATURE_SUITE_EVIDENCE_STALE", "Feature suite evidence is not current."));

        assertThatThrownBy(() -> service.fulfil("responsibility.party", "resource:party-v2",
                "sha256:" + "a".repeat(64), null, engineer("project-a")))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FEATURE_SUITE_EVIDENCE_STALE"));
        assertThat(registry.requireFeature(scope("project-a"), "responsibility.party").speccing()).isTrue();
        assertThat(states.find(scope("project-a"), FeatureHandoffService.FEATURE_HANDOFF,
                "responsibility.party").orElseThrow().data().path("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void currentSuiteEvidenceIsLockedAndBecomesTheVerifiedBindingBasis() {
        register("project-a", "", "Responsibility party");
        String evidenceRef = "sha256:" + "a".repeat(64);
        FeatureControlledSuiteService suites = mock(FeatureControlledSuiteService.class);
        FeatureHandoffService service = suiteService(suites);
        service.submit("responsibility.party", author("project-a"));
        states.save(scope("project-a"), FeatureControlledSuiteService.FEATURE_CONTROLLED_SUITE,
                "responsibility.party", mapper.createObjectNode().put("status", "PASSED"));
        FeatureControlledSuiteEvidence evidence = new FeatureControlledSuiteEvidence(
                "responsibility.party", 1, "PASSED", evidenceRef,
                "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64),
                2, 2, 0, 0, new FeatureControlledSuiteEvidence.Coverage(2, 2, 100, 100));
        when(suites.requireCurrentEvidence("responsibility.party", "resource:party-v2",
                evidenceRef, engineer("project-a"))).thenReturn(evidence);

        Map<String, Object> result = service.fulfil("responsibility.party", "resource:party-v2",
                evidenceRef, null, engineer("project-a"));

        assertThat(result).containsEntry("status", "VERIFIED")
                .containsEntry("verificationMode", "CONTROLLED_SUITE")
                .containsEntry("suiteEvidenceRef", evidenceRef);
        assertThat(registry.requireFeature(scope("project-a"), "responsibility.party").speccing()).isFalse();
    }

    private FeatureHandoffService service(com.leanowtech.bloge.gateway.solution.FeatureEvaluationBackend backend) {
        FeatureControlledSuiteProperties properties = new FeatureControlledSuiteProperties();
        properties.setLegacySingleFixtureEnabled(true);
        return new FeatureHandoffService(
                states, registry, backend, mapper, mock(FeatureControlledSuiteService.class), properties);
    }

    private FeatureHandoffService suiteService(FeatureControlledSuiteService suites) {
        return new FeatureHandoffService(states, registry,
                (feature, inputs, identity) -> mapper.valueToTree("driver"), mapper,
                suites, new FeatureControlledSuiteProperties());
    }

    private void register(String project, String evaluationRef, String semantics) {
        registry.upsertFeature(scope(project), new FeatureContract(
                "responsibility.party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), evaluationRef, "", "", semantics));
    }

    private static IntegrationRequestContext author(String project) {
        return identity(project, "WORKLOAD", "AGENT_TDD_AUTHORING");
    }

    private static IntegrationRequestContext engineer(String project) {
        return identity(project, "USER", "AGENT_TDD_FEATURE_ENG");
    }

    private static IntegrationRequestContext identity(String project, String actorType, String purpose) {
        return new IntegrationRequestContext("tenant-a", "org-a", project, "test", "sg",
                actorType, actorType.toLowerCase() + "-1", "", purpose, "corr-1");
    }

    private static String scope(String project) {
        return AgentTddMutationService.scopeKey(author(project));
    }
}
