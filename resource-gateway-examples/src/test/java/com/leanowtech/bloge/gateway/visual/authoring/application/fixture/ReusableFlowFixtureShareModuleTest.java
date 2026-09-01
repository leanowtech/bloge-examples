package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

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

class ReusableFlowFixtureShareModuleTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");

    @Test
    void protectsEveryInlineReturnAndReplaysBeforeWriterInvocation() {
        ReusableFlowVersion version = version();
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        GeneratedDefaultFixture source = source(version);
        var saved = store.save(new StandaloneFixtureSetSaveIntent(
                SCOPE, "author", "cases", ExpectedRevision.create(), "save",
                fingerprint('1'), source));
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        when(publications.findVersion(SCOPE, version.publicationId(), version.revision()))
                .thenReturn(java.util.Optional.of(version));
        AtomicInteger writes = new AtomicInteger();
        FixtureSetShareMaterialWriter writer = (request, identity) -> {
            writes.incrementAndGet();
            assertThat(request.payload()).isEqualTo(output());
            assertThat(request.policy().classification()).isEqualTo("CONFIDENTIAL");
            return new FixtureSetShareMaterialWriter.Result(
                    new FixtureSetCommand.Material.FixtureAsset(
                            request.fixtureAssetId(), 2, fingerprint('a')), output());
        };
        ReusableFlowFixtureShareModule module = new ReusableFlowFixtureShareModule(
                store, publications, writer);
        FixtureShareCommand share = shareCommand(source);

        var result = module.share(identity(), "cases", saved.strongEtag(), "share", share);
        var replay = module.share(identity(), "cases", saved.strongEtag(), "share", share);

        assertThat(result.view().status().name()).isEqualTo("SHARING_PENDING");
        assertThat(result.view().cases().getFirst().controls().getFirst().behavior())
                .isInstanceOfSatisfying(FixtureSetCommand.Behavior.Return.class,
                        returned -> assertThat(returned.material())
                                .isInstanceOf(FixtureSetCommand.Material.FixtureAsset.class));
        assertThat(result.view().cases().getFirst().expect().output()).isEqualTo(output());
        assertThat(replay.replayed()).isTrue();
        assertThat(writes).hasValue(1);
    }

    @Test
    void unavailableWriterAndNonSubjectReturnFailClosed() {
        ReusableFlowVersion version = version();
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        GeneratedDefaultFixture source = source(version);
        var saved = store.save(new StandaloneFixtureSetSaveIntent(
                SCOPE, "author", "cases", ExpectedRevision.create(), "save",
                fingerprint('1'), source));
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        when(publications.findVersion(SCOPE, version.publicationId(), version.revision()))
                .thenReturn(java.util.Optional.of(version));
        ReusableFlowFixtureShareModule unavailable = new ReusableFlowFixtureShareModule(
                store, publications, FixtureSetShareMaterialWriter.unavailable());

        assertCode(() -> unavailable.share(identity(), "cases", saved.strongEtag(), "share",
                shareCommand(source)), ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        assertThat(store.findHead(SCOPE, "cases")).get()
                .extracting(value -> value.generated().view().revision()).isEqualTo(1);
    }

    private static GeneratedDefaultFixture source(ReusableFlowVersion version) {
        FixtureSetCommand template = command(version.subject(), FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output())),
                FixtureSetCommand.Fidelity.OUTPUT_LEVEL);
        return new WholeFlowFixtureMaterializer().generate("cases", version, template);
    }

    private static FixtureShareCommand shareCommand(GeneratedDefaultFixture source) {
        return new FixtureShareCommand(FixtureShareCommand.SCHEMA_VERSION,
                new FixtureShareCommand.Source("cases", source.view().revision(),
                        source.view().fingerprint(), source.view().statusRevision()),
                new FixtureShareCommand.Policy("CONFIDENTIAL", 30,
                        new FixtureShareCommand.Redaction("default-v1", List.of("/email"))));
    }

    private static FixtureShareIdentity identity() {
        return new FixtureShareIdentity(
                SCOPE, "org", "sg", "USER", "author", "", "corr");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   ApiFixtureSetAuthoringFailure.Code code) {
        assertThatThrownBy(call).isInstanceOf(ApiFixtureSetAuthoringFailure.class)
                .extracting("code").isEqualTo(code);
    }
}
