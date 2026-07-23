package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Full-scope append-only store for isolation attestations and irreversible local status.
 *
 * <p>Implementations atomically commit an immutable attestation body, its initial active status,
 * and the current stream floor. Revocation appends a second status and advances only the status
 * pointer. Historical rows remain immutable, while serving methods expose only the current floor
 * to avoid creating a downgrade oracle.</p>
 */
public interface MirrorDeploymentIsolationAttestationRepository {
    /**
     * Appends the exact bootstrap revision or one continuous successor.
     *
     * @param candidate canonical active bundle verified against current authority
     * @param bootstrapRevision exact locally pinned first revision for an empty stream
     * @return newly committed, idempotently recovered, or already-revoked current bundle
     * @throws Violation when a full-scope, integrity, or monotonic invariant fails
     */
    MirrorDeploymentIsolationAttestationBundle append(
            MirrorDeploymentIsolationAttestationBundle candidate,
            long bootstrapRevision);

    /**
     * Irreversibly revokes the exact current attestation and active status.
     *
     * @param stream exact full-scope stream
     * @param expected optimistic current coordinates
     * @param reason closed non-accepted revocation reason
     * @param revokedAt trusted control-plane transition time
     * @return current revoked bundle, including idempotent retry recovery
     * @throws Violation when the expected current state or stream invariants fail
     */
    MirrorDeploymentIsolationAttestationBundle revoke(
            StreamIdentity stream,
            CurrentExpectation expected,
            MirrorDeploymentIsolationAttestationStatusPublication.Reason reason,
            Instant revokedAt);

    /**
     * Reads the atomic bundle at the durable current floor.
     *
     * @param stream exact full-scope stream identity
     * @return current atomic bundle, or empty before bootstrap
     */
    Optional<MirrorDeploymentIsolationAttestationBundle> current(StreamIdentity stream);

    /**
     * Reads a content address only when every coordinate still equals the current floor.
     *
     * @param stream exact full-scope stream identity
     * @param expected exact attestation and status coordinates
     * @return current atomic bundle when every coordinate matches
     */
    Optional<MirrorDeploymentIsolationAttestationBundle> current(
            StreamIdentity stream, CurrentExpectation expected);

    /**
     * Complete identity of one attestation revision stream.
     *
     * @param scope complete enterprise scope
     * @param deployment immutable workload generation
     * @param keySetId exact authority publication stream
     * @param attestationId stable external attestation stream
     */
    record StreamIdentity(
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            String keySetId,
            String attestationId
    ) {
        /** Validates complete lookup coordinates before persistence access. */
        public StreamIdentity {
            scope = Objects.requireNonNull(scope, "scope");
            deployment = Objects.requireNonNull(deployment, "deployment");
            keySetId = required(keySetId, "keySetId");
            attestationId = required(attestationId, "attestationId");
        }

        /**
         * Derives the complete stream from one canonical bundle.
         *
         * @param bundle canonical current bundle
         * @return exact stream identity
         */
        public static StreamIdentity from(MirrorDeploymentIsolationAttestationBundle bundle) {
            Objects.requireNonNull(bundle, "bundle");
            return new StreamIdentity(bundle.scope(), bundle.attestation().material().deployment(),
                    bundle.authorityKeySetRef().id(),
                    bundle.attestation().material().attestationId());
        }
    }

    /**
     * Optimistic exact-current coordinates used by revocation and content-addressed reads.
     *
     * @param attestationRevision expected current external revision
     * @param attestationFingerprint expected current external content address
     * @param statusRevision expected current local status revision
     * @param statusFingerprint expected current local status content address
     */
    record CurrentExpectation(
            long attestationRevision,
            String attestationFingerprint,
            long statusRevision,
            String statusFingerprint
    ) {
        /** Validates positive revisions and canonical fingerprints. */
        public CurrentExpectation {
            if (attestationRevision < 1 || statusRevision < 1 || statusRevision > 2) {
                throw new IllegalArgumentException("current expectation revisions are invalid");
            }
            attestationFingerprint = fingerprint(
                    attestationFingerprint, "attestationFingerprint");
            statusFingerprint = fingerprint(statusFingerprint, "statusFingerprint");
        }

        /**
         * Derives exact current coordinates from one bundle.
         *
         * @param bundle canonical current bundle
         * @return exact optimistic expectation
         */
        public static CurrentExpectation from(MirrorDeploymentIsolationAttestationBundle bundle) {
            Objects.requireNonNull(bundle, "bundle");
            return new CurrentExpectation(bundle.attestation().material().revision(),
                    bundle.attestation().attestationFingerprint(),
                    bundle.status().material().statusRevision(),
                    bundle.status().statusFingerprint());
        }
    }

    /** Closed repository rejection vocabulary suitable for stable service mapping. */
    enum Reason {
        /** Candidate fingerprints or persisted canonical material are invalid. */
        CANONICAL_INVALID,
        /** Candidate scope, deployment, key-set, or attestation identity drifted. */
        IDENTITY_MISMATCH,
        /** First accepted revision disagrees with the operator-owned bootstrap floor. */
        BOOTSTRAP_REVISION_MISMATCH,
        /** Candidate revision is below the durable floor. */
        REVISION_ROLLBACK,
        /** Another fingerprint occupies the candidate external revision. */
        REVISION_FORK,
        /** Candidate skipped one or more external revisions. */
        REVISION_GAP,
        /** A content address already belongs to another immutable stream. */
        CONTENT_ADDRESS_CONFLICT,
        /** Revocation expectation or terminal reason disagrees with current status. */
        STATUS_CONFLICT,
        /** Persisted index, body, status, or floor is internally inconsistent. */
        STORED_STATE_CORRUPT
    }

    /** Repository invariant failure with no attestation or policy payload in its message. */
    final class Violation extends RuntimeException {
        /** Closed rejection category safe for service-level mapping. */
        private final Reason reason;

        /**
         * Creates one payload-free invariant failure.
         *
         * @param reason closed machine-readable rejection reason
         */
        public Violation(Reason reason) {
            super("Mirror deployment-isolation attestation repository rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /**
         * Returns the closed rejection category.
         *
         * @return machine-readable repository reason
         */
        public Reason reason() {
            return reason;
        }
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
