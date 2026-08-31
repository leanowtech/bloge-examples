package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.command;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.output;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WholeFlowSimulationModuleTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

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
        SimulationModule module = new SimulationModule(resources, fixtures, publications,
                new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-flow");

        SimulationRun run = module.run(SCOPE, "whole-flow-run",
                SimulationRequest.fixtureCase("eligibility-cases", 1, "approved"));

        assertThat(run.status()).isEqualTo(SimulationRun.Status.SUCCEEDED);
        assertThat(run.subject()).isEqualTo(version.subject());
        assertThat(run.output()).isEqualTo(output());
        assertThat(run.nodes()).isEmpty();
        assertThat(run.verdicts()).isEqualTo(new SimulationRun.Verdicts(
                SimulationRun.ExecutionVerdict.SIMULATED_ONLY, SimulationRun.Verdict.PASSED,
                SimulationRun.Verdict.PASSED, SimulationRun.Verdict.NOT_CHECKED));
        verifyNoInteractions(resources);
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
        SimulationModule module = new SimulationModule(mock(ApiResourceCommitStore.class), fixtures, publications,
                new InMemorySimulationRunStore(), Clock.fixed(NOW, ZoneOffset.UTC), () -> "sim-flow");

        assertThatThrownBy(() -> module.run(SCOPE, "whole-flow-run",
                SimulationRequest.fixtureCase("eligibility-cases", 1, "approved")))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.INTEGRITY);
    }
}
