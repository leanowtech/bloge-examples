package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable payload-free receipt for one accepted finalization remediation.
 *
 * <p>The receipt links the superseded and replacement immutable intents, the database commit
 * time, the renewed retention floor, and a content address. Exact command replay returns the same
 * receipt even after later control transitions.</p>
 *
 * @param schemaVersion exact receipt protocol version
 * @param receiptFingerprint canonical receipt content address
 * @param commandId caller-stable remediation identity
 * @param jobId stable Scenario batch identity
 * @param remediationGeneration monotonic accepted remediation generation for the job
 * @param previousIntentFingerprint superseded quarantined finalization intent
 * @param currentIntentFingerprint newly queued immutable finalization intent
 * @param previousAttemptCount quarantined attempt count superseded by this command
 * @param acceptedAt database-authoritative transaction time
 * @param effectiveRetainUntil renewed minimum evidence-retention deadline
 * @param reasonCode bounded machine-readable owner reason
 */
public record ScenarioRehearsalBatchFinalizationRemediationReceipt(
        String schemaVersion,
        String receiptFingerprint,
        String commandId,
        String jobId,
        long remediationGeneration,
        String previousIntentFingerprint,
        String currentIntentFingerprint,
        int previousAttemptCount,
        Instant acceptedAt,
        Instant effectiveRetainUntil,
        String reasonCode
) {
    /** Current finalization-remediation receipt version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchFinalizationRemediationReceipt.v1";
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern JOB_ID =
            Pattern.compile("scenario-batch-[a-f0-9]{64}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Enforces immutable intent lineage, retention, and content-address shape. */
    public ScenarioRehearsalBatchFinalizationRemediationReceipt {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        receiptFingerprint = optionalFingerprint(
                receiptFingerprint, "receiptFingerprint");
        commandId = normalized(commandId);
        jobId = normalized(jobId);
        previousIntentFingerprint = fingerprint(
                previousIntentFingerprint, "previousIntentFingerprint");
        currentIntentFingerprint = fingerprint(
                currentIntentFingerprint, "currentIntentFingerprint");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        effectiveRetainUntil = Objects.requireNonNull(
                effectiveRetainUntil, "effectiveRetainUntil");
        reasonCode = normalized(reasonCode).toUpperCase(Locale.ROOT);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !IDENTIFIER.matcher(commandId).matches()
                || !JOB_ID.matcher(jobId).matches()
                || remediationGeneration < 1
                || previousIntentFingerprint.equals(currentIntentFingerprint)
                || previousAttemptCount < 1
                || acceptedAt.isBefore(Instant.EPOCH)
                || !effectiveRetainUntil.isAfter(acceptedAt)
                || !CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "Scenario batch finalization remediation receipt is inconsistent");
        }
    }

    /** Returns this receipt carrying its canonical content address. */
    public ScenarioRehearsalBatchFinalizationRemediationReceipt
    withFingerprint(String value) {
        return new ScenarioRehearsalBatchFinalizationRemediationReceipt(
                schemaVersion,
                value,
                commandId,
                jobId,
                remediationGeneration,
                previousIntentFingerprint,
                currentIntentFingerprint,
                previousAttemptCount,
                acceptedAt,
                effectiveRetainUntil,
                reasonCode);
    }

    private static String fingerprint(String value, String field) {
        String normalized = normalized(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String optionalFingerprint(String value, String field) {
        String normalized = normalized(value);
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
