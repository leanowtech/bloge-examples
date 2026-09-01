package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.InMemoryStandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSaveIntent;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.command;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.output;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReusableFlowFixtureReviewModuleTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");

    @Test
    void reviewerActivatesExactAssetsAndReplayDoesNotRepeatGovernanceWrites() {
        ReusableFlowVersion version = version();
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        GeneratedDefaultFixture source = new WholeFlowFixtureMaterializer().generate("cases", version,
                command(version.subject(), FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(
                                FixtureSetCommand.Material.inline(output())),
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL));
        var saved = store.save(new StandaloneFixtureSetSaveIntent(
                SCOPE, "author", "cases", ExpectedRevision.create(), "save",
                fingerprint('1'), source));
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        when(publications.findVersion(SCOPE, version.publicationId(), version.revision()))
                .thenReturn(java.util.Optional.of(version));
        var shared = new ReusableFlowFixtureShareModule(store, publications,
                (request, identity) -> new FixtureSetShareMaterialWriter.Result(
                        new FixtureSetCommand.Material.FixtureAsset(
                                request.fixtureAssetId(), 2, fingerprint('a')), output())).share(
                identity("author"), "cases", saved.strongEtag(), "share", share(source));
        AtomicInteger reviews = new AtomicInteger();
        var module = new ReusableFlowFixtureReviewModule(store, (request, reviewer) -> {
            reviews.incrementAndGet();
            assertThat(reviewer.actorId()).isEqualTo("reviewer");
            assertThat(request.proposedAssets()).hasSize(1);
            var proposed = request.proposedAssets().getFirst();
            return List.of(new FixtureSetCommand.Material.FixtureAsset(
                    proposed.fixtureAssetId(), 5, proposed.schemaFingerprint()));
        });
        FixtureReviewCommand command = review(shared);

        var completed = module.review(identity("reviewer"), "cases", shared.strongEtag(),
                "review", command);
        var replay = module.review(identity("reviewer"), "cases", shared.strongEtag(),
                "review", command);

        assertThat(completed.view().status().name()).isEqualTo("TEAM_AVAILABLE");
        assertThat(completed.view().revision()).isEqualTo(3);
        assertThat(completed.receipt().activatedAssetCount()).isEqualTo(1);
        assertThat(((FixtureSetCommand.Material.FixtureAsset)
                ((FixtureSetCommand.Behavior.Return) completed.view().cases().getFirst()
                        .controls().getFirst().behavior()).material()).revision()).isEqualTo(5);
        assertThat(replay.replayed()).isTrue();
        assertThat(reviews).hasValue(1);
    }

    @Test
    void creatorCannotReviewItsOwnPendingFixture() {
        ReusableFlowVersion version = version();
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        GeneratedDefaultFixture source = new WholeFlowFixtureMaterializer().generate("cases", version,
                command(version.subject(), FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(
                                FixtureSetCommand.Material.inline(output())),
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL));
        var saved = store.save(new StandaloneFixtureSetSaveIntent(
                SCOPE, "author", "cases", ExpectedRevision.create(), "save",
                fingerprint('1'), source));
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        when(publications.findVersion(SCOPE, version.publicationId(), version.revision()))
                .thenReturn(java.util.Optional.of(version));
        var shared = new ReusableFlowFixtureShareModule(store, publications,
                (request, identity) -> new FixtureSetShareMaterialWriter.Result(
                        new FixtureSetCommand.Material.FixtureAsset(
                                request.fixtureAssetId(), 2, fingerprint('a')), output())).share(
                identity("author"), "cases", saved.strongEtag(), "share", share(source));
        var module = new ReusableFlowFixtureReviewModule(store, (request, reviewer) -> List.of());

        assertThatThrownBy(() -> module.review(identity("author"), "cases", shared.strongEtag(),
                "review", review(shared))).isInstanceOf(ApiFixtureSetAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiFixtureSetAuthoringFailure.Code.CAS_MISMATCH);
    }

    private static FixtureShareCommand share(GeneratedDefaultFixture source) {
        return new FixtureShareCommand(FixtureShareCommand.SCHEMA_VERSION,
                new FixtureShareCommand.Source("cases", 1, source.view().fingerprint(), 1),
                new FixtureShareCommand.Policy("CONFIDENTIAL", 30,
                        new FixtureShareCommand.Redaction("default-v1", List.of("/email"))));
    }

    private static FixtureReviewCommand review(
            com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence
                    .StandaloneFixtureSetShareResult shared) {
        return new FixtureReviewCommand(FixtureReviewCommand.SCHEMA_VERSION,
                new FixtureReviewCommand.Source(shared.receipt().reviewRequestId(), "cases",
                        shared.view().revision(), shared.view().fingerprint(),
                        shared.view().statusRevision()),
                new FixtureReviewCommand.Attestations(true, true, true, "Reviewed"));
    }

    private static FixtureShareIdentity identity(String actor) {
        return new FixtureShareIdentity(SCOPE, "org", "sg", "USER", actor, "", "corr");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
