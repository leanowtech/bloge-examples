package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Multi-instance-safe reconciliation command service over immutable run evidence. */
@Service
public class SideEffectReconciliationService {
    static final String SIDE_EFFECT_GAP =
            "One or more external side-effect attempts do not have a definitive commit outcome.";
    private static final Duration DEFAULT_CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration DEFAULT_RECONCILER_TIMEOUT = Duration.ofSeconds(20);

    private final VisualGraphRunRepository runRepository;
    private final SideEffectReconciliationRepository repository;
    private final SideEffectReconcilerRegistry registry;
    private final Clock clock;
    private final Duration claimLease;
    private final Duration reconcilerTimeout;
    private final ExecutorService executor;

    @Autowired
    public SideEffectReconciliationService(VisualGraphRunRepository runRepository,
                                           SideEffectReconciliationRepository repository,
                                           SideEffectReconcilerRegistry registry) {
        this(runRepository, repository, registry, Clock.systemUTC(), DEFAULT_CLAIM_LEASE,
                DEFAULT_RECONCILER_TIMEOUT);
    }

    SideEffectReconciliationService(VisualGraphRunRepository runRepository,
                                    SideEffectReconciliationRepository repository,
                                    SideEffectReconcilerRegistry registry,
                                    Clock clock,
                                    Duration claimLease,
                                    Duration reconcilerTimeout) {
        this.runRepository = runRepository;
        this.repository = repository;
        this.registry = registry;
        this.clock = clock;
        this.claimLease = claimLease;
        this.reconcilerTimeout = reconcilerTimeout;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public IntegrationEnvelope<SideEffectReconciliationRecord> reconcile(
            String runId,
            String attemptId,
            SideEffectReconciliationRequest request,
            IntegrationRequestContext context) {
        context.requireComplete();
        requirePurpose(context, "SIDE_EFFECT_RECONCILIATION");
        validate(request, context);
        VisualGraphRunRecord run = findRun(runId, context);
        RunEvidenceBundle evidence = RunEvidenceBundle.from(run, runRepository.evidenceSigner());
        verifyBaseEvidenceIntegrity(evidence, context);
        LocatedAttempt located = locate(evidence, attemptId, context);
        verifyExpectedSnapshot(request, evidence, located, context);
        ensureReconcilable(located, context);
        SideEffectReconciler reconciler = registry.find(located.attempt().request().reconcilerRef())
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                        "RG.INTEGRATION.SIDE_EFFECT_RECONCILER_UNAVAILABLE",
                        "The operator reconciliation adapter is not available.", context.correlationId(),
                        Map.of("reconcilerRef", located.attempt().request().reconcilerRef()))));

        String ownerToken = UUID.randomUUID().toString();
        Instant now = clock.instant();
        SideEffectReconciliationRepository.Claim claim = repository.claim(
                new SideEffectReconciliationRepository.ClaimRequest(
                        runId, attemptId, request.requestId(), request.requestFingerprint(),
                        context.tenantId(), context.environmentId(), ownerToken, now, now.plus(claimLease)));
        if (claim.status() == SideEffectReconciliationRepository.ClaimStatus.RESOLVED) {
            verifyStored(claim.existing(), context);
            verifyBinding(claim.existing(), evidence, context);
            return envelope(claim.existing());
        }
        handleRejectedClaim(claim, context);

        SideEffectReconciler.Resolution providerResolution = invoke(reconciler,
                query(evidence, located, context), context);
        SideEffectReconciliationRecord unsigned = record(request, evidence, located, providerResolution, context);
        SideEffectReconciliationRecord signed = unsigned.withEvidenceSeal(
                runRepository.evidenceSigner().seal(unsigned.recordFingerprint()));
        SideEffectReconciliationRecord stored = repository.complete(
                runId, attemptId, ownerToken, signed);
        verifyStored(stored, context);
        verifyBinding(stored, evidence, context);
        return envelope(stored);
    }

    public IntegrationEnvelope<SideEffectReconciliationSummary> summary(
            String runId,
            IntegrationRequestContext context) {
        context.requireComplete();
        VisualGraphRunRecord run = findRun(runId, context);
        RunEvidenceBundle evidence = RunEvidenceBundle.from(run, runRepository.evidenceSigner());
        verifyBaseEvidenceIntegrity(evidence, context);
        List<SideEffectReconciliationRecord> records = repository.forRun(runId);
        records.forEach(record -> {
            verifyStored(record, context);
            verifyBinding(record, evidence, context);
        });
        SideEffectReconciliationSummary summary = summary(evidence, records);
        return IntegrationEnvelope.of("SIDE_EFFECT_RECONCILIATION_SUMMARY",
                SideEffectReconciliationSummary.SCHEMA_VERSION, summary);
    }

    public boolean available() {
        return repository != null && repository.available();
    }

    public boolean hasRegisteredReconcilers() {
        return registry != null && registry.available();
    }

    private SideEffectReconciliationSummary summary(
            RunEvidenceBundle evidence,
            List<SideEffectReconciliationRecord> records) {
        Set<String> unresolved = new LinkedHashSet<>();
        for (RunEvidenceBundle.NodeEvidence node : evidence.nodes()) {
            boolean hasAddressableUncertainty = false;
            for (RunEvidenceBundle.SideEffectAttempt attempt : node.sideEffectAttempts()) {
                if (Set.of("PREPARED", "UNKNOWN_COMMIT").contains(attempt.outcome())) {
                    unresolved.add(attempt.attemptId());
                    hasAddressableUncertainty = true;
                }
            }
            if ("PARTIAL_COMMIT".equals(node.sideEffectOutcome())) {
                unresolved.add("node:" + node.nodeId() + ":partial-commit");
            } else if (Set.of("PREPARED", "UNKNOWN_COMMIT").contains(node.sideEffectOutcome())
                    && !hasAddressableUncertainty) {
                unresolved.add("node:" + node.nodeId() + ":unattributed-unknown-commit");
            }
        }
        Set<String> resolved = new LinkedHashSet<>();
        records.forEach(record -> resolved.add(record.target().attemptId()));
        unresolved.removeAll(resolved);
        List<String> remainingGaps = new ArrayList<>(evidence.manifest().gaps());
        if (unresolved.isEmpty()) {
            remainingGaps.remove(SIDE_EFFECT_GAP);
        }
        String status = records.isEmpty() && unresolved.isEmpty()
                ? "NOT_REQUIRED" : unresolved.isEmpty() ? "RESOLVED" : "OUTSTANDING";
        String governanceStatus = remainingGaps.isEmpty()
                && "VERIFIED".equals(evidence.manifest().signatureStatus()) ? "READY" : "QUARANTINED";
        return new SideEffectReconciliationSummary("", evidence.runId(), evidence.evidenceId(),
                evidence.manifest().manifestHash(), status, governanceStatus,
                List.copyOf(unresolved), remainingGaps, records);
    }

    private SideEffectReconciler.Query query(RunEvidenceBundle evidence,
                                             LocatedAttempt located,
                                             IntegrationRequestContext context) {
        RunEvidenceBundle.SideEffectRequest request = located.attempt().request();
        return new SideEffectReconciler.Query(
                new SideEffectReconciler.Base(evidence.runId(), evidence.evidenceId(),
                        evidence.manifest().manifestHash()),
                new SideEffectReconciler.Attempt(located.nodeId(), located.attempt().attemptId(),
                        located.attempt().attemptFingerprint(), request.operationRef(),
                        request.idempotencyKeyFingerprint(), request.reconciliationLookupRef()),
                new SideEffectReconciler.Scope(evidence.source().tenantId(), evidence.source().namespace(),
                        evidence.source().environment()), context.correlationId());
    }

    private SideEffectReconciliationRecord record(
            SideEffectReconciliationRequest request,
            RunEvidenceBundle evidence,
            LocatedAttempt located,
            SideEffectReconciler.Resolution resolution,
            IntegrationRequestContext context) {
        RunEvidenceBundle.SideEffectRequest sideEffectRequest = located.attempt().request();
        return SideEffectReconciliationRecord.create(
                request.requestId(), request.requestFingerprint(),
                new SideEffectReconciliationRecord.BaseEvidence(
                        evidence.runId(), evidence.evidenceId(), evidence.manifest().manifestHash(),
                        evidence.source().tenantId(), evidence.source().namespace(), evidence.source().environment()),
                new SideEffectReconciliationRecord.Target(
                        located.nodeId(), located.attempt().attemptId(), located.attempt().attemptFingerprint(),
                        sideEffectRequest.operationRef(), sideEffectRequest.idempotencyKeyFingerprint(),
                        sideEffectRequest.reconcilerRef(), sideEffectRequest.reconciliationLookupRef()),
                new SideEffectReconciliationRecord.Resolution(
                        resolution.outcome(), resolution.receipt(), resolution.reasonCode(), resolution.observedAt()),
                new SideEffectReconciliationRecord.Actor(
                        context.actorType(), context.actorId(), context.delegatedBy(), context.correlationId()),
                new SideEffectReconciliationRecord.Chain(1, ""));
    }

    private SideEffectReconciler.Resolution invoke(SideEffectReconciler reconciler,
                                                   SideEffectReconciler.Query query,
                                                   IntegrationRequestContext context) {
        Future<SideEffectReconciler.Resolution> future = executor.submit(() -> reconciler.reconcile(query));
        try {
            SideEffectReconciler.Resolution resolution = future.get(
                    reconcilerTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (resolution == null) {
                throw new IllegalStateException("Reconciler returned no resolution");
            }
            return resolution;
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw unavailable("RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_TIMEOUT",
                    "The operator reconciliation adapter did not respond before its deadline.", context);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw unavailable("RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_INTERRUPTED",
                    "The reconciliation command was interrupted.", context);
        } catch (ExecutionException exception) {
            throw unavailable("RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_FAILED",
                    "The operator reconciliation adapter failed.", context);
        }
    }

    private static IntegrationProblemException unavailable(String code,
                                                           String title,
                                                           IntegrationRequestContext context) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, context.correlationId(), Map.of()));
    }

    private static void handleRejectedClaim(SideEffectReconciliationRepository.Claim claim,
                                            IntegrationRequestContext context) {
        if (claim.status() == SideEffectReconciliationRepository.ClaimStatus.ACQUIRED) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (claim.leaseUntil() != null && !Instant.EPOCH.equals(claim.leaseUntil())) {
            details.put("retryAfter", claim.leaseUntil().toString());
        }
        String code = switch (claim.status()) {
            case IN_PROGRESS -> "RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_IN_PROGRESS";
            case REQUEST_CONFLICT -> "RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_REQUEST_CONFLICT";
            case TARGET_CONFLICT -> "RG.INTEGRATION.SIDE_EFFECT_ALREADY_RECONCILED";
            default -> "RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_CONFLICT";
        };
        throw new IntegrationProblemException(IntegrationProblem.conflict(
                code, "The side-effect reconciliation command conflicts with durable state.",
                context.correlationId(), details));
    }

    private static void verifyExpectedSnapshot(SideEffectReconciliationRequest request,
                                               RunEvidenceBundle evidence,
                                               LocatedAttempt located,
                                               IntegrationRequestContext context) {
        if (!request.expectedEvidenceFingerprint().equals(evidence.manifest().manifestHash())
                || !request.expectedAttemptFingerprint().equals(located.attempt().attemptFingerprint())) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_STALE",
                    "The reconciliation command does not target the current immutable evidence snapshot.",
                    context.correlationId(), Map.of(
                            "evidenceFingerprint", evidence.manifest().manifestHash(),
                            "attemptFingerprint", located.attempt().attemptFingerprint())));
        }
    }

    private static void ensureReconcilable(LocatedAttempt located, IntegrationRequestContext context) {
        RunEvidenceBundle.SideEffectAttempt attempt = located.attempt();
        if (!Set.of("PREPARED", "UNKNOWN_COMMIT").contains(attempt.outcome())) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.SIDE_EFFECT_ALREADY_TERMINAL",
                    "Only an unresolved side-effect attempt can be reconciled.",
                    context.correlationId(), Map.of("outcome", attempt.outcome())));
        }
        if (attempt.request().reconcilerRef().isBlank()
                || attempt.request().reconciliationLookupRef().isBlank()) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.SIDE_EFFECT_NOT_RECONCILABLE",
                    "The operator did not persist a reconciler and opaque lookup reference.",
                    context.correlationId(), Map.of("attemptId", attempt.attemptId())));
        }
    }

    private static void validate(SideEffectReconciliationRequest request,
                                 IntegrationRequestContext context) {
        Map<String, Object> invalid = new LinkedHashMap<>();
        if (request == null) {
            invalid.put("request", "required");
        } else {
            if (!SideEffectReconciliationRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
                invalid.put("schemaVersion", SideEffectReconciliationRequest.SCHEMA_VERSION);
            }
            if (request.requestId().isBlank()) invalid.put("requestId", "required");
            if (request.expectedEvidenceFingerprint().isBlank()) {
                invalid.put("expectedEvidenceFingerprint", "required");
            }
            if (request.expectedAttemptFingerprint().isBlank()) {
                invalid.put("expectedAttemptFingerprint", "required");
            }
        }
        if (!invalid.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_REQUEST_INVALID",
                    "The side-effect reconciliation request is invalid.",
                    context.correlationId(), invalid));
        }
    }

    private static LocatedAttempt locate(RunEvidenceBundle evidence,
                                         String attemptId,
                                         IntegrationRequestContext context) {
        for (RunEvidenceBundle.NodeEvidence node : evidence.nodes()) {
            for (RunEvidenceBundle.SideEffectAttempt attempt : node.sideEffectAttempts()) {
                if (attempt.attemptId().equals(attemptId)) {
                    return new LocatedAttempt(node.nodeId(), attempt);
                }
            }
        }
        throw new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.INTEGRATION.SIDE_EFFECT_ATTEMPT_NOT_FOUND",
                "The side-effect attempt was not found in the authorized run evidence.",
                context.correlationId(), Map.of()));
    }

    private VisualGraphRunRecord findRun(String runId, IntegrationRequestContext context) {
        VisualGraphRunRecord run = runRepository.find(runId).orElse(null);
        if (run == null || !context.tenantId().equals(run.tenantId())
                || !context.environmentId().equals(run.environment())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.INTEGRATION.RUN_NOT_FOUND",
                    "Run was not found in the authorized integration scope.",
                    context.correlationId(), Map.of()));
        }
        return run;
    }

    private void verifyStored(SideEffectReconciliationRecord record,
                              IntegrationRequestContext context) {
        if (record == null) {
            throw unavailable("RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_EVIDENCE_MISSING",
                    "Durable reconciliation state has no immutable evidence record.", context);
        }
        VisualEvidenceSigner.Verification verification = record.verify(runRepository.evidenceSigner());
        if (!verification.valid()) {
            throw unavailable("RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_EVIDENCE_INVALID",
                    "Stored reconciliation evidence failed integrity verification.", context);
        }
        if (!context.tenantId().equals(record.baseEvidence().tenantId())
                || !context.environmentId().equals(record.baseEvidence().environmentId())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.INTEGRATION.RUN_NOT_FOUND",
                    "Run was not found in the authorized integration scope.",
                    context.correlationId(), Map.of()));
        }
    }

    private static void verifyBaseEvidenceIntegrity(RunEvidenceBundle evidence,
                                                    IntegrationRequestContext context) {
        if (!"VERIFIED".equals(evidence.manifest().signatureStatus())) {
            throw unavailable("RG.INTEGRATION.RUN_EVIDENCE_INTEGRITY_INVALID",
                    "Run evidence must have a valid signature before reconciliation.", context);
        }
    }

    private static void verifyBinding(SideEffectReconciliationRecord record,
                                      RunEvidenceBundle evidence,
                                      IntegrationRequestContext context) {
        if (!record.baseEvidence().runId().equals(evidence.runId())
                || !record.baseEvidence().evidenceId().equals(evidence.evidenceId())
                || !record.baseEvidence().evidenceFingerprint().equals(evidence.manifest().manifestHash())) {
            throw unavailable("RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_BINDING_INVALID",
                    "Reconciliation evidence is not bound to the immutable base evidence.", context);
        }
        boolean targetMatches = evidence.nodes().stream()
                .filter(node -> node.nodeId().equals(record.target().nodeId()))
                .flatMap(node -> node.sideEffectAttempts().stream())
                .anyMatch(attempt -> attempt.attemptId().equals(record.target().attemptId())
                        && attempt.attemptFingerprint().equals(record.target().attemptFingerprint()));
        if (!targetMatches) {
            throw unavailable("RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_BINDING_INVALID",
                    "Reconciliation evidence does not identify an immutable side-effect attempt.", context);
        }
    }

    private static IntegrationEnvelope<SideEffectReconciliationRecord> envelope(
            SideEffectReconciliationRecord record) {
        return IntegrationEnvelope.of("SIDE_EFFECT_RECONCILIATION_RECORD",
                SideEffectReconciliationRecord.SCHEMA_VERSION, record);
    }

    private static void requirePurpose(IntegrationRequestContext context, String purpose) {
        if (!purpose.equals(context.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.INTEGRATION.PURPOSE_FORBIDDEN",
                    "The integration purpose is not permitted for reconciliation.",
                    context.correlationId(), Map.of("requiredPurpose", purpose)));
        }
    }

    @PreDestroy
    void closeExecutor() {
        executor.close();
    }

    private record LocatedAttempt(String nodeId, RunEvidenceBundle.SideEffectAttempt attempt) {
    }
}
