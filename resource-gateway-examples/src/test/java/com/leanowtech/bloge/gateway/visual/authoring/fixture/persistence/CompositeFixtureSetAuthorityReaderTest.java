package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.command;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.output;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CompositeFixtureSetAuthorityReaderTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");

    @Test
    void combinesDistinctAuthoritiesAndRejectsAmbiguousIds() {
        InMemoryStandaloneFixtureSetStore first = store("fixture-a", "key-a");
        InMemoryStandaloneFixtureSetStore second = store("fixture-b", "key-b");
        CompositeFixtureSetAuthorityReader reader = new CompositeFixtureSetAuthorityReader(
                List.of(first, second));

        assertThat(reader.findHead(SCOPE, "fixture-a")).isPresent();
        assertThat(reader.findHead(SCOPE, "fixture-b")).isPresent();
        assertThat(reader.listSummariesBySubject(SCOPE, version().subject()))
                .extracting(summary -> summary.fixtureSetId()).containsExactly("fixture-a", "fixture-b");

        InMemoryStandaloneFixtureSetStore duplicate = store("fixture-a", "key-c");
        CompositeFixtureSetAuthorityReader ambiguous = new CompositeFixtureSetAuthorityReader(
                List.of(first, duplicate));
        assertThatThrownBy(() -> ambiguous.findHead(SCOPE, "fixture-a"))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class);
    }

    private static InMemoryStandaloneFixtureSetStore store(String id, String key) {
        InMemoryStandaloneFixtureSetStore store = new InMemoryStandaloneFixtureSetStore();
        GeneratedDefaultFixture generated = new WholeFlowFixtureMaterializer().generate(id, version(),
                command(version().subject(), com.leanowtech.bloge.gateway.visual.authoring.fixture
                                .FixtureSetCommand.Target.subject(),
                        com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Behavior
                                .returned(com.leanowtech.bloge.gateway.visual.authoring.fixture
                                        .FixtureSetCommand.Material.inline(output())), null));
        store.save(new StandaloneFixtureSetSaveIntent(SCOPE, "author", id,
                ExpectedRevision.create(), key,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", generated));
        return store;
    }
}
