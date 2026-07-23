package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;

/** Deployment-owned authority for admitting and committing certifiable Mirror runs. */
public interface MirrorDeploymentIsolationRunTrustAuthority {
    /**
     * Captures current active deployment trust for one exact execution scope.
     *
     * @param scope authenticated enterprise scope
     * @return verified payload-free admission
     * @throws TrustException when positive deployment trust is unavailable
     */
    MirrorDeploymentIsolationRunTrust.Admission admit(CapabilitySnapshot.Scope scope);

    /**
     * Rechecks the same stable decision after execution and produces signed evidence facts.
     *
     * @param admission exact pre-execution admission
     * @param startedAt observed execution start
     * @param completedAt observed execution completion
     * @return portable double-observation binding
     * @throws TrustException when trust changed or does not cover the complete execution window
     */
    MirrorDeploymentIsolationRunTrust.Binding confirm(
            MirrorDeploymentIsolationRunTrust.Admission admission,
            Instant startedAt,
            Instant completedAt);

    /**
     * Acquires a local read permit for the exact evidence binding until transaction completion.
     *
     * @param scope exact evidence scope
     * @param binding binding already signed into terminal evidence
     * @return permit that must be closed exactly once
     * @throws TrustException when the local current decision no longer matches
     */
    CommitPermit acquireCommitPermit(
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationRunTrust.Binding binding);

    /** @return whether this authority can currently attempt positive admission */
    boolean available();

    /** Creates a fail-closed placeholder for deployments without an isolation agent. */
    static MirrorDeploymentIsolationRunTrustAuthority unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Local linearization permit held through evidence transaction completion. */
    interface CommitPermit extends AutoCloseable {
        /** Releases the local agent generation permit. */
        @Override
        void close();
    }

    /** Stable payload-free trust denial. */
    final class TrustException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String reasonCode;

        /** @param reasonCode bounded machine-readable denial reason */
        public TrustException(String reasonCode) {
            super("mirror deployment isolation run trust is unavailable: "
                    + normalized(reasonCode));
            this.reasonCode = normalized(reasonCode);
            if (!this.reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("run trust reasonCode is invalid");
            }
        }

        /** @return stable payload-free denial reason */
        public String reasonCode() {
            return reasonCode;
        }
    }

    /** Fail-closed singleton used when no deployment agent is assembled. */
    final class Unavailable implements MirrorDeploymentIsolationRunTrustAuthority {
        private static final Unavailable INSTANCE = new Unavailable();

        private Unavailable() {
        }

        @Override
        public MirrorDeploymentIsolationRunTrust.Admission admit(
                CapabilitySnapshot.Scope scope) {
            throw new TrustException("RUN_TRUST_AUTHORITY_UNAVAILABLE");
        }

        @Override
        public MirrorDeploymentIsolationRunTrust.Binding confirm(
                MirrorDeploymentIsolationRunTrust.Admission admission,
                Instant startedAt,
                Instant completedAt) {
            throw new TrustException("RUN_TRUST_AUTHORITY_UNAVAILABLE");
        }

        @Override
        public CommitPermit acquireCommitPermit(
                CapabilitySnapshot.Scope scope,
                MirrorDeploymentIsolationRunTrust.Binding binding) {
            throw new TrustException("RUN_TRUST_AUTHORITY_UNAVAILABLE");
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
