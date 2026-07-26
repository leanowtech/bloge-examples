package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free durable head and work projection for one continuous completeness stream.
 *
 * <p>The immutable assessment stream remains the evidence authority. This rebuildable projection
 * records the exact selected denominator, last published source closure, a bounded freshness
 * window, scheduling state, and an opaque owner/epoch fence. It never stores selected member,
 * observation, disposition, or customer payload data.</p>
 *
 * @param schemaVersion exact projection version
 * @param scope complete enterprise namespace
 * @param projectionId stable continuous projection identity
 * @param populationRef exact immutable selected denominator
 * @param assessmentId server-owned immutable assessment stream
 * @param status durable worker state
 * @param lastAssessmentRef latest adopted immutable assessment, null before first publication
 * @param observationSetFingerprint latest adopted observation closure, blank before publication
 * @param dispositionSetFingerprint latest adopted disposition closure, blank before publication
 * @param currentThrough database time of the latest successful source-head check
 * @param freshUntil exclusive bounded freshness deadline
 * @param attemptCount total acquired worker turns
 * @param consecutiveFailures current failure streak
 * @param nextEligibleAt next database-time scheduling cursor
 * @param leaseOwnerFingerprint opaque current worker correlation, blank without a lease
 * @param leaseEpoch monotonic worker fencing generation
 * @param leaseExpiresAt exclusive database-time lease boundary, epoch without a lease
 * @param failureCode bounded stable failure reason
 * @param createdAt first registration time
 * @param updatedAt latest committed transition time
 * @param terminalAt quarantine time, null while active
 * @param recordFingerprint canonical mutable-projection address
 */
public record AuthoritativeOutcomeContinuousAssessmentProjection(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        String projectionId,
        MirrorArtifactRef populationRef,
        String assessmentId,
        Status status,
        MirrorArtifactRef lastAssessmentRef,
        String observationSetFingerprint,
        String dispositionSetFingerprint,
        Instant currentThrough,
        Instant freshUntil,
        long attemptCount,
        int consecutiveFailures,
        Instant nextEligibleAt,
        String leaseOwnerFingerprint,
        long leaseEpoch,
        Instant leaseExpiresAt,
        String failureCode,
        Instant createdAt,
        Instant updatedAt,
        Instant terminalAt,
        String recordFingerprint
) {
    /** Current durable projection version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentProjection.v1";
    /** Maximum canonical projection bytes. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            256 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern FAILURE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Enforces state, freshness, assessment, and lease-field correspondence. */
    public AuthoritativeOutcomeContinuousAssessmentProjection {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported continuous assessment projection schemaVersion");
        }
        scope = Objects.requireNonNull(scope, "scope");
        projectionId = identifier(
                projectionId, "projectionId");
        populationRef = Objects.requireNonNull(
                populationRef, "populationRef");
        if (!AuthoritativeOutcomeSelectedPopulationManifest
                .ARTIFACT_KIND.equals(populationRef.kind())) {
            throw new IllegalArgumentException(
                    "continuous assessment populationRef is invalid");
        }
        assessmentId = identifier(
                assessmentId, "assessmentId");
        if (!assessmentId.equals(
                AuthoritativeOutcomeContinuousAssessmentRequest
                        .ASSESSMENT_ID_PREFIX + projectionId)) {
            throw new IllegalArgumentException(
                    "continuous assessment stream is not server-owned");
        }
        status = Objects.requireNonNull(status, "status");
        observationSetFingerprint = normalized(
                observationSetFingerprint);
        dispositionSetFingerprint = normalized(
                dispositionSetFingerprint);
        currentThrough = Objects.requireNonNull(
                currentThrough, "currentThrough");
        freshUntil = Objects.requireNonNull(
                freshUntil, "freshUntil");
        nextEligibleAt = Objects.requireNonNull(
                nextEligibleAt, "nextEligibleAt");
        leaseOwnerFingerprint = normalized(
                leaseOwnerFingerprint);
        leaseExpiresAt = Objects.requireNonNull(
                leaseExpiresAt, "leaseExpiresAt");
        failureCode = normalized(failureCode);
        createdAt = Objects.requireNonNull(
                createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(
                updatedAt, "updatedAt");
        recordFingerprint = optionalFingerprint(
                recordFingerprint);
        if (attemptCount < 0
                || consecutiveFailures < 0
                || leaseEpoch < 0
                || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "continuous assessment counters or time are invalid");
        }
        boolean published = lastAssessmentRef != null;
        if (published) {
            if (!AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    .ARTIFACT_KIND.equals(
                    lastAssessmentRef.kind())
                    || !assessmentId.equals(
                    lastAssessmentRef.id())) {
                throw new IllegalArgumentException(
                        "continuous assessment reference is invalid");
            }
            fingerprint(
                    observationSetFingerprint,
                    "observationSetFingerprint");
            fingerprint(
                    dispositionSetFingerprint,
                    "dispositionSetFingerprint");
            if (Instant.EPOCH.equals(currentThrough)
                    || !freshUntil.isAfter(currentThrough)) {
                throw new IllegalArgumentException(
                        "published continuous assessment freshness is invalid");
            }
        } else if (!observationSetFingerprint.isBlank()
                || !dispositionSetFingerprint.isBlank()
                || !Instant.EPOCH.equals(currentThrough)
                || !Instant.EPOCH.equals(freshUntil)) {
            throw new IllegalArgumentException(
                    "unpublished continuous assessment carries source state");
        }
        if (!leaseOwnerFingerprint.isBlank()) {
            fingerprint(
                    leaseOwnerFingerprint,
                    "leaseOwnerFingerprint");
        }
        if (!failureCode.isBlank()
                && !FAILURE_CODE.matcher(
                failureCode).matches()) {
            throw new IllegalArgumentException(
                    "continuous assessment failureCode is invalid");
        }
        validateState(
                status,
                nextEligibleAt,
                leaseOwnerFingerprint,
                leaseExpiresAt,
                failureCode,
                updatedAt,
                terminalAt);
    }

    /** Durable projection states. */
    public enum Status {
        QUEUED,
        RUNNING,
        RETRY_WAIT,
        QUARANTINED
    }

    /** Consumer-facing bounded freshness states. */
    public enum Freshness {
        UNINITIALIZED,
        CURRENT,
        REFRESHING,
        STALE,
        QUARANTINED
    }

    /**
     * Resolves freshness at one database observation time.
     *
     * <p>The deadline is exclusive: at {@code freshUntil} the previous conclusion is stale even
     * when a replacement worker has not yet acquired the projection.</p>
     */
    public Freshness freshnessAt(Instant observedAt) {
        Instant exact = Objects.requireNonNull(
                observedAt, "observedAt");
        if (status == Status.QUARANTINED) {
            return Freshness.QUARANTINED;
        }
        if (lastAssessmentRef == null) {
            return status == Status.RUNNING
                    ? Freshness.REFRESHING
                    : Freshness.UNINITIALIZED;
        }
        if (status == Status.RUNNING) {
            return Freshness.REFRESHING;
        }
        return status == Status.QUEUED
                && exact.isBefore(freshUntil)
                ? Freshness.CURRENT
                : Freshness.STALE;
    }

    /** @return whether the projection can still be autonomously scheduled */
    public boolean active() {
        return status != Status.QUARANTINED;
    }

    /** Seals the mutable projection with a canonical content address. */
    public AuthoritativeOutcomeContinuousAssessmentProjection seal(
            ObjectMapper mapper) {
        AuthoritativeOutcomeContinuousAssessmentProjection material =
                withRecordFingerprint("");
        return material.withRecordFingerprint(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies the mutable projection fingerprint. */
    public void verify(ObjectMapper mapper) {
        if (recordFingerprint.isBlank()
                || !recordFingerprint.equals(
                seal(mapper).recordFingerprint())) {
            throw new IllegalArgumentException(
                    "continuous assessment projection fingerprint mismatch");
        }
    }

    AuthoritativeOutcomeContinuousAssessmentProjection
    withRecordFingerprint(String value) {
        return new AuthoritativeOutcomeContinuousAssessmentProjection(
                schemaVersion,
                scope,
                projectionId,
                populationRef,
                assessmentId,
                status,
                lastAssessmentRef,
                observationSetFingerprint,
                dispositionSetFingerprint,
                currentThrough,
                freshUntil,
                attemptCount,
                consecutiveFailures,
                nextEligibleAt,
                leaseOwnerFingerprint,
                leaseEpoch,
                leaseExpiresAt,
                failureCode,
                createdAt,
                updatedAt,
                terminalAt,
                value);
    }

    private static void validateState(
            Status status,
            Instant nextEligibleAt,
            String ownerFingerprint,
            Instant leaseExpiresAt,
            String failureCode,
            Instant updatedAt,
            Instant terminalAt) {
        boolean running = status == Status.RUNNING;
        if (running != (!ownerFingerprint.isBlank()
                && !Instant.EPOCH.equals(leaseExpiresAt))
                || running && !leaseExpiresAt.isAfter(updatedAt)
                || !running && (!ownerFingerprint.isBlank()
                || !Instant.EPOCH.equals(leaseExpiresAt))) {
            throw new IllegalArgumentException(
                    "continuous assessment lease fields do not match status");
        }
        boolean quarantined = status == Status.QUARANTINED;
        if (quarantined != (terminalAt != null)
                || terminalAt != null
                && !terminalAt.equals(updatedAt)
                || quarantined && failureCode.isBlank()
                || !quarantined && terminalAt != null
                || status == Status.RETRY_WAIT
                && failureCode.isBlank()
                || status == Status.QUEUED
                && !failureCode.isBlank()
                || running && !failureCode.isBlank()
                || nextEligibleAt.isBefore(updatedAt)
                && status != Status.RUNNING) {
            throw new IllegalArgumentException(
                    "continuous assessment state fields are inconsistent");
        }
    }

    private static String identifier(
            String value, String field) {
        String exact = normalized(value);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(
            String value) {
        String exact = normalized(value);
        if (!exact.isBlank()) {
            fingerprint(exact, "recordFingerprint");
        }
        return exact;
    }

    private static String fingerprint(
            String value, String field) {
        String exact = normalized(value);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
