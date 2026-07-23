package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Result of a newly committed or exactly replayed session command.
 *
 * @param schemaVersion result wire version
 * @param descriptor payload-free current session projection
 * @param receipt exact original or newly committed transaction receipt
 * @param replayed whether the idempotency journal supplied an existing receipt
 */
public record MirrorSessionCommandResult(
        String schemaVersion,
        MirrorSessionDescriptor descriptor,
        SessionStateSpace.TransactionReceipt receipt,
        boolean replayed
) {
    /** Current session command-result version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionCommandResult.v1";

    /** Validates one complete state-transition result. */
    public MirrorSessionCommandResult {
        schemaVersion = version(schemaVersion);
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        receipt = Objects.requireNonNull(receipt, "receipt");
        if ((!replayed && receipt.revisionAfter() != descriptor.stateRevision())
                || (replayed && receipt.revisionAfter() > descriptor.stateRevision())) {
            throw new IllegalArgumentException(
                    "command result receipt revision is inconsistent with the descriptor");
        }
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported mirror session command result schemaVersion");
        }
        return normalized;
    }
}
