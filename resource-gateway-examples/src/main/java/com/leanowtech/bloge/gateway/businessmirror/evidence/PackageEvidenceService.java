package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationProjection;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.impact.BusinessMirrorDeepLinks;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEvent;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable Package evidence projection, portfolio query, and owner-task application boundary. */
public class PackageEvidenceService implements PackageCompilationProjection {
    private final PackageEvidenceRepository evidence;
    private final PackageCompilationFactRepository facts;
    private final DomainFidelityRepository fidelity;
    private final IntegrationChangeEventOutbox changeEvents;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PackageEvidenceService(
            PackageEvidenceRepository evidence,
            PackageCompilationFactRepository facts,
            DomainFidelityRepository fidelity,
            IntegrationChangeEventOutbox changeEvents,
            ObjectMapper mapper,
            Clock clock) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.fidelity = Objects.requireNonNull(fidelity, "fidelity");
        this.changeEvents = Objects.requireNonNull(changeEvents, "changeEvents");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Admits one successful immutable Package compile in the caller's publication transaction. */
    @Override
    public void enqueue(CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt) {
        evidence.enqueue(scope, receipt);
    }

    @Transactional
    public Optional<PackageEvidenceRepository.ProjectionLease> claim(
            String leaseOwner, Duration leaseDuration) {
        return evidence.claim(leaseOwner, leaseDuration);
    }

    /** Projects one exact Package, reconciles tasks, emits change, and completes its lease atomically. */
    @Transactional
    public PackageEvidenceRepository.ProjectionResult consume(
            PackageEvidenceRepository.ProjectionLease lease) {
        PackageEvidenceRepository.ProjectionLease exact = Objects.requireNonNull(lease, "lease");
        PackageCompilationReceipt receipt = facts.find(
                        exact.scope(), exact.packageId(), exact.compilationRevision())
                .orElseThrow(() -> new IllegalStateException(
                        "Package evidence outbox lost its immutable compilation facts"));
        if (receipt.snapshot() == null
                || !receipt.snapshot().fingerprint().equals(exact.snapshotFingerprint())) {
            throw new IllegalStateException(
                    "Package evidence outbox Snapshot differs from immutable facts");
        }
        PackageEvidenceRepository.ProjectionResult result = project(receipt);
        if (!evidence.complete(exact)) {
            throw new IllegalStateException("Package evidence lease expired before commit");
        }
        return result;
    }

    @Transactional
    public PackageEvidenceRepository.ProjectionRelease release(
            PackageEvidenceRepository.ProjectionLease lease,
            String failureCode,
            int maximumAttempts) {
        return evidence.release(lease, failureCode, maximumAttempts);
    }

    /** Reprojects one current Package against the newest signed Fidelity head. */
    @Transactional
    public PackageEvidenceRepository.ProjectionResult refresh(
            String packageId, IntegrationRequestContext identity) {
        IntegrationRequestContext actor = requireIdentity(identity);
        CapabilitySnapshot.Scope scope = scope(actor);
        PackageEvidenceIndex current = evidence.findCurrent(scope, packageId)
                .orElseThrow(() -> notFound(actor, "RG.BUSINESS_MIRROR.EVIDENCE_INDEX_NOT_FOUND",
                        "The Package evidence index does not exist.", Map.of("packageId", packageId)));
        PackageCompilationReceipt receipt = facts.find(scope, current.packageId(),
                        current.compilationRevision())
                .orElseThrow(() -> new IllegalStateException(
                        "Current Package evidence index lost immutable compilation facts"));
        return project(receipt);
    }

    /** Reads one current Package evidence index without inventing a freshness pass flag. */
    public PackageEvidenceIndex findCurrent(
            String packageId, IntegrationRequestContext identity) {
        IntegrationRequestContext actor = requireIdentity(identity);
        return evidence.findCurrent(scope(actor), packageId)
                .orElseThrow(() -> notFound(actor, "RG.BUSINESS_MIRROR.EVIDENCE_INDEX_NOT_FOUND",
                        "The Package evidence index does not exist.", Map.of("packageId", packageId)));
    }

    /** Builds one bounded domain Portfolio from current Package heads and task state. */
    public DomainEvidencePortfolio portfolio(
            String domainId,
            String afterPackageId,
            int limit,
            IntegrationRequestContext identity) {
        IntegrationRequestContext actor = requireIdentity(identity);
        if (limit < 1 || limit > 200) {
            throw badRequest(actor, "RG.BUSINESS_MIRROR.PORTFOLIO_PAGE_INVALID",
                    "Portfolio page limit must be between 1 and 200.", Map.of("limit", limit));
        }
        CapabilitySnapshot.Scope scope = scope(actor);
        PackageEvidenceRepository.CurrentPage page = evidence.findCurrentByDomain(
                scope, domainId, normalized(afterPackageId), limit);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<DomainEvidencePortfolio.PackageView> packages = new ArrayList<>();
        for (PackageEvidenceIndex index : page.items()) {
            List<EvidenceOwnerTask> tasks = new ArrayList<>();
            tasks.addAll(evidence.findTasks(scope, domainId, index.packageId(),
                    EvidenceOwnerTask.Status.OPEN, PackageEvidenceIndex.MAXIMUM_DRIFT_SIGNALS));
            tasks.addAll(evidence.findTasks(scope, domainId, index.packageId(),
                    EvidenceOwnerTask.Status.ACKNOWLEDGED,
                    PackageEvidenceIndex.MAXIMUM_DRIFT_SIGNALS));
            tasks.sort(Comparator.comparing(EvidenceOwnerTask::dueAt)
                    .thenComparing(EvidenceOwnerTask::taskId));
            if (tasks.size() > PackageEvidenceIndex.MAXIMUM_DRIFT_SIGNALS) {
                throw new IllegalStateException(
                        "Package has more active evidence tasks than the protocol permits");
            }
            packages.add(DomainEvidencePortfolio.summarize(index, tasks, now,
                    BusinessMirrorDeepLinks.evidenceLink(
                            index.packageId(), index.compilationRevision())));
        }
        return new DomainEvidencePortfolio("", "", scope, domainId, packages,
                page.nextCursor(), now).seal(mapper);
    }

    public List<EvidenceOwnerTask> tasks(
            String domainId,
            String packageId,
            EvidenceOwnerTask.Status status,
            int limit,
            IntegrationRequestContext identity) {
        IntegrationRequestContext actor = requireIdentity(identity);
        if (limit < 1 || limit > 500) {
            throw badRequest(actor, "RG.BUSINESS_MIRROR.EVIDENCE_TASK_PAGE_INVALID",
                    "Evidence task page limit must be between 1 and 500.", Map.of("limit", limit));
        }
        return evidence.findTasks(scope(actor), normalized(domainId),
                normalized(packageId), status, limit);
    }

    @Transactional
    public EvidenceOwnerTask acknowledge(
            String taskId, long expectedVersion, IntegrationRequestContext identity) {
        return transition(taskId, expectedVersion, EvidenceOwnerTask.Status.ACKNOWLEDGED,
                null, identity);
    }

    @Transactional
    public EvidenceOwnerTask resolve(
            String taskId,
            long expectedVersion,
            MirrorArtifactRef resolutionEvidenceRef,
            IntegrationRequestContext identity) {
        return transition(taskId, expectedVersion, EvidenceOwnerTask.Status.RESOLVED,
                Objects.requireNonNull(resolutionEvidenceRef, "resolutionEvidenceRef"), identity);
    }

    private EvidenceOwnerTask transition(
            String taskId,
            long expectedVersion,
            EvidenceOwnerTask.Status target,
            MirrorArtifactRef resolutionEvidenceRef,
            IntegrationRequestContext identity) {
        IntegrationRequestContext actor = requireIdentity(identity);
        if (expectedVersion < 1) {
            throw badRequest(actor, "RG.BUSINESS_MIRROR.EVIDENCE_TASK_VERSION_INVALID",
                    "expectedVersion must be positive.", Map.of("expectedVersion", expectedVersion));
        }
        try {
            EvidenceOwnerTask task = evidence.transitionTask(scope(actor), taskId,
                    expectedVersion, target, actor.actorId(), resolutionEvidenceRef,
                    clock.instant().truncatedTo(ChronoUnit.MICROS));
            appendEvent("BUSINESS_MIRROR_EVIDENCE_TASK_CHANGED", task.scope(), task.taskId(),
                    task.version(), task.taskFingerprint(), task.deepLink(), "evidence-owner-task");
            return task;
        } catch (PackageEvidenceRepository.TaskVersionConflictException conflict) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.BUSINESS_MIRROR.EVIDENCE_TASK_VERSION_CONFLICT",
                    "The evidence task changed after the requested version.",
                    actor.correlationId(), Map.of("taskId", taskId)));
        } catch (PackageEvidenceRepository.TaskNotFoundException missing) {
            throw notFound(actor, "RG.BUSINESS_MIRROR.EVIDENCE_TASK_NOT_FOUND",
                    "The evidence owner task does not exist.", Map.of("taskId", taskId));
        } catch (IllegalStateException invalidState) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.BUSINESS_MIRROR.EVIDENCE_TASK_STATE_CONFLICT",
                    "The evidence task cannot make the requested state transition.",
                    actor.correlationId(), Map.of("taskId", taskId, "target", target.name())));
        } catch (IllegalArgumentException invalidResolution) {
            throw badRequest(actor, "RG.BUSINESS_MIRROR.EVIDENCE_TASK_RESOLUTION_INVALID",
                    "The task resolution evidence is invalid.", Map.of("taskId", taskId));
        }
    }

    private PackageEvidenceRepository.ProjectionResult project(
            PackageCompilationReceipt receipt) {
        MirrorArtifactRef inventoryRef = receipt.snapshot().dependencyManifest().stream()
                .filter(value -> DomainFidelityInventory.ARTIFACT_KIND.equals(value.kind()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Compiled Package has no Fidelity inventory dependency"));
        Optional<DomainFidelityInventory> inventory = fidelity.findInventory(
                        receipt.snapshot().scope(), inventoryRef.id(), inventoryRef.revision())
                .filter(value -> value.fingerprint().equals(inventoryRef.fingerprint()));
        Optional<DomainFidelityProfile> profile = fidelity.findLatestProfile(
                receipt.snapshot().scope(), receipt.snapshot().businessDefinition().domainId());
        PackageEvidenceRepository.ProjectionReservation reservation =
                evidence.reserveProjectionRevision(receipt.snapshot().scope(), receipt.packageId(),
                        receipt.compilationRevision());
        PackageEvidenceIndex index = PackageEvidenceProjector.project(receipt, inventory, profile,
                reservation.projectionRevision(), reservation.reservedAt(), mapper);
        String deepLink = BusinessMirrorDeepLinks.evidenceLink(
                index.packageId(), index.compilationRevision());
        PackageEvidenceRepository.ProjectionResult result = evidence.append(index, deepLink);
        if (!result.replayed()) {
            appendEvent("PACKAGE_EVIDENCE_INDEX_CHANGED", index.scope(), index.packageId(),
                    index.projectionRevision(), index.indexFingerprint(), deepLink,
                    "package-evidence-index");
        }
        return result;
    }

    private void appendEvent(
            String eventType,
            CapabilitySnapshot.Scope scope,
            String id,
            long revision,
            String fingerprint,
            String payloadRef,
            String kind) {
        changeEvents.append(IntegrationChangeEvent.pending(eventType, scope.tenantId(),
                namespace(scope), scope.environmentId(),
                new IntegrationChangeEvent.Aggregate(kind, id, revision, fingerprint),
                payloadRef, ""));
    }

    private static String namespace(CapabilitySnapshot.Scope scope) {
        return scope.organizationId() + "/" + scope.projectId() + "@" + scope.region();
    }

    private static IntegrationRequestContext requireIdentity(IntegrationRequestContext identity) {
        IntegrationRequestContext exact = Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        return exact;
    }

    private static CapabilitySnapshot.Scope scope(IntegrationRequestContext identity) {
        return new CapabilitySnapshot.Scope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
