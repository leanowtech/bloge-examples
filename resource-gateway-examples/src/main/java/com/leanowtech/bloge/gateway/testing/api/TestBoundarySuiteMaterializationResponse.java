package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuite;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Exact immutable assets produced from one reviewed boundary plan.
 *
 * @param schemaVersion response protocol version
 * @param materializationFingerprint canonical response-material fingerprint
 * @param target exact graph or operator target
 * @param inputSchemaFingerprint exact projected input-schema fingerprint
 * @param boundaryPlanFingerprint exact source boundary-plan fingerprint
 * @param sourcePlanStatus generated or partial source plan status
 * @param coverageGapsAccepted whether the caller explicitly accepted source gaps
 * @param selectedCaseIds selected cases in deterministic source-plan order
 * @param fixtureRef inert provenance fixture shared by all cases
 * @param suiteRef exact immutable v3 suite revision
 */
public record TestBoundarySuiteMaterializationResponse(
        String schemaVersion,
        String materializationFingerprint,
        TestExecutionApiRequest.Target target,
        String inputSchemaFingerprint,
        String boundaryPlanFingerprint,
        TestBoundaryCasePlan.Status sourcePlanStatus,
        boolean coverageGapsAccepted,
        List<String> selectedCaseIds,
        TestSuite.FixtureBundleRef fixtureRef,
        TestSuiteExecutionRequest.SuiteRef suiteRef
) {
    /** Current materialization response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testBoundarySuiteMaterialization.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Normalizes and validates exact materialization references. */
    public TestBoundarySuiteMaterializationResponse {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        materializationFingerprint = normalized(materializationFingerprint);
        inputSchemaFingerprint = normalized(inputSchemaFingerprint);
        boundaryPlanFingerprint = normalized(boundaryPlanFingerprint);
        selectedCaseIds = selectedCaseIds == null ? List.of() : List.copyOf(selectedCaseIds);
        if (!SCHEMA_VERSION.equals(schemaVersion) || target == null || sourcePlanStatus == null
                || selectedCaseIds.isEmpty() || fixtureRef == null || suiteRef == null
                || sourcePlanStatus == TestBoundaryCasePlan.Status.UNAVAILABLE
                || coverageGapsAccepted != (sourcePlanStatus == TestBoundaryCasePlan.Status.PARTIAL)
                || selectedCaseIds.size() > 64
                || selectedCaseIds.stream().anyMatch(caseId -> caseId == null
                || caseId.isBlank() || caseId.length() > 128)
                || new java.util.LinkedHashSet<>(selectedCaseIds).size() != selectedCaseIds.size()
                || !FINGERPRINT.matcher(target.fingerprint()).matches()
                || fixtureRef.revision() <= 0 || suiteRef.revision() <= 0
                || !FINGERPRINT.matcher(fixtureRef.fingerprint()).matches()
                || !FINGERPRINT.matcher(suiteRef.fingerprint()).matches()
                || !FINGERPRINT.matcher(materializationFingerprint).matches()
                || !FINGERPRINT.matcher(inputSchemaFingerprint).matches()
                || !FINGERPRINT.matcher(boundaryPlanFingerprint).matches()) {
            throw new IllegalArgumentException("Boundary-suite materialization response is incomplete");
        }
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
