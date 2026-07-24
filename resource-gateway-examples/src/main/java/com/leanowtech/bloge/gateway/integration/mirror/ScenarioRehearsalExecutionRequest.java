package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.regex.Pattern;

/**
 * Strict command for executing one exact compiler-issued Scenario rehearsal plan.
 *
 * <p>The command deliberately carries no context, fixture, fault, clock, random seed, Session, or
 * policy override. Runtime resolves those values from the immutable ScenarioCase, TestSuite,
 * FixtureBundle, MirrorPlan, and optional checkpoint closure frozen by the compiled plan.</p>
 *
 * @param schemaVersion exact execution-command protocol version
 * @param requestId stable aggregate idempotency identity inside one enterprise scope
 * @param compiledPlanRef exact compiler-issued rehearsal plan
 */
public record ScenarioRehearsalExecutionRequest(
        String schemaVersion,
        String requestId,
        MirrorArtifactRef compiledPlanRef
) {
    /** Current Scenario rehearsal execution-command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalExecutionRequest.v1";
    private static final Pattern REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

    /** Validates the complete payload-free execution identity. */
    public ScenarioRehearsalExecutionRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported scenario rehearsal execution request schemaVersion");
        }
        requestId = required(requestId, "requestId");
        if (!REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("requestId is invalid");
        }
        if (compiledPlanRef == null
                || !"COMPILED_REHEARSAL_PLAN".equals(compiledPlanRef.kind())) {
            throw new IllegalArgumentException(
                    "compiledPlanRef must be an exact COMPILED_REHEARSAL_PLAN ref");
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
