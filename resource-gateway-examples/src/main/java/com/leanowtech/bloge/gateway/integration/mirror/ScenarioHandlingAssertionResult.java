package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Content-addressed, payload-free result of evaluating one business handling assertion.
 *
 * <p>The result is not a second source of runtime truth. It binds an exact assertion to one
 * independently verified Mirror Evidence bundle and records only statuses, error codes,
 * fingerprints, sources, counts, durations, booleans, and bounded limitations. A missing evidence
 * fact is represented as {@link Outcome#INDETERMINATE}; it is never promoted to a pass from the
 * whole-run status.</p>
 *
 * @param schemaVersion exact assertion-result protocol version
 * @param resultFingerprint canonical fingerprint with this field blanked
 * @param runId exact Mirror run
 * @param evidenceBundleFingerprint exact independently verified evidence bundle
 * @param planFingerprint exact MirrorPlan generation observed by the run
 * @param assertionRef exact governed handling assertion
 * @param observation evidence dimension evaluated
 * @param outcome pass, fail, or evidence-indeterminate result
 * @param severity governance consequence inherited from the assertion
 * @param governanceCode stable workbook or gate code inherited from the assertion
 * @param reasonCode stable evaluator reason
 * @param observed payload-free facts used by the evaluator
 */
public record ScenarioHandlingAssertionResult(
        String schemaVersion,
        String resultFingerprint,
        String runId,
        String evidenceBundleFingerprint,
        String planFingerprint,
        MirrorArtifactRef assertionRef,
        CaseHandlingAssertion.Observation observation,
        Outcome outcome,
        CaseHandlingAssertion.Severity severity,
        String governanceCode,
        ReasonCode reasonCode,
        ObservedFacts observed
) {
    /** Current payload-free assertion-result protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioHandlingAssertionResult.v1";
    /** Maximum facts retained in any one observed-fact dimension. */
    public static final int MAXIMUM_FACTS = 256;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern MACHINE_VALUE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,191}");

    /** Conservative assertion outcomes consumed by aggregate rehearsal gates. */
    public enum Outcome {
        /** Every required evidence fact was available and matched. */
        PASS,
        /** Required evidence was available and did not match. */
        FAIL,
        /** The signed evidence protocol could not establish the assertion either way. */
        INDETERMINATE
    }

    /** Closed evaluator reasons shared by runtime aggregation and independent consumers. */
    public enum ReasonCode {
        /** Every available fact matched the typed expectation. */
        ASSERTION_MATCHED,
        /** Available facts contradicted the typed expectation. */
        ASSERTION_MISMATCH,
        /** No evidence fact matched the assertion selector. */
        ASSERTION_OBSERVATION_ABSENT,
        /** The signed run declared its evidence incomplete. */
        ASSERTION_EVIDENCE_INCOMPLETE,
        /** The current signed evidence protocol does not expose the required fact. */
        ASSERTION_EVIDENCE_FACT_UNAVAILABLE
    }

    /** Validates exact identity, bounded observations, and outcome/reason consistency. */
    public ScenarioHandlingAssertionResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported scenario handling assertion result schemaVersion");
        }
        resultFingerprint = optionalFingerprint(
                resultFingerprint, "resultFingerprint");
        runId = identifier(runId, "runId");
        evidenceBundleFingerprint = fingerprint(
                evidenceBundleFingerprint, "evidenceBundleFingerprint");
        planFingerprint = fingerprint(planFingerprint, "planFingerprint");
        if (assertionRef == null
                || !"CASE_HANDLING_ASSERTION".equals(assertionRef.kind())) {
            throw new IllegalArgumentException(
                    "assertionRef must be an exact CASE_HANDLING_ASSERTION ref");
        }
        observation = Objects.requireNonNull(observation, "observation");
        outcome = Objects.requireNonNull(outcome, "outcome");
        severity = Objects.requireNonNull(severity, "severity");
        governanceCode = machineValue(governanceCode, "governanceCode");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        observed = Objects.requireNonNull(observed, "observed");
        boolean reasonMatchesOutcome = switch (outcome) {
            case PASS -> reasonCode == ReasonCode.ASSERTION_MATCHED;
            case FAIL -> reasonCode == ReasonCode.ASSERTION_MISMATCH
                    || reasonCode == ReasonCode.ASSERTION_OBSERVATION_ABSENT;
            case INDETERMINATE ->
                    reasonCode == ReasonCode.ASSERTION_EVIDENCE_INCOMPLETE
                            || reasonCode
                            == ReasonCode.ASSERTION_EVIDENCE_FACT_UNAVAILABLE;
        };
        if (!reasonMatchesOutcome) {
            throw new IllegalArgumentException(
                    "assertion result outcome and reasonCode are inconsistent");
        }
    }

    /**
     * Payload-free evidence facts presented to one assertion.
     *
     * @param statuses normalized observed statuses
     * @param errorCodes normalized observed error codes
     * @param fingerprints canonical observed value, schema, request, receipt, or state fingerprints
     * @param sources normalized resolver, evidence-class, or state sources
     * @param occurrenceCount observed bounded occurrence count, or {@code null}
     * @param durationMillis observed run or selected-node duration, or {@code null}
     * @param booleanValue derived evidence boolean, or {@code null}
     * @param limitations bounded reasons that constrained evaluation
     */
    public record ObservedFacts(
            List<String> statuses,
            List<String> errorCodes,
            List<String> fingerprints,
            List<String> sources,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Long occurrenceCount,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Long durationMillis,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Boolean booleanValue,
            List<String> limitations
    ) {
        /** Canonicalizes every set-like fact dimension and rejects invalid numeric facts. */
        public ObservedFacts {
            statuses = machineValues(statuses, "statuses");
            errorCodes = machineValues(errorCodes, "errorCodes");
            fingerprints = canonicalFingerprints(fingerprints);
            sources = machineValues(sources, "sources");
            limitations = machineValues(limitations, "limitations");
            if ((occurrenceCount != null && occurrenceCount < 0)
                    || (durationMillis != null && durationMillis < 0)) {
                throw new IllegalArgumentException(
                        "observed assertion counts and durations must be non-negative");
            }
        }

        /** @return an empty payload-free observation */
        public static ObservedFacts empty() {
            return new ObservedFacts(
                    List.of(), List.of(), List.of(), List.of(),
                    null, null, null, List.of());
        }
    }

    /** @return identical material carrying a replacement canonical result fingerprint */
    public ScenarioHandlingAssertionResult withFingerprint(String value) {
        return new ScenarioHandlingAssertionResult(
                schemaVersion, value, runId, evidenceBundleFingerprint,
                planFingerprint, assertionRef, observation, outcome, severity,
                governanceCode, reasonCode, observed);
    }

    /** Keeps evidence fact sets out of generic application logs. */
    @Override
    public String toString() {
        return "ScenarioHandlingAssertionResult[runId=" + runId
                + ", assertionRef=" + assertionRef
                + ", observation=" + observation
                + ", outcome=" + outcome
                + ", reasonCode=" + reasonCode + "]";
    }

    private static List<String> machineValues(
            List<String> values, String field) {
        List<String> source = values == null ? List.of() : values;
        if (source.size() > MAXIMUM_FACTS) {
            throw new IllegalArgumentException(field + " exceeds its item limit");
        }
        Set<String> normalized = new TreeSet<>();
        for (String value : source) {
            String fact = machineValue(value, field + " item");
            if (!normalized.add(fact)) {
                throw new IllegalArgumentException(field + " must be unique");
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> canonicalFingerprints(List<String> values) {
        List<String> source = values == null ? List.of() : values;
        if (source.size() > MAXIMUM_FACTS) {
            throw new IllegalArgumentException("fingerprints exceeds its item limit");
        }
        Set<String> normalized = new TreeSet<>();
        for (String value : source) {
            if (!normalized.add(fingerprint(value, "fingerprint"))) {
                throw new IllegalArgumentException("fingerprints must be unique");
            }
        }
        return List.copyOf(normalized);
    }

    private static String identifier(String value, String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String machineValue(String value, String field) {
        String normalized = required(value, field);
        if (!MACHINE_VALUE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String optionalFingerprint(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
