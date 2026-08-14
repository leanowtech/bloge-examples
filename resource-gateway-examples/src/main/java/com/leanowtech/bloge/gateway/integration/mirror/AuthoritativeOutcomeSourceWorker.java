package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One-turn production outcome source worker using durable stage/apply/commit semantics.
 *
 * <p>The worker does not interpret customer payloads. It verifies one externally attested page,
 * signs each independently authoritative observation through the existing integrity boundary,
 * appends it to the existing inbox, and advances the checkpoint only after the whole page is
 * durable. Exact inbox replay makes a crash after any individual mutation recoverable.</p>
 */
public final class AuthoritativeOutcomeSourceWorker {
    private static final String SOURCE_UNAVAILABLE =
            "RG.MIRROR.OUTCOME_SOURCE.SOURCE_UNAVAILABLE";
    private static final String PROTOCOL_REJECTED =
            "RG.MIRROR.OUTCOME_SOURCE.PROTOCOL_REJECTED";
    private static final String AUTHORITY_UNAVAILABLE =
            "RG.MIRROR.OUTCOME_SOURCE.AUTHORITY_UNAVAILABLE";
    private static final String AUTHORITY_REJECTED =
            "RG.MIRROR.OUTCOME_SOURCE.AUTHORITY_REJECTED";
    private static final String OBSERVATION_REJECTED =
            "RG.MIRROR.OUTCOME_SOURCE.OBSERVATION_REJECTED";
    private static final String GENERATION_REVOKED =
            "RG.MIRROR.OUTCOME_SOURCE.GENERATION_REVOKED";
    private static final String UNEXPECTED_FAILURE =
            "RG.MIRROR.OUTCOME_SOURCE.UNEXPECTED_FAILURE";

    private final AuthoritativeOutcomeSourceCheckpointRepository checkpoints;
    private final AuthoritativeOutcomeSource source;
    private final AuthoritativeOutcomeSourceAuthorityVerifier sourceAuthority;
    private final AuthoritativeOutcomeObservationIntegrity observationIntegrity;
    private final AuthoritativeOutcomeInboxRepository inbox;
    private final AuthoritativeOutcomeSourceCheckpointRepository.Policy policy;
    private final ObjectMapper mapper;

    /** Creates one deterministic, payload-isolated source worker. */
    public AuthoritativeOutcomeSourceWorker(
            AuthoritativeOutcomeSourceCheckpointRepository checkpoints,
            AuthoritativeOutcomeSource source,
            AuthoritativeOutcomeSourceAuthorityVerifier sourceAuthority,
            AuthoritativeOutcomeObservationIntegrity observationIntegrity,
            AuthoritativeOutcomeInboxRepository inbox,
            AuthoritativeOutcomeSourceCheckpointRepository.Policy policy,
            ObjectMapper mapper) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.source = Objects.requireNonNull(source, "source");
        this.sourceAuthority = Objects.requireNonNull(sourceAuthority, "sourceAuthority");
        this.observationIntegrity = Objects.requireNonNull(
                observationIntegrity, "observationIntegrity");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Claims and processes at most one source stream turn. */
    public AuthoritativeOutcomeSourceCheckpointRepository.Claim runOne(
            String region, String environmentId, String ownerId) {
        if (!ready()) {
            return AuthoritativeOutcomeSourceCheckpointRepository.Claim.noWork(
                    checkpoints.observedAt());
        }
        AuthoritativeOutcomeSourceCheckpointRepository.Claim claim =
                checkpoints.claimNext(region, environmentId, ownerId, policy);
        if (claim.outcome()
                == AuthoritativeOutcomeSourceCheckpointRepository.Claim.Outcome.NO_WORK) {
            return claim;
        }
        MutableLease lease = new MutableLease(claim.lease());
        try {
            AuthoritativeOutcomeSourcePage page = claim.stagedPage();
            if (page == null) {
                AuthoritativeOutcomeSource.FetchResult fetched =
                        Objects.requireNonNull(
                                source.fetch(claim.snapshot().position()),
                                "source fetch result");
                switch (fetched.status()) {
                    case NO_CHANGE -> {
                        checkpoints.release(
                                lease.current(),
                                AuthoritativeOutcomeSourceCheckpointRepository.Release.IDLE,
                                policy);
                        return claim;
                    }
                    case STREAM_COMPLETE -> {
                        if (claim.snapshot().key().streamKind()
                                != AuthoritativeOutcomeSourcePage.StreamKind.BACKFILL) {
                            fail(lease, PROTOCOL_REJECTED, false);
                        } else {
                            checkpoints.release(
                                    lease.current(),
                                    AuthoritativeOutcomeSourceCheckpointRepository.Release
                                            .STREAM_COMPLETE,
                                    policy);
                        }
                        return claim;
                    }
                    case SOURCE_UNAVAILABLE -> {
                        fail(lease, SOURCE_UNAVAILABLE, true);
                        return claim;
                    }
                    case PROTOCOL_REJECTED -> {
                        fail(lease, PROTOCOL_REJECTED, false);
                        return claim;
                    }
                    case GENERATION_REVOKED -> {
                        fail(lease, GENERATION_REVOKED, false);
                        return claim;
                    }
                    case PAGE -> page = fetched.page();
                }
                verifyPage(claim.snapshot(), page);
                checkpoints.stage(lease.current(), page);
            } else {
                verifyPage(claim.snapshot(), page);
            }
            applyPage(lease, page);
            checkpoints.commit(lease.current(), page.pageFingerprint(), policy);
        } catch (AuthoritativeOutcomeSourceCheckpointRepository.Violation violation) {
            if (violation.reason()
                    != AuthoritativeOutcomeSourceCheckpointRepository.Reason.LEASE_LOST) {
                fail(lease, PROTOCOL_REJECTED, false);
            }
        } catch (AuthoritativeOutcomeObservationIntegrity.Violation violation) {
            boolean retryable = violation.reason()
                    == AuthoritativeOutcomeObservationIntegrity.Reason.AUTHORITY_UNAVAILABLE
                    || violation.reason()
                    == AuthoritativeOutcomeObservationIntegrity.Reason.KEY_UNAVAILABLE;
            fail(lease, retryable ? AUTHORITY_UNAVAILABLE : OBSERVATION_REJECTED, retryable);
        } catch (SourceAuthorityFailure rejected) {
            fail(lease, AUTHORITY_REJECTED, false);
        } catch (IllegalArgumentException rejected) {
            fail(lease, PROTOCOL_REJECTED, false);
        } catch (RuntimeException unexpected) {
            fail(lease, UNEXPECTED_FAILURE, false);
        }
        return claim;
    }

    /** @return whether source transport and both authority chains are locally usable */
    public boolean ready() {
        try {
            AuthoritativeOutcomeSource.Descriptor descriptor = source.descriptor();
            return descriptor.payloadIsolated()
                    && descriptor.mutualTls()
                    && sourceAuthority.available()
                    && observationIntegrity.available()
                    && checkpoints.durable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void verifyPage(
            AuthoritativeOutcomeSourceCheckpointRepository.Snapshot checkpoint,
            AuthoritativeOutcomeSourcePage page) {
        AuthoritativeOutcomeSourcePage exact = Objects.requireNonNull(page, "page");
        exact.verify(mapper);
        try {
            sourceAuthority.verifyPage(exact);
        } catch (AuthoritativeOutcomeObservationIntegrity.Violation violation) {
            throw violation;
        } catch (RuntimeException rejected) {
            throw new SourceAuthorityFailure();
        }
        AuthoritativeOutcomeSourceCheckpointRepository.StreamKey key = checkpoint.key();
        if (!key.scope().equals(exact.scope())
                || !key.connectorId().equals(exact.connectorId())
                || key.connectorGeneration() != exact.connectorGeneration()
                || key.streamKind() != exact.streamKind()
                || !key.streamId().equals(exact.streamId())
                || exact.sequence() != checkpoint.committedSequence() + 1
                || !exact.previousPageFingerprint().equals(
                checkpoint.committedPageFingerprint())
                || !exact.previousCursorRef().equals(checkpoint.committedCursorRef())
                || (key.streamKind() == AuthoritativeOutcomeSourcePage.StreamKind.BACKFILL
                && !exact.controlCommandRef().equals(checkpoint.controlCommandRef()))) {
            throw new IllegalArgumentException("source page does not continue its checkpoint");
        }
        Set<String> mutations = new HashSet<>();
        for (AuthoritativeOutcomeSourcePage.Entry entry : exact.entries()) {
            AuthoritativeOutcomeObservation observation = entry.observation();
            observation.verify(mapper);
            String coordinate = observation.observationId() + "@" + observation.revision();
            if (!mutations.add(coordinate)) {
                throw new IllegalArgumentException("source page repeats an observation revision");
            }
            if (entry.operation() == AuthoritativeOutcomeSourcePage.Operation.REVOKE
                    && observation.authorityFacts().stream()
                    .anyMatch(fact -> fact.sourceRef().equals(entry.affectedSourceRef()))) {
                throw new IllegalArgumentException(
                        "revoked source record remains in the successor observation");
            }
        }
    }

    private void applyPage(MutableLease lease, AuthoritativeOutcomeSourcePage page) {
        for (AuthoritativeOutcomeSourcePage.Entry entry : page.entries()) {
            lease.heartbeat();
            AuthoritativeOutcomeObservation signed =
                    observationIntegrity.sign(entry.observation());
            inbox.append(
                    signed,
                    entry.expectedPredecessorFingerprint());
        }
    }

    private void fail(MutableLease lease, String code, boolean retryable) {
        try {
            checkpoints.fail(lease.current(), code, retryable, policy);
        } catch (AuthoritativeOutcomeSourceCheckpointRepository.Violation ignored) {
            // A replacement epoch or an externally authorized revocation owns the stream.
        }
    }

    private final class MutableLease {
        private AuthoritativeOutcomeSourceCheckpointRepository.Lease lease;

        private MutableLease(AuthoritativeOutcomeSourceCheckpointRepository.Lease lease) {
            this.lease = Objects.requireNonNull(lease, "lease");
        }

        private void heartbeat() {
            lease = checkpoints.heartbeat(lease, policy);
        }

        private AuthoritativeOutcomeSourceCheckpointRepository.Lease current() {
            return lease;
        }
    }

    private static final class SourceAuthorityFailure extends RuntimeException {
    }
}
