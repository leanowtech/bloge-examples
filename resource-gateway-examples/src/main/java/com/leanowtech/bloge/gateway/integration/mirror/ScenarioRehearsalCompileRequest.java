package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Exact public command for compiling one registered ScenarioPack revision.
 *
 * @param schemaVersion request protocol version
 * @param revision exact positive ScenarioPack revision
 * @param fingerprint reviewed ScenarioPack fingerprint
 */
public record ScenarioRehearsalCompileRequest(
        String schemaVersion,
        long revision,
        String fingerprint
) {
    /** Current strict compile-command protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalCompileRequest.v1";

    /** Applies only the protocol default; the authenticated service validates exact coordinates. */
    public ScenarioRehearsalCompileRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        fingerprint = fingerprint == null ? "" : fingerprint.trim();
    }
}
