package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.InMemoryStandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveResult;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.command;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.output;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ReusableFlowFixtureModuleTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");

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

    private static ReusableFlowFixtureModule module(
            ReusableFlowVersion version, InMemoryStandaloneFixtureSetStore store) {
        ReusableFlowPublicationStore publications = new ReusableFlowPublicationStore() {
            @Override public com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishResult publish(
                    com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublishIntent intent) {
                throw new UnsupportedOperationException();
            }

            @Override public Optional<ReusableFlowVersion> findVersion(
                    AuthoringScope scope, String publicationId, int revision) {
                return SCOPE.equals(scope) && version.publicationId().equals(publicationId)
                        && version.revision() == revision ? Optional.of(version) : Optional.empty();
            }
        };
        return new ReusableFlowFixtureModule(
                publications, store, new WholeFlowFixtureMaterializer());
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   ApiFixtureSetAuthoringFailure.Code code) {
        assertThatThrownBy(call).isInstanceOf(ApiFixtureSetAuthoringFailure.class)
                .extracting("code").isEqualTo(code);
    }
}
