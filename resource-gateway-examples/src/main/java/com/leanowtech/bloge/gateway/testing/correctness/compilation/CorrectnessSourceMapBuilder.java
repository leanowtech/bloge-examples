package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.OutputCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceMapping;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;

import java.util.List;

/** Materializes exact authoring-to-runtime lineage after content-addressed refs are known. */
final class CorrectnessSourceMapBuilder {

    private CorrectnessSourceMapBuilder() {
    }

    static List<SourceMapping> materialize(
            List<PendingMapping> mappings,
            ExactAssetRef suiteRef
    ) {
        return mappings.stream().map(mapping -> mapping.materialize(suiteRef)).toList();
    }

    record PendingMapping(
            SourceCoordinate source,
            OutputCoordinate exactOutput,
            String suiteElementKind,
            String suiteElementId,
            String fixtureElementKind,
            String fixtureElementId
    ) {
        static PendingMapping exact(SourceCoordinate source, OutputCoordinate output) {
            return new PendingMapping(source, output, "", "", "", "");
        }

        static PendingMapping scenarioToFixture(
                ExactAssetRef sourceRef, String scenarioId, ExactAssetRef fixtureRef) {
            return exact(new SourceCoordinate(sourceRef, "SCENARIO_CASE", scenarioId),
                    new OutputCoordinate(fixtureRef, "FIXTURE_BUNDLE", fixtureRef.id()));
        }

        static PendingMapping scenarioToCase(
                ExactAssetRef sourceRef, String scenarioId, String caseId) {
            return new PendingMapping(
                    new SourceCoordinate(sourceRef, "SCENARIO_CASE", scenarioId),
                    null, "TEST_CASE", caseId, "", "");
        }

        static PendingMapping obligationToCase(
                ExactAssetRef sourceRef, String obligationId, String caseId) {
            return new PendingMapping(
                    new SourceCoordinate(sourceRef, "OBLIGATION", obligationId),
                    null, "TEST_CASE", caseId, "", "");
        }

        static PendingMapping oracleToFixture(
                ExactAssetRef sourceRef, String scenarioId, ExactAssetRef fixtureRef) {
            return exact(new SourceCoordinate(sourceRef, "BUSINESS_ORACLE", sourceRef.id()),
                    new OutputCoordinate(fixtureRef, "CASE_ASSERTION_SET", scenarioId));
        }

        static PendingMapping fixtureToRule(ExactAssetRef sourceRef, String ruleId) {
            return new PendingMapping(
                    new SourceCoordinate(sourceRef, "FIXTURE_VARIANT", sourceRef.id()),
                    null, "", "", "FIXTURE_RULE", ruleId);
        }

        PendingMapping bindFixture(ExactAssetRef fixtureRef) {
            if (exactOutput != null || fixtureElementKind.isEmpty()) return this;
            return exact(source, new OutputCoordinate(
                    fixtureRef, fixtureElementKind, fixtureElementId));
        }

        SourceMapping materialize(ExactAssetRef suiteRef) {
            OutputCoordinate output = exactOutput != null ? exactOutput
                    : new OutputCoordinate(suiteRef, suiteElementKind, suiteElementId);
            return new SourceMapping(source, output);
        }
    }
}
