package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ParentFlowApplyCaseCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.InMemoryStandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowStoredDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Map;

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.command;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.output;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReusableFlowFixtureModuleTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");

    @Test
    void createsWholeFlowFixtureForAnExactSavedDraft() {
        ReusableFlowVersion version = version();
        ReusableFlowDraft draft = new ReusableFlowDraft(ReusableFlowDraft.SCHEMA_VERSION,
                version.flowId(), version.source().draftId(), version.source().revision(),
                version.source().fingerprint(), version.displayName(), version.kind(),
                version.description(), version.contract(), version.graph(),
                new ReusableFlowCommand.Layout(Map.of("decision",
                        new ReusableFlowCommand.Position(0, 0))), ReusableFlowDraft.Status.DRAFT);
        ReusableFlowDraftStore drafts = mock(ReusableFlowDraftStore.class);
        ReusableFlowStoredDraft storedDraft = new ReusableFlowStoredDraft(draft,
                new ReusableFlowSaveReceipt(ReusableFlowSaveReceipt.SCHEMA_VERSION,
                        draft.flowId(), draft.subject(), ReusableFlowSaveReceipt.Validation.VALID),
                "\"draft-etag\"");
        when(drafts.findDraftRevisionStored(SCOPE, draft.draftId(), draft.revision()))
                .thenReturn(Optional.of(storedDraft));
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        ReusableFlowFixtureModule module = new ReusableFlowFixtureModule(
                publications(version), drafts, store, new WholeFlowFixtureMaterializer(), null);
        FixtureSetCommand command = command(draft.subject(), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())), null);

        StandaloneFixtureSetSaveResult created = module.save(SCOPE, "author", "draft-cases",
                FixtureSetPrecondition.create(), "create-draft", command);

        assertThat(created.view().subject()).isEqualTo(draft.subject());
        assertThat(created.view().revision()).isEqualTo(1);
    }

    @Test
    void draftSubjectsFailClosedForDriftAndNodeLevelControls() {
        ReusableFlowVersion version = version();
        ReusableFlowDraft draft = new ReusableFlowDraft(ReusableFlowDraft.SCHEMA_VERSION,
                version.flowId(), version.source().draftId(), version.source().revision(),
                version.source().fingerprint(), version.displayName(), version.kind(),
                version.description(), version.contract(), version.graph(),
                new ReusableFlowCommand.Layout(Map.of("decision",
                        new ReusableFlowCommand.Position(0, 0))), ReusableFlowDraft.Status.DRAFT);
        ReusableFlowDraftStore drafts = mock(ReusableFlowDraftStore.class);
        ReusableFlowStoredDraft stored = new ReusableFlowStoredDraft(draft,
                new ReusableFlowSaveReceipt(ReusableFlowSaveReceipt.SCHEMA_VERSION,
                        draft.flowId(), draft.subject(), ReusableFlowSaveReceipt.Validation.VALID),
                "\"draft-etag\"");
        when(drafts.findDraftRevisionStored(SCOPE, draft.draftId(), draft.revision()))
                .thenReturn(Optional.of(stored));
        ReusableFlowFixtureModule module = new ReusableFlowFixtureModule(
                publications(version), drafts, new InMemoryStandaloneFixtureSetStore(),
                new WholeFlowFixtureMaterializer(), mock(ParentFlowApplyCaseCompiler.class));
        FixtureSetCommand drifted = command(new com.leanowtech.bloge.gateway.visual.authoring.fixture
                        .FixtureSubjectRef.FlowDraft(draft.draftId(), draft.revision(),
                        "sha256:" + "f".repeat(64)), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())), null);
        FixtureSetCommand nodeControl = command(draft.subject(), FixtureSetCommand.Target.node("decision"),
                new FixtureSetCommand.Behavior.ApplyCase("child-cases", 1, "approved"), null);

        assertCode(() -> module.save(SCOPE, "author", "drifted-cases",
                        FixtureSetPrecondition.create(), "drifted", drifted),
                ApiFixtureSetAuthoringFailure.Code.INTEGRITY);
        assertCode(() -> module.save(SCOPE, "author", "node-cases",
                        FixtureSetPrecondition.create(), "node", nodeControl),
                ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
    }

    @Test
    void createsUpdatesAndExactlyReplaysAWholeFlowFixture() {
        ReusableFlowVersion version = version();
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        ReusableFlowFixtureModule module = module(version, store);
        FixtureSetCommand first = command(version.subject(), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())), null);

        StandaloneFixtureSetSaveResult created = module.save(SCOPE, "author", "eligibility-cases",
                FixtureSetPrecondition.create(), "create-1", first);
        StandaloneFixtureSetSaveResult replay = module.save(SCOPE, "author", "eligibility-cases",
                FixtureSetPrecondition.create(), "create-1", first);
        FixtureSetCommand second = new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION,
                "Eligibility regression cases", version.subject(), first.cases());
        StandaloneFixtureSetSaveResult updated = module.save(SCOPE, "author", "eligibility-cases",
                FixtureSetPrecondition.match(created.strongEtag()), "update-1", second);

        assertThat(created.view().revision()).isEqualTo(1);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.strongEtag()).isEqualTo(created.strongEtag());
        assertThat(updated.view().revision()).isEqualTo(2);
        assertThat(updated.view().displayName()).isEqualTo("Eligibility regression cases");
        assertThat(store.findRevision(SCOPE, "eligibility-cases", 1)).isPresent();
        assertThat(store.findHead(SCOPE, "eligibility-cases")).get()
                .extracting(value -> value.generated().view().revision()).isEqualTo(2);
    }

    @Test
    void failsClosedForStaleEtagsChangedReplayAndUnknownFlowVersions() {
        ReusableFlowVersion version = version();
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        ReusableFlowFixtureModule module = module(version, store);
        FixtureSetCommand command = command(version.subject(), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())), null);
        StandaloneFixtureSetSaveResult created = module.save(SCOPE, "author", "eligibility-cases",
                FixtureSetPrecondition.create(), "create-1", command);
        module.save(SCOPE, "author", "eligibility-cases",
                FixtureSetPrecondition.match(created.strongEtag()), "update-1", command);

        assertCode(() -> module.save(SCOPE, "author", "eligibility-cases",
                        FixtureSetPrecondition.match(created.strongEtag()), "stale-update", command),
                ApiFixtureSetAuthoringFailure.Code.CAS_MISMATCH);
        FixtureSetCommand changed = new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION,
                "Changed", version.subject(), command.cases());
        assertCode(() -> module.save(SCOPE, "author", "eligibility-cases",
                        FixtureSetPrecondition.create(), "create-1", changed),
                ApiFixtureSetAuthoringFailure.Code.CONFLICT);
        FixtureSetCommand missing = new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION,
                "Missing", new com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef
                .FlowVersion("missing", 1, version.fingerprint()), command.cases());
        assertCode(() -> module.save(SCOPE, "author", "missing-cases",
                        FixtureSetPrecondition.create(), "missing-1", missing),
                ApiFixtureSetAuthoringFailure.Code.NOT_FOUND);
    }

    @Test
    void validatesParentApplyCaseBeforeSavingTheRevision() {
        ReusableFlowVersion version = version();
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        ReusableFlowPublicationStore publications = publications(version);
        ParentFlowApplyCaseCompiler compiler = mock(ParentFlowApplyCaseCompiler.class);
        ReusableFlowFixtureModule module = new ReusableFlowFixtureModule(
                publications, store, new WholeFlowFixtureMaterializer(), compiler);
        FixtureSetCommand.Case fixtureCase = new FixtureSetCommand.Case(
                "approved", "Approved customer",
                command(version.subject(), FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(
                                FixtureSetCommand.Material.inline(output())), null)
                        .cases().getFirst().input(),
                java.util.List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.node("decision"),
                        new FixtureSetCommand.Behavior.ApplyCase(
                                "decision-cases", 1, "approved"), null)),
                new FixtureSetCommand.Expect(output()));
        FixtureSetCommand parent = new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION,
                "Parent cases", version.subject(), java.util.List.of(fixtureCase));

        module.save(SCOPE, "author", "parent-cases", FixtureSetPrecondition.create(),
                "parent-create", parent);

        verify(compiler).validateCommand(SCOPE, version, parent);
        assertThat(store.findHead(SCOPE, "parent-cases")).isPresent();
    }

    private static ReusableFlowFixtureModule module(
            ReusableFlowVersion version, InMemoryStandaloneFixtureSetStore store) {
        return new ReusableFlowFixtureModule(
                publications(version), store, new WholeFlowFixtureMaterializer());
    }

    private static ReusableFlowPublicationStore publications(ReusableFlowVersion version) {
        return new ReusableFlowPublicationStore() {
            @Override public com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishResult publish(
                    com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishIntent intent) {
                throw new UnsupportedOperationException();
            }

            @Override public Optional<ReusableFlowVersion> findVersion(
                    AuthoringScope scope, String publicationId, int revision) {
                return SCOPE.equals(scope) && version.publicationId().equals(publicationId)
                        && version.revision() == revision ? Optional.of(version) : Optional.empty();
            }

            @Override public Optional<ReusableFlowVersion> findLatestVersion(
                    AuthoringScope scope, String flowId) {
                return SCOPE.equals(scope) && version.flowId().equals(flowId)
                        ? Optional.of(version) : Optional.empty();
            }
        };
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   ApiFixtureSetAuthoringFailure.Code code) {
        assertThatThrownBy(call).isInstanceOf(ApiFixtureSetAuthoringFailure.class)
                .extracting("code").isEqualTo(code);
    }
}
