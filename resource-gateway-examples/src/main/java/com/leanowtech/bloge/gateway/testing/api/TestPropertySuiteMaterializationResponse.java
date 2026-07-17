package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Exact immutable V4 asset produced from one reviewed seeded property plan.
 *
 * @param schemaVersion response protocol version
 * @param materializationFingerprint canonical response-material fingerprint
 * @param target exact graph or operator target
 * @param inputSchemaFingerprint exact projected input-schema fingerprint
 * @param propertyPlanFingerprint exact source property-plan fingerprint
 * @param sourcePlanStatus generated or partial source plan status
 * @param generationGapsAccepted whether the caller explicitly accepted source gaps
 * @param generationPolicy exact seed and generator resource bounds
 * @param rootTrialIds root trial ids in source-plan order
 * @param caseIds complete root-plus-shrink closure in execution order
 * @param fixtureRef exact assertion-bearing fixture shared by all cases
 * @param suiteRef exact immutable V4 suite revision
 */
public record TestPropertySuiteMaterializationResponse(
        String schemaVersion,
        String materializationFingerprint,
        TestExecutionApiRequest.Target target,
        String inputSchemaFingerprint,
        String propertyPlanFingerprint,
        TestPropertyCasePlan.Status sourcePlanStatus,
        boolean generationGapsAccepted,
        TestSuiteV4.PropertyGenerationPolicy generationPolicy,
        List<String> rootTrialIds,
        List<String> caseIds,
        TestSuite.FixtureBundleRef fixtureRef,
        TestSuiteExecutionRequest.SuiteRef suiteRef
) {
    /** Current property-suite materialization response version. */
    public static final String SCHEMA_VERSION = "bloge.testPropertySuiteMaterialization.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Freezes and validates the complete materialization reference closure. */
    public TestPropertySuiteMaterializationResponse {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        materializationFingerprint = normalized(materializationFingerprint);
        inputSchemaFingerprint = normalized(inputSchemaFingerprint);
        propertyPlanFingerprint = normalized(propertyPlanFingerprint);
        rootTrialIds = rootTrialIds == null ? List.of() : List.copyOf(rootTrialIds);
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        if (!SCHEMA_VERSION.equals(schemaVersion) || target == null || sourcePlanStatus == null
                || sourcePlanStatus == TestPropertyCasePlan.Status.UNAVAILABLE
                || generationGapsAccepted != (sourcePlanStatus == TestPropertyCasePlan.Status.PARTIAL)
                || generationPolicy == null || rootTrialIds.isEmpty() || caseIds.isEmpty()
                || rootTrialIds.size() > 16 || caseIds.size() > 96
                || rootTrialIds.size() > caseIds.size()
                || invalidIds(rootTrialIds) || invalidIds(caseIds)
                || fixtureRef == null || fixtureRef.revision() <= 0
                || suiteRef == null || suiteRef.revision() <= 0
                || !FINGERPRINT.matcher(target.fingerprint()).matches()
                || !FINGERPRINT.matcher(materializationFingerprint).matches()
                || !FINGERPRINT.matcher(inputSchemaFingerprint).matches()
                || !FINGERPRINT.matcher(propertyPlanFingerprint).matches()
                || !FINGERPRINT.matcher(fixtureRef.fingerprint()).matches()
                || !FINGERPRINT.matcher(suiteRef.fingerprint()).matches()) {
            throw new IllegalArgumentException("Property-suite materialization response is incomplete");
        }
    }

    private static boolean invalidIds(List<String> values) {
        return values.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > 128) || new LinkedHashSet<>(values).size() != values.size();
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
