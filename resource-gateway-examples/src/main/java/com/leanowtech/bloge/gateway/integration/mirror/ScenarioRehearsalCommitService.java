package com.leanowtech.bloge.gateway.integration.mirror;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Atomic terminal boundary for signed Scenario evidence and aggregate request state.
 *
 * <p>Evidence insertion and the exact owner/epoch/database-expiry terminal transition share one
 * transaction. A stale worker therefore cannot leave orphan evidence after takeover, and a
 * completed request can always resolve its exact signed bundle.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class ScenarioRehearsalCommitService {
    private final ScenarioRehearsalEvidenceRepository evidence;
    private final ScenarioRehearsalRunRepository requests;
    private final ScenarioRehearsalRetentionRepository retention;

    /**
     * @param evidence append-only independently verified evidence store
     * @param requests fenced aggregate coordinator
     * @param retention signed retention and deletion-proof authority
     */
    public ScenarioRehearsalCommitService(
            ScenarioRehearsalEvidenceRepository evidence,
            ScenarioRehearsalRunRepository requests,
            ScenarioRehearsalRetentionRepository retention) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.retention = Objects.requireNonNull(
                retention, "retention");
    }

    /**
     * Atomically stores one complete signed aggregate under the exact current lease.
     *
     * @param observation authenticated mandatory operation-audit token
     * @throws ScenarioRehearsalLeaseLostException when the lease expired or was replaced
     */
    @Transactional
    public ScenarioRehearsalEvidenceBundle commit(
            ScenarioRehearsalRunRepository.Lease lease,
            ScenarioRehearsalEvidenceBundle bundle,
            MirrorOperationObservability.Observation observation) {
        Objects.requireNonNull(lease, "lease");
        ScenarioRehearsalEvidenceBundle exact =
                Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(observation, "observation");
        ScenarioRehearsalRunRepository.State state =
                requests.find(
                        lease.scope(), lease.requestId()).orElseThrow(
                        ScenarioRehearsalLeaseLostException::new);
        ScenarioRehearsalResult result = exact.result();
        ScenarioRehearsalRunRepository.Registration registration =
                state.registration();
        boolean current = state.status()
                == ScenarioRehearsalRunRepository.Status.ACTIVE
                && state.leaseOwner().equals(lease.leaseOwner())
                && state.leaseEpoch() == lease.leaseEpoch();
        if (!current) {
            throw new ScenarioRehearsalLeaseLostException();
        }
        if (!lease.scope().equals(result.scope())
                || !lease.requestId().equals(result.requestId())
                || !registration.runId().equals(
                exact.attestation().runId())
                || !registration.compiledPlanRef().equals(
                result.compiledPlanRef())
                || registration.totalCases()
                != result.caseResults().size()
                || state.nextCaseIndex()
                != registration.totalCases()) {
            throw new IllegalArgumentException(
                    "Scenario evidence differs from its durable request registration");
        }
        ScenarioRehearsalEvidenceBundle persisted =
                evidence.create(exact);
        retention.register(
                persisted, registration.retainUntil());
        if (!requests.complete(
                lease, persisted.bundleFingerprint())) {
            throw new ScenarioRehearsalLeaseLostException();
        }
        observation.succeeded(exact.attestation().runId());
        return persisted;
    }
}
