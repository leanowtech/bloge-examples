package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free proposal for creating a reviewed successor to one terminal Scenario batch.
 *
 * <p>The proposal can only rerun the exact predecessor or replace selected entries with existing
 * content-addressed compiled plans. It cannot remove entries, alter assertions, lower evidence
 * policy, override runtime controls, or carry arbitrary JSON/DSL. The server resolves the
 * predecessor workbook and freezes the complete successor request in a separate remediation
 * plan before any approval is accepted.</p>
 *
 * @param schemaVersion exact proposal wire version
 * @param previewRequestId caller-stable idempotency identity
 * @param expectedWorkbookSeedFingerprint exact predecessor workbook reviewed by the caller
 * @param strategy closed remediation strategy
 * @param replacements ordered entry replacements; empty for an exact rerun
 * @param governanceTicketRef exact external governance review ticket
 * @param reasonCode closed low-cardinality remediation reason
 */
public record ScenarioRehearsalRemediationPreviewRequest(
        String schemaVersion,
        String previewRequestId,
        String expectedWorkbookSeedFingerprint,
        Strategy strategy,
        List<PlanReplacement> replacements,
        MirrorArtifactRef governanceTicketRef,
        ReasonCode reasonCode
) {
    /** Current reviewed-remediation preview request version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRemediationPreviewRequest.v1";
    /** Maximum plan replacements admitted in one proposal. */
    public static final int MAXIMUM_REPLACEMENTS =
            ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** Enforces the closed strategy and complete compare-and-set proposal shape. */
    public ScenarioRehearsalRemediationPreviewRequest {
        schemaVersion = version(schemaVersion);
        previewRequestId = identifier(
                previewRequestId, "previewRequestId");
        expectedWorkbookSeedFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        expectedWorkbookSeedFingerprint,
                        "expectedWorkbookSeedFingerprint");
        strategy = Objects.requireNonNull(strategy, "strategy");
        replacements = replacements == null
                ? List.of() : List.copyOf(replacements);
        if (replacements.size() > MAXIMUM_REPLACEMENTS) {
            throw new IllegalArgumentException(
                    "remediation replacements exceed the protocol bound");
        }
        Set<Integer> indexes = new HashSet<>();
        Set<String> entryIds = new HashSet<>();
        int previousIndex = -1;
        for (PlanReplacement replacement : replacements) {
            PlanReplacement exact =
                    Objects.requireNonNull(replacement, "replacement");
            if (exact.entryIndex() <= previousIndex
                    || !indexes.add(exact.entryIndex())
                    || !entryIds.add(exact.entryId())) {
                throw new IllegalArgumentException(
                        "remediation replacements must be strictly ordered and unique");
            }
            previousIndex = exact.entryIndex();
        }
        governanceTicketRef = exactRef(
                governanceTicketRef,
                "GOVERNANCE_REVIEW_TICKET",
                "governanceTicketRef");
        reasonCode = Objects.requireNonNull(
                reasonCode, "reasonCode");
        if (strategy == Strategy.RERUN_EXACT
                && (!replacements.isEmpty()
                || !reasonCode.rerunReason())
                || strategy == Strategy.REPLACE_COMPILED_PLANS
                && (replacements.isEmpty()
                || reasonCode.rerunReason())) {
            throw new IllegalArgumentException(
                    "remediation strategy, replacements, and reasonCode are inconsistent");
        }
    }

    /**
     * One exact entry-level compiled-plan replacement.
     *
     * @param entryIndex immutable predecessor manifest index
     * @param entryId immutable predecessor entry identity
     * @param expectedCompiledPlanRef exact predecessor plan CAS fence
     * @param replacementCompiledPlanRef exact precompiled successor plan
     */
    public record PlanReplacement(
            int entryIndex,
            String entryId,
            MirrorArtifactRef expectedCompiledPlanRef,
            MirrorArtifactRef replacementCompiledPlanRef
    ) {
        /** Rejects index drift, wrong artifact kinds, and no-op replacements. */
        public PlanReplacement {
            if (entryIndex < 0
                    || entryIndex
                    >= ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES) {
                throw new IllegalArgumentException(
                        "remediation entryIndex is invalid");
            }
            entryId = identifier(entryId, "entryId");
            expectedCompiledPlanRef = exactRef(
                    expectedCompiledPlanRef,
                    "COMPILED_REHEARSAL_PLAN",
                    "expectedCompiledPlanRef");
            replacementCompiledPlanRef = exactRef(
                    replacementCompiledPlanRef,
                    "COMPILED_REHEARSAL_PLAN",
                    "replacementCompiledPlanRef");
            if (expectedCompiledPlanRef.equals(
                    replacementCompiledPlanRef)) {
                throw new IllegalArgumentException(
                        "remediation replacement must change the compiled plan");
            }
        }
    }

    /** Closed first-generation successor construction strategy. */
    public enum Strategy {
        /** Re-executes every exact predecessor plan to test transient or evidence uncertainty. */
        RERUN_EXACT,
        /** Replaces selected plans while preserving every entry identity and position. */
        REPLACE_COMPILED_PLANS
    }

    /** Closed reason vocabulary preventing free-form payload from entering the control plane. */
    public enum ReasonCode {
        TRANSIENT_EXECUTION_RECHECK(true),
        EVIDENCE_RECOVERY_RECHECK(true),
        SCENARIO_REVISION(false),
        FIXTURE_REVISION(false),
        ASSERTION_REVISION(false),
        MIRROR_PLAN_REVISION(false);

        private final boolean rerunReason;

        ReasonCode(boolean rerunReason) {
            this.rerunReason = rerunReason;
        }

        /** @return whether this reason permits only an exact rerun */
        public boolean rerunReason() {
            return rerunReason;
        }
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario remediation preview request schemaVersion");
        }
        return exact;
    }

    private static String identifier(
            String value,
            String field) {
        String exact =
                MirrorStateProtocolSupport.required(value, field);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static MirrorArtifactRef exactRef(
            MirrorArtifactRef value,
            String kind,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return exact;
    }
}
