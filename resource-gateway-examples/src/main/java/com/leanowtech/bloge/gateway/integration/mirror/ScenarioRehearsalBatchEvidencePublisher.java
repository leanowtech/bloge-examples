package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Fail-closed terminal publication boundary for signed Scenario batch evidence.
 *
 * <p>Remote signing and child-evidence verification happen in {@link #prepare} outside the
 * terminal transaction. {@link #persist} performs only exact append/CAS work in the final database
 * transaction. This keeps KMS latency out of queue locks while preserving atomic evidence,
 * retention, terminal job, and lifecycle publication.</p>
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
        String signingRequestId =
                "scenario-batch-sign:"
                        + UUID.randomUUID();
        PreparedFinalization prepared = prepare(
                request,
                manifest,
                job,
                items,
                retainUntil,
                null,
                signingRequestId);
        return persist(prepared);
    }

    /**
     * Verifies children and prepares both signatures without holding a database mutation lock.
     *
     * @param request original strict payload-free request
     * @param manifest immutable exact-plan closure
     * @param job frozen terminal projection
     * @param items complete ordered terminal items
     * @param retainUntil immutable minimum retention boundary
     * @param signedAt database time frozen by the first outbox claim
     * @param signingRequestId stable KMS idempotency identity
     * @return exact evidence and pre-signed retention registration
     */
    public PreparedFinalization prepare(
            ScenarioRehearsalBatchRequest request,
            ScenarioRehearsalBatchManifest manifest,
            ScenarioRehearsalBatchJob job,
            List<ScenarioRehearsalBatchItemPage.Item> items,
            Instant retainUntil,
            Instant signedAt,
            String signingRequestId) {
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
                request,
                manifest,
                job,
                exactItems,
                signedAt,
                signingRequestId);
        if (!sealed.verified()) {
            throw new ScenarioRehearsalBatchFinalizationException(
                    preparationReason(sealed.failureCode()));
        }
        Instant retentionOccurredAt =
                sealed.bundle().attestation().signedAt();
        ScenarioRehearsalBatchRetentionRepository
                .PreparedRegistration registration =
                prepareRetention(
                        sealed.bundle(),
                        retainUntil,
                        retentionOccurredAt,
                        signingRequestId);
        return new PreparedFinalization(
                sealed.bundle(), registration);
    }

    /**
     * Persists exact prepared material in the caller's terminal transaction.
     *
     * @param prepared signatures and closure prepared outside the transaction
     * @return inserted or exact idempotently existing bundle
     */
    public ScenarioRehearsalBatchEvidenceBundle persist(
            PreparedFinalization prepared) {
        PreparedFinalization exact =
                Objects.requireNonNull(prepared, "prepared");
        ScenarioRehearsalBatchEvidenceBundle persisted =
                batches.create(exact.bundle());
        retention.register(
                persisted,
                exact.retentionRegistration());
        return persisted;
    }

    /** Exact pre-signed terminal artifacts safe to carry across the outbox commit boundary. */
    public record PreparedFinalization(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            ScenarioRehearsalBatchRetentionRepository
                    .PreparedRegistration retentionRegistration
    ) {
        /** Enforces evidence/retention identity closure. */
        public PreparedFinalization {
            bundle = Objects.requireNonNull(
                    bundle, "bundle");
            retentionRegistration = Objects.requireNonNull(
                    retentionRegistration,
                    "retentionRegistration");
            if (!bundle.bundleFingerprint().equals(
                    retentionRegistration.bundleFingerprint())
                    || !bundle.index().job().jobId().equals(
                    retentionRegistration.event().jobId())) {
                throw new IllegalArgumentException(
                        "Scenario batch prepared finalization is inconsistent");
            }
        }
    }

    private ScenarioRehearsalBatchRetentionRepository
    .PreparedRegistration prepareRetention(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            Instant retainUntil,
            Instant occurredAt,
            String signingRequestId) {
        try {
            return retention.prepareRegistration(
                    bundle,
                    Objects.requireNonNull(
                            retainUntil, "retainUntil"),
                    occurredAt,
                    signingRequestId + ":retention");
        } catch (ScenarioRehearsalBatchFinalizationException
                classified) {
            throw classified;
        } catch (IllegalArgumentException invalid) {
            throw new ScenarioRehearsalBatchFinalizationException(
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.MATERIAL_INVALID);
        } catch (RuntimeException unavailable) {
            throw new ScenarioRehearsalBatchFinalizationException(
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.CONTROL_UNAVAILABLE);
        }
    }

    private static ScenarioRehearsalBatchFinalizationException.Reason
    preparationReason(String failureCode) {
        return switch (failureCode) {
            case ScenarioRehearsalBatchEvidenceIntegrityService
                    .MATERIAL_INVALID ->
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.MATERIAL_INVALID;
            case ScenarioRehearsalBatchEvidenceIntegrityService
                    .SIGNATURE_INVALID ->
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.SIGNATURE_INVALID;
            default ->
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.SIGNER_UNAVAILABLE;
        };
    }
}
