package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic authority for externally verified control-plane certificate status.
 *
 * <p>The floor verifies every publication against database time, a deployment-pinned cursor
 * baseline, the exact governed target inventory, and the previous publication fingerprint. It
 * rejects rollback, gaps, forks, publication-id reuse, status resurrection, and direct storage
 * drift before exposing any status to a request-path cache.</p>
 */
public interface ControlPlaneCertificateStatusFloor {

    /** Closed acceptance outcomes for one exact status publication. */
    enum AcceptanceStatus {
        /** A new contiguous complete status publication became the durable head. */
        APPLIED,
        /** The exact durable head was submitted again without mutation. */
        REPLAYED
    }

    /**
     * Exact configured target inventory entry.
     *
     * @param targetId stable governed transport target
     */
    record ExpectedTarget(String targetId) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects blank or ambiguous target identities. */
        public ExpectedTarget {
            targetId = normalized(targetId);
            if (!IDENTIFIER.matcher(targetId).matches()) {
                throw invalid("Control-plane certificate status target is invalid");
            }
        }
    }

    /**
     * Tamper-checked durable status head.
     *
     * @param schemaVersion snapshot protocol version
     * @param deploymentScopeId exact deployment scope
     * @param baselineSequence deployment-pinned source cursor baseline
     * @param baselinePublicationFingerprint deployment-pinned predecessor fingerprint
     * @param sequence current sequence, equal to baseline before the first publication
     * @param publicationId current publication identity, empty before the first publication
     * @param publicationFingerprint current or baseline publication fingerprint
     * @param issuedAt current publication issue time, null before the first publication
     * @param expiresAt exclusive current status freshness deadline, null before first publication
     * @param observedAt database acceptance time, null before the first publication
     * @param targets complete current target status list, empty before the first publication
     */
    record Snapshot(
            String schemaVersion,
            String deploymentScopeId,
            long baselineSequence,
            String baselinePublicationFingerprint,
            long sequence,
            String publicationId,
            String publicationFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt,
            List<ControlPlaneCertificateStatusPublication.TargetStatus> targets) {

        /** Current durable certificate-status snapshot protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusFloorSnapshot.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Validates the all-or-none initial and live snapshot forms. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            deploymentScopeId = normalized(deploymentScopeId);
            baselinePublicationFingerprint = normalized(baselinePublicationFingerprint);
            publicationId = normalized(publicationId);
            publicationFingerprint = normalized(publicationFingerprint);
            List<ControlPlaneCertificateStatusPublication.TargetStatus> supplied =
                    Objects.requireNonNull(targets, "targets");
            boolean initialized = !publicationId.isBlank();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || baselineSequence < 0 || sequence < baselineSequence
                    || !FINGERPRINT.matcher(baselinePublicationFingerprint).matches()
                    || !FINGERPRINT.matcher(publicationFingerprint).matches()
                    || initialized && (!IDENTIFIER.matcher(publicationId).matches()
                    || sequence <= baselineSequence || issuedAt == null || expiresAt == null
                    || observedAt == null || supplied.isEmpty()
                    || supplied.size() > 128 || supplied.stream().anyMatch(Objects::isNull)
                    || !expiresAt.isAfter(issuedAt) || !expiresAt.isAfter(observedAt))
                    || !initialized && (sequence != baselineSequence
                    || !publicationFingerprint.equals(baselinePublicationFingerprint)
                    || issuedAt != null || expiresAt != null || observedAt != null
                    || !supplied.isEmpty())) {
                throw invalid("Control-plane certificate status snapshot is invalid");
            }
            String previousTarget = "";
            for (ControlPlaneCertificateStatusPublication.TargetStatus target : supplied) {
                if (target.targetId().compareTo(previousTarget) <= 0) {
                    throw invalid("Control-plane certificate status snapshot is invalid");
                }
                previousTarget = target.targetId();
            }
            targets = List.copyOf(supplied);
        }

        /** @return whether at least one signed publication has been durably accepted */
        public boolean initialized() {
            return !publicationId.isBlank();
        }

        /** @return whether the current complete publication remains fresh at {@code now} */
        public boolean freshAt(Instant now) {
            return initialized() && now != null && now.isBefore(expiresAt);
        }
    }

    /**
     * Result of accepting or exactly replaying one publication.
     *
     * @param status bounded mutation outcome
     * @param snapshot exact durable head after the operation
     */
    record Acceptance(AcceptanceStatus status, Snapshot snapshot) {
        /** Requires a non-null outcome and durable state. */
        public Acceptance {
            status = Objects.requireNonNull(status, "status");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * Verifies and atomically accepts one contiguous complete status publication.
     *
     * @param publication untrusted externally supplied publication
     * @return applied or exact-replay result
     */
    Acceptance accept(ControlPlaneCertificateStatusPublication publication);

    /** @return current tamper-checked status head */
    Snapshot snapshot();

    /** @return true only when state survives process restart */
    boolean durable();

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
