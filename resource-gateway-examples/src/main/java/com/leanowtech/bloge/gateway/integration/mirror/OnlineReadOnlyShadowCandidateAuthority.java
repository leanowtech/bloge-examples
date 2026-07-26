package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Isolated sealed-plan execution and immutable candidate-evidence authority.
 *
 * <p>A production implementation resolves the command's payload-vault receipt inside the
 * regional data plane, executes only the exact sealed Mirror plan, and returns a signed
 * {@link MirrorEvidenceBundle}. The returned bundle is untrusted until Resource Gateway
 * independently verifies its content addresses and detached signature.</p>
 */
public interface OnlineReadOnlyShadowCandidateAuthority {
    /**
     * Reports current isolated runtime and evidence-store readiness.
     *
     * @return whether new execution and exact evidence resolution are currently usable
     */
    boolean ready();

    /**
     * Executes or idempotently resolves one exact candidate command.
     *
     * @param command exact payload-free candidate command
     * @return untrusted signed Mirror evidence requiring independent verification
     */
    MirrorEvidenceBundle execute(
            OnlineReadOnlyShadowCandidateCommand command);

    /**
     * Resolves one immutable candidate bundle by complete content-addressed coordinates.
     *
     * @param scope complete enterprise scope
     * @param evidenceRef exact Mirror evidence bundle reference
     * @return untrusted signed Mirror evidence requiring independent verification
     */
    MirrorEvidenceBundle resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef evidenceRef);

    /**
     * Creates the default fail-closed candidate authority.
     *
     * @return unavailable candidate authority
     */
    static OnlineReadOnlyShadowCandidateAuthority unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Remote or isolated candidate failure family. */
    enum Failure {
        /** Runtime, evidence store, timeout, overload, or dependency is unavailable. */
        UNAVAILABLE,
        /** Command is malformed, unauthorized, conflicting, or otherwise rejected. */
        REJECTED,
        /** Exact immutable candidate evidence coordinates do not exist. */
        NOT_FOUND
    }

    /** Bounded payload-free failure safe for data-plane classification and queue state. */
    final class AuthorityException extends RuntimeException {
        private final Failure failure;
        private final String reasonCode;

        /**
         * Creates one classified candidate-authority failure.
         *
         * @param failure bounded failure family
         * @param reasonCode stable payload-free diagnostic code
         */
        public AuthorityException(
                Failure failure,
                String reasonCode) {
            this(failure, reasonCode, null);
        }

        /**
         * Creates one classified failure with an internal non-projected cause.
         *
         * @param failure bounded failure family
         * @param reasonCode stable payload-free diagnostic code
         * @param cause internal cause that must never enter durable evidence
         */
        public AuthorityException(
                Failure failure,
                String reasonCode,
                Throwable cause) {
            super(normalized(reasonCode), cause);
            this.failure = Objects.requireNonNull(
                    failure, "failure");
            this.reasonCode = normalized(reasonCode);
            if (!this.reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "online candidate authority reasonCode is invalid");
            }
        }

        /**
         * Returns the bounded failure family.
         *
         * @return bounded candidate failure family
         */
        public Failure failure() {
            return failure;
        }

        /**
         * Returns the stable payload-free diagnostic.
         *
         * @return stable reason code
         */
        public String reasonCode() {
            return reasonCode;
        }

        private static String normalized(
                String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements OnlineReadOnlyShadowCandidateAuthority {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public MirrorEvidenceBundle execute(
                OnlineReadOnlyShadowCandidateCommand command) {
            Objects.requireNonNull(command, "command");
            throw new AuthorityException(
                    Failure.UNAVAILABLE,
                    "ONLINE_CANDIDATE_AUTHORITY_UNAVAILABLE");
        }

        @Override
        public MirrorEvidenceBundle resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef evidenceRef) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(
                    evidenceRef, "evidenceRef");
            throw new AuthorityException(
                    Failure.UNAVAILABLE,
                    "ONLINE_CANDIDATE_AUTHORITY_UNAVAILABLE");
        }
    }
}
