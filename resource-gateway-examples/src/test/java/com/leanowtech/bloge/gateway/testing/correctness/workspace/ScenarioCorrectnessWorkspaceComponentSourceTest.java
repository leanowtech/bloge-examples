package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository.ScenarioCasePage;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository.ScenarioCaseSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Coordinate;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.PageRequest;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioCorrectnessWorkspaceComponentSourceTest {

    @Test
    void projectsMultiSetRowsWithStableExactCoordinatesAndOpaqueCursor() {
        ScenarioDraftSetV2Repository scenarios = mock(ScenarioDraftSetV2Repository.class);
        ExactAssetRef setA = setRef("set-a", '1');
        ExactAssetRef setB = setRef("set-b", '2');
        when(scenarios.pageByTarget(scope(), target(), "cursor-in", 20))
                .thenReturn(new ScenarioCasePage(
                        2, List.of(row(setA, "shared-case"), row(setB, "shared-case")),
                        "v2.next", List.of(setA, setB)));
        var source = new ScenarioCorrectnessWorkspaceComponentSource(
                new DefinitionOnlyCorrectnessWorkspaceComponentSource(), scenarios);

        var result = source.load(coordinate(), new PageRequest(
                "cursor-in", 20, fingerprint('e')));

        assertThat(result.cases().availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.cases().scenarioDraftSetRef()).isNull();
        assertThat(result.cases().rows())
                .extracting(row -> row.scenarioDraftSetRef().id() + ":" + row.caseId())
                .containsExactly("set-a:shared-case", "set-b:shared-case");
        assertThat(result.cases().nextCursor()).isEqualTo("v2.next");
        assertThat(result.capabilities()).contains(
                "SCENARIO_MATRIX_V2", "SCENARIO_COMPOSITE_CURSOR_V2");
    }

    private ScenarioCaseSummary row(ExactAssetRef setRef, String caseId) {
        return new ScenarioCaseSummary(
                setRef, caseId, fingerprint(setRef.id().endsWith("a") ? '3' : '4'),
                "Shared case", "Prove a business branch", "GOLDEN", RiskLevel.HIGH,
                owner(), "CANONICAL", 1, 1, 1, 2, "APPROVED", List.of("loan"));
    }

    private Coordinate coordinate() {
        return new Coordinate(
                scope(), new ExactAssetRef("DEFINITION", "loan", 1, fingerprint('d')),
                target(), null);
    }

    private static ExactAssetRef setRef(String id, char seed) {
        return new ExactAssetRef("SCENARIO_DRAFT_SET", id, 3, fingerprint(seed));
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static PrincipalRef owner() {
        return new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
