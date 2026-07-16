package com.leanowtech.bloge.gateway.testkit;

import java.time.Instant;
import java.util.Set;

/**
 * Durable consumer state required to detect trust-log rollback, fork, and pin resurrection.
 *
 * @param trustDomain bound governance trust domain
 * @param logId bound append-only log identity
 * @param sequence last fully verified publication sequence
 * @param publicationFingerprint fingerprint at that sequence
 * @param recoveryEpoch last verified compromised-pin recovery generation
 * @param publishedAt last verified publication time used to detect clock rollback across pages
 * @param permanentlyRevokedPins fingerprints that may never become accepted again
 */
public record EvidenceTrustCheckpoint(
        String trustDomain,
        String logId,
        long sequence,
        String publicationFingerprint,
        long recoveryEpoch,
        Instant publishedAt,
        Set<String> permanentlyRevokedPins
) {
    /** Normalizes immutable checkpoint state and validates identity. */
    public EvidenceTrustCheckpoint {
        trustDomain = normalized(trustDomain);
        logId = normalized(logId);
        publicationFingerprint = normalized(publicationFingerprint);
        permanentlyRevokedPins = permanentlyRevokedPins == null
                ? Set.of() : Set.copyOf(permanentlyRevokedPins);
        if (trustDomain.isBlank() || logId.isBlank() || sequence < 1 || recoveryEpoch < 0
                || publishedAt == null
                || !publicationFingerprint.matches("sha256:[0-9a-f]{64}")
                || permanentlyRevokedPins.stream()
                .anyMatch(value -> !normalized(value).matches("sha256:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Evidence trust checkpoint is invalid");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
