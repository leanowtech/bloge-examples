package com.leanowtech.bloge.gateway.testing.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable suite generation for schema-admission boundary verification.
 *
 * <p>V3 is deliberately separate from executable v1/v2 suites. It binds every selected case to
 * one explicit admission expectation and to the exact boundary-plan and input-schema fingerprints
 * reviewed by the author. The extra semantics are first-class canonical fields rather than
 * metadata, preserving historical fingerprints while preventing a case label from masquerading as
 * an expected rejection.</p>
 *
 * @param schemaVersion exact v3 suite schema version
 * @param suiteId stable suite identifier
 * @param revision immutable suite revision
 * @param target exact graph or operator target
 * @param classification maximum data classification
 * @param cases ordered boundary inputs
 * @param coveragePolicy schema-boundary composition policy
 * @param semanticCoveragePolicy orchestration policy, empty for admission-only suites
 * @param promotionPolicy policy retained for common registry shape; v3 cannot prove business promotion
 * @param evaluationMode fixed admission-only evaluation mode
 * @param boundaryPlanFingerprint exact reviewed boundary-plan fingerprint
 * @param inputSchemaFingerprint exact projected input-schema fingerprint
 * @param admissionExpectations case-id keyed expected admission outcomes
 * @param metadata bounded provenance facts
 */
public record TestSuiteV3(
        String schemaVersion,
        String suiteId,
        long revision,
        TestSuite.Target target,
        String classification,
        List<TestSuite.TestCase> cases,
        TestSuite.CoveragePolicy coveragePolicy,
        SemanticCoveragePolicy semanticCoveragePolicy,
        TestSuite.PromotionPolicy promotionPolicy,
        EvaluationMode evaluationMode,
        String boundaryPlanFingerprint,
        String inputSchemaFingerprint,
        Map<String, AdmissionExpectation> admissionExpectations,
        Map<String, Object> metadata
) implements TestSuiteProtocol {
    /** Current schema-admission suite protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuite.v3";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Normalizes common fields and freezes expectation/provenance collections. */
    public TestSuiteV3 {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteId = normalized(suiteId);
        classification = defaulted(classification, "INTERNAL").toUpperCase(Locale.ROOT);
        cases = cases == null ? List.of() : List.copyOf(cases);
        coveragePolicy = coveragePolicy == null
                ? TestSuite.CoveragePolicy.defaults() : coveragePolicy;
        semanticCoveragePolicy = semanticCoveragePolicy == null
                ? SemanticCoveragePolicy.empty() : semanticCoveragePolicy;
        promotionPolicy = promotionPolicy == null
                ? new TestSuite.PromotionPolicy(true, 0, false) : promotionPolicy;
        evaluationMode = evaluationMode == null
                ? EvaluationMode.SCHEMA_ADMISSION : evaluationMode;
        boundaryPlanFingerprint = normalized(boundaryPlanFingerprint);
        inputSchemaFingerprint = normalized(inputSchemaFingerprint);
        admissionExpectations = immutableExpectations(admissionExpectations);
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        if (!FINGERPRINT.matcher(boundaryPlanFingerprint).matches()
                || !FINGERPRINT.matcher(inputSchemaFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Schema-admission suite requires canonical plan and input-schema fingerprints");
        }
    }

    /** Evaluation modes owned by v3. */
    public enum EvaluationMode {
        /** Validate input admission only; never invoke the DAG or operator. */
        SCHEMA_ADMISSION
    }

    /** Expected schema-admission outcomes. */
    public enum ExpectedOutcome {
        /** The exact input must be accepted by the target's current frozen input contract. */
        ACCEPTED,
        /** The exact input must be rejected with the declared diagnostic family. */
        SCHEMA_REJECTED
    }

    /**
     * One case's expected schema-admission result.
     *
     * @param expectedOutcome accepted or schema-rejected
     * @param validationCodes stable expected validator diagnostic codes
     */
    public record AdmissionExpectation(
            ExpectedOutcome expectedOutcome,
            List<String> validationCodes
    ) {
        /** Canonicalizes diagnostics and enforces outcome/code consistency. */
        public AdmissionExpectation {
            expectedOutcome = Objects.requireNonNull(expectedOutcome, "expectedOutcome");
            validationCodes = sortedStrings(validationCodes);
            if (expectedOutcome == ExpectedOutcome.ACCEPTED && !validationCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "Accepted admission expectation cannot require validation errors");
            }
            if (expectedOutcome == ExpectedOutcome.SCHEMA_REJECTED
                    && validationCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "Rejected admission expectation requires stable validation codes");
            }
        }
    }

    private static Map<String, AdmissionExpectation> immutableExpectations(
            Map<String, AdmissionExpectation> values) {
        if (values == null) {
            return Map.of();
        }
        LinkedHashMap<String, AdmissionExpectation> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(String::compareTo)))
                .forEach(entry -> {
                    String caseId = normalized(entry.getKey());
                    if (caseId.isBlank() || entry.getValue() == null
                            || sorted.putIfAbsent(caseId, entry.getValue()) != null) {
                        throw new IllegalArgumentException(
                                "Admission expectations require unique non-empty case ids and values");
                    }
                });
        return Collections.unmodifiableMap(sorted);
    }

    private static List<String> sortedStrings(List<String> values) {
        Objects.requireNonNull(values, "validationCodes");
        List<String> normalizedValues = values.stream().map(TestSuiteV3::normalized).toList();
        if (normalizedValues.stream().anyMatch(String::isBlank)
                || new LinkedHashSet<>(normalizedValues).size() != normalizedValues.size()) {
            throw new IllegalArgumentException(
                    "Admission validation codes must be unique non-empty identifiers");
        }
        List<String> sorted = new ArrayList<>(normalizedValues);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
