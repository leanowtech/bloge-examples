package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Protected application boundary for human-reviewed Scenario batch remediation.
 *
 * <p>The service reads an independently signed predecessor workbook and evidence index, derives a
 * complete payload-free successor, validates every exact compiled plan through the normal batch
 * compiler, and freezes that successor before approval. Later calls may append only role-bound
 * decisions or submit the exact approved successor. Runtime policy, actor identity, timestamps,
 * and successor request ids are server-owned.</p>
 */
public final class ScenarioRehearsalRemediationService {
    private static final int MAXIMUM_PREVIEW_FINGERPRINT_BYTES =
            512 * 1024;
    private static final int MAXIMUM_COMMAND_BYTES =
            256 * 1024;

    private final ScenarioRehearsalRemediationRepository
            repository;
    private final ScenarioRehearsalRemediationPolicy policy;
    private final ScenarioRehearsalBatchPolicy batchPolicy;
    private final ScenarioRehearsalBatchWorkbookService workbooks;
    private final ScenarioRehearsalBatchEvidenceRepository evidence;
    private final ScenarioRehearsalBatchEvidenceIntegrityService
            evidenceIntegrity;
    private final ScenarioRehearsalBatchCompiler batchCompiler;
    private final ObjectMapper mapper;
    private final MirrorOperationObservability observations;
    private final Clock clock;

    /**
     * Creates the production service with a UTC application clock.
     *
     * @param repository durable remediation fact ledger
     * @param policy server-owned authorization and lifetime policy
     * @param batchPolicy server-owned successor queue policy
     * @param workbooks signed predecessor workbook reader
     * @param evidence signed predecessor batch evidence store
     * @param evidenceIntegrity independent batch evidence verifier
     * @param batchCompiler normal exact-plan successor compiler
     * @param mapper canonical protocol mapper
     * @param observations protected-operation audit and telemetry boundary
     */
    public ScenarioRehearsalRemediationService(
            ScenarioRehearsalRemediationRepository repository,
            ScenarioRehearsalRemediationPolicy policy,
            ScenarioRehearsalBatchPolicy batchPolicy,
            ScenarioRehearsalBatchWorkbookService workbooks,
            ScenarioRehearsalBatchEvidenceRepository evidence,
            ScenarioRehearsalBatchEvidenceIntegrityService
                    evidenceIntegrity,
            ScenarioRehearsalBatchCompiler batchCompiler,
            ObjectMapper mapper,
            MirrorOperationObservability observations) {
        this(
                repository,
                policy,
                batchPolicy,
                workbooks,
                evidence,
                evidenceIntegrity,
                batchCompiler,
                mapper,
                observations,
                Clock.systemUTC());
    }

    /** Package-private deterministic clock seam for application-service tests. */
    ScenarioRehearsalRemediationService(
            ScenarioRehearsalRemediationRepository repository,
            ScenarioRehearsalRemediationPolicy policy,
            ScenarioRehearsalBatchPolicy batchPolicy,
            ScenarioRehearsalBatchWorkbookService workbooks,
            ScenarioRehearsalBatchEvidenceRepository evidence,
            ScenarioRehearsalBatchEvidenceIntegrityService
                    evidenceIntegrity,
            ScenarioRehearsalBatchCompiler batchCompiler,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            Clock clock) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.batchPolicy = Objects.requireNonNull(
                batchPolicy, "batchPolicy");
        this.workbooks = Objects.requireNonNull(
                workbooks, "workbooks");
        this.evidence = Objects.requireNonNull(
                evidence, "evidence");
        this.evidenceIntegrity = Objects.requireNonNull(
                evidenceIntegrity, "evidenceIntegrity");
        this.batchCompiler = Objects.requireNonNull(
                batchCompiler, "batchCompiler");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.observations = Objects.requireNonNull(
                observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Freezes one blocked signed predecessor and a complete validated successor for review.
     *
     * @param predecessorJobId exact terminal predecessor batch
     * @param request strict replacement or exact-rerun proposal
     * @param identity authenticated human business owner
     * @return new or exactly recovered immutable remediation plan
     */
    public ScenarioRehearsalRemediationRepository.PreviewResult
    preview(
            String predecessorJobId,
            ScenarioRehearsalRemediationPreviewRequest request,
            IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation operation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW,
                        identity,
                        request == null
                                ? "" : request.previewRequestId(),
                        "",
                        predecessorJobId);
        try {
            CapabilitySnapshot.Scope scope =
                    requireOwner(identity);
            String predecessor = canonicalJobId(
                    predecessorJobId, identity);
            ScenarioRehearsalRemediationPreviewRequest exact =
                    Objects.requireNonNull(request, "request");
            IntegrationRequestContext internal =
                    internalRehearsalIdentity(identity);
            ScenarioRehearsalBatchWorkbookSeed workbook =
                    workbooks.workbookSeed(
                            predecessor, internal);
            workbook.verify(mapper);
            if (!workbook.seedFingerprint().equals(
                    exact.expectedWorkbookSeedFingerprint())) {
                throw conflict(
                        ScenarioRehearsalRemediationConflictException
                                .Reason
                                .WORKBOOK_FINGERPRINT_MISMATCH,
                        "The reviewed predecessor workbook changed");
            }
            if (workbook.gateReady()
                    || workbook.blockers().isEmpty()) {
                throw conflict(
                        ScenarioRehearsalRemediationConflictException
                                .Reason.PREDECESSOR_NOT_BLOCKED,
                        "Only a blocked correctness workbook can enter remediation");
            }
            ScenarioRehearsalBatchEvidenceBundle bundle =
                    evidence.find(scope, predecessor)
                            .orElseThrow(() ->
                                    conflict(
                                            ScenarioRehearsalRemediationConflictException
                                                    .Reason.NOT_FOUND,
                                            "Scenario remediation predecessor evidence was not found"));
            ScenarioRehearsalBatchEvidenceBundle verified =
                    evidenceIntegrity.requireVerified(
                            bundle).bundle();
            requirePredecessorClosure(
                    workbook, verified, predecessor);
            ScenarioRehearsalBatchRequest original =
                    verified.index().request();
            String remediationId =
                    ScenarioRehearsalRemediationIdentity.derive(
                            mapper,
                            scope,
                            predecessor,
                            exact.previewRequestId());
            ScenarioRehearsalBatchRequest successor =
                    successor(
                            original,
                            exact,
                            remediationId);
            batchCompiler.compile(successor, internal);
            String successorFingerprint =
                    ProtocolFingerprint.ofBounded(
                            mapper,
                            successor,
                            ScenarioRehearsalRemediationPlan
                                    .MAXIMUM_CANONICAL_BYTES);
            Instant generatedAt = clock.instant()
                    .truncatedTo(ChronoUnit.MILLIS);
            ScenarioRehearsalRemediationPlan plan =
                    ScenarioRehearsalRemediationPlan.seal(
                            mapper,
                            new ScenarioRehearsalRemediationPlan(
                                    "",
                                    "",
                                    scope,
                                    remediationId,
                                    exact.previewRequestId(),
                                    predecessor,
                                    workbook.seedFingerprint(),
                                    verified.bundleFingerprint(),
                                    verified.index().job().status(),
                                    workbook.blockers(),
                                    exact.strategy(),
                                    exact.reasonCode(),
                                    exact.replacements(),
                                    successor,
                                    successorFingerprint,
                                    exact.governanceTicketRef(),
                                    ScenarioRehearsalRemediationPlan
                                            .ApprovalPolicy
                                            .twoPerson(
                                                    policy.generation(),
                                                    policy.fingerprint(
                                                            mapper)),
                                    generatedAt,
                                    generatedAt.plus(
                                            policy.planLifetime())));
            String previewFingerprint =
                    previewFingerprint(plan);
            return repository.create(
                    new ScenarioRehearsalRemediationRepository
                            .Preview(
                            plan,
                            previewFingerprint),
                    policy,
                    operation);
        } catch (ScenarioRehearsalRemediationConflictException
                 conflict) {
            throw operation.failed(
                    problem(conflict, identity));
        } catch (IntegrationProblemException expected) {
            throw operation.failed(expected);
        } catch (IllegalArgumentException invalid) {
            throw operation.failed(new IntegrationProblemException(
                    IntegrationProblem.conflict(
                            "RG.MIRROR.REMEDIATION.EVIDENCE_CLOSURE_INVALID",
                            "The signed predecessor or proposed successor does not form a valid remediation closure.",
                            identity.correlationId(),
                            Map.of())));
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    /**
     * Appends one authenticated owner or independent-reviewer decision.
     *
     * @param remediationId exact server-derived remediation lineage
     * @param command role-bound decision and approval-head fence
     * @param identity authenticated human decision maker
     * @return newly appended or exact actor-bound idempotent decision
     */
    public ScenarioRehearsalRemediationRepository.ApprovalResult
    approve(
            String remediationId,
            ScenarioRehearsalRemediationApprovalCommand command,
            IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation operation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_APPROVE,
                        identity,
                        command == null
                                ? "" : command.commandId(),
                        command == null
                                ? ""
                                : command
                                .remediationPlanFingerprint(),
                        remediationId);
        try {
            CapabilitySnapshot.Scope scope =
                    requireRemediationIdentity(identity);
            ScenarioRehearsalRemediationApprovalCommand exact =
                    Objects.requireNonNull(command, "command");
            requireRole(identity, exact.role());
            return repository.approve(
                    new ScenarioRehearsalRemediationRepository
                            .ApprovalMutation(
                            scope,
                            remediationId,
                            exact,
                            ProtocolFingerprint.ofBounded(
                                    mapper,
                                    exact,
                                    MAXIMUM_COMMAND_BYTES),
                            identity.actorId(),
                            identity.delegatedBy()),
                    policy,
                    operation);
        } catch (ScenarioRehearsalRemediationConflictException
                 conflict) {
            throw operation.failed(
                    problem(conflict, identity));
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    /**
     * Recompiles and atomically admits the exact fully approved successor.
     *
     * @param remediationId exact server-derived remediation lineage
     * @param command plan and final approval-head compare-and-set fence
     * @param identity authenticated human owner accepting the successor
     * @return immutable receipt for a new or exactly replayed submission
     */
    public ScenarioRehearsalRemediationRepository.SubmissionResult
    submit(
            String remediationId,
            ScenarioRehearsalRemediationSubmitCommand command,
            IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation operation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT,
                        identity,
                        command == null
                                ? "" : command.commandId(),
                        command == null
                                ? ""
                                : command
                                .remediationPlanFingerprint(),
                        remediationId);
        try {
            CapabilitySnapshot.Scope scope =
                    requireOwner(identity);
            ScenarioRehearsalRemediationSubmitCommand exact =
                    Objects.requireNonNull(command, "command");
            ScenarioRehearsalRemediationRepository.Snapshot
                    snapshot = repository.find(
                    scope, remediationId).orElseThrow(() ->
                    conflict(
                            ScenarioRehearsalRemediationConflictException
                                    .Reason.NOT_FOUND,
                            "Scenario remediation was not found in the authorized scope"));
            ScenarioRehearsalRemediationPlan plan =
                    snapshot.plan();
            IntegrationRequestContext internal =
                    internalRehearsalIdentity(identity);
            ScenarioRehearsalBatchManifest manifest =
                    batchCompiler.compile(
                            plan.successorRequest(),
                            internal);
            ScenarioRehearsalBatchManifestIntegrity.verify(
                    mapper, manifest);
            String requestFingerprint =
                    ProtocolFingerprint.ofBounded(
                            mapper,
                            plan.successorRequest(),
                            ScenarioRehearsalRemediationPlan
                                    .MAXIMUM_CANONICAL_BYTES);
            ScenarioRehearsalBatchRepository.Submission
                    successor =
                    new ScenarioRehearsalBatchRepository.Submission(
                            plan.successorRequest(),
                            requestFingerprint,
                            manifest,
                            principal(identity, scope));
            return repository.submit(
                    new ScenarioRehearsalRemediationRepository
                            .SubmissionMutation(
                            scope,
                            remediationId,
                            exact,
                            ProtocolFingerprint.ofBounded(
                                    mapper,
                                    exact,
                                    MAXIMUM_COMMAND_BYTES),
                            successor,
                            identity.actorId(),
                            identity.delegatedBy()),
                    policy,
                    batchPolicy,
                    operation);
        } catch (ScenarioRehearsalRemediationConflictException
                 conflict) {
            throw operation.failed(
                    problem(conflict, identity));
        } catch (ScenarioRehearsalBatchConflictException conflict) {
            throw operation.failed(
                    batchProblem(conflict, identity));
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    /**
     * Reads one complete immutable remediation lineage inside the authenticated scope.
     *
     * @param remediationId exact server-derived remediation lineage
     * @param identity authenticated remediation reader
     * @return integrity-verified plan, approval chain, state, and optional receipt
     */
    public Optional<ScenarioRehearsalRemediationLineage>
    find(
            String remediationId,
            IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation operation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_READ,
                        identity,
                        "",
                        "",
                        remediationId);
        try {
            CapabilitySnapshot.Scope scope =
                    requireRemediationIdentity(identity);
            Optional<ScenarioRehearsalRemediationRepository.Snapshot>
                    retained = repository.find(
                    scope, remediationId);
            if (retained.isEmpty()) {
                throw problem(
                        conflict(
                                ScenarioRehearsalRemediationConflictException
                                        .Reason.NOT_FOUND,
                                "Scenario remediation was not found in the authorized scope"),
                        identity);
            }
            ScenarioRehearsalRemediationLineage result =
                    ScenarioRehearsalRemediationLineage.from(
                            mapper, retained.orElseThrow());
            operation.succeeded(remediationId);
            return Optional.of(result);
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    /**
     * Compares the exact predecessor and terminal successor using only root-signed workbooks.
     *
     * @param remediationId exact submitted remediation lineage
     * @param identity authenticated remediation reader
     * @return deterministic content-addressed blocker and entry comparison
     */
    public ScenarioRehearsalRemediationComparison compare(
            String remediationId,
            IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation operation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_COMPARISON_READ,
                        identity,
                        "",
                        "",
                        remediationId);
        try {
            CapabilitySnapshot.Scope scope =
                    requireRemediationIdentity(identity);
            ScenarioRehearsalRemediationRepository.Snapshot
                    retained = repository.find(
                    scope, remediationId).orElseThrow(() ->
                    problem(
                            conflict(
                                    ScenarioRehearsalRemediationConflictException
                                            .Reason.NOT_FOUND,
                                    "Scenario remediation was not found in the authorized scope"),
                            identity));
            ScenarioRehearsalRemediationLineage lineage =
                    ScenarioRehearsalRemediationLineage.from(
                            mapper, retained);
            if (lineage.state()
                    != ScenarioRehearsalRemediationRepository
                    .State.SUBMITTED
                    || lineage.receipt() == null) {
                throw new IntegrationProblemException(
                        IntegrationProblem.conflict(
                                "RG.MIRROR.REMEDIATION.COMPARISON_NOT_READY",
                                "A terminal successor is required before signed-workbook comparison.",
                                identity.correlationId(),
                                Map.of()));
            }
            IntegrationRequestContext internal =
                    internalRehearsalIdentity(identity);
            ScenarioRehearsalBatchWorkbookSeed predecessor =
                    workbooks.workbookSeed(
                            lineage.plan().predecessorJobId(),
                            internal);
            ScenarioRehearsalBatchWorkbookSeed successor =
                    workbooks.workbookSeed(
                            lineage.receipt().successorJobId(),
                            internal);
            ScenarioRehearsalRemediationComparison result =
                    ScenarioRehearsalRemediationComparison.project(
                            mapper,
                            lineage,
                            predecessor,
                            successor);
            operation.succeeded(remediationId);
            return result;
        } catch (IntegrationProblemException expected) {
            throw operation.failed(expected);
        } catch (IllegalArgumentException invalid) {
            throw operation.failed(
                    new IntegrationProblemException(
                            IntegrationProblem.conflict(
                                    "RG.MIRROR.REMEDIATION.COMPARISON_CLOSURE_INVALID",
                                    "The submitted remediation and signed workbooks do not form one comparison closure.",
                                    identity.correlationId(),
                                    Map.of())));
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    private ScenarioRehearsalBatchRequest successor(
            ScenarioRehearsalBatchRequest original,
            ScenarioRehearsalRemediationPreviewRequest proposal,
            String remediationId) {
        List<ScenarioRehearsalBatchRequest.Entry> entries =
                new ArrayList<>(original.entries());
        for (ScenarioRehearsalRemediationPreviewRequest.PlanReplacement
                replacement : proposal.replacements()) {
            if (replacement.entryIndex() >= entries.size()) {
                throw conflict(
                        ScenarioRehearsalRemediationConflictException
                                .Reason.REPLACEMENT_FENCE_MISMATCH,
                        "Scenario remediation replacement index is outside the predecessor");
            }
            ScenarioRehearsalBatchRequest.Entry predecessor =
                    entries.get(replacement.entryIndex());
            if (!predecessor.entryId().equals(
                    replacement.entryId())
                    || !predecessor.compiledPlanRef().equals(
                    replacement.expectedCompiledPlanRef())) {
                throw conflict(
                        ScenarioRehearsalRemediationConflictException
                                .Reason.REPLACEMENT_FENCE_MISMATCH,
                        "Scenario remediation replacement fence differs from signed predecessor");
            }
            entries.set(
                    replacement.entryIndex(),
                    new ScenarioRehearsalBatchRequest.Entry(
                            predecessor.entryId(),
                            replacement
                                    .replacementCompiledPlanRef()));
        }
        return new ScenarioRehearsalBatchRequest(
                "",
                remediationId,
                entries);
    }

    private void requirePredecessorClosure(
            ScenarioRehearsalBatchWorkbookSeed workbook,
            ScenarioRehearsalBatchEvidenceBundle evidence,
            String predecessorJobId) {
        if (!workbook.jobId().equals(predecessorJobId)
                || !evidence.index().job().jobId().equals(
                predecessorJobId)
                || !workbook.scope().equals(
                evidence.index().job().scope())
                || !workbook.evidenceBundleFingerprint()
                .equals(evidence.bundleFingerprint())
                || !workbook.status().equals(
                evidence.index().job().status())
                || !workbook.requestFingerprint().equals(
                evidence.index().job()
                        .requestFingerprint())
                || !workbook.manifestFingerprint().equals(
                evidence.index().manifest()
                        .manifestFingerprint())) {
            throw conflict(
                    ScenarioRehearsalRemediationConflictException
                            .Reason.EVIDENCE_CLOSURE_INVALID,
                    "Scenario remediation predecessor workbook and evidence differ");
        }
    }

    private String previewFingerprint(
            ScenarioRehearsalRemediationPlan plan) {
        return ProtocolFingerprint.ofBounded(
                mapper,
                new DatabaseScenarioRehearsalRemediationRepository
                        .PreviewFingerprintMaterial(
                        plan.predecessorJobId(),
                        plan.previewRequestId(),
                        plan.strategy(),
                        plan.reasonCode(),
                        plan.replacements(),
                        plan.predecessorWorkbookSeedFingerprint(),
                        plan.governanceTicketRef()),
                MAXIMUM_PREVIEW_FINGERPRINT_BYTES);
    }

    private CapabilitySnapshot.Scope requireOwner(
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope =
                requireRemediationIdentity(identity);
        if (!policy.mayOwn(identity)) {
            throw forbidden(
                    identity,
                    "RG.MIRROR.REMEDIATION.OWNER_REQUIRED",
                    "Reviewed Scenario remediation requires an authenticated human owner group.");
        }
        return scope;
    }

    private CapabilitySnapshot.Scope requireRemediationIdentity(
            IntegrationRequestContext identity) {
        return MirrorPlanIntegrationService
                .requireMirrorRemediationIdentity(identity);
    }

    private void requireRole(
            IntegrationRequestContext identity,
            ScenarioRehearsalRemediationApprovalCommand.Role role) {
        if (!policy.mayApprove(identity, role)) {
            throw forbidden(
                    identity,
                    "RG.MIRROR.REMEDIATION.ROLE_REQUIRED",
                    "The authenticated human is not authorized for the requested remediation role.");
        }
    }

    private static IntegrationRequestContext
    internalRehearsalIdentity(
            IntegrationRequestContext identity) {
        return new IntegrationRequestContext(
                identity.tenantId(),
                identity.organizationId(),
                identity.projectId(),
                identity.environmentId(),
                identity.region(),
                identity.actorType(),
                identity.actorId(),
                identity.delegatedBy(),
                MirrorPlanIntegrationService
                        .AUTHORIZED_PURPOSE,
                identity.correlationId(),
                identity.groups(),
                identity.clearance(),
                identity.delegationGrantId());
    }

    private static ScenarioRehearsalBatchPrincipal principal(
            IntegrationRequestContext identity,
            CapabilitySnapshot.Scope scope) {
        return new ScenarioRehearsalBatchPrincipal(
                scope,
                identity.actorType(),
                identity.actorId(),
                identity.delegatedBy(),
                identity.groups(),
                identity.clearance(),
                identity.delegationGrantId());
    }

    private static String canonicalJobId(
            String value,
            IntegrationRequestContext identity) {
        String jobId = value == null ? "" : value.trim();
        if (!ScenarioRehearsalBatchIdentity
                .hasCanonicalShape(jobId)) {
            throw new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.REMEDIATION.PREDECESSOR_ID_INVALID",
                            "Scenario remediation predecessor job id is invalid.",
                            identity.correlationId(),
                            Map.of()));
        }
        return jobId;
    }

    private static IntegrationProblemException problem(
            ScenarioRehearsalRemediationConflictException conflict,
            IntegrationRequestContext identity) {
        String code = "RG.MIRROR.REMEDIATION."
                + conflict.reason().name();
        IntegrationProblem problem = switch (conflict.reason()) {
            case NOT_FOUND ->
                    IntegrationProblem.notFound(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of());
            case PLAN_EXPIRED ->
                    IntegrationProblem.gone(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of());
            case POLICY_MISMATCH ->
                    IntegrationProblem.serviceUnavailable(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of());
            default ->
                    IntegrationProblem.conflict(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of());
        };
        return new IntegrationProblemException(problem);
    }

    private static IntegrationProblemException batchProblem(
            ScenarioRehearsalBatchConflictException conflict,
            IntegrationRequestContext identity) {
        String code = "RG.MIRROR.REMEDIATION.SUCCESSOR_"
                + conflict.reason().name();
        IntegrationProblem problem = switch (conflict.reason()) {
            case GLOBAL_QUEUE_FULL, TENANT_QUEUE_FULL ->
                    IntegrationProblem.tooManyRequests(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of("retryAfterSeconds", 1));
            case POLICY_MISMATCH, LEASE_LOST ->
                    IntegrationProblem.serviceUnavailable(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of());
            default ->
                    IntegrationProblem.conflict(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of());
        };
        return new IntegrationProblemException(problem);
    }

    private static IntegrationProblemException forbidden(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.forbidden(
                        code,
                        title,
                        identity == null
                                ? ""
                                : identity.correlationId(),
                        Map.of()));
    }

    private static ScenarioRehearsalRemediationConflictException
    conflict(
            ScenarioRehearsalRemediationConflictException.Reason
                    reason,
            String message) {
        return new ScenarioRehearsalRemediationConflictException(
                reason, message);
    }
}
