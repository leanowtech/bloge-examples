package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free operator projection for one durable batch-evidence finalization.
 *
 * <p>The projection exposes retry, lease, quarantine, and completion coordinates without worker
 * identity, provider diagnostics, signature bytes, fixture values, or business payload. It is
 * suitable for Author UX, deep links, alerts, and ANEKE incident evidence.</p>
 *
 * @param schemaVersion exact public projection version
 * @param jobId stable Scenario batch identity
 * @param state durable finalization state
 * @param attemptCount durable preparation attempts
 * @param nextEligibleAt database time of the next retry
 * @param leaseExpiresAt active claim expiry, otherwise epoch
 * @param signingStartedAt first-claim time frozen into signature material
 * @param failureCode latest stable payload-free failure code
 * @param evidenceBundleFingerprint terminal evidence identity, or blank
 * @param createdAt outbox admission time
 * @param updatedAt latest control transition time
 * @param finalizedAt atomic terminal publication time, or null
 */
public record ScenarioRehearsalBatchFinalizationStatus(
        String schemaVersion,
        String jobId,
        State state,
        int attemptCount,
        Instant nextEligibleAt,
        Instant leaseExpiresAt,
        Instant signingStartedAt,
        String failureCode,
        String evidenceBundleFingerprint,
        Instant createdAt,
        Instant updatedAt,
        Instant finalizedAt
) {
    /** Current public finalization status version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchFinalizationStatus.v1";
    private static final Pattern JOB_ID =
            Pattern.compile("scenario-batch-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Public finalization state vocabulary. */
    public enum State {
        PENDING,
        SIGNING,
        RETRY_WAIT,
        QUARANTINED,
        FINALIZED
    }

    /** Enforces retry, lease, quarantine, and terminal correspondence. */
    public ScenarioRehearsalBatchFinalizationStatus {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch finalization status version");
        }
        jobId = normalized(jobId);
        state = Objects.requireNonNull(state, "state");
        nextEligibleAt = Objects.requireNonNull(
                nextEligibleAt, "nextEligibleAt");
        leaseExpiresAt = Objects.requireNonNull(
                leaseExpiresAt, "leaseExpiresAt");
        signingStartedAt = Objects.requireNonNull(
                signingStartedAt, "signingStartedAt");
        failureCode = normalized(
                failureCode).toUpperCase(
                java.util.Locale.ROOT);
        evidenceBundleFingerprint = normalized(
                evidenceBundleFingerprint);
        createdAt = Objects.requireNonNull(
                createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(
                updatedAt, "updatedAt");
        boolean finalized =
                state == State.FINALIZED;
        if (!JOB_ID.matcher(jobId).matches()
                || attemptCount < 0
                || !failureCode.isBlank()
                && !CODE.matcher(failureCode).matches()
                || !evidenceBundleFingerprint.isBlank()
                && !FINGERPRINT.matcher(
                evidenceBundleFingerprint).matches()
                || updatedAt.isBefore(createdAt)
                || state == State.SIGNING
                && (attemptCount < 1
                || signingStartedAt.equals(Instant.EPOCH)
                || !leaseExpiresAt.isAfter(updatedAt))
                || state != State.SIGNING
                && !leaseExpiresAt.equals(Instant.EPOCH)
                || finalized != (finalizedAt != null
                && !evidenceBundleFingerprint.isBlank())
                || !finalized
                && finalizedAt != null) {
            throw new IllegalArgumentException(
                    "Scenario batch finalization status is inconsistent");
        }
    }

    /** Creates a public identity-safe projection from the verified durable snapshot. */
    public static ScenarioRehearsalBatchFinalizationStatus from(
            ScenarioRehearsalBatchRepository.FinalizationSnapshot
                    snapshot) {
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                exact = Objects.requireNonNull(
                snapshot, "snapshot");
        return new ScenarioRehearsalBatchFinalizationStatus(
                SCHEMA_VERSION,
                exact.jobId(),
                State.valueOf(exact.state().name()),
                exact.attemptCount(),
                exact.nextEligibleAt(),
                exact.leaseExpiresAt(),
                exact.signingStartedAt(),
                exact.lastFailureCode(),
                exact.evidenceBundleFingerprint(),
                exact.createdAt(),
                exact.updatedAt(),
                exact.finalizedAt());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
