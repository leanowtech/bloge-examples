package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Deployment-owned durable, epoch-fenced journal for destructive certification execution.
 *
 * <p>The implementation must atomically reserve an authorization nonce across all replicas,
 * persist every terminal scenario before the next fault, and return an already completed report
 * on exact replay. Resource Gateway has no in-memory production fallback.</p>
 */
public interface RuntimeCertificationExecutionJournal {
    /** Claims, resumes, or exact-replays one authorization-bound run. */
    Claim claimOrResume(
            RunIdentity identity,
            String ownerId,
            Duration leaseDuration,
            Instant now);

    /** Renews the current epoch before and after each bounded Adapter invocation. */
    Lease heartbeat(Lease lease, Duration leaseDuration, Instant now);

    /** Appends one new terminal scenario result under the current epoch. */
    void appendScenario(Lease lease, RuntimeCertificationReport.ScenarioResult result);

    /** Atomically stores the signed report and consumes the authorization permanently. */
    void complete(Lease lease, RuntimeCertificationReport report);

    /** Exact identity that makes one external authorization single-use. */
    record RunIdentity(
            String runId,
            MirrorArtifactRef manifestRef,
            MirrorArtifactRef authorizationRef,
            String authorizationNonceFingerprint,
            String environmentFingerprint
    ) {
        /** Validates exact replay and anti-fork coordinates. */
        public RunIdentity {
            runId = RegionalDataPlaneDeploymentContract.identifier(runId, "runId");
            manifestRef = RuntimeCertificationExecutionAuthorization.requireKind(manifestRef,
                    RuntimeCertificationManifest.ARTIFACT_KIND, "manifestRef");
            authorizationRef = RuntimeCertificationExecutionAuthorization.requireKind(
                    authorizationRef,
                    RuntimeCertificationExecutionAuthorization.ARTIFACT_KIND,
                    "authorizationRef");
            authorizationNonceFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                    authorizationNonceFingerprint, "authorizationNonceFingerprint");
            environmentFingerprint = RegionalDataPlaneDeploymentContract.fingerprint(
                    environmentFingerprint, "environmentFingerprint");
        }
    }

    /** Epoch-fenced ownership returned by the durable journal. */
    record Lease(
            String runId,
            String ownerId,
            long epoch,
            Instant expiresAt
    ) {
        /** Validates bounded ownership coordinates. */
        public Lease {
            runId = RegionalDataPlaneDeploymentContract.identifier(runId, "runId");
            ownerId = RegionalDataPlaneDeploymentContract.identifier(ownerId, "ownerId");
            if (epoch < 1) {
                throw new IllegalArgumentException("journal lease epoch must be positive");
            }
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** Journal claim outcome. */
    enum ClaimStatus {
        ACQUIRED,
        RESUMED,
        COMPLETED,
        CONFLICT,
        UNAVAILABLE
    }

    /**
     * Durable claim result.
     *
     * @param status bounded claim status
     * @param lease current lease for ACQUIRED/RESUMED
     * @param authorizationConsumptionRef immutable single-use journal fact
     * @param savedResults previously committed scenario prefix
     * @param completedReport exact replay result when status is COMPLETED
     * @param reasonCode bounded failure reason
     */
    record Claim(
            ClaimStatus status,
            Lease lease,
            MirrorArtifactRef authorizationConsumptionRef,
            List<RuntimeCertificationReport.ScenarioResult> savedResults,
            RuntimeCertificationReport completedReport,
            String reasonCode
    ) {
        /** Normalizes and validates a fail-closed claim result. */
        public Claim {
            status = Objects.requireNonNull(status, "status");
            savedResults = savedResults == null ? List.of() : List.copyOf(savedResults);
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("journal claim reasonCode is invalid");
            }
            boolean active = status == ClaimStatus.ACQUIRED || status == ClaimStatus.RESUMED;
            if (active && (lease == null || authorizationConsumptionRef == null
                    || completedReport != null)
                    || status == ClaimStatus.COMPLETED
                    && (completedReport == null || lease != null)
                    || !active && status != ClaimStatus.COMPLETED
                    && (lease != null || completedReport != null)) {
                throw new IllegalArgumentException("journal claim state is inconsistent");
            }
            if (authorizationConsumptionRef != null) {
                authorizationConsumptionRef =
                        RuntimeCertificationExecutionAuthorization.requireKind(
                                authorizationConsumptionRef,
                                "RUNTIME_CERTIFICATION_AUTHORIZATION_CONSUMPTION",
                                "authorizationConsumptionRef");
            }
        }
    }

    /** Stable loss-of-ownership signal emitted by a durable implementation. */
    final class LeaseLostException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** @param message bounded journal diagnostic */
        public LeaseLostException(String message) {
            super(message == null ? "runtime certification lease lost" : message);
        }
    }
}
