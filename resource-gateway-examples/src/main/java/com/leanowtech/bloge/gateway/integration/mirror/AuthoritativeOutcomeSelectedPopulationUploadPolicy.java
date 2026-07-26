package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.util.Objects;

/**
 * Server-owned resource bounds for selected-population staged uploads.
 *
 * @param maximumActiveUploadsPerScope maximum open or finalizing uploads in one exact scope
 * @param maximumBytesPerUpload maximum encoded chunk bytes retained by one upload
 * @param maximumStagedBytesPerScope maximum encoded chunk bytes retained by one exact scope
 * @param uploadTtl lifetime of an incomplete upload
 * @param finalizationLease lease used to fence crashed finalizers
 * @param terminalRetention retention of finalized, aborted, or expired upload metadata
 */
public record AuthoritativeOutcomeSelectedPopulationUploadPolicy(
        int maximumActiveUploadsPerScope,
        long maximumBytesPerUpload,
        long maximumStagedBytesPerScope,
        Duration uploadTtl,
        Duration finalizationLease,
        Duration terminalRetention
) {
    /** Conservative defaults for the non-production product surface. */
    public static AuthoritativeOutcomeSelectedPopulationUploadPolicy
    defaults() {
        return new AuthoritativeOutcomeSelectedPopulationUploadPolicy(
                16,
                256L * 1024 * 1024,
                1024L * 1024 * 1024,
                Duration.ofHours(24),
                Duration.ofMinutes(2),
                Duration.ofDays(7));
    }

    /** Enforces bounded positive resource and lifecycle limits. */
    public AuthoritativeOutcomeSelectedPopulationUploadPolicy {
        uploadTtl = duration(uploadTtl, "uploadTtl");
        finalizationLease = duration(
                finalizationLease, "finalizationLease");
        terminalRetention = duration(
                terminalRetention, "terminalRetention");
        if (maximumActiveUploadsPerScope < 1
                || maximumActiveUploadsPerScope > 1_024
                || maximumBytesPerUpload < 1
                || maximumStagedBytesPerScope
                < maximumBytesPerUpload
                || maximumStagedBytesPerScope
                > 1024L * 1024 * 1024 * 1024
                || uploadTtl.compareTo(
                Duration.ofMinutes(5)) < 0
                || uploadTtl.compareTo(
                Duration.ofDays(30)) > 0
                || finalizationLease.compareTo(
                Duration.ofSeconds(5)) < 0
                || finalizationLease.compareTo(
                Duration.ofHours(1)) > 0
                || terminalRetention.compareTo(
                uploadTtl) < 0
                || terminalRetention.compareTo(
                Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException(
                    "selected-population upload policy is outside supported bounds");
        }
    }

    private static Duration duration(
            Duration value, String field) {
        Duration exact = Objects.requireNonNull(
                value, field);
        if (exact.isZero() || exact.isNegative()) {
            throw new IllegalArgumentException(
                    field + " must be positive");
        }
        return exact;
    }
}
