package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed payload-free aggregate for one complete Scenario rehearsal request.
 *
 * <p>The aggregate is a deterministic projection of the ordered case closure. It cannot claim a
 * caller-selected outcome or summary: case indices, identities, counts, and fail-closed precedence
 * are recomputed by the constructor. A later portable evidence bundle signs this complete value
 * without copying TestSuite inputs, fixture values, or Session entities.</p>
 *
 * @param schemaVersion exact aggregate protocol version
 * @param resultFingerprint canonical fingerprint with this field blanked
 * @param requestId aggregate idempotency identity
 * @param compiledPlanRef exact compiler-issued execution license
 * @param scope exact enterprise namespace
 * @param targetCapabilityRef exact rehearsed capability
 * @param outcome server-derived aggregate outcome
 * @param caseResults complete ordered case-result closure
 * @param summary server-derived case and assertion counts
 * @param startedAt aggregate start time
 * @param completedAt aggregate terminal time
 */
public record ScenarioRehearsalResult(
        String schemaVersion,
        String resultFingerprint,
        String requestId,
        MirrorArtifactRef compiledPlanRef,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef targetCapabilityRef,
        ScenarioCaseRehearsalResult.Outcome outcome,
        List<ScenarioCaseRehearsalResult> caseResults,
        Summary summary,
        Instant startedAt,
        Instant completedAt
) {
    /** Current aggregate-result protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalResult.v1";
    private static final Pattern REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates the complete ordered aggregate and every derived field. */
    public ScenarioRehearsalResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported scenario rehearsal result schemaVersion");
        }
        resultFingerprint = optionalFingerprint(resultFingerprint);
        requestId = required(requestId, "requestId");
        if (!REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("requestId is invalid");
        }
        compiledPlanRef = exactKind(
                compiledPlanRef,
                "COMPILED_REHEARSAL_PLAN",
                "compiledPlanRef");
        scope = Objects.requireNonNull(scope, "scope");
        targetCapabilityRef = exactKind(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        outcome = Objects.requireNonNull(outcome, "outcome");
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        summary = Objects.requireNonNull(summary, "summary");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (caseResults.isEmpty()
                || caseResults.size() > ScenarioPack.MAXIMUM_CASES
                || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "aggregate result requires bounded terminal cases");
        }
        Set<MirrorArtifactRef> caseRefs = new HashSet<>();
        for (int index = 0; index < caseResults.size(); index++) {
            ScenarioCaseRehearsalResult result =
                    Objects.requireNonNull(caseResults.get(index), "caseResult");
            if (result.caseIndex() != index
                    || !caseRefs.add(result.scenarioCaseRef())
                    || result.startedAt().isBefore(startedAt)
                    || result.completedAt().isAfter(completedAt)) {
                throw new IllegalArgumentException(
                        "aggregate case order, identity, or time closure is invalid");
            }
        }
        ScenarioCaseRehearsalResult.Outcome derivedOutcome =
                deriveOutcome(caseResults);
        Summary derivedSummary = Summary.from(caseResults);
        if (outcome != derivedOutcome || !summary.equals(derivedSummary)) {
            throw new IllegalArgumentException(
                    "aggregate outcome and summary must be derived from cases");
        }
    }

    /**
     * Applies fail-closed aggregate precedence.
     *
     * @param cases complete terminal case closure
     * @return {@code FAIL}, then {@code INDETERMINATE}, otherwise {@code PASS}
     */
    public static ScenarioCaseRehearsalResult.Outcome deriveOutcome(
            List<ScenarioCaseRehearsalResult> cases) {
        List<ScenarioCaseRehearsalResult> values =
                cases == null ? List.of() : cases;
        if (values.stream().anyMatch(result ->
                result.outcome() == ScenarioCaseRehearsalResult.Outcome.FAIL)) {
            return ScenarioCaseRehearsalResult.Outcome.FAIL;
        }
        if (values.stream().anyMatch(result ->
                result.outcome()
                        == ScenarioCaseRehearsalResult.Outcome.INDETERMINATE)) {
            return ScenarioCaseRehearsalResult.Outcome.INDETERMINATE;
        }
        return ScenarioCaseRehearsalResult.Outcome.PASS;
    }

    /**
     * Derived payload-free aggregate counters.
     *
     * @param totalCases number of terminal case rows
     * @param passedCases passing case rows
     * @param failedCases failed case rows
     * @param indeterminateCases evidence-indeterminate case rows
     * @param assertionResults total handling-assertion result rows
     * @param blockerFailures failed blocker assertions
     * @param blockerIndeterminate indeterminate blocker assertions
     * @param warningFailures failed warning assertions
     * @param warningIndeterminate indeterminate warning assertions
     */
    public record Summary(
            int totalCases,
            int passedCases,
            int failedCases,
            int indeterminateCases,
            int assertionResults,
            int blockerFailures,
            int blockerIndeterminate,
            int warningFailures,
            int warningIndeterminate
    ) {
        /** Rejects impossible aggregate counters. */
        public Summary {
            if (totalCases < 1
                    || passedCases < 0
                    || failedCases < 0
                    || indeterminateCases < 0
                    || assertionResults < 0
                    || blockerFailures < 0
                    || blockerIndeterminate < 0
                    || warningFailures < 0
                    || warningIndeterminate < 0
                    || passedCases + failedCases + indeterminateCases
                    != totalCases
                    || blockerFailures + blockerIndeterminate
                    + warningFailures + warningIndeterminate
                    > assertionResults) {
                throw new IllegalArgumentException(
                        "scenario rehearsal summary counters are inconsistent");
            }
        }

        /** @return exact counters derived from one complete case closure */
        public static Summary from(
                List<ScenarioCaseRehearsalResult> cases) {
            List<ScenarioCaseRehearsalResult> values =
                    cases == null ? List.of() : cases;
            return new Summary(
                    values.size(),
                    countOutcome(
                            values, ScenarioCaseRehearsalResult.Outcome.PASS),
                    countOutcome(
                            values, ScenarioCaseRehearsalResult.Outcome.FAIL),
                    countOutcome(
                            values,
                            ScenarioCaseRehearsalResult.Outcome.INDETERMINATE),
                    exactInt(values.stream()
                            .mapToLong(result ->
                                    result.assertionResults().size())
                            .sum()),
                    exactInt(values.stream()
                            .mapToLong(
                                    ScenarioCaseRehearsalResult::blockerFailures)
                            .sum()),
                    exactInt(values.stream()
                            .mapToLong(
                                    ScenarioCaseRehearsalResult
                                            ::blockerIndeterminate)
                            .sum()),
                    exactInt(values.stream()
                            .mapToLong(
                                    ScenarioCaseRehearsalResult::warningFailures)
                            .sum()),
                    exactInt(values.stream()
                            .mapToLong(
                                    ScenarioCaseRehearsalResult
                                            ::warningIndeterminate)
                            .sum()));
        }

        private static int countOutcome(
                List<ScenarioCaseRehearsalResult> values,
                ScenarioCaseRehearsalResult.Outcome outcome) {
            return exactInt(values.stream()
                    .filter(result -> result.outcome() == outcome)
                    .count());
        }

        private static int exactInt(long value) {
            return Math.toIntExact(value);
        }
    }

    /** @return identical material carrying a replacement canonical fingerprint */
    public ScenarioRehearsalResult withFingerprint(String value) {
        return new ScenarioRehearsalResult(
                schemaVersion, value, requestId, compiledPlanRef, scope,
                targetCapabilityRef, outcome, caseResults, summary,
                startedAt, completedAt);
    }

    /** Keeps child run and assertion detail out of generic application logs. */
    @Override
    public String toString() {
        return "ScenarioRehearsalResult[requestId=" + requestId
                + ", compiledPlanRef=" + compiledPlanRef
                + ", outcome=" + outcome
                + ", summary=" + summary + "]";
    }

    private static MirrorArtifactRef exactKind(
            MirrorArtifactRef value, String kind, String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(
                    field + " must be an exact " + kind + " ref");
        }
        return value;
    }

    private static String optionalFingerprint(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "resultFingerprint must be blank or canonical SHA-256");
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
