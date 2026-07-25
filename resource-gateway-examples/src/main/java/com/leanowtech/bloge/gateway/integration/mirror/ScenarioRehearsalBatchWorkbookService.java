package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Protected application boundary for ANEKE Scenario batch correctness-workbook projection.
 */
public final class ScenarioRehearsalBatchWorkbookService {
    private final ScenarioRehearsalBatchEvidenceRepository
            evidence;
    private final ScenarioRehearsalBatchEvidenceIntegrityService
            integrity;
    private final ScenarioRehearsalBatchRetentionRepository
            retention;
    private final ScenarioRehearsalRuntimeService rehearsals;
    private final ObjectMapper mapper;
    private final MirrorOperationObservability observations;
    private final VisualEvidenceSigner signer;
    private final Projector projector;

    /**
     * @param evidence exact-scope terminal batch evidence store
     * @param integrity independent batch signature and content-address verifier
     * @param retention signed batch-retention event authority
     * @param rehearsals child Scenario workbook source
     * @param mapper canonical protocol mapper
     * @param observations mandatory protected-operation audit
     * @param signer trusted detached batch-workbook signing authority
     */
    public ScenarioRehearsalBatchWorkbookService(
            ScenarioRehearsalBatchEvidenceRepository evidence,
            ScenarioRehearsalBatchEvidenceIntegrityService integrity,
            ScenarioRehearsalBatchRetentionRepository retention,
            ScenarioRehearsalRuntimeService rehearsals,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            VisualEvidenceSigner signer) {
        this(
                evidence,
                integrity,
                retention,
                rehearsals,
                mapper,
                observations,
                signer,
                ScenarioRehearsalBatchWorkbookSeed::project);
    }

    ScenarioRehearsalBatchWorkbookService(
            ScenarioRehearsalBatchEvidenceRepository evidence,
            ScenarioRehearsalBatchEvidenceIntegrityService integrity,
            ScenarioRehearsalBatchRetentionRepository retention,
            ScenarioRehearsalRuntimeService rehearsals,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            VisualEvidenceSigner signer,
            Projector projector) {
        this.evidence = Objects.requireNonNull(
                evidence, "evidence");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.retention = Objects.requireNonNull(
                retention, "retention");
        this.rehearsals = Objects.requireNonNull(
                rehearsals, "rehearsals");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.observations = Objects.requireNonNull(
                observations, "observations");
        this.signer = Objects.requireNonNull(
                signer, "signer");
        this.projector = Objects.requireNonNull(
                projector, "projector");
    }

    /**
     * Reconstructs one signed, retained batch and all evidence-backed child workbooks.
     *
     * @param jobId canonical batch identity
     * @param identity authenticated complete enterprise identity
     * @return deterministic payload-free ANEKE workbook seed
     */
    public ScenarioRehearsalBatchWorkbookSeed workbookSeed(
            String jobId,
            IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_BATCH_WORKBOOK_READ,
                        identity,
                        "",
                        "",
                        jobId);
        try {
            CapabilitySnapshot.Scope scope =
                    MirrorPlanIntegrationService
                            .requireMirrorReadIdentity(identity);
            String id = canonicalJobId(jobId, identity);
            ScenarioRehearsalBatchEvidenceBundle bundle =
                    evidence.find(scope, id)
                            .orElseThrow(() ->
                                    notFound(identity));
            ScenarioRehearsalBatchEvidenceBundle verified =
                    integrity.requireVerified(bundle).bundle();
            ScenarioRehearsalBatchRetentionState state =
                    retention.find(scope, id)
                            .orElseThrow(() ->
                                    incomplete(identity));
            Map<String, ScenarioRehearsalWorkbookSeed> children =
                    new LinkedHashMap<>();
            for (ScenarioRehearsalBatchItemPage.Item item
                    : verified.index().items()) {
                if (item.runId().isBlank()) {
                    continue;
                }
                ScenarioRehearsalWorkbookSeed child =
                        rehearsals.workbookSeed(
                                item.runId(), identity);
                child.verify(mapper);
                if (!item.workbookSeedFingerprint()
                        .equals(child.seedFingerprint())
                        || children.putIfAbsent(
                        child.runId(), child) != null) {
                    throw new IllegalArgumentException(
                            "Scenario batch child workbook differs from signed evidence");
                }
            }
            ScenarioRehearsalBatchWorkbookSeed material =
                    projector.project(
                            mapper,
                            verified,
                            state,
                            retention.events(scope, id),
                            children);
            material.verify(mapper);
            String attestationFingerprint =
                    material.attestationMaterialFingerprint(
                            mapper);
            VisualRunEvidenceSeal seal = signer.seal(
                    attestationFingerprint,
                    "scenario-batch-workbook:"
                            + id + ":"
                            + material.seedFingerprint());
            VisualEvidenceSigner.Verification signature =
                    signer.verify(
                            seal, attestationFingerprint);
            if (!signature.valid()) {
                throw new IllegalStateException(
                        "Scenario batch workbook seal could not be verified");
            }
            ScenarioRehearsalBatchWorkbookSeed result =
                    material.withWorkbookSeal(seal);
            result.verify(mapper);
            observation.succeeded(id);
            return result;
        } catch (IntegrationProblemException expected) {
            throw observation.failed(expected);
        } catch (IllegalArgumentException invalid) {
            throw observation.failed(new IntegrationProblemException(
                    IntegrationProblem.conflict(
                            "RG.MIRROR.REHEARSAL_BATCH.WORKBOOK_CLOSURE_INVALID",
                            "The Scenario batch artifacts do not form one complete correctness-workbook closure.",
                            identity.correlationId(),
                            Map.of())));
        } catch (IllegalStateException unavailable) {
            throw observation.failed(new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.REHEARSAL_BATCH.WORKBOOK_VERIFICATION_UNAVAILABLE",
                            "The Scenario batch correctness-workbook verification authority is unavailable.",
                            identity.correlationId(),
                            Map.of())));
        } catch (RuntimeException unavailable) {
            throw observation.failed(new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.REHEARSAL_BATCH.WORKBOOK_UNAVAILABLE",
                            "The Scenario batch correctness-workbook seed could not be projected safely.",
                            identity.correlationId(),
                            Map.of())));
        }
    }

    private static String canonicalJobId(
            String value,
            IntegrationRequestContext identity) {
        String jobId = value == null ? "" : value.trim();
        if (!ScenarioRehearsalBatchIdentity
                .hasCanonicalShape(jobId)) {
            throw new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.REHEARSAL_BATCH.JOB_ID_INVALID",
                            "Scenario rehearsal batch job id is invalid.",
                            identity.correlationId(),
                            Map.of()));
        }
        return jobId;
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.notFound(
                        "RG.MIRROR.REHEARSAL_BATCH.WORKBOOK_NOT_FOUND",
                        "Scenario batch evidence was not found in the authorized scope.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException incomplete(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.conflict(
                        "RG.MIRROR.REHEARSAL_BATCH.WORKBOOK_RETENTION_MISSING",
                        "Scenario batch retention registration is required before workbook projection.",
                        identity.correlationId(),
                        Map.of()));
    }

    @FunctionalInterface
    interface Projector {
        ScenarioRehearsalBatchWorkbookSeed project(
                ObjectMapper mapper,
                ScenarioRehearsalBatchEvidenceBundle bundle,
                ScenarioRehearsalBatchRetentionState
                        retentionState,
                java.util.List<
                        ScenarioRehearsalBatchRetentionEvent>
                        retentionEvents,
                Map<String, ScenarioRehearsalWorkbookSeed>
                        childWorkbooks);
    }
}
