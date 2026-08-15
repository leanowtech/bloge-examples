package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository.FixtureReferenceUsage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixtureUsageProjectionServiceTest {

    @Test
    void rebuildsEveryCurrentConsumerIncludingThoseWithNoFixture() {
        ScenarioDraftSetV2Repository scenarios = mock(ScenarioDraftSetV2Repository.class);
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        ExactAssetRef setA = ref("SCENARIO_DRAFT_SET", "set-a", '1');
        ExactAssetRef setB = ref("SCENARIO_DRAFT_SET", "set-b", '2');
        ExactAssetRef fixture = ref("FIXTURE_ASSET", "profile", '3');
        when(scenarios.currentDraftSetRefsByTarget(scope(), target()))
                .thenReturn(List.of(setA, setB));
        when(scenarios.fixtureUsagesByTarget(scope(), target()))
                .thenReturn(List.of(new FixtureReferenceUsage(setA, fixture)));
        var projector = new FixtureUsageProjectionService(scenarios, fixtures);

        var result = projector.rebuild(scope(), target());

        assertThat(result).isEqualTo(
                new FixtureUsageProjectionService.ProjectionResult(2, 1));
        verify(fixtures).replaceUsageForConsumer(scope(), setA, List.of(fixture));
        verify(fixtures).replaceUsageForConsumer(scope(), setB, List.of());
    }

    @Test
    void rejectsAnInconsistentUsageProjection() {
        ScenarioDraftSetV2Repository scenarios = mock(ScenarioDraftSetV2Repository.class);
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        ExactAssetRef staleConsumer = ref("SCENARIO_DRAFT_SET", "old-set", '4');
        when(scenarios.currentDraftSetRefsByTarget(scope(), target())).thenReturn(List.of());
        when(scenarios.fixtureUsagesByTarget(scope(), target())).thenReturn(List.of(
                new FixtureReferenceUsage(
                        staleConsumer, ref("FIXTURE_ASSET", "profile", '5'))));

        assertThatThrownBy(() -> new FixtureUsageProjectionService(scenarios, fixtures)
                .rebuild(scope(), target()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-current");
    }

    private static ExactAssetRef ref(String kind, String id, char seed) {
        return new ExactAssetRef(kind, id, 2, fingerprint(seed));
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
