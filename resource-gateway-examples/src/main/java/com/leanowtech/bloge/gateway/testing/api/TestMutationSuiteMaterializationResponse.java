package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Content-addressed result of exact mutation-suite materialization.
 *
 * @param schemaVersion exact response protocol version
 * @param materializationFingerprint canonical response-material fingerprint
 * @param target exact baseline graph target
 * @param baselineSourceFingerprint exact recoverable source identity
 * @param baselineGraphArtifactFingerprint exact graph-artifact identity
 * @param mutationPlanFingerprint exact reviewed plan identity
 * @param sourcePlanStatus complete or explicitly accepted partial source plan
 * @param planningGapsAccepted whether partial-plan gaps were explicitly accepted
 * @param mutationPolicy exact deterministic generation policy
 * @param mutantIds complete ordered frozen mutant closure
 * @param oracleCaseIds complete ordered business-oracle case closure
 * @param mutantCaseExecutions bounded mutant-case work units
 * @param oracleSuiteRef exact source business suite
 * @param suiteRef exact immutable V5 suite revision
 */
public record TestMutationSuiteMaterializationResponse(
        String schemaVersion,
        String materializationFingerprint,
        TestExecutionApiRequest.Target target,
        String baselineSourceFingerprint,
        String baselineGraphArtifactFingerprint,
        String mutationPlanFingerprint,
        TestMutationCasePlan.Status sourcePlanStatus,
        boolean planningGapsAccepted,
        TestSuiteV5.MutationPolicy mutationPolicy,
        List<String> mutantIds,
        List<String> oracleCaseIds,
        int mutantCaseExecutions,
        TestSuiteExecutionRequest.SuiteRef oracleSuiteRef,
        TestSuiteExecutionRequest.SuiteRef suiteRef
) {
    /** Current mutation-suite materialization response version. */
    public static final String SCHEMA_VERSION = "bloge.testMutationSuiteMaterialization.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Freezes and validates the complete materialized asset closure. */
    public TestMutationSuiteMaterializationResponse {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        materializationFingerprint = normalized(materializationFingerprint);
        baselineSourceFingerprint = normalized(baselineSourceFingerprint);
        baselineGraphArtifactFingerprint = normalized(baselineGraphArtifactFingerprint);
        mutationPlanFingerprint = normalized(mutationPlanFingerprint);
        mutantIds = mutantIds == null ? List.of() : List.copyOf(mutantIds);
        oracleCaseIds = oracleCaseIds == null ? List.of() : List.copyOf(oracleCaseIds);
        if (!SCHEMA_VERSION.equals(schemaVersion) || target == null || sourcePlanStatus == null
                || sourcePlanStatus == TestMutationCasePlan.Status.UNAVAILABLE
                || planningGapsAccepted != (sourcePlanStatus == TestMutationCasePlan.Status.PARTIAL)
                || mutationPolicy == null || mutantIds.isEmpty() || oracleCaseIds.isEmpty()
                || mutantIds.size() > TestSuiteV5.MAX_MUTANTS
                || oracleCaseIds.size() > TestSuiteV5.MAX_CASES
                || invalidIds(mutantIds) || invalidIds(oracleCaseIds)
                || mutantCaseExecutions != mutantIds.size() * oracleCaseIds.size()
                || mutantCaseExecutions > TestSuiteV5.MAX_MUTANT_CASE_EXECUTIONS
                || oracleSuiteRef == null || oracleSuiteRef.revision() <= 0
                || suiteRef == null || suiteRef.revision() <= 0
                || !fingerprint(target.fingerprint())
                || !fingerprint(materializationFingerprint)
                || !fingerprint(baselineSourceFingerprint)
                || !fingerprint(baselineGraphArtifactFingerprint)
                || !fingerprint(mutationPlanFingerprint)
                || !fingerprint(oracleSuiteRef.fingerprint())
                || !fingerprint(suiteRef.fingerprint())) {
            throw new IllegalArgumentException(
                    "Mutation-suite materialization response is incomplete");
        }
    }

    private static boolean invalidIds(List<String> values) {
        return values.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > 255)
                || new LinkedHashSet<>(values).size() != values.size();
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
