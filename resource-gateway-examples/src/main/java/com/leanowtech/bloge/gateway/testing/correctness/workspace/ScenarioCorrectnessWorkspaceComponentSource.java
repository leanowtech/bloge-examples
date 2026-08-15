package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository.ScenarioCaseSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CasePage;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CaseSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adds the bounded, metadata-only Scenario v2 Matrix to a Workspace projection. */
public final class ScenarioCorrectnessWorkspaceComponentSource
        implements CorrectnessWorkspaceComponentSource {

    private final CorrectnessWorkspaceComponentSource delegate;
    private final ScenarioDraftSetV2Repository scenarios;

    public ScenarioCorrectnessWorkspaceComponentSource(
            CorrectnessWorkspaceComponentSource delegate,
            ScenarioDraftSetV2Repository scenarios
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
    }

    @Override
    public Components load(Coordinate coordinate, PageRequest pageRequest) {
        Components base = delegate.load(coordinate, pageRequest);
        var page = scenarios.pageByTarget(
                coordinate.scope(), coordinate.target(),
                pageRequest.cursor(), pageRequest.limit());
        List<CaseSummary> rows = page.rows().stream()
                .map(ScenarioCorrectnessWorkspaceComponentSource::toWorkspaceRow)
                .toList();
        ExactAssetRef commonRef = page.scenarioDraftSetRefs().size() == 1
                ? page.scenarioDraftSetRefs().getFirst() : null;
        CasePage matrix = new CasePage(
                Availability.AVAILABLE, commonRef, page.total(), rows,
                page.nextCursor(), pageRequest.queryFingerprint());
        List<String> capabilities = new ArrayList<>(base.capabilities());
        capabilities.add("SCENARIO_MATRIX_V2");
        capabilities.add("SCENARIO_COMPOSITE_CURSOR_V2");
        return new Components(
                base.coverage(), base.oracleAssertions(), matrix, base.fixtures(),
                base.reviews(), base.lastPublication(), base.lastRun(), base.verdict(),
                base.staleReasons(), List.copyOf(capabilities), base.commandPolicy());
    }

    private static CaseSummary toWorkspaceRow(ScenarioCaseSummary row) {
        return new CaseSummary(
                row.scenarioDraftSetRef(), row.caseId(), row.caseFingerprint(), row.name(),
                row.businessIntent(), row.caseType(), row.risk(), row.owner(), row.lifecycle(),
                row.obligationCount(), row.oracleCount(), row.assertionSetCount(),
                row.dependencyCount(), row.reviewStatus(), row.tags());
    }
}
