package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * One-step owner/epoch-fenced worker for authoritative outcome successor reconciliation.
 *
 * <p>The worker verifies the claimed observation through both Resource Gateway and external
 * business-authority trust boundaries before customer I/O. A connector result is never persisted
 * directly: successor semantics and authority closure are verified, then Resource Gateway signs
 * the candidate before the repository atomically appends it and advances the head. Raw connector
 * exceptions are reduced to a stable failure code.</p>
 */
public final class AuthoritativeOutcomeReconciliationWorker {
    private static final String RESULT_INVALID =
            "RG.MIRROR.OUTCOME.RESULT_INVALID";
    private static final String AUTHORITY_UNAVAILABLE =
            "RG.MIRROR.OUTCOME.AUTHORITY_UNAVAILABLE";
    private static final String KEY_UNAVAILABLE =
            "RG.MIRROR.OUTCOME.KEY_UNAVAILABLE";
    private static final String UNEXPECTED_FAILURE =
            "RG.MIRROR.OUTCOME.UNEXPECTED_FAILURE";

    private final AuthoritativeOutcomeInboxRepository repository;
    private final AuthoritativeOutcomeConnector connector;
    private final AuthoritativeOutcomeObservationIntegrity integrity;
    private final AuthoritativeOutcomeInboxPolicy policy;

    /**
     * Creates one deterministic durable reconciliation worker.
     *
     * @param repository append-only inbox and fencing authority
     * @param connector customer-owned payload-isolated authority connector
     * @param integrity external authority verifier and Resource Gateway signer
     * @param policy server-owned lease and retry controls
     */
    public AuthoritativeOutcomeReconciliationWorker(
            AuthoritativeOutcomeInboxRepository repository,
            AuthoritativeOutcomeConnector connector,
            AuthoritativeOutcomeObservationIntegrity integrity,
            AuthoritativeOutcomeInboxPolicy policy) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.connector = Objects.requireNonNull(
                connector, "connector");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.policy = Objects.requireNonNull(
                policy, "policy");
    }

    /**
     * Claims and reconciles at most one pending observation in an exact partition.
     *
     * @return acquired pre-execution claim or a bounded no-work observation
     */
    public AuthoritativeOutcomeInboxRepository.Claim runOne(
            String region,
            String environmentId,
            String ownerId) {
        if (!ready()) {
            return AuthoritativeOutcomeInboxRepository.Claim
                    .noWork(repository.observedAt());
        }
        AuthoritativeOutcomeInboxRepository.Claim claim =
                repository.claimNext(
                        region,
                        environmentId,
                        ownerId,
                        policy);
        if (claim.outcome()
                == AuthoritativeOutcomeInboxRepository
                .Claim.Outcome.NO_WORK) {
            return claim;
        }
        MutableControl control =
                new MutableControl(claim.lease());
        try {
            AuthoritativeOutcomeObservation current =
                    integrity.verify(claim.observation());
            AuthoritativeOutcomeConnector.Result result =
                    Objects.requireNonNull(
                            connector.reconcile(
                                    current,
                                    claim.observedAt(),
                                    control),
                            "connector result");
            if (result.disposition()
                    == AuthoritativeOutcomeConnector
                    .Disposition.NO_CHANGE) {
                repository.noChange(
                        control.lease(), policy);
            } else {
                AuthoritativeOutcomeObservation candidate =
                        result.successorOptional()
                                .orElseThrow();
                requireUnsignedSuccessor(
                        current, candidate);
                AuthoritativeOutcomeObservation signed =
                        integrity.sign(candidate);
                repository.publishSuccessor(
                        control.lease(),
                        signed,
                        policy);
            }
        } catch (AuthoritativeOutcomeConnector.Failure failure) {
            failCurrent(
                    control,
                    "RG.MIRROR.OUTCOME."
                            + failure.reason().name(),
                    failure.reason().retryable());
        } catch (AuthoritativeOutcomeObservationIntegrity
                 .Violation violation) {
            boolean retryable =
                    violation.reason()
                            == AuthoritativeOutcomeObservationIntegrity
                            .Reason.AUTHORITY_UNAVAILABLE
                            || violation.reason()
                            == AuthoritativeOutcomeObservationIntegrity
                            .Reason.KEY_UNAVAILABLE;
            failCurrent(
                    control,
                    violation.reason()
                            == AuthoritativeOutcomeObservationIntegrity
                            .Reason.KEY_UNAVAILABLE
                            ? KEY_UNAVAILABLE
                            : retryable
                            ? AUTHORITY_UNAVAILABLE
                            : RESULT_INVALID,
                    retryable);
        } catch (AuthoritativeOutcomeInboxRepository
                 .Violation violation) {
            if (violation.reason()
                    != AuthoritativeOutcomeInboxRepository
                    .Reason.LEASE_LOST) {
                failCurrent(
                        control,
                        RESULT_INVALID,
                        false);
            }
        } catch (IllegalArgumentException invalid) {
            failCurrent(
                    control,
                    RESULT_INVALID,
                    false);
        } catch (RuntimeException unexpected) {
            failCurrent(
                    control,
                    UNEXPECTED_FAILURE,
                    false);
        }
        return claim;
    }

    /** @return whether connector and both observation trust boundaries are currently usable */
    public boolean ready() {
        try {
            return connector.ready()
                    && integrity.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static void requireUnsignedSuccessor(
            AuthoritativeOutcomeObservation current,
            AuthoritativeOutcomeObservation candidate) {
        if (!candidate.observationFingerprint().isBlank()
                || candidate.observationSeal().signed()
                || candidate.revision()
                != current.revision() + 1
                || !candidate.observationId().equals(
                current.observationId())) {
            throw new IllegalArgumentException(
                    "connector successor is not an unsigned continuous revision");
        }
    }

    private void failCurrent(
            MutableControl control,
            String failureCode,
            boolean retryable) {
        try {
            repository.fail(
                    control.lease(),
                    failureCode,
                    retryable,
                    policy);
        } catch (AuthoritativeOutcomeInboxRepository
                 .Violation ignored) {
            // A replacement owner or externally admitted successor owns the current head.
        }
    }

    private final class MutableControl
            implements AuthoritativeOutcomeConnector.ExecutionControl {
        private AuthoritativeOutcomeInboxRepository.Lease lease;

        private MutableControl(
                AuthoritativeOutcomeInboxRepository.Lease lease) {
            this.lease = Objects.requireNonNull(
                    lease, "lease");
        }

        @Override
        public Instant leaseExpiresAt() {
            return lease.expiresAt();
        }

        @Override
        public Instant heartbeat() {
            AuthoritativeOutcomeInboxRepository.Heartbeat renewed =
                    repository.heartbeat(
                            lease, policy);
            lease = renewed.lease();
            return lease.expiresAt();
        }

        private AuthoritativeOutcomeInboxRepository.Lease lease() {
            return lease;
        }
    }
}
