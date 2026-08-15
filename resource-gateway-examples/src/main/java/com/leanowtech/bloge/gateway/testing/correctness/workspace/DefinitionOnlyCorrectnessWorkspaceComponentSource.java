package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CasePage;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CommandPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CoverageSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.FixtureCatalogSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.OracleAssertionSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.ReviewSummary;

import java.util.List;

/** Honest shadow source used before later correctness aggregate projections are installed. */
public final class DefinitionOnlyCorrectnessWorkspaceComponentSource
        implements CorrectnessWorkspaceComponentSource {

    @Override
    public Components load(Coordinate coordinate, PageRequest pageRequest) {
        CorrectnessVerdict verdict = new CorrectnessVerdict(
                CorrectnessVerdict.ExecutionVerdict.NOT_RUN,
                CorrectnessVerdict.AssertionVerdict.NONE,
                CorrectnessVerdict.CoverageVerdict.NOT_EVALUATED,
                CorrectnessVerdict.EvidenceVerdict.NONE,
                CorrectnessVerdict.GateVerdict.BLOCKED,
                CorrectnessVerdict.ProofLevel.STRUCTURAL,
                List.of(new CorrectnessVerdict.Reason(
                        "AUTHORING_ASSETS_UNAVAILABLE", "GATE",
                        "correctness.authoringAssets.unavailable")),
                List.of(new CorrectnessVerdict.Remediation(
                        "OPEN_COVERAGE_INVENTORY", "AUTHORING_ASSETS_UNAVAILABLE")));
        return new Components(
                CoverageSummary.unavailable(),
                OracleAssertionSummary.unavailable(),
                new CasePage(
                        Availability.UNAVAILABLE, null, 0, List.of(), "",
                        pageRequest.queryFingerprint()),
                FixtureCatalogSummary.unavailable(),
                ReviewSummary.empty(), null, null, verdict, List.of(),
                List.of("CORRECTNESS_DEFINITION_READ_V1"), CommandPolicy.readOnly());
    }
}
