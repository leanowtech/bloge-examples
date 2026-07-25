package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.regex.Pattern;

/**
 * Compare-and-set command for submitting one fully approved remediation successor.
 *
 * <p>The command can reference only a frozen plan and the exact append-only approval head.
 * Successor plans, runtime controls, actor identity, and timestamps cannot be supplied here.</p>
 *
 * @param schemaVersion exact submit-command version
 * @param commandId caller-stable idempotency identity
 * @param remediationPlanFingerprint exact frozen remediation plan
 * @param expectedApprovalGeneration exact approved chain generation
 * @param expectedApprovalHeadFingerprint exact latest approval fact
 * @param reasonCode closed submit rationale
 */
public record ScenarioRehearsalRemediationSubmitCommand(
        String schemaVersion,
        String commandId,
        String remediationPlanFingerprint,
        long expectedApprovalGeneration,
        String expectedApprovalHeadFingerprint,
        ReasonCode reasonCode
) {
    /** Current remediation submission command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRemediationSubmitCommand.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** Enforces the minimum two-person approval fence and exact chain head. */
    public ScenarioRehearsalRemediationSubmitCommand {
        schemaVersion = version(schemaVersion);
        commandId = identifier(commandId);
        remediationPlanFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        remediationPlanFingerprint,
                        "remediationPlanFingerprint");
        if (expectedApprovalGeneration < 2) {
            throw new IllegalArgumentException(
                    "Scenario remediation submission requires two approval generations");
        }
        expectedApprovalHeadFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        expectedApprovalHeadFingerprint,
                        "expectedApprovalHeadFingerprint");
        reasonCode = reasonCode == null
                ? ReasonCode.APPROVALS_COMPLETE : reasonCode;
    }

    /** Closed first-generation submit rationale. */
    public enum ReasonCode {
        APPROVALS_COMPLETE
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario remediation submit command schemaVersion");
        }
        return exact;
    }

    private static String identifier(String value) {
        String exact =
                MirrorStateProtocolSupport.required(
                        value, "commandId");
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "commandId is invalid");
        }
        return exact;
    }
}
