package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed terminal publication boundary for signed Scenario batch evidence.
 *
 * <p>The database batch repository invokes this boundary inside the same transaction that
 * publishes the terminal job projection. Every referenced child aggregate is reloaded through
 * the independently verifying evidence repository before the batch is sealed. A missing signer,
 * child, signature, or identity therefore rolls back terminal publication and leaves the job
 * recoverable under its durable queue semantics.</p>
 */
public final class ScenarioRehearsalBatchEvidencePublisher {
    private final ScenarioRehearsalEvidenceRepository
            childEvidence;
    private final ScenarioRehearsalBatchEvidenceIntegrityService
            integrity;
    private final ScenarioRehearsalBatchEvidenceRepository
            batches;
    private final ScenarioRehearsalBatchRetentionRepository
            retention;

    /**
     * Creates the atomic terminal evidence publisher.
     *
     * @param childEvidence independently verifying aggregate evidence repository
     * @param integrity batch signature and content-address authority
     * @param batches append-only batch evidence repository
     * @param retention signed retention and logical-deletion authority
     */
    public ScenarioRehearsalBatchEvidencePublisher(
            ScenarioRehearsalEvidenceRepository childEvidence,
            ScenarioRehearsalBatchEvidenceIntegrityService integrity,
            ScenarioRehearsalBatchEvidenceRepository batches,
            ScenarioRehearsalBatchRetentionRepository retention) {
        this.childEvidence = Objects.requireNonNull(
                childEvidence, "childEvidence");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.batches = Objects.requireNonNull(
                batches, "batches");
        this.retention = Objects.requireNonNull(
                retention, "retention");
    }

    /**
     * Verifies every available child and atomically appends one signed terminal batch bundle.
     *
     * @param request original strict payload-free request
     * @param manifest immutable exact-plan closure
     * @param job terminal integrity-sealed job
     * @param items complete ordered terminal items
     * @param retainUntil immutable minimum batch retention boundary
     * @return inserted or exact idempotently existing bundle
     */
    public ScenarioRehearsalBatchEvidenceBundle publish(
            ScenarioRehearsalBatchRequest request,
            ScenarioRehearsalBatchManifest manifest,
            ScenarioRehearsalBatchJob job,
            List<ScenarioRehearsalBatchItemPage.Item> items,
            Instant retainUntil) {
        List<ScenarioRehearsalBatchItemPage.Item> exactItems =
                items == null ? List.of() : List.copyOf(items);
        for (ScenarioRehearsalBatchItemPage.Item item
                : exactItems) {
            if (item.runId().isBlank()) {
                continue;
            }
            ScenarioRehearsalEvidenceBundle child =
                    childEvidence.find(
                                    job.scope(), item.runId())
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Scenario batch child evidence is unavailable"));
            if (!child.bundleFingerprint().equals(
                    item.evidenceBundleFingerprint())
                    || !child.result().scope().equals(job.scope())
                    || !child.result().requestId().equals(
                    item.childRequestId())
                    || !child.result().compiledPlanRef().equals(
                    item.compiledPlanRef())
                    || child.result().outcome() != item.outcome()) {
                throw new IllegalArgumentException(
                        "Scenario batch child evidence differs from its terminal item");
            }
        }
        ScenarioRehearsalBatchEvidenceIntegrityService.SealResult
                sealed = integrity.seal(
                request, manifest, job, exactItems);
        if (!sealed.verified()) {
            throw new IllegalStateException(
                    "Scenario batch evidence could not be signed and verified: "
                            + sealed.failureCode());
        }
        ScenarioRehearsalBatchEvidenceBundle persisted =
                batches.create(sealed.bundle());
        retention.register(
                persisted,
                Objects.requireNonNull(
                        retainUntil, "retainUntil"));
        return persisted;
    }
}
