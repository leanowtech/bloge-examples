package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free proof that a signed checkpoint was admitted against the exact durable state head.
 *
 * @param schemaVersion recovery-result protocol version
 * @param recoveryId unique recovery admission identity
 * @param checkpointId admitted checkpoint identity
 * @param checkpointFingerprint admitted checkpoint fingerprint
 * @param storeGenerationFingerprint admitted durable data-plane generation
 * @param descriptor current exact Session descriptor
 * @param runBinding exact binding for the next DAG run
 * @param recoveredAt server recovery-admission time
 * @param fingerprint canonical result fingerprint
 */
public record MirrorSessionRecoveryResult(
        String schemaVersion,
        String recoveryId,
        String checkpointId,
        String checkpointFingerprint,
        String storeGenerationFingerprint,
        MirrorSessionDescriptor descriptor,
        MirrorSessionRunBinding runBinding,
        Instant recoveredAt,
        String fingerprint
) {
    /** Current recovery-result protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionRecoveryResult.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:-]{0,511}");

    /** Validates exact checkpoint, descriptor, and run-binding closure. */
    public MirrorSessionRecoveryResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror Session recovery result schemaVersion");
        }
        recoveryId = identifier(recoveryId, "recoveryId");
        checkpointId = identifier(checkpointId, "checkpointId");
        checkpointFingerprint = MirrorStateProtocolSupport.fingerprint(
                checkpointFingerprint, "checkpointFingerprint");
        storeGenerationFingerprint = MirrorStateProtocolSupport.fingerprint(
                storeGenerationFingerprint, "storeGenerationFingerprint");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        runBinding = Objects.requireNonNull(runBinding, "runBinding");
        recoveredAt = Objects.requireNonNull(recoveredAt, "recoveredAt");
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "recovery result fingerprint");
        if (!descriptor.sessionId().equals(runBinding.sessionId())
                || !descriptor.stateFingerprint().equals(
                runBinding.expectedStateFingerprint())
                || descriptor.status() != MirrorSessionDescriptor.Status.ACTIVE
                || recoveredAt.isBefore(descriptor.updatedAt())
                || !descriptor.expiresAt().isAfter(recoveredAt)) {
            throw new IllegalArgumentException(
                    "recovery result does not bind one active exact Session head");
        }
    }

    /**
     * Creates a copy carrying a replacement canonical fingerprint.
     *
     * @return recovery-result copy with the supplied fingerprint
     */
    public MirrorSessionRecoveryResult withFingerprint(String value) {
        return new MirrorSessionRecoveryResult(
                schemaVersion, recoveryId, checkpointId,
                checkpointFingerprint, storeGenerationFingerprint,
                descriptor, runBinding, recoveredAt, value);
    }

    /** Keeps checkpoint and state fingerprint closure out of generic logs. */
    @Override
    public String toString() {
        return "MirrorSessionRecoveryResult[recoveryId=" + recoveryId
                + ", checkpointId=" + checkpointId
                + ", sessionId=" + descriptor.sessionId()
                + ", stateRevision=" + descriptor.stateRevision()
                + ", recoveredAt=" + recoveredAt + "]";
    }

    private static String identifier(String value, String field) {
        String normalized = MirrorStateProtocolSupport.required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " contains unsupported characters");
        }
        return normalized;
    }
}
