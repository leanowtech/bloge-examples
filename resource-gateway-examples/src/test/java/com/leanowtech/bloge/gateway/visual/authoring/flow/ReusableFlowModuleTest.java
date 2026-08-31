package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReusableFlowModuleTest {
    private static final String DEPENDENCY = "sha256:" + "a".repeat(64);
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant-a", "project-a", "test");

    @Test
    void createsUpdatesAndReadsExactDraftRevisions() {
        ReusableFlowModule module = module();

        ReusableFlowSaveResult created = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.create(), "create-1", command("Customer tool", 0));
        ReusableFlowSaveResult updated = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.match(1), "update-1", command("Customer tool v2", 40));

        assertThat(created.replayed()).isFalse();
        assertThat(created.draft().revision()).isEqualTo(1);
        assertThat(created.receipt().draft().draftId()).isEqualTo(created.draft().draftId());
        assertThat(updated.draft().revision()).isEqualTo(2);
        assertThat(updated.draft().draftId()).isEqualTo(created.draft().draftId());
        assertThat(updated.strongEtag()).isNotEqualTo(created.strongEtag());
        assertThat(module.findHead(SCOPE, "customer-tool")).contains(updated.draft());
        assertThat(module.findRevision(SCOPE, "customer-tool", 1)).contains(created.draft());
    }

    @Test
    void exactReplayPrecedesCurrentHeadCasAndChangedIntentConflicts() {
        ReusableFlowModule module = module();
        ReusableFlowSaveResult created = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.create(), "create-1", command("Customer tool", 0));
        ReusableFlowSaveResult firstUpdate = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.match(1), "update-1", command("Customer tool v2", 40));
        module.save(SCOPE, "alice", "customer-tool", ExpectedRevision.match(2),
                "update-2", command("Customer tool v3", 80));

        ReusableFlowSaveResult replay = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.match(1), "update-1", command("Customer tool v2", 40));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.draft()).isEqualTo(firstUpdate.draft());
        assertThat(replay.strongEtag()).isEqualTo(firstUpdate.strongEtag());

        assertThatThrownBy(() -> module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.match(1), "update-1", command("Changed intent", 40)))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.CONFLICT);
        assertThat(created.draft().revision()).isEqualTo(1);
    }

    @Test
    void strongEtagUpdateReplaysAfterHeadAdvancesButNewStaleIntentFails() {
        ReusableFlowModule module = module();
        ReusableFlowSaveResult created = module.save(SCOPE, "alice", "customer-tool",
                ReusableFlowPrecondition.create(), "create-1", command("Customer tool", 0));
        ReusableFlowSaveResult firstUpdate = module.save(SCOPE, "alice", "customer-tool",
                ReusableFlowPrecondition.matchStrongEtag(created.strongEtag()), "update-1",
                command("Customer tool v2", 40));
        module.save(SCOPE, "alice", "customer-tool",
                ReusableFlowPrecondition.matchStrongEtag(firstUpdate.strongEtag()), "update-2",
                command("Customer tool v3", 80));

        ReusableFlowSaveResult replay = module.save(SCOPE, "alice", "customer-tool",
                ReusableFlowPrecondition.matchStrongEtag(created.strongEtag()), "update-1",
                command("Customer tool v2", 40));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.draft()).isEqualTo(firstUpdate.draft());

        assertThatThrownBy(() -> module.save(SCOPE, "alice", "customer-tool",
                ReusableFlowPrecondition.matchStrongEtag(created.strongEtag()), "stale-new-key",
                command("Stale", 120)))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.CAS_MISMATCH);
    }

    @Test
    void unknownStrongEtagDistinguishesMissingFlowFromChangedFlow() {
        ReusableFlowModule module = module();
        assertThatThrownBy(() -> module.save(SCOPE, "alice", "missing",
                ReusableFlowPrecondition.matchStrongEtag("\"unknown\""), "update-1",
                command("Missing", 0)))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.NOT_FOUND);

        module.save(SCOPE, "alice", "customer-tool", ReusableFlowPrecondition.create(),
                "create-1", command("Customer tool", 0));
        assertThatThrownBy(() -> module.save(SCOPE, "alice", "customer-tool",
                ReusableFlowPrecondition.matchStrongEtag("\"unknown\""), "update-1",
                command("Changed", 40)))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.CAS_MISMATCH);
    }

    @Test
    void staleCasFailsWithoutOccupyingAReusableIdempotencyCoordinate() {
        ReusableFlowModule module = module();
        module.save(SCOPE, "alice", "customer-tool", ExpectedRevision.create(),
                "create-1", command("Customer tool", 0));

        assertThatThrownBy(() -> module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.match(7), "stale-1", command("Stale", 40)))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.CAS_MISMATCH);
        assertThatThrownBy(() -> module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.match(7), "stale-1", command("Stale", 40)))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.CAS_MISMATCH);
    }

    @Test
    void readsAndIdempotencyAreIsolatedByTrustedScopeAndActor() {
        ReusableFlowModule module = module();
        ReusableFlowSaveResult alice = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.create(), "same-key", command("Alice tool", 0));
        AuthoringScope other = new AuthoringScope("tenant-b", "project-a", "test");
        ReusableFlowSaveResult bob = module.save(other, "bob", "customer-tool",
                ExpectedRevision.create(), "same-key", command("Bob tool", 0));

        assertThat(alice.draft().draftId()).isNotEqualTo(bob.draft().draftId());
        assertThat(module.findHead(other, "customer-tool")).contains(bob.draft());
        assertThat(module.findRevision(other, "customer-tool", 1)).contains(bob.draft());
        assertThat(module.findRevision(SCOPE, "customer-tool", 2)).isEqualTo(Optional.empty());
    }

    @Test
    void invalidDagDoesNotOccupyKeyAndLayoutOnlyUpdatesKeepContentFingerprint() {
        ReusableFlowModule module = module();
        ReusableFlowCommand valid = command("Customer tool", 0);
        ReusableFlowCommand invalid = new ReusableFlowCommand(valid.schemaVersion(),
                new ReusableFlowCommand.Flow(valid.flow().displayName(), valid.flow().kind(),
                        valid.flow().description(), valid.flow().contract(), valid.flow().graph(),
                        new ReusableFlowCommand.Layout(Map.of())));
        assertThatThrownBy(() -> module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.create(), "create-1", invalid))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.LAYOUT_INVALID);

        ReusableFlowSaveResult created = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.create(), "create-1", valid);
        ReusableFlowSaveResult moved = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.match(1), "move-1", command("Customer tool", 240));
        assertThat(moved.draft().revision()).isEqualTo(2);
        assertThat(moved.draft().fingerprint()).isEqualTo(created.draft().fingerprint());
        assertThat(moved.strongEtag()).isNotEqualTo(created.strongEtag());
    }

    @Test
    void publishesExactDraftAsImmutableVersionAndReplaysWithoutNewRevision() {
        ReusableFlowModule module = publishingModule();
        ReusableFlowSaveResult saved = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.create(), "create-1", command("Customer tool", 0));
        ReusableFlowPublishCommand publish = new ReusableFlowPublishCommand(
                ReusableFlowPublishCommand.SCHEMA_VERSION, saved.draft().subject());

        ReusableFlowPublishResult first = module.publish(
                SCOPE, "alice", "customer-tool", "publish-1", publish);
        ReusableFlowPublishResult replay = module.publish(
                SCOPE, "alice", "customer-tool", "publish-1", publish);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(first.version().source()).isEqualTo(new ReusableFlowVersion.Source(
                saved.draft().draftId(), 1, saved.draft().fingerprint()));
        assertThat(first.version().graph()).isEqualTo(saved.draft().graph());
        assertThat(module.findVersion(SCOPE, first.version().publicationId(), 1))
                .contains(first.version());
    }

    @Test
    void laterPublishUsesStablePublicationIdentityAndExactSourceLineage() {
        ReusableFlowModule module = publishingModule();
        ReusableFlowSaveResult created = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.create(), "create-1", command("Customer tool", 0));
        ReusableFlowPublishResult first = module.publish(SCOPE, "alice", "customer-tool", "publish-1",
                new ReusableFlowPublishCommand(null, created.draft().subject()));
        ReusableFlowSaveResult moved = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.match(1), "move-1", command("Customer tool", 240));
        ReusableFlowPublishResult second = module.publish(SCOPE, "alice", "customer-tool", "publish-2",
                new ReusableFlowPublishCommand(null, moved.draft().subject()));

        assertThat(second.version().publicationId()).isEqualTo(first.version().publicationId());
        assertThat(second.version().revision()).isEqualTo(2);
        assertThat(moved.draft().fingerprint()).isEqualTo(created.draft().fingerprint());
        assertThat(second.version().fingerprint()).isNotEqualTo(first.version().fingerprint());
        assertThat(second.version().source().revision()).isEqualTo(2);
    }

    @Test
    void publishRejectsDriftedOrMissingDraftBeforePublicationStoreMutation() {
        ReusableFlowModule module = publishingModule();
        ReusableFlowSaveResult saved = module.save(SCOPE, "alice", "customer-tool",
                ExpectedRevision.create(), "create-1", command("Customer tool", 0));
        assertThatThrownBy(() -> module.publish(SCOPE, "alice", "customer-tool", "publish-1",
                new ReusableFlowPublishCommand(null,
                        new com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef.FlowDraft(
                                saved.draft().draftId(), 1, "sha256:" + "b".repeat(64)))))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.DEPENDENCY_DRIFT);
        assertThatThrownBy(() -> module.publish(SCOPE, "alice", "missing", "publish-1",
                new ReusableFlowPublishCommand(null, saved.draft().subject())))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.NOT_FOUND);
    }

    private static ReusableFlowModule module() {
        return module(null);
    }

    private static ReusableFlowModule publishingModule() {
        return module(new InMemoryReusableFlowPublicationStore(
                () -> "publication-customer-tool", java.time.Clock.fixed(
                        java.time.Instant.parse("2026-09-01T00:00:00Z"), java.time.ZoneOffset.UTC)));
    }

    private static ReusableFlowModule module(ReusableFlowPublicationStore publications) {
        ComposableDefinition dependency = new ComposableDefinition(
                new ReusableFlowCommand.ComposableRef.ApiResource("customer.profile", 3, DEPENDENCY),
                schema("customerId"), schema("tier"));
        ReusableFlowCompiler compiler = new ReusableFlowCompiler((scope, reference) ->
                reference.equals(dependency.reference()) ? Optional.of(dependency) : Optional.empty());
        return new ReusableFlowModule(compiler, new InMemoryReusableFlowDraftStore(), publications);
    }

    private static ReusableFlowCommand command(String displayName, double x) {
        ReusableFlowCommand.Node node = new ReusableFlowCommand.Node("profile", "Customer profile",
                new ReusableFlowCommand.ComposableRef.ApiResource("customer.profile", 3, DEPENDENCY),
                List.of(new ReusableFlowCommand.Input("$.customerId",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.customerId"))));
        return new ReusableFlowCommand(ReusableFlowCommand.SCHEMA_VERSION,
                new ReusableFlowCommand.Flow(displayName, ReusableFlowCommand.Kind.TOOL, "Reusable profile lookup",
                        new ReusableFlowCommand.Contract(schema("customerId"), schema("tier")),
                        new ReusableFlowCommand.Graph(List.of(node),
                                new ReusableFlowCommand.Output("profile", "$")),
                        new ReusableFlowCommand.Layout(Map.of("profile",
                                new ReusableFlowCommand.Position(x, 120)))));
    }

    private static SchemaEnvelope schema(String property) {
        return SchemaEnvelope.object(Map.of(property, Map.of("type", "string")), List.of(property));
    }
}
