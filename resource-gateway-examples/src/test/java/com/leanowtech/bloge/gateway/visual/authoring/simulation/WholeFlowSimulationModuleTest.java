package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ParentFlowApplyCaseCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableDefinition;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowStoredDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.command;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.output;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WholeFlowSimulationModuleTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void wholeFlowDraftReturnUsesTheExactCommittedDraftAuthority() {
        ReusableFlowVersion version = version();
        ReusableFlowDraft draft = new ReusableFlowDraft(ReusableFlowDraft.SCHEMA_VERSION,
                "eligibility", "draft-1", 2, "sha256:" + "d".repeat(64),
                version.displayName(), version.kind(), version.description(), version.contract(),
                version.graph(), new ReusableFlowCommand.Layout(java.util.Map.of()),
                ReusableFlowDraft.Status.DRAFT);
        FixtureSetCommand command = command(draft.subject(), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())), null);
        GeneratedDefaultFixture generated = new WholeFlowFixtureMaterializer()
                .generate("eligibility-draft-cases", 1, draft, command);
        ApiFixtureSetCommitStore fixtures = mock(ApiFixtureSetCommitStore.class);
        when(fixtures.findRevision(SCOPE, "eligibility-draft-cases", 1))
                .thenReturn(Optional.of(new StoredFixtureSet(SCOPE, generated)));
        ReusableFlowDraftStore drafts = mock(ReusableFlowDraftStore.class);
        when(drafts.findDraftRevisionStored(SCOPE, draft.draftId(), draft.revision()))
                .thenReturn(Optional.of(new ReusableFlowStoredDraft(draft,
                        new ReusableFlowSaveReceipt(ReusableFlowSaveReceipt.SCHEMA_VERSION,
                                draft.flowId(), draft.subject(), ReusableFlowSaveReceipt.Validation.VALID),
                        "\"flow-r2\"")));
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        SimulationModule module = new SimulationModule(resources, fixtures,
                new SimulationModule.Authorities(null, drafts, null, null),
                new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-draft");

        SimulationRun run = module.run(SCOPE, "whole-flow-draft-run",
                SimulationRequest.fixtureCase("eligibility-draft-cases", 1, "approved"));

        assertThat(run.status()).isEqualTo(SimulationRun.Status.SUCCEEDED);
        assertThat(run.subject()).isEqualTo(draft.subject());
        assertThat(run.output()).isEqualTo(output());
        assertThat(run.nodes()).singleElement().satisfies(node -> {
            assertThat(node.nodeId()).isEqualTo("subject");
            assertThat(node.execution()).isEqualTo(SimulationRun.Execution.MOCKED);
            assertThat(node.fixtureSource()).isEqualTo(SimulationRun.FixtureSource.INLINE);
            assertThat(node.fidelity()).isEqualTo(SimulationRun.Fidelity.OUTPUT_LEVEL);
            assertThat(node.egress()).isEqualTo(new SimulationRun.Egress.Fixture(false));
        });
        verifyNoInteractions(resources);
    }

    @Test
    void wholeFlowReturnRunsWithoutExecutingInternalNodes() {
        ReusableFlowVersion version = version();
        GeneratedDefaultFixture generated = new WholeFlowFixtureMaterializer().generate(
                "eligibility-cases", version, command(version.subject(),
                        com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Target.subject(),
                        com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Behavior.returned(
                                com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Material
                                        .inline(output())), null));
        ApiFixtureSetCommitStore fixtures = mock(ApiFixtureSetCommitStore.class);
        when(fixtures.findRevision(SCOPE, "eligibility-cases", 1))
                .thenReturn(Optional.of(new StoredFixtureSet(SCOPE, generated)));
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        when(publications.findVersion(SCOPE, version.publicationId(), version.revision()))
                .thenReturn(Optional.of(version));
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        SimulationModule module = new SimulationModule(resources, fixtures,
                new SimulationModule.Authorities(publications, null, null, null),
                new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-flow");

        SimulationRun run = module.run(SCOPE, "whole-flow-run",
                SimulationRequest.fixtureCase("eligibility-cases", 1, "approved"));

        assertThat(run.status()).isEqualTo(SimulationRun.Status.SUCCEEDED);
        assertThat(run.subject()).isEqualTo(version.subject());
        assertThat(run.output()).isEqualTo(output());
        assertThat(run.nodes()).singleElement().satisfies(node -> {
            assertThat(node.nodeId()).isEqualTo("subject");
            assertThat(node.execution()).isEqualTo(SimulationRun.Execution.MOCKED);
            assertThat(node.fixtureSource()).isEqualTo(SimulationRun.FixtureSource.INLINE);
            assertThat(node.fidelity()).isEqualTo(SimulationRun.Fidelity.OUTPUT_LEVEL);
            assertThat(node.egress()).isEqualTo(new SimulationRun.Egress.Fixture(false));
        });
        assertThat(run.verdicts()).isEqualTo(new SimulationRun.Verdicts(
                SimulationRun.ExecutionVerdict.SIMULATED_ONLY, SimulationRun.Verdict.PASSED,
                SimulationRun.Verdict.PASSED, SimulationRun.Verdict.NOT_CHECKED));
        verifyNoInteractions(resources);
    }

    @Test
    void teamAvailableWholeFlowResolvesExactProtectedMaterialWithGovernanceEvidence() {
        ReusableFlowVersion version = version();
        FixtureSetCommand.Material.FixtureAsset asset = new FixtureSetCommand.Material.FixtureAsset(
                "asset-approved", 5, "sha256:" + "a".repeat(64));
        GeneratedDefaultFixture generated = protectedFixture(version, asset);
        ApiFixtureSetCommitStore fixtures = mock(ApiFixtureSetCommitStore.class);
        when(fixtures.findRevision(SCOPE, "eligibility-cases", 2))
                .thenReturn(Optional.of(new StoredFixtureSet(SCOPE, generated)));
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        when(publications.findVersion(SCOPE, version.publicationId(), version.revision()))
                .thenReturn(Optional.of(version));
        FixtureAssetSimulationResolver resolver = mock(FixtureAssetSimulationResolver.class);
        SimulationIdentity identity = new SimulationIdentity(
                SCOPE, "org", "local", "HUMAN", "reviewer", "RESTRICTED", "request-1");
        when(resolver.resolve(identity, asset)).thenReturn(output());
        SimulationModule module = new SimulationModule(mock(ApiResourceCommitStore.class), fixtures,
                new SimulationModule.Authorities(publications, null, null, resolver),
                new InMemorySimulationRunStore(),
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-protected");

        SimulationRun run = module.execute(SCOPE, "protected-flow-run",
                SimulationRequest.fixtureCase("eligibility-cases", 2, "approved"), identity).run();

        assertThat(run.status()).isEqualTo(SimulationRun.Status.SUCCEEDED);
        assertThat(run.output()).isEqualTo(output());
        assertThat(run.nodes()).singleElement().satisfies(node -> {
            assertThat(node.nodeId()).isEqualTo("subject");
            assertThat(node.execution()).isEqualTo(SimulationRun.Execution.MOCKED);
            assertThat(node.fixtureSource()).isEqualTo(SimulationRun.FixtureSource.FIXTURE_ASSET);
            assertThat(node.fidelity()).isEqualTo(SimulationRun.Fidelity.OUTPUT_LEVEL);
            assertThat(node.egress()).isEqualTo(new SimulationRun.Egress.Fixture(false));
        });
        assertThat(run.verdicts().governance()).isEqualTo(SimulationRun.Verdict.PASSED);
        verify(resolver).resolve(identity, asset);
    }

    @Test
    void publicationFingerprintDriftFailsClosed() {
        ReusableFlowVersion version = version();
        GeneratedDefaultFixture generated = new WholeFlowFixtureMaterializer().generate(
                "eligibility-cases", version, command(version.subject(),
                        com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Target.subject(),
                        com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Behavior.returned(
                                com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Material
                                        .inline(output())), null));
        ApiFixtureSetCommitStore fixtures = mock(ApiFixtureSetCommitStore.class);
        when(fixtures.findRevision(SCOPE, "eligibility-cases", 1))
                .thenReturn(Optional.of(new StoredFixtureSet(SCOPE, generated)));
        ReusableFlowVersion drifted = new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION,
                version.publicationId(), version.revision(),
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                version.source(), version.flowId(), version.displayName(), version.kind(), version.description(),
                version.contract(), version.graph(), version.publishedAt(), version.publishedBy(), version.status());
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        when(publications.findVersion(SCOPE, version.publicationId(), version.revision()))
                .thenReturn(Optional.of(drifted));
        SimulationModule module = new SimulationModule(mock(ApiResourceCommitStore.class), fixtures,
                new SimulationModule.Authorities(publications, null, null, null),
                new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-flow");

        assertThatThrownBy(() -> module.run(SCOPE, "whole-flow-run",
                SimulationRequest.fixtureCase("eligibility-cases", 1, "approved")))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.INTEGRITY);
    }

    @Test
    void parentFlowApplyCaseRunsMappingsWithoutExecutingTheChildFlow() {
        ReusableFlowVersion child = version();
        ReusableFlowCommand.Contract parentContract = new ReusableFlowCommand.Contract(
                child.contract().input(), child.contract().output());
        ReusableFlowCommand.ComposableRef.FlowVersion childRef =
                new ReusableFlowCommand.ComposableRef.FlowVersion(
                        child.publicationId(), child.revision(), child.fingerprint());
        ReusableFlowCommand.Graph parentGraph = new ReusableFlowCommand.Graph(List.of(
                new ReusableFlowCommand.Node("eligibility", "Eligibility", childRef,
                        List.of(new ReusableFlowCommand.Input("$.customerId",
                                new ReusableFlowCommand.MappingSource.FlowInput("$.customerId"))))),
                new ReusableFlowCommand.Output("eligibility", "$"));
        ReusableFlowVersion parent = new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION,
                "parent-v1", 1, "sha256:" + "e".repeat(64),
                new ReusableFlowVersion.Source("parent-draft", 1, "sha256:" + "f".repeat(64)),
                "parent", "Parent", ReusableFlowCommand.Kind.SOLUTION, "Parent solution",
                parentContract, parentGraph, NOW, "author", ReusableFlowVersion.Status.PUBLISHED);
        FixtureSetCommand childCommand = command(child.subject(), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())), null);
        GeneratedDefaultFixture childFixture = new WholeFlowFixtureMaterializer()
                .generate("eligibility-cases", child, childCommand);
        FixtureSetCommand.Case parentCase = new FixtureSetCommand.Case(
                "parent-approved", "Parent approved", childCommand.cases().getFirst().input(),
                List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.node("eligibility"),
                        new FixtureSetCommand.Behavior.ApplyCase(
                                "eligibility-cases", 1, "approved"), null)),
                new FixtureSetCommand.Expect(output()));
        FixtureSetCommand parentCommand = new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION,
                "Parent cases", parent.subject(), List.of(parentCase));
        GeneratedDefaultFixture parentFixture = new WholeFlowFixtureMaterializer()
                .generate("parent-cases", parent, parentCommand);
        ApiFixtureSetCommitStore fixtures = mock(ApiFixtureSetCommitStore.class);
        when(fixtures.findRevision(SCOPE, "parent-cases", 1))
                .thenReturn(Optional.of(new StoredFixtureSet(SCOPE, parentFixture)));
        when(fixtures.findRevision(SCOPE, "eligibility-cases", 1))
                .thenReturn(Optional.of(new StoredFixtureSet(SCOPE, childFixture)));
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        when(publications.findVersion(SCOPE, parent.publicationId(), parent.revision()))
                .thenReturn(Optional.of(parent));
        ParentFlowApplyCaseCompiler compiler = new ParentFlowApplyCaseCompiler(
                (scope, reference) -> childRef.equals(reference)
                        ? Optional.of(new ComposableDefinition(childRef,
                        child.contract().input(), child.contract().output())) : Optional.empty(),
                fixtures);
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        SimulationModule module = new SimulationModule(resources, fixtures,
                new SimulationModule.Authorities(publications, null, compiler, null),
                new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "sim-parent");

        SimulationRun run = module.run(SCOPE, "parent-flow-run",
                SimulationRequest.fixtureCase("parent-cases", 1, "parent-approved"));

        assertThat(run.status()).isEqualTo(SimulationRun.Status.SUCCEEDED);
        assertThat(run.subject()).isEqualTo(parent.subject());
        assertThat(run.output()).isEqualTo(output());
        assertThat(run.nodes()).containsExactly(new SimulationRun.Node("eligibility",
                SimulationRun.NodeStatus.COMPLETED, SimulationRun.Execution.MOCKED,
                SimulationRun.FixtureSource.APPLY_CASE, SimulationRun.Fidelity.OUTPUT_LEVEL,
                SimulationRun.Egress.notApplicable()));
        assertThat(run.verdicts()).isEqualTo(new SimulationRun.Verdicts(
                SimulationRun.ExecutionVerdict.PASSED_WITH_MOCKS, SimulationRun.Verdict.PASSED,
                SimulationRun.Verdict.PASSED, SimulationRun.Verdict.NOT_CHECKED));
        verifyNoInteractions(resources);
    }

    private static GeneratedDefaultFixture protectedFixture(
            ReusableFlowVersion version, FixtureSetCommand.Material.FixtureAsset asset) {
        FixtureSetCommand.Case fixtureCase = new FixtureSetCommand.Case(
                "approved", "Approved", command(version.subject(), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())), null)
                .cases().getFirst().input(),
                List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(asset), FixtureSetCommand.Fidelity.OUTPUT_LEVEL)),
                new FixtureSetCommand.Expect(output()));
        List<FixtureSetCommand.Case> cases = List.of(fixtureCase);
        String fingerprint = FixtureSetFingerprints.of("Eligibility cases", version.subject(), cases);
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION,
                "eligibility-cases", 2, fingerprint, 3, "Eligibility cases", version.subject(),
                cases, FixtureSetView.Status.TEAM_AVAILABLE);
        FixtureSetSaveReceipt receipt = new FixtureSetSaveReceipt(FixtureSetSaveReceipt.SCHEMA_VERSION,
                view.fixtureSetId(), view.revision(), fingerprint, view.subject(), List.of("approved"),
                view.status(), view.statusRevision());
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION,
                view.fixtureSetId(), view.revision(), fingerprint, view.displayName(), view.subject(),
                List.of(new FixtureSetSummary.CaseSummary("approved", "Approved")),
                view.status(), view.statusRevision());
        return new GeneratedDefaultFixture(view, receipt, summary,
                List.of(new GeneratedDefaultFixture.CaseMapping("approved", "approved")));
    }
}
