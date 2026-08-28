package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visualadapter.DynamicGatewayComposerVisualDslRunner;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Endpoint-level tests for {@link VisualGraphSimulationController} using direct instantiation,
 * matching the existing visual controller test conventions.
 */
class VisualGraphSimulationControllerTest {

    @Test
    void simulateEndpointRunsDesignOnlyDraftThroughMocks() {
        VisualGraphSimulationController controller = controllerFor(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));

        VisualGraphSimulationResponse response = controller.simulate(
                new VisualGraphSimulationRequest(eligibilityDraft(), Map.of(), ""));

        assertThat(response.success()).isTrue();
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        assertThat(response.output()).isEqualTo(Map.of("eligible", false, "ruleId", "string"));
    }

    @Test
    void simulateEndpointRunsDslPrimitiveForReal() {
        VisualGraphSimulationController controller = controllerFor(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));

        VisualGraphSimulationResponse response = controller.simulate(
                new VisualGraphSimulationRequest(eligibilityDraft(), Map.of("score", 720, "amount", 250_000), ""));

        assertThat(response.success()).isTrue();
        assertThat(response.realNodeIds()).containsExactly("eligibility");
        assertThat(response.output()).isEqualTo(Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1"));
    }

    @Test
    void governedFixturePreservesProtocolFidelityAndInjectsResolvedOutput() {
        VisualGraphSimulationService simulation = mock(VisualGraphSimulationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        GovernedFixtureSimulationResolver resolver = mock(GovernedFixtureSimulationResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<GovernedFixtureSimulationResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        IntegrationRequestContext identity = new IntegrationRequestContext(
                "tenant", "org", "project", "test", "sg", "USER", "author", "",
                "CORRECTNESS_FIXTURE_MATERIAL_READ", "corr");
        when(authenticator.authenticate(any(HttpHeaders.class), eq(IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_READ)))
                .thenReturn(identity);
        when(resolver.resolve(any(), any(), eq(identity), any(), eq("eligibility")))
                .thenReturn(new NodeFixture(Map.of("eligible", true)));
        VisualGraphSimulationResponse expected = new VisualGraphSimulationResponse(
                true, true, true, "graph", "eligibility", Map.of("eligible", true),
                Map.of(), Map.of(), 1, Map.of(), List.of("eligibility"), List.of(), true,
                List.of(), List.of(), "");
        when(simulation.simulate(any(), any(), any(), any())).thenReturn(expected);
        VisualGraphSimulationController controller = new VisualGraphSimulationController(
                simulation, authenticator, provider);
        VisualGraphSimulationRequest request = new VisualGraphSimulationRequest(
                eligibilityDraft(), Map.of(), "", Map.of("eligibility",
                        new NodeFixture(null, Map.of("score", 720), new GovernedFixtureRef(
                                "fixture", 1, "sha256:" + "a".repeat(64)),
                                NodeFixture.ResourceFidelity.PROTOCOL_DERIVED)));

        assertThat(controller.simulate(request, new HttpHeaders())).isSameAs(expected);
        ArgumentCaptor<Map<String, NodeFixture>> forwarded =
                ArgumentCaptor.forClass(Map.class);
        verify(simulation).simulate(any(), any(), any(), forwarded.capture());
        NodeFixture forwardedFixture = forwarded.getValue().get("eligibility");
        assertThat(forwardedFixture.output()).isEqualTo(Map.of("eligible", true));
        assertThat(forwardedFixture.expectedInput()).isEqualTo(Map.of("score", 720));
        assertThat(forwardedFixture.governedRef()).isEqualTo(request.fixtures().get("eligibility").governedRef());
        assertThat(forwardedFixture.resourceFidelity())
                .isEqualTo(NodeFixture.ResourceFidelity.PROTOCOL_DERIVED);
        verify(authenticator).authenticate(any(HttpHeaders.class), eq(
                IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_READ));
        verify(resolver).resolve(any(), any(), eq(identity), any(), eq("eligibility"));
        verify(resolver).recordReuse(any(), eq(request.draft()), anyList());

        VisualGraphSimulationResponse failed = new VisualGraphSimulationResponse(
                true, true, false, "graph", "eligibility", null,
                Map.of(), Map.of(), 1, Map.of(), List.of(), List.of(), false,
                List.of(), List.of("simulation failed"), "");
        when(simulation.simulate(any(), any(), any(), any())).thenReturn(failed);
        assertThat(controller.simulate(request, new HttpHeaders())).isSameAs(failed);
        verify(resolver, times(1)).recordReuse(any(), eq(request.draft()), anyList());
    }

    @Test
    void governedFixturePreservesTransportFidelity() {
        VisualGraphSimulationService simulation = mock(VisualGraphSimulationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        GovernedFixtureSimulationResolver resolver = mock(GovernedFixtureSimulationResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<GovernedFixtureSimulationResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        IntegrationRequestContext identity = new IntegrationRequestContext(
                "tenant", "org", "project", "test", "sg", "USER", "author", "",
                "CORRECTNESS_FIXTURE_MATERIAL_READ", "corr");
        when(authenticator.authenticate(any(HttpHeaders.class), eq(IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_READ)))
                .thenReturn(identity);
        when(resolver.resolve(any(), any(), eq(identity), any(), eq("eligibility")))
                .thenReturn(new NodeFixture(Map.of("eligible", true)));
        VisualGraphSimulationResponse expected = new VisualGraphSimulationResponse(
                true, true, true, "graph", "eligibility", Map.of("eligible", true),
                Map.of(), Map.of(), 1, Map.of(), List.of("eligibility"), List.of(), true,
                List.of(), List.of(), "");
        when(simulation.simulate(any(), any(), any(), any())).thenReturn(expected);
        VisualGraphSimulationController controller = new VisualGraphSimulationController(
                simulation, authenticator, provider);
        VisualGraphSimulationRequest request = new VisualGraphSimulationRequest(
                eligibilityDraft(), Map.of(), "", Map.of("eligibility",
                        new NodeFixture(null, Map.of("score", 720), new GovernedFixtureRef(
                                "fixture", 1, "sha256:" + "a".repeat(64)),
                                NodeFixture.ResourceFidelity.TRANSPORT_LEVEL)));

        assertThat(controller.simulate(request, new HttpHeaders())).isSameAs(expected);
        ArgumentCaptor<Map<String, NodeFixture>> forwarded =
                ArgumentCaptor.forClass(Map.class);
        verify(simulation).simulate(any(), any(), any(), forwarded.capture());
        assertThat(forwarded.getValue().get("eligibility").resourceFidelity())
                .isEqualTo(NodeFixture.ResourceFidelity.TRANSPORT_LEVEL);
        assertThat(forwarded.getValue().get("eligibility").output())
                .isEqualTo(Map.of("eligible", true));
    }

    @Test
    void storedOnlyGovernedFixtureIsAuthenticatedResolvedAndRecordedAgainstEffectiveReference() {
        VisualGraphSimulationService simulation = mock(VisualGraphSimulationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        GovernedFixtureSimulationResolver resolver = mock(GovernedFixtureSimulationResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<GovernedFixtureSimulationResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        IntegrationRequestContext identity = new IntegrationRequestContext(
                "tenant", "org", "project", "test", "sg", "USER", "author", "",
                "CORRECTNESS_FIXTURE_MATERIAL_READ", "corr");
        when(authenticator.authenticate(any(HttpHeaders.class), eq(IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_READ)))
                .thenReturn(identity);
        GovernedFixtureRef ref = new GovernedFixtureRef("stored-fixture", 3,
                "sha256:" + "b".repeat(64));
        when(resolver.resolve(any(), eq(ref), eq(identity), any(), eq("eligibility")))
                .thenReturn(new NodeFixture(Map.of("eligible", true, "ruleId", "STORED")));
        VisualGraphSimulationResponse expected = new VisualGraphSimulationResponse(
                true, true, true, "graph", "eligibility", Map.of("eligible", true),
                Map.of(), Map.of(), 1, Map.of(), List.of("eligibility"), List.of(), true,
                List.of(), List.of(), "");
        when(simulation.simulate(any(), any(), any(), any())).thenReturn(expected);
        VisualGraphSimulationController controller = new VisualGraphSimulationController(
                simulation, authenticator, provider);
        GraphDraft draft = eligibilityDraft().withNodeFixtures(Map.of("eligibility",
                new GraphDraft.NodeFixture(null, null,
                        new GraphDraft.GovernedFixtureRef("stored-fixture", 3,
                                "sha256:" + "b".repeat(64)),
                        GraphDraft.NodeFixture.ResourceFidelity.PROTOCOL_DERIVED)));
        VisualGraphSimulationRequest request = new VisualGraphSimulationRequest(draft, Map.of(), "");

        assertThat(controller.simulate(request, new HttpHeaders())).isSameAs(expected);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, NodeFixture>> forwarded =
                ArgumentCaptor.forClass(Map.class);
        verify(simulation).simulate(eq(draft), eq(Map.of()), eq(""), forwarded.capture());
        NodeFixture forwardedFixture = forwarded.getValue().get("eligibility");
        assertThat(forwardedFixture.output()).isEqualTo(Map.of("eligible", true, "ruleId", "STORED"));
        assertThat(forwardedFixture.governedRef()).isEqualTo(ref);
        assertThat(forwardedFixture.resourceFidelity()).isEqualTo(NodeFixture.ResourceFidelity.PROTOCOL_DERIVED);
        verify(authenticator).authenticate(any(HttpHeaders.class), eq(
                IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_READ));
        verify(resolver).resolve(any(), eq(ref), eq(identity), eq(draft), eq("eligibility"));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<GovernedFixtureRef>> refs =
                ArgumentCaptor.forClass(List.class);
        verify(resolver).recordReuse(any(), eq(draft), refs.capture());
        assertThat(refs.getValue()).containsExactly(ref);
        assertThat(request.draft().nodeFixtures().get("eligibility").output()).isNull();
    }

    @Test
    void requestFixtureOverridesStoredGovernedFixtureBeforeAuthentication() {
        VisualGraphSimulationService simulation = mock(VisualGraphSimulationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        GovernedFixtureSimulationResolver resolver = mock(GovernedFixtureSimulationResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<GovernedFixtureSimulationResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        VisualGraphSimulationResponse expected = new VisualGraphSimulationResponse(
                true, true, true, "graph", "eligibility", Map.of("eligible", false),
                Map.of(), Map.of(), 1, Map.of(), List.of("eligibility"), List.of(), true,
                List.of(), List.of(), "");
        when(simulation.simulate(any(), any(), any(), any())).thenReturn(expected);
        VisualGraphSimulationController controller = new VisualGraphSimulationController(
                simulation, authenticator, provider);
        GraphDraft draft = eligibilityDraft().withNodeFixtures(Map.of("eligibility",
                new GraphDraft.NodeFixture(null, null,
                        new GraphDraft.GovernedFixtureRef("stored-fixture", 3,
                                "sha256:" + "b".repeat(64)), null)));
        NodeFixture requestFixture = new NodeFixture(Map.of("eligible", false, "ruleId", "REQUEST"));
        VisualGraphSimulationRequest request = new VisualGraphSimulationRequest(
                draft, Map.of(), "", Map.of("eligibility", requestFixture));

        assertThat(controller.simulate(request, new HttpHeaders())).isSameAs(expected);
        verifyNoInteractions(authenticator, resolver);
        verify(simulation).simulate(eq(draft), eq(Map.of()), eq(""), eq(request.fixtures()));
    }

    @Test
    void governedFixtureWithoutAuthenticationFailsBeforeResolution() {
        VisualGraphSimulationService simulation = mock(VisualGraphSimulationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        ObjectProvider<GovernedFixtureSimulationResolver> provider = mock(ObjectProvider.class);
        GovernedFixtureSimulationResolver resolver = mock(GovernedFixtureSimulationResolver.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        when(authenticator.authenticate(any(HttpHeaders.class), eq(
                IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_READ))).thenThrow(
                new IntegrationProblemException(IntegrationProblem.unauthorized(
                        "AUTH_REQUIRED", "Authentication required", "corr", Map.of())));
        VisualGraphSimulationController controller = new VisualGraphSimulationController(
                simulation, authenticator, provider);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.simulate(
                new VisualGraphSimulationRequest(eligibilityDraft(), Map.of(), "",
                        Map.of("eligibility", new NodeFixture(null, null,
                                new GovernedFixtureRef("fixture", 1, "sha256:" + "a".repeat(64))))),
                new HttpHeaders())).isInstanceOf(IntegrationProblemException.class);
        verifyNoInteractions(resolver, simulation);
    }

    @Test
    void legacyFixtureDoesNotRequireOrAttemptAuthentication() {
        VisualGraphSimulationService simulation = mock(VisualGraphSimulationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        ObjectProvider<GovernedFixtureSimulationResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(GovernedFixtureSimulationResolver.class));
        VisualGraphSimulationResponse expected = new VisualGraphSimulationResponse(
                true, true, true, "graph", "eligibility", Map.of(), Map.of(), Map.of(), 1,
                Map.of(), List.of(), List.of("eligibility"), true, List.of(), List.of(), "");
        when(simulation.simulate(any(), any(), any(), any())).thenReturn(expected);
        VisualGraphSimulationController controller = new VisualGraphSimulationController(
                simulation, authenticator, provider);

        assertThat(controller.simulate(new VisualGraphSimulationRequest(eligibilityDraft(), Map.of(), "",
                Map.of("eligibility", new NodeFixture(Map.of("eligible", true)))))).isSameAs(expected);
        verifyNoInteractions(authenticator);
    }

    private static VisualGraphSimulationController controllerFor(
            com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary library) {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        VisualGraphSimulationService service = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog),
                catalog,
                new JsonSchemaSampleGenerator(),
                new DynamicGatewayComposerVisualDslRunner(new DefaultOperatorRegistry()));
        return new VisualGraphSimulationController(service);
    }

    private static GraphDraft eligibilityDraft() {
        return new GraphDraft(
                "", "", 0, "eligibilityPolicy", "", "", "", "", null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")),
                        Map.of(),
                        null)),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""));
    }
}
