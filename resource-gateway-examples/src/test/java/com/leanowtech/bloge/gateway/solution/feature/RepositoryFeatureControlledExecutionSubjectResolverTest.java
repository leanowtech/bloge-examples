package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies server-owned Feature execution coordinates and coverage obligations. */
class RepositoryFeatureControlledExecutionSubjectResolverTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void derivesTheDenominatorAndRejectsCallerOmissions() {
        Fixture fixture = fixture();

        FeatureControlledExecutionSubjectResolver.Subject subject = fixture.resolver().freeze(
                "graph:risk-v1", List.of("risk"),
                List.of("node:eligibility", "node:limit"), identity());

        assertThat(subject.graphRevision()).isEqualTo(1);
        assertThat(subject.coverageObligations())
                .containsExactly("node:eligibility", "node:limit");
        assertThatThrownBy(() -> fixture.resolver().freeze(
                "graph:risk-v1", List.of("risk"), List.of("node:eligibility"), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("FEATURE_SUITE_COVERAGE_INVALID"));
    }

    @Test
    void invalidatesEvidenceAfterGraphOrLibraryRevisionDrift() {
        Fixture fixture = fixture();
        FeatureControlledExecutionSubjectResolver.Subject subject = fixture.resolver().freeze(
                "graph:risk-v1", List.of("risk"),
                List.of("node:eligibility", "node:limit"), identity());

        fixture.drafts().save(graph("graph:risk-v1", fixture.library(), true));
        assertThatThrownBy(() -> fixture.resolver().requireCurrent(subject,
                "graph:risk-v1", List.of("risk"),
                List.of("node:eligibility", "node:limit"), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("FEATURE_SUITE_EVIDENCE_STALE"));

        Fixture libraryFixture = fixture();
        FeatureControlledExecutionSubjectResolver.Subject librarySubject = libraryFixture.resolver().freeze(
                "graph:risk-v1", List.of("risk"),
                List.of("node:eligibility", "node:limit"), identity());
        OperatorLibrary revised = new OperatorLibrary(
                libraryFixture.library().schemaVersion(), "risk", "Risk revised", "2.0.0",
                libraryFixture.library().owner(), libraryFixture.library().status(),
                libraryFixture.library().builtInFunctions(), libraryFixture.library().operators());
        libraryFixture.libraries().upsert(revised);
        assertThatThrownBy(() -> libraryFixture.resolver().requireCurrent(librarySubject,
                "graph:risk-v1", List.of("risk"),
                List.of("node:eligibility", "node:limit"), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("FEATURE_SUITE_EVIDENCE_STALE"));
    }

    private Fixture fixture() {
        OperatorLibrary source = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibrary library = new OperatorLibrary(
                source.schemaVersion(), "risk", source.displayName(), source.version(), source.owner(),
                source.status(), source.builtInFunctions(), source.operators());
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(library);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        drafts.save(graph("graph:risk-v1", library, false));
        return new Fixture(drafts, libraries, library,
                new RepositoryFeatureControlledExecutionSubjectResolver(drafts, libraries, mapper));
    }

    private static GraphDraft graph(String ref, OperatorLibrary library, boolean changed) {
        var operator = library.operators().getFirst();
        List<GraphDraft.DraftNode> nodes = List.of(
                new GraphDraft.DraftNode("eligibility", operator.operatorRef(), "Eligibility",
                        Map.of(), Map.of(), new GraphDraft.Position(0, 0)),
                new GraphDraft.DraftNode("limit", operator.operatorRef(),
                        changed ? "Changed limit" : "Limit", Map.of(), Map.of(),
                        new GraphDraft.Position(100, 0)));
        return new GraphDraft(GraphDraft.SCHEMA_VERSION, ref, 0, "riskGraph",
                identity().tenantId(), identity().projectId(), identity().environmentId(),
                GraphDraft.STATUS_DRAFT, SchemaEnvelope.opaque(), nodes, List.of(), Map.of(),
                GraphDraft.OutputSelection.empty(),
                Map.of("eligibility", "fp-1", "limit", "fp-1"),
                Map.of("eligibility", operator, "limit", operator),
                GraphDraft.RevisionMetadata.empty());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "agent-1", "", "AGENT_TDD_EXECUTION", "corr-subject");
    }

    private record Fixture(
            InMemoryGraphDraftRepository drafts,
            InMemoryOperatorLibraryRegistry libraries,
            OperatorLibrary library,
            RepositoryFeatureControlledExecutionSubjectResolver resolver) { }
}
