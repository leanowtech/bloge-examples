package com.leanowtech.bloge.gateway.integration.mirror;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Atomic terminal commit boundary for mirror evidence and request idempotency state.
 *
 * <p>The current registration and lease identity are checked first, evidence is inserted inside
 * the surrounding transaction, then the exact lease owner, epoch, and database-clock expiry are
 * fenced by the request repository. A timeout, release, or takeover raises an exception, causing
 * the evidence insert to roll back rather than leaving an orphan that an idempotent retry cannot
 * discover.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public class MirrorRunCommitService {
    private final MirrorEvidenceRepository evidence;
    private final MirrorRunRequestRepository requests;

    /**
     * @param evidence append-only independently verified evidence store
     * @param requests fenced durable request coordinator
     */
    public MirrorRunCommitService(
            MirrorEvidenceRepository evidence,
            MirrorRunRequestRepository requests) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    /**
     * Atomically persists evidence and marks the exact request lease completed.
     *
     * @param lease fenced execution authority
     * @param bundle independently verified payload-free terminal evidence
     * @return persisted bundle
     * @throws MirrorRunLeaseLostException when the lease expired, was released, or was replaced
     */
    @Transactional
    public MirrorEvidenceBundle commit(
            MirrorRunRequestRepository.Lease lease,
            MirrorEvidenceBundle bundle) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(bundle, "bundle");
        MirrorRunEvidence run = bundle.evidence();
        MirrorRunRequestRepository.State state = requests.find(
                lease.scope(), lease.requestId()).orElseThrow(MirrorRunLeaseLostException::new);
        boolean currentLease = state.status() == MirrorRunRequestRepository.Status.ACTIVE
                && state.leaseOwner().equals(lease.leaseOwner())
                && state.leaseEpoch() == lease.leaseEpoch();
        if (!currentLease) {
            throw new MirrorRunLeaseLostException();
        }
        MirrorRunRequestRepository.Registration registration = state.registration();
        if (!lease.scope().equals(run.scope())
                || !lease.requestId().equals(run.requestId())
                || !registration.contextFingerprint().equals(run.requestContextFingerprint())
                || !registration.planId().equals(run.planId())
                || !registration.planFingerprint().equals(run.planFingerprint())) {
            throw new IllegalArgumentException(
                    "mirror evidence must match its complete durable request registration");
        }
        MirrorEvidenceBundle persisted = evidence.create(bundle);
        if (!requests.complete(lease, run.runId(), persisted.bundleFingerprint())) {
            throw new MirrorRunLeaseLostException();
        }
        return persisted;
    }
}
