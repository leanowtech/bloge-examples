package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * Database-observed effective status of one continuous completeness projection.
 *
 * <p>{@code sourceFreshness} answers whether the immutable source heads were checked within the
 * server-owned polling bound. {@code authoritiesReady} independently reflects current selection,
 * outcome, disposition, and signing readiness. Governance consumers must use {@code ready}, which
 * is true only when both claims hold; historical assessments remain readable when it is false.</p>
 *
 * @param schemaVersion exact status protocol version
 * @param projection integrity-checked durable projection
 * @param observedAt database time used to interpret the exclusive freshness deadline
 * @param sourceFreshness bounded source-head freshness state
 * @param authoritiesReady whether all external trust and signing boundaries are currently usable
 * @param ready whether this projection may currently support a governance decision
 */
public record AuthoritativeOutcomeContinuousAssessmentStatus(
        String schemaVersion,
        AuthoritativeOutcomeContinuousAssessmentProjection
                projection,
        Instant observedAt,
        AuthoritativeOutcomeContinuousAssessmentProjection.Freshness
                sourceFreshness,
        boolean authoritiesReady,
        boolean ready
) {
    /** Current effective status version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentStatus.v1";

    /** Derives and verifies effective readiness from exact status facts. */
    public AuthoritativeOutcomeContinuousAssessmentStatus {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported continuous assessment status schemaVersion");
        }
        projection = Objects.requireNonNull(
                projection, "projection");
        observedAt = Objects.requireNonNull(
                observedAt, "observedAt");
        sourceFreshness = Objects.requireNonNull(
                sourceFreshness, "sourceFreshness");
        if (sourceFreshness
                != projection.freshnessAt(observedAt)
                || ready != (sourceFreshness
                == AuthoritativeOutcomeContinuousAssessmentProjection
                .Freshness.CURRENT
                && authoritiesReady)) {
            throw new IllegalArgumentException(
                    "continuous assessment effective readiness is not derived");
        }
    }

    /** Creates an effective status from a database-observed projection. */
    public static AuthoritativeOutcomeContinuousAssessmentStatus
    from(
            AuthoritativeOutcomeContinuousAssessmentRepository
                    .ObservedProjection observed,
            boolean authoritiesReady) {
        AuthoritativeOutcomeContinuousAssessmentRepository
                .ObservedProjection exact =
                Objects.requireNonNull(observed, "observed");
        AuthoritativeOutcomeContinuousAssessmentProjection
                .Freshness freshness = exact.freshness();
        return new AuthoritativeOutcomeContinuousAssessmentStatus(
                SCHEMA_VERSION,
                exact.projection(),
                exact.observedAt(),
                freshness,
                authoritiesReady,
                freshness
                        == AuthoritativeOutcomeContinuousAssessmentProjection
                        .Freshness.CURRENT
                        && authoritiesReady);
    }
}
