package com.leanowtech.bloge.gateway.visual.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.testing.correctness.demo.LoanDecisionReferenceCandidateContributor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCorrectnessDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceGatewayReferenceCandidateProviderTest {

    @Test
    void projectsGraphOperatorAndFunctionMetadataAndReResolvesDrift() {
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        VisualOperatorCatalog operators = mock(VisualOperatorCatalog.class);
        when(drafts.all()).thenReturn(List.of(graph()));
        when(operators.list(any(OperatorCatalogQuery.class))).thenReturn(List.of(operator()));
        when(operators.builtInFunctions(any(OperatorCatalogQuery.class))).thenReturn(List.of(function()));
        ResourceGatewayReferenceCandidateProvider provider = new ResourceGatewayReferenceCandidateProvider(
                drafts, operators, new ObjectMapper().findAndRegisterModules());

        ReferenceCandidateProvider.ProviderSnapshot snapshot = provider.snapshot(
                new SearchRequest("", "", scope()));

        assertThat(snapshot.candidates()).extracting(ReferenceCandidate::kind)
                .containsExactly("GRAPH", "OPERATOR", "FUNCTION");
        assertThat(snapshot.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.scope()).isEqualTo(scope());
            assertThat(candidate.fingerprint()).startsWith("sha256:");
            assertThat(candidate.description()).doesNotContain("payload");
        });
        ReferenceCandidate graph = snapshot.candidates().getFirst();
        assertThat(provider.resolve(ResolveRequest.from(graph, scope(), "CORRECTNESS")))
                .extracting(ReferenceCandidateProvider.ProviderResolution::status)
                .isEqualTo(ResolveResult.Status.RESOLVED);
        assertThat(provider.resolve(new ResolveRequest(
                graph.kind(), graph.id(), graph.revision(), "sha256:old", scope(), "CORRECTNESS")))
                .extracting(ReferenceCandidateProvider.ProviderResolution::status,
                        ReferenceCandidateProvider.ProviderResolution::candidate)
                .containsExactly(ResolveResult.Status.DRIFTED, graph);
    }

    @Test
    void aCorrectnessTargetDiscoveredFromDefinitionAuthorityCanBeReResolved() {
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        VisualOperatorCatalog operators = mock(VisualOperatorCatalog.class);
        CorrectnessDefinitionRepository definitions = mock(CorrectnessDefinitionRepository.class);
        when(drafts.all()).thenReturn(List.of());
        when(operators.list(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        when(operators.builtInFunctions(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        when(definitions.supportsHeadListing()).thenReturn(true);
        when(definitions.listHeads(enterpriseScope(), 100)).thenReturn(List.of(storedDefinition()));
        ResourceGatewayReferenceCandidateProvider provider = new ResourceGatewayReferenceCandidateProvider(
                drafts, operators, new ObjectMapper().findAndRegisterModules(), definitions);

        ReferenceCandidate target = provider.snapshot(new SearchRequest("GRAPH", "loan", scope()))
                .candidates().getFirst();
        ReferenceCandidateProvider.ProviderResolution resolution = provider.resolve(
                ResolveRequest.from(target, scope(), "CORRECTNESS"));

        assertThat(target.id()).isEqualTo("loan-decision");
        assertThat(target.authority()).isEqualTo("resource-gateway://correctness-targets");
        assertThat(resolution.status()).isEqualTo(ResolveResult.Status.RESOLVED);
        assertThat(resolution.candidate()).isEqualTo(target);
    }

    @Test
    void mergesExternalMetadataAndResolvesItsExactCoordinate() {
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        VisualOperatorCatalog operators = mock(VisualOperatorCatalog.class);
        when(drafts.all()).thenReturn(List.of());
        when(operators.list(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        when(operators.builtInFunctions(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        ReferenceCandidate external = externalCandidate("BUSINESS_DOMAIN", "credit-decision", scope());
        ResourceGatewayReferenceCandidateProvider provider = new ResourceGatewayReferenceCandidateProvider(
                drafts, operators, new ObjectMapper().findAndRegisterModules(), null,
                List.of(new FixedContributor("business-catalog", external)));

        ReferenceCandidate resolved = provider.snapshot(new SearchRequest("", "", scope()))
                .candidates().getFirst();

        assertThat(resolved).isEqualTo(external);
        assertThat(provider.resolve(ResolveRequest.from(resolved, scope(), "CORRECTNESS")))
                .extracting(ReferenceCandidateProvider.ProviderResolution::status,
                        ReferenceCandidateProvider.ProviderResolution::candidate)
                .containsExactly(ResolveResult.Status.RESOLVED, external);
    }

    @Test
    void coreAuthorityWinsWhenContributorEmitsTheSameExactCoordinate() {
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        VisualOperatorCatalog operators = mock(VisualOperatorCatalog.class);
        when(drafts.all()).thenReturn(List.of());
        when(operators.list(any(OperatorCatalogQuery.class))).thenReturn(List.of(operator()));
        when(operators.builtInFunctions(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        OperatorDefinition core = operator();
        ReferenceCandidate duplicate = new ReferenceCandidate(
                ReferenceCandidate.SCHEMA_VERSION, "OPERATOR", core.operatorRef(), "External override",
                "Must never replace core metadata.", 1, core.fingerprint(),
                "resource-gateway://external", scope(), ReferenceCandidate.Lifecycle.ACTIVE,
                null, List.of("external"), ReferenceCandidate.Compatibility.COMPATIBLE, "");
        ResourceGatewayReferenceCandidateProvider provider = new ResourceGatewayReferenceCandidateProvider(
                drafts, operators, new ObjectMapper().findAndRegisterModules(), null,
                List.of(new FixedContributor("external", duplicate)));

        List<ReferenceCandidate> candidates = provider.snapshot(new SearchRequest("OPERATOR", "", scope()))
                .candidates();

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().authority())
                .isEqualTo("resource-gateway://operator-libraries/risk-library");
        assertThat(candidates.getFirst().displayName()).isEqualTo("Risk lookup");
    }

    @Test
    void contributorOrderDoesNotChangeGeneration() {
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        VisualOperatorCatalog operators = mock(VisualOperatorCatalog.class);
        when(drafts.all()).thenReturn(List.of());
        when(operators.list(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        when(operators.builtInFunctions(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        ReferenceCandidate first = externalCandidate("OWNER", "credit-service-design", scope());
        ReferenceCandidate second = externalCandidate("SOLUTION", "loan-fallback", scope());

        ResourceGatewayReferenceCandidateProvider left = new ResourceGatewayReferenceCandidateProvider(
                drafts, operators, new ObjectMapper().findAndRegisterModules(), null,
                List.of(new FixedContributor("zulu", second), new FixedContributor("alpha", first)));
        ResourceGatewayReferenceCandidateProvider right = new ResourceGatewayReferenceCandidateProvider(
                drafts, operators, new ObjectMapper().findAndRegisterModules(), null,
                List.of(new FixedContributor("alpha", first), new FixedContributor("zulu", second)));

        assertThat(left.snapshot(new SearchRequest("", "", scope())).generation())
                .isEqualTo(right.snapshot(new SearchRequest("", "", scope())).generation());
    }

    @Test
    void loanDecisionDemoContributorProvidesCompleteMetadataOnlyBusinessCatalog() {
        List<ReferenceCandidate> candidates = new LoanDecisionReferenceCandidateContributor()
                .contribute(scope());

        assertThat(candidates).extracting(ReferenceCandidate::kind)
                .containsExactlyInAnyOrder(
                        "BUSINESS_DOMAIN", "PROBLEM_TAXONOMY", "OWNER", "PACKAGE_CONTRACT",
                        "STATE_MODEL", "EFFECT_MODEL", "SOLUTION", "AGENT", "CHANNEL_APPLICATION",
                        "SCENARIO_INVENTORY", "SCENARIO_PACK", "FIDELITY_INVENTORY", "OUTCOME_DEFINITION");
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.scope()).isEqualTo(scope());
            assertThat(candidate.fingerprint()).matches("sha256:[0-9a-f]{64}");
            assertThat(candidate.authority()).isEqualTo(
                    "resource-gateway://demo/business-catalog/loan-decision");
            assertThat(candidate.description()).doesNotContainIgnoringCase("payload", "credential", "secret");
            assertThat(candidate.labels()).allMatch(label ->
                    !label.toLowerCase().contains("password") && !label.toLowerCase().contains("token"));
        });
    }

    @Test
    void serviceStillRejectsContributorCandidateWithAnIncorrectScope() {
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        VisualOperatorCatalog operators = mock(VisualOperatorCatalog.class);
        when(drafts.all()).thenReturn(List.of());
        when(operators.list(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        when(operators.builtInFunctions(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        ReferenceCandidate leaked = externalCandidate("BUSINESS_DOMAIN", "wrong-scope", otherScope());
        ResourceGatewayReferenceCandidateProvider provider = new ResourceGatewayReferenceCandidateProvider(
                drafts, operators, new ObjectMapper().findAndRegisterModules(), null,
                List.of(new FixedContributor("malicious", leaked)));
        ReferenceCandidateService service = new ReferenceCandidateService(provider);

        assertThat(service.search(new SearchRequest("", "", scope())).items()).isEmpty();
        assertThat(service.resolve(ResolveRequest.from(leaked, scope(), "CORRECTNESS")))
                .extracting(ResolveResult::status, ResolveResult::errorCode)
                .containsExactly(ResolveResult.Status.FORBIDDEN, "RG.REFERENCE.FORBIDDEN");
    }

    private static ReferenceCandidate externalCandidate(String kind, String id, ReferenceScope scope) {
        return new ReferenceCandidate(
                ReferenceCandidate.SCHEMA_VERSION, kind, id, id,
                "External metadata candidate.", 1, fingerprint(kind + ":" + id),
                "resource-gateway://external/business-catalog", scope,
                ReferenceCandidate.Lifecycle.ACTIVE, null, List.of("external"),
                ReferenceCandidate.Compatibility.COMPATIBLE, "");
    }

    private static String fingerprint(String value) {
        return "sha256:" + String.valueOf(value.hashCode()).replace('-', '0').repeat(64).substring(0, 64);
    }

    private record FixedContributor(String contributorId, ReferenceCandidate candidate)
            implements ReferenceCandidateContributor {
        @Override
        public List<ReferenceCandidate> contribute(ReferenceScope scope) {
            return List.of(candidate);
        }
    }

    private static GraphDraft graph() {
        return new GraphDraft(
                GraphDraft.SCHEMA_VERSION, "loan-draft", 7, "loanDecision", "tenant-a",
                "project-a", "test", GraphDraft.STATUS_DRAFT, SchemaEnvelope.opaque(),
                List.of(), List.of(), Map.of(), GraphDraft.OutputSelection.empty(), Map.of(),
                GraphDraft.RevisionMetadata.patch("risk-team", "test", "Candidate test", List.of()));
    }

    private static OperatorDefinition operator() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", "risk.lookup", "1.0.0",
                new OperatorDefinition.Display("Risk lookup", "Reads a risk score.", List.of("risk")),
                new OperatorDefinition.Source("java-operator", "", "", "", false, "risk-library"),
                new OperatorDefinition.Ports(List.of(), List.of()), SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk.lookup", Map.of()), List.of());
    }

    private static OperatorLibrary.BuiltInFunction function() {
        return new OperatorLibrary.BuiltInFunction(
                "coalesce", "core", "Coalesce", "Returns the first available value.",
                "null-handling", List.of(), List.of("coalesce(value, fallback)"));
    }

    private static ReferenceScope scope() {
        return new ReferenceScope("tenant-a", "org-a", "project-a", "test", "local");
    }

    private static ReferenceScope otherScope() {
        return new ReferenceScope("tenant-b", "org-b", "project-b", "staging", "local");
    }

    private static EnterpriseScope enterpriseScope() {
        return new EnterpriseScope("tenant-a", "org-a", "project-a", "test", "local");
    }

    private static StoredCorrectnessDefinition storedDefinition() {
        PrincipalRef owner = new PrincipalRef("risk-team", PrincipalKind.TEAM, "Risk Team");
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        CorrectnessDefinition definition = new CorrectnessDefinition(
                CorrectnessDefinition.SCHEMA_VERSION, "loan-correctness", 1, enterpriseScope(),
                new ExactTargetRef(TargetKind.GRAPH, "loan-decision", 7, fingerprint('a')),
                "Loan decision correctness", "Proves policy-compliant decisions.",
                List.of("Eligible requests receive the reviewed result"), RiskLevel.CRITICAL,
                owner, List.of(), null, null, CorrectnessDefinition.DefinitionLifecycle.DRAFT,
                null, new AuditMetadata(now, now, owner, owner));
        return StoredCorrectnessDefinition.verified(
                new ObjectMapper().findAndRegisterModules(), definition);
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
