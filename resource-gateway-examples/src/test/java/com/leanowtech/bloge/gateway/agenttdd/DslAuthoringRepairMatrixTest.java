package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the v1.4.2 twenty-row repair matrix bound to executable behavior tests.
 *
 * <p>The referenced tests own the RED assertion and either a corrected GREEN candidate or the
 * exact governed stop condition. This index fails when a row is removed or casually renamed, so
 * design coverage cannot silently shrink while individual focused suites remain green.</p>
 */
class DslAuthoringRepairMatrixTest {

    @Test
    void everyRequiredRepairClassNamesAnExecutableBehaviorTest() throws Exception {
        Map<Integer, Evidence> matrix = new LinkedHashMap<>();
        matrix.put(1, evidence(AgentDslAuthoringSupportTest.class,
                "returnsSafeParseGuidanceThenAcceptsTheCorrectedCandidate"));
        matrix.put(2, evidence(AgentDslAuthoringSupportTest.class,
                "classifiesReservedDeclarationIdentifiersWithoutEchoingTheKeyword"));
        matrix.put(3, evidence(AgentDslAuthoringSupportTest.class,
                "rejectsUnsupportedRootsAndUnknownFunctionsThenAcceptsGraphCorrections"));
        matrix.put(4, evidence(AgentDslAuthoringSupportTest.class,
                "suggestsOnlyVisibleOperatorsAndNeverEchoesRejectedSourceOrLowerMessages"));
        matrix.put(5, evidence(AgentDslAuthoringSupportTest.class,
                "rejectsUnsupportedRootsAndUnknownFunctionsThenAcceptsGraphCorrections"));
        matrix.put(6, evidence(AgentDslAuthoringSupportTest.class,
                "validatesNamedResourceInputsRequiredBindingsAndOutputPathsBeforeAcceptance"));
        matrix.put(7, evidence(AgentDslAuthoringSupportTest.class,
                "validatesNamedResourceInputsRequiredBindingsAndOutputPathsBeforeAcceptance"));
        matrix.put(8, evidence(AgentDslAuthoringSupportTest.class,
                "validatesNamedResourceInputsRequiredBindingsAndOutputPathsBeforeAcceptance"));
        matrix.put(9, evidence(AgentDslAuthoringSupportTest.class,
                "typeChecksPureTransformDeclarationsBeforeAcceptingThem"));
        matrix.put(10, evidence(AgentDslAuthoringSupportTest.class,
                "mapsDecisionTableLintRulesWithoutPassingThroughRuleMessages"));
        matrix.put(11, evidence(AgentDslAuthoringSupportTest.class,
                "mapsDecisionTableLintRulesWithoutPassingThroughRuleMessages"));
        matrix.put(12, evidence(DslSafeDiagnosticRegistryTest.class,
                "classifiesDisallowedEffectsAsGovernedStopsWithoutSuggestingBypass"));
        matrix.put(13, evidence(AgentDslAuthoringSupportTest.class,
                "stopsOnAnUnsupportedProjectionConstructInsteadOfDroppingIt"));
        matrix.put(14, evidence(AgentDslAuthoringSupportTest.class,
                "reportsRoundTripDriftAndAcceptsAProjectionStableCorrection"));
        matrix.put(15, evidence(AgentDslAuthoringSupportTest.class,
                "rejectsARealCatalogDriftThenAcceptsOnlyAfterFetchingTheNewContext"));
        matrix.put(16, evidence(DslSafeDiagnosticRegistryTest.class,
                "neverCopiesLowerMessageMetadataOrBusinessTargetSegments"));
        matrix.put(17, evidence(DslSafeDiagnosticRegistryTest.class,
                "truncationKeepsLateBlockingErrorsAndReportsTheUntruncatedTotals"));
        matrix.put(18, evidence(AgentTddCodexCertificationArtifactTest.class,
                "certificationReducerExecutesMismatchAcceptanceAndBoundedRepairNegativeCases"));
        matrix.put(19, evidence(AgentTddMutationServiceTest.class,
                "mcpComposeRejectsChangedSourceForgedReceiptAndClientGraphWithoutWriting"));
        matrix.put(20, evidence(AgentTddMutationServiceTest.class,
                "mcpComposeRejectsChangedSourceForgedReceiptAndClientGraphWithoutWriting"));

        assertThat(matrix.keySet()).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
        for (Evidence evidence : matrix.values()) {
            Method method = evidence.testClass().getDeclaredMethod(evidence.method());
            assertThat(method.getAnnotation(Test.class))
                    .as("%s#%s", evidence.testClass().getSimpleName(), evidence.method())
                    .isNotNull();
        }
    }

    private static Evidence evidence(Class<?> testClass, String method) {
        return new Evidence(testClass, method);
    }

    private record Evidence(Class<?> testClass, String method) { }
}
