package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free durable head and reconciliation-work projection for one outcome observation.
 *
 * <p>The append-only observation table remains the evidence authority. This mutable record is only
 * a rebuildable head and work cursor. It duplicates exact lineage, reconciliation, scheduling, and
 * fencing coordinates and carries its own content address so partial SQL mutations fail closed on
 * read. Worker identities are represented only by domain-separated fingerprints.</p>
 *
 * @param schemaVersion exact inbox projection version
 * @param scope complete enterprise namespace
 * @param observationId stable observation lineage
 * @param currentRevision current append-only revision
 * @param currentObservationFingerprint current observation content address
 * @param inventoryRef exact Fidelity denominator
 * @param unitId exact denominator unit
 * @param cohortRef exact pre-treatment calibration cohort
 * @param reconciliation current fact-derived outcome
 * @param status current durable work state
 * @param attemptCount total acquired worker turns
 * @param consecutiveFailures current dependency-failure streak
 * @param nextEligibleAt database-time scheduling cursor
 * @param leaseOwnerFingerprint opaque current worker correlation, blank without a lease
 * @param leaseEpoch monotonic fencing generation
 * @param leaseExpiresAt database-time lease expiry, epoch without a lease
 * @param failureCode bounded stable failure reason
 * @param createdAt first admission time
 * @param updatedAt latest committed transition time
 * @param terminalAt settlement or quarantine time, null while active
 * @param recordFingerprint canonical mutable-projection fingerprint
 */
public record AuthoritativeOutcomeInboxEntry(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        String observationId,
        long currentRevision,
        String currentObservationFingerprint,
        MirrorArtifactRef inventoryRef,
        String unitId,
        MirrorArtifactRef cohortRef,
        AuthoritativeOutcomeObservation.Reconciliation reconciliation,
        Status status,
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
    /** Exact first-generation durable inbox projection version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeInboxEntry.v1";
    /** Maximum canonical mutable projection bytes. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            256 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern FAILURE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Enforces exact state, lease, and terminal-field correspondence. */
    public AuthoritativeOutcomeInboxEntry {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported authoritative outcome inbox schemaVersion");
        }
        scope = Objects.requireNonNull(scope, "scope");
        observationId = identifier(
                observationId, "observationId");
        if (currentRevision < 1) {
            throw new IllegalArgumentException(
                    "currentRevision must be positive");
        }
        currentObservationFingerprint = fingerprint(
                currentObservationFingerprint,
                "currentObservationFingerprint");
        if (inventoryRef == null
                || !DomainFidelityInventory.ARTIFACT_KIND.equals(
                inventoryRef.kind())) {
            throw new IllegalArgumentException(
                    "inventoryRef must be exact");
        }
        unitId = identifier(unitId, "unitId");
        if (cohortRef == null
                || !"OUTCOME_CALIBRATION_COHORT".equals(
                cohortRef.kind())) {
            throw new IllegalArgumentException(
                    "cohortRef must be exact");
        }
        reconciliation = Objects.requireNonNull(
                reconciliation, "reconciliation");
        status = Objects.requireNonNull(status, "status");
        if (attemptCount < 0
                || consecutiveFailures < 0
                || leaseEpoch < 0) {
            throw new IllegalArgumentException(
                    "inbox counters cannot be negative");
        }
        nextEligibleAt = Objects.requireNonNull(
                nextEligibleAt, "nextEligibleAt");
        leaseOwnerFingerprint = normalized(
                leaseOwnerFingerprint);
        if (!leaseOwnerFingerprint.isBlank()) {
            fingerprint(
                    leaseOwnerFingerprint,
                    "leaseOwnerFingerprint");
        }
        leaseExpiresAt = Objects.requireNonNull(
                leaseExpiresAt, "leaseExpiresAt");
        failureCode = normalized(failureCode);
        if (!failureCode.isBlank()
                && !FAILURE_CODE.matcher(
                failureCode).matches()) {
            throw new IllegalArgumentException(
                    "failureCode is invalid");
        }
        createdAt = Objects.requireNonNull(
                createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(
                updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "updatedAt cannot precede createdAt");
        }
        recordFingerprint = optionalFingerprint(
                recordFingerprint);
        validateState(
                reconciliation,
                status,
                nextEligibleAt,
                leaseOwnerFingerprint,
                leaseExpiresAt,
                failureCode,
                updatedAt,
                terminalAt);
    }

    /** Durable reconciliation work states. */
    public enum Status {
        QUEUED,
        RUNNING,
        SETTLED,
        QUARANTINED
    }

    /** @return whether another autonomous connector turn may be scheduled */
    public boolean active() {
        return status == Status.QUEUED
                || status == Status.RUNNING;
    }

    /** Seals the mutable projection with a canonical content address. */
    public AuthoritativeOutcomeInboxEntry seal(
            ObjectMapper mapper) {
        AuthoritativeOutcomeInboxEntry material =
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
                    "authoritative outcome inbox projection fingerprint mismatch");
        }
    }

    AuthoritativeOutcomeInboxEntry withRecordFingerprint(
            String value) {
        return new AuthoritativeOutcomeInboxEntry(
                schemaVersion,
                scope,
                observationId,
                currentRevision,
                currentObservationFingerprint,
                inventoryRef,
                unitId,
                cohortRef,
                reconciliation,
                status,
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
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation,
            Status status,
            Instant nextEligibleAt,
            String leaseOwnerFingerprint,
            Instant leaseExpiresAt,
            String failureCode,
            Instant updatedAt,
            Instant terminalAt) {
        boolean leased = status == Status.RUNNING;
        if (leased != (!leaseOwnerFingerprint.isBlank()
                && !Instant.EPOCH.equals(leaseExpiresAt))
                || leased && !leaseExpiresAt.isAfter(updatedAt)
                || !leased && (!leaseOwnerFingerprint.isBlank()
                || !Instant.EPOCH.equals(leaseExpiresAt))) {
            throw new IllegalArgumentException(
                    "inbox lease fields do not match status");
        }
        boolean terminal = status == Status.SETTLED
                || status == Status.QUARANTINED;
        if (terminal != (terminalAt != null)
                || terminalAt != null
                && !terminalAt.equals(updatedAt)
                || status == Status.SETTLED
                && (reconciliation
                == AuthoritativeOutcomeObservation
                .Reconciliation.PENDING
                || !failureCode.isBlank())
                || status == Status.QUARANTINED
                && failureCode.isBlank()
                || !terminal && !failureCode.isBlank()
                && status != Status.QUEUED) {
            throw new IllegalArgumentException(
                    "inbox terminal fields do not match status");
        }
        if (status == Status.QUEUED
                && reconciliation
                != AuthoritativeOutcomeObservation
                .Reconciliation.PENDING
                || status == Status.RUNNING
                && reconciliation
                != AuthoritativeOutcomeObservation
                .Reconciliation.PENDING
                || status == Status.SETTLED
                && !Instant.EPOCH.equals(nextEligibleAt)
                || status == Status.QUARANTINED
                && !Instant.EPOCH.equals(nextEligibleAt)) {
            throw new IllegalArgumentException(
                    "inbox scheduling fields do not match reconciliation state");
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

    private static String fingerprint(
            String value, String field) {
        String exact = normalized(value);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 fingerprint");
        }
        return exact;
    }

    private static String optionalFingerprint(
            String value) {
        String exact = normalized(value);
        if (!exact.isBlank()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "recordFingerprint is invalid");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
