package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Payload-free regional command and immutable observation authority.
 *
 * <p>The implementation owns only protocol exchange. Production binding resolution, payload-vault
 * access, workload credentials, and source request/response values remain inside the independently
 * deployed TEE sidecar.</p>
 */
public interface OnlineReadOnlyShadowBaselineAuthority {
    /**
     * Reports current sidecar safety and service readiness.
     *
     * @return whether a fresh strict sidecar capability probe currently passes
     */
    boolean ready();

    /**
     * Executes or idempotently resolves one exact payload-free baseline command.
     *
     * @param command exact immutable source command
     * @return untrusted observation requiring detached-signature verification
     */
    OnlineReadOnlyShadowBaselineObservation observe(
            OnlineReadOnlyShadowBaselineCommand command);

    /**
     * Reads one immutable observation by complete content-addressed coordinates.
     *
     * @param scope complete enterprise scope
     * @param observationRef exact online baseline observation reference
     * @return untrusted observation requiring detached-signature verification
     */
    OnlineReadOnlyShadowBaselineObservation resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef observationRef);

    /**
     * Creates the default fail-closed authority.
     *
     * @return a fail-closed authority used when no sidecar is configured
     */
    static OnlineReadOnlyShadowBaselineAuthority unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Remote protocol failure family. */
    enum Failure {
        /** Transport, timeout, overload, or remote service availability failure. */
        UNAVAILABLE,
        /** Malformed, downgraded, unauthorized, or otherwise rejected exchange. */
        REJECTED,
        /** Exact immutable observation coordinates do not exist. */
        NOT_FOUND
    }

    /** Bounded payload-free remote failure safe for data-plane classification. */
    final class AuthorityException extends RuntimeException {
        /** Bounded failure family. */
        private final Failure failure;
        /** Stable payload-free reason. */
        private final String reasonCode;

        /**
         * Creates one classified authority failure.
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
         * Creates one classified authority failure with a non-projected cause.
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
                        "online baseline authority reasonCode is invalid");
            }
        }

        /**
         * Returns the bounded failure family.
         *
         * @return bounded failure family
         */
        public Failure failure() {
            return failure;
        }

        /**
         * Returns the stable payload-free diagnostic.
         *
         * @return stable payload-free reason code
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
            implements OnlineReadOnlyShadowBaselineAuthority {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public OnlineReadOnlyShadowBaselineObservation observe(
                OnlineReadOnlyShadowBaselineCommand command) {
            Objects.requireNonNull(command, "command");
            throw new AuthorityException(
                    Failure.UNAVAILABLE,
                    "ONLINE_BASELINE_AUTHORITY_UNAVAILABLE");
        }

        @Override
        public OnlineReadOnlyShadowBaselineObservation resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef observationRef) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(
                    observationRef, "observationRef");
            throw new AuthorityException(
                    Failure.UNAVAILABLE,
                    "ONLINE_BASELINE_AUTHORITY_UNAVAILABLE");
        }
    }
}
