package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Content-addressed append-only approval fact for one remediation plan.
 *
 * <p>Each generation binds the previous approval fingerprint, authenticated actor, delegation,
 * governance ticket, and trusted decision time. A rejected generation remains immutable and
 * cannot be overwritten by a later approval.</p>
 *
 * @param schemaVersion exact approval-fact version
 * @param approvalFingerprint canonical content address with this field blanked
 * @param sourceCommandFingerprint exact accepted command address
 * @param scope complete enterprise namespace
 * @param remediationId stable remediation identity
 * @param remediationPlanFingerprint exact frozen plan
 * @param generation monotonic append-only generation
 * @param previousApprovalFingerprint previous generation, blank only for generation one
 * @param role approved separation-of-duties role
 * @param decision terminal role decision
 * @param governanceTicketRef exact external governance ticket
 * @param reasonCode closed decision rationale
 * @param actorId authenticated decision maker
 * @param delegatedBy authenticated delegating principal, when present
 * @param decidedAt trusted server decision time
 */
public record ScenarioRehearsalRemediationApproval(
        String schemaVersion,
        String approvalFingerprint,
        String sourceCommandFingerprint,
        CapabilitySnapshot.Scope scope,
        String remediationId,
        String remediationPlanFingerprint,
        long generation,
        String previousApprovalFingerprint,
        ScenarioRehearsalRemediationApprovalCommand.Role role,
        ScenarioRehearsalRemediationApprovalCommand.Decision decision,
        MirrorArtifactRef governanceTicketRef,
        ScenarioRehearsalRemediationApprovalCommand.ReasonCode reasonCode,
        String actorId,
        String delegatedBy,
        Instant decidedAt
) {
    /** Current append-only remediation approval version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRemediationApproval.v1";
    /** Maximum canonical approval size. */
    public static final int MAXIMUM_CANONICAL_BYTES = 128 * 1024;
    private static final Pattern REMEDIATION_ID =
            Pattern.compile("scenario-remediation-[a-f0-9]{64}");
    private static final Pattern ACTOR =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,254}");

    /** Enforces immutable chain, actor, ticket, and decision correspondence. */
    public ScenarioRehearsalRemediationApproval {
        schemaVersion = version(schemaVersion);
        approvalFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        approvalFingerprint,
                        "approvalFingerprint");
        sourceCommandFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        sourceCommandFingerprint,
                        "sourceCommandFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        remediationId = remediationId(remediationId);
        remediationPlanFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        remediationPlanFingerprint,
                        "remediationPlanFingerprint");
        previousApprovalFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        previousApprovalFingerprint,
                        "previousApprovalFingerprint");
        if (generation < 1
                || generation == 1
                != previousApprovalFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "Scenario remediation approval generation chain is invalid");
        }
        role = Objects.requireNonNull(role, "role");
        decision = Objects.requireNonNull(
                decision, "decision");
        governanceTicketRef = exactTicket(
                governanceTicketRef);
        reasonCode = Objects.requireNonNull(
                reasonCode, "reasonCode");
        if (decision
                == ScenarioRehearsalRemediationApprovalCommand.Decision
                .APPROVE
                != reasonCode.approvalReason()) {
            throw new IllegalArgumentException(
                    "Scenario remediation approval decision and reason differ");
        }
        actorId = actor(actorId, "actorId");
        delegatedBy = optionalActor(
                delegatedBy, "delegatedBy");
        decidedAt = Objects.requireNonNull(
                decidedAt, "decidedAt");
    }

    /** Returns this approval carrying its canonical content address. */
    public ScenarioRehearsalRemediationApproval withFingerprint(
            String value) {
        return new ScenarioRehearsalRemediationApproval(
                schemaVersion,
                value,
                sourceCommandFingerprint,
                scope,
                remediationId,
                remediationPlanFingerprint,
                generation,
                previousApprovalFingerprint,
                role,
                decision,
                governanceTicketRef,
                reasonCode,
                actorId,
                delegatedBy,
                decidedAt);
    }

    /** Seals one server-authored approval fact. */
    public static ScenarioRehearsalRemediationApproval seal(
            ObjectMapper mapper,
            ScenarioRehearsalRemediationApproval value) {
        ScenarioRehearsalRemediationApproval material =
                Objects.requireNonNull(value, "value")
                        .withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies the exact approval content address. */
    public void verify(ObjectMapper mapper) {
        if (approvalFingerprint.isBlank()
                || !approvalFingerprint.equals(
                seal(mapper, this).approvalFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario remediation approval fingerprint mismatch");
        }
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario remediation approval schemaVersion");
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

    private static String actor(
            String value,
            String field) {
        String exact =
                MirrorStateProtocolSupport.required(value, field);
        if (!ACTOR.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String optionalActor(
            String value,
            String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isBlank()
                && !ACTOR.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static MirrorArtifactRef exactTicket(
            MirrorArtifactRef value) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(
                        value, "governanceTicketRef");
        if (!"GOVERNANCE_REVIEW_TICKET".equals(
                exact.kind())) {
            throw new IllegalArgumentException(
                    "governanceTicketRef must reference GOVERNANCE_REVIEW_TICKET");
        }
        return exact;
    }
}
