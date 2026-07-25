package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Compare-and-set command for one role-bound remediation approval decision.
 *
 * <p>The authenticated service binds the actual actor, delegation, trusted time, and previous
 * approval head. The client supplies no reviewer identity or comments and cannot change the
 * frozen remediation plan.</p>
 *
 * @param schemaVersion exact approval-command version
 * @param commandId caller-stable idempotency identity
 * @param remediationPlanFingerprint exact frozen preview
 * @param expectedApprovalGeneration exact append-only approval generation fence
 * @param role required separation-of-duties role
 * @param decision terminal decision for this role
 * @param governanceTicketRef exact ticket already frozen by the plan
 * @param reasonCode closed low-cardinality decision reason
 */
public record ScenarioRehearsalRemediationApprovalCommand(
        String schemaVersion,
        String commandId,
        String remediationPlanFingerprint,
        long expectedApprovalGeneration,
        Role role,
        Decision decision,
        MirrorArtifactRef governanceTicketRef,
        ReasonCode reasonCode
) {
    /** Current remediation approval-command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRemediationApprovalCommand.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** Enforces exact CAS, ticket, role, decision, and reason correspondence. */
    public ScenarioRehearsalRemediationApprovalCommand {
        schemaVersion = version(schemaVersion);
        commandId = identifier(commandId, "commandId");
        remediationPlanFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        remediationPlanFingerprint,
                        "remediationPlanFingerprint");
        if (expectedApprovalGeneration < 0) {
            throw new IllegalArgumentException(
                    "expectedApprovalGeneration must not be negative");
        }
        role = Objects.requireNonNull(role, "role");
        decision = Objects.requireNonNull(decision, "decision");
        governanceTicketRef = exactTicket(
                governanceTicketRef);
        reasonCode = Objects.requireNonNull(
                reasonCode, "reasonCode");
        if (decision == Decision.APPROVE
                != reasonCode.approvalReason()) {
            throw new IllegalArgumentException(
                    "remediation approval decision and reasonCode differ");
        }
    }

    /** Required first-generation separation-of-duties roles. */
    public enum Role {
        OWNER,
        INDEPENDENT_REVIEWER
    }

    /** Terminal per-role approval decision. */
    public enum Decision {
        APPROVE,
        REJECT
    }

    /** Closed approval rationale vocabulary. */
    public enum ReasonCode {
        APPROVED_AS_REVIEWED(true),
        REJECTED_REQUIRES_CHANGES(false),
        REJECTED_POLICY_CONFLICT(false),
        REJECTED_INSUFFICIENT_EVIDENCE(false);

        private final boolean approvalReason;

        ReasonCode(boolean approvalReason) {
            this.approvalReason = approvalReason;
        }

        /** @return whether this reason accompanies an approval */
        public boolean approvalReason() {
            return approvalReason;
        }
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario remediation approval command schemaVersion");
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
