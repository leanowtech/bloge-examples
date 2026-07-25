package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Content-addressed immutable preview of one reviewed Scenario batch remediation.
 *
 * <p>The plan freezes the exact predecessor signed workbook, every requested replacement, and the
 * complete successor batch request. Approval and submission APIs accept only its fingerprint, so
 * no caller can alter the successor after reviewers inspect this preview.</p>
 *
 * @param schemaVersion exact plan protocol version
 * @param planFingerprint canonical plan content address with this field blanked
 * @param scope complete enterprise namespace
 * @param remediationId server-derived stable remediation identity
 * @param previewRequestId original caller idempotency identity
 * @param predecessorJobId exact terminal predecessor batch
 * @param predecessorWorkbookSeedFingerprint exact signed predecessor workbook
 * @param predecessorEvidenceBundleFingerprint exact signed predecessor batch evidence
 * @param predecessorStatus terminal predecessor status
 * @param predecessorBlockers sorted blockers reviewed by the caller
 * @param strategy closed successor construction strategy
 * @param replacements exact resolved entry replacements
 * @param successorRequest complete payload-free successor batch command
 * @param successorRequestFingerprint canonical successor command address
 * @param governanceTicketRef exact external governance review ticket
 * @param approvalPolicy fixed two-person approval policy
 * @param generatedAt trusted server preview time
 * @param expiresAt deadline after which approval or submission fails closed
 */
public record ScenarioRehearsalRemediationPlan(
        String schemaVersion,
        String planFingerprint,
        CapabilitySnapshot.Scope scope,
        String remediationId,
        String previewRequestId,
        String predecessorJobId,
        String predecessorWorkbookSeedFingerprint,
        String predecessorEvidenceBundleFingerprint,
        ScenarioRehearsalBatchJob.Status predecessorStatus,
        List<String> predecessorBlockers,
        ScenarioRehearsalRemediationPreviewRequest.Strategy strategy,
        List<ScenarioRehearsalRemediationPreviewRequest.PlanReplacement>
                replacements,
        ScenarioRehearsalBatchRequest successorRequest,
        String successorRequestFingerprint,
        MirrorArtifactRef governanceTicketRef,
        ApprovalPolicy approvalPolicy,
        Instant generatedAt,
        Instant expiresAt
) {
    /** Current immutable reviewed-remediation plan version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRemediationPlan.v1";
    /** Maximum canonical plan size. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            4 * 1024 * 1024;
    private static final Pattern REMEDIATION_ID =
            Pattern.compile("scenario-remediation-[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern MACHINE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Enforces immutable predecessor, successor, replacement, and approval closure. */
    public ScenarioRehearsalRemediationPlan {
        schemaVersion = version(schemaVersion);
        planFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        planFingerprint, "planFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        remediationId = remediationId(remediationId);
        previewRequestId = identifier(
                previewRequestId, "previewRequestId");
        predecessorJobId = batchId(
                predecessorJobId, "predecessorJobId");
        predecessorWorkbookSeedFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        predecessorWorkbookSeedFingerprint,
                        "predecessorWorkbookSeedFingerprint");
        predecessorEvidenceBundleFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        predecessorEvidenceBundleFingerprint,
                        "predecessorEvidenceBundleFingerprint");
        predecessorStatus = Objects.requireNonNull(
                predecessorStatus, "predecessorStatus");
        if (!predecessorStatus.terminal()) {
            throw new IllegalArgumentException(
                    "Scenario remediation predecessor must be terminal");
        }
        TreeSet<String> blockers = new TreeSet<>();
        if (predecessorBlockers != null) {
            for (String blocker : predecessorBlockers) {
                String exact = MirrorStateProtocolSupport.required(
                        blocker, "predecessorBlocker");
                if (!MACHINE_CODE.matcher(exact).matches()
                        || !blockers.add(exact)) {
                    throw new IllegalArgumentException(
                            "Scenario remediation blockers must be unique machine codes");
                }
            }
        }
        predecessorBlockers = List.copyOf(blockers);
        if (predecessorBlockers.isEmpty()
                || predecessorBlockers.size()
                > ScenarioRehearsalBatchWorkbookSeed.MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "Scenario remediation requires a bounded blocked predecessor");
        }
        strategy = Objects.requireNonNull(strategy, "strategy");
        replacements = replacements == null
                ? List.of() : List.copyOf(replacements);
        successorRequest = Objects.requireNonNull(
                successorRequest, "successorRequest");
        successorRequestFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        successorRequestFingerprint,
                        "successorRequestFingerprint");
        if (!successorRequest.requestId().equals(remediationId)) {
            throw new IllegalArgumentException(
                    "Scenario remediation successor requestId must equal remediationId");
        }
        requireReplacementClosure(
                strategy, replacements, successorRequest);
        governanceTicketRef = exactRef(
                governanceTicketRef,
                "GOVERNANCE_REVIEW_TICKET",
                "governanceTicketRef");
        approvalPolicy = Objects.requireNonNull(
                approvalPolicy, "approvalPolicy");
        generatedAt = Objects.requireNonNull(
                generatedAt, "generatedAt");
        expiresAt = Objects.requireNonNull(
                expiresAt, "expiresAt");
        if (!expiresAt.isAfter(generatedAt)) {
            throw new IllegalArgumentException(
                    "Scenario remediation plan expiry must follow generation");
        }
    }

    /** Returns this plan carrying its canonical content address. */
    public ScenarioRehearsalRemediationPlan withFingerprint(
            String value) {
        return new ScenarioRehearsalRemediationPlan(
                schemaVersion,
                value,
                scope,
                remediationId,
                previewRequestId,
                predecessorJobId,
                predecessorWorkbookSeedFingerprint,
                predecessorEvidenceBundleFingerprint,
                predecessorStatus,
                predecessorBlockers,
                strategy,
                replacements,
                successorRequest,
                successorRequestFingerprint,
                governanceTicketRef,
                approvalPolicy,
                generatedAt,
                expiresAt);
    }

    /** Seals one server-constructed preview with a canonical SHA-256 content address. */
    public static ScenarioRehearsalRemediationPlan seal(
            ObjectMapper mapper,
            ScenarioRehearsalRemediationPlan value) {
        ScenarioRehearsalRemediationPlan material =
                Objects.requireNonNull(value, "value")
                        .withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies this plan's exact content address. */
    public void verify(ObjectMapper mapper) {
        if (planFingerprint.isBlank()
                || !planFingerprint.equals(
                seal(mapper, this).planFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario remediation plan fingerprint mismatch");
        }
    }

    /**
     * Fixed first-generation separation-of-duties policy.
     *
     * @param requiredRoles exact ordered approval roles
     * @param minimumDistinctActors minimum unique authenticated actors
     */
    public record ApprovalPolicy(
            List<ScenarioRehearsalRemediationApprovalCommand.Role>
                    requiredRoles,
            int minimumDistinctActors
    ) {
        /** Rejects weakened or caller-defined approval policies. */
        public ApprovalPolicy {
            requiredRoles = requiredRoles == null
                    ? List.of() : List.copyOf(requiredRoles);
            List<ScenarioRehearsalRemediationApprovalCommand.Role>
                    expected = List.of(
                    ScenarioRehearsalRemediationApprovalCommand.Role
                            .OWNER,
                    ScenarioRehearsalRemediationApprovalCommand.Role
                            .INDEPENDENT_REVIEWER);
            if (!requiredRoles.equals(expected)
                    || minimumDistinctActors != 2) {
                throw new IllegalArgumentException(
                        "Scenario remediation requires owner and independent reviewer separation");
            }
        }

        /** Returns the only policy admitted by the first-generation protocol. */
        public static ApprovalPolicy twoPerson() {
            return new ApprovalPolicy(
                    List.of(
                            ScenarioRehearsalRemediationApprovalCommand.Role
                                    .OWNER,
                            ScenarioRehearsalRemediationApprovalCommand.Role
                                    .INDEPENDENT_REVIEWER),
                    2);
        }
    }

    private static void requireReplacementClosure(
            ScenarioRehearsalRemediationPreviewRequest.Strategy
                    strategy,
            List<ScenarioRehearsalRemediationPreviewRequest.PlanReplacement>
                    replacements,
            ScenarioRehearsalBatchRequest successorRequest) {
        if (strategy
                == ScenarioRehearsalRemediationPreviewRequest.Strategy
                .RERUN_EXACT) {
            if (!replacements.isEmpty()) {
                throw new IllegalArgumentException(
                        "exact rerun must not contain replacements");
            }
            return;
        }
        if (replacements.isEmpty()) {
            throw new IllegalArgumentException(
                    "compiled-plan remediation requires replacements");
        }
        int previousIndex = -1;
        for (ScenarioRehearsalRemediationPreviewRequest.PlanReplacement
                replacement : replacements) {
            if (replacement.entryIndex() <= previousIndex
                    || replacement.entryIndex()
                    >= successorRequest.entries().size()) {
                throw new IllegalArgumentException(
                        "Scenario remediation replacements are not canonical");
            }
            ScenarioRehearsalBatchRequest.Entry successor =
                    successorRequest.entries().get(
                            replacement.entryIndex());
            if (!successor.entryId().equals(
                    replacement.entryId())
                    || !successor.compiledPlanRef().equals(
                    replacement.replacementCompiledPlanRef())) {
                throw new IllegalArgumentException(
                        "Scenario remediation successor does not apply every replacement");
            }
            previousIndex = replacement.entryIndex();
        }
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario remediation plan schemaVersion");
        }
        return exact;
    }

    private static String remediationId(String value) {
        String exact =
                MirrorStateProtocolSupport.required(
                        value, "remediationId");
        if (!REMEDIATION_ID.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "remediationId is invalid");
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

    private static String batchId(
            String value,
            String field) {
        String exact =
                MirrorStateProtocolSupport.required(value, field);
        if (!ScenarioRehearsalBatchIdentity
                .hasCanonicalShape(exact)) {
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
