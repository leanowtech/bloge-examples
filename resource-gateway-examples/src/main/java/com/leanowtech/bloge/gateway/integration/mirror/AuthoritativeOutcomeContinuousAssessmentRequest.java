package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable registration command for one server-owned continuous completeness projection.
 *
 * <p>The command binds an exact selected-population revision. Polling, leases, retries, assessment
 * revision allocation, and the assessment stream identifier are deliberately server-owned so a
 * caller cannot weaken freshness or create competing writers for the same projection.</p>
 *
 * @param schemaVersion exact command version
 * @param projectionId stable projection identity inside one enterprise scope
 * @param populationRef exact immutable selected-population root
 */
public record AuthoritativeOutcomeContinuousAssessmentRequest(
        String schemaVersion,
        String projectionId,
        MirrorArtifactRef populationRef
) {
    /** Current continuous-assessment registration version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentRequest.v1";
    /** Reserved assessment-stream prefix owned by the projection runtime. */
    public static final String ASSESSMENT_ID_PREFIX =
            "continuous-assessment:";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,479}");

    /** Requires a bounded identity and an exact selected-population reference. */
    public AuthoritativeOutcomeContinuousAssessmentRequest {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported continuous assessment request schemaVersion");
        }
        projectionId = projectionId == null
                ? "" : projectionId.trim();
        if (!IDENTIFIER.matcher(projectionId).matches()) {
            throw new IllegalArgumentException(
                    "continuous assessment projectionId is invalid");
        }
        populationRef = Objects.requireNonNull(
                populationRef, "populationRef");
        if (!AuthoritativeOutcomeSelectedPopulationManifest
                .ARTIFACT_KIND.equals(populationRef.kind())) {
            throw new IllegalArgumentException(
                    "continuous assessment populationRef kind is invalid");
        }
    }

    /** @return server-owned immutable assessment stream identifier */
    public String assessmentId() {
        return ASSESSMENT_ID_PREFIX + projectionId;
    }
}
