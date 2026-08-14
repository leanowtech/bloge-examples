package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceIndex;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceRepository;
import com.leanowtech.bloge.gateway.businessmirror.impact.BusinessMirrorDeepLinks;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEvent;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Stable Resource Gateway/ANEKE Package integration boundary.
 *
 * <p>Resource Gateway exports immutable facts and caches a signed external projection. It never
 * creates an ANEKE registry record, owner approval, workbook, gate decision, or publish state.</p>
 */
public class PackageGovernanceIntegrationService {
    private final PackageCompilationFactRepository facts;
    private final PackageEvidenceRepository evidence;
    private final PackageGovernanceProjectionRepository projections;
    private final PackageRegistryIngestBundleIntegrity bundleIntegrity;
    private final DomainCapabilityPackageGovernanceProjectionIntegrity projectionIntegrity;
    private final PackageGovernanceProjectionTrust trust;
    private final IntegrationChangeEventOutbox outbox;
    private final Clock clock;

    public PackageGovernanceIntegrationService(
            PackageCompilationFactRepository facts,
            PackageEvidenceRepository evidence,
            PackageGovernanceProjectionRepository projections,
            PackageRegistryIngestBundleIntegrity bundleIntegrity,
            DomainCapabilityPackageGovernanceProjectionIntegrity projectionIntegrity,
            PackageGovernanceProjectionTrust trust,
            IntegrationChangeEventOutbox outbox,
            Clock clock) {
        this.facts = Objects.requireNonNull(facts, "facts");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.bundleIntegrity = Objects.requireNonNull(bundleIntegrity, "bundleIntegrity");
        this.projectionIntegrity = Objects.requireNonNull(
                projectionIntegrity, "projectionIntegrity");
        this.trust = trust == null ? PackageGovernanceProjectionTrust.unavailable() : trust;
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Exports one exact immutable Package/evidence closure for ANEKE registry ingestion. */
    public PackageRegistryIngestBundle exportBundle(
            String packageId, long compilationRevision, IntegrationRequestContext identity) {
        IntegrationRequestContext actor = requireIdentity(identity);
        if (compilationRevision < 1) {
            throw badRequest(actor, "RG.BUSINESS_MIRROR.REGISTRY_BUNDLE_REVISION_INVALID",
                    "Package compilation revision must be positive.", Map.of());
        }
        CapabilitySnapshot.Scope scope = scope(actor);
        PackageCompilationReceipt receipt = facts.find(scope, packageId, compilationRevision)
                .orElseThrow(() -> notFound(actor,
                        "RG.BUSINESS_MIRROR.REGISTRY_BUNDLE_NOT_FOUND",
                        "The Package compilation was not found in the authorized scope.",
                        Map.of("packageId", normalized(packageId),
                                "compilationRevision", compilationRevision)));
        PackageEvidenceIndex index = evidence.findByCompilation(
                        scope, receipt.packageId(), compilationRevision)
                .orElseThrow(() -> conflict(actor,
                        "RG.BUSINESS_MIRROR.REGISTRY_BUNDLE_EVIDENCE_UNAVAILABLE",
                        "The exact Package compilation has no projected evidence index.",
                        Map.of("packageId", receipt.packageId(),
                                "compilationRevision", compilationRevision)));
        return bundle(receipt, index);
    }

    /** Verifies trust, exact current RG facts, and external generation before durable ingestion. */
    @Transactional
    public PackageGovernanceProjectionReceipt ingest(
            String packageId,
            DomainCapabilityPackageGovernanceProjection projection,
            IntegrationRequestContext identity) {
        IntegrationRequestContext actor = requireIdentity(identity);
        DomainCapabilityPackageGovernanceProjection candidate;
        try {
            candidate = Objects.requireNonNull(projection, "projection");
        } catch (RuntimeException invalid) {
            throw badRequest(actor, "RG.BUSINESS_MIRROR.GOVERNANCE_PROJECTION_INVALID",
                    "The Package governance projection is invalid.", Map.of());
        }
        CapabilitySnapshot.Scope exactScope = scope(actor);
        if (!normalized(packageId).equals(candidate.packageSnapshotRef().id())
                || !exactScope.equals(candidate.scope())) {
            throw notFound(actor, "RG.BUSINESS_MIRROR.GOVERNANCE_PACKAGE_NOT_FOUND",
                    "The Package was not found in the authorized governance scope.", Map.of());
        }
        if (!trustAvailable()) {
            throw unavailable(actor,
                    "RG.BUSINESS_MIRROR.GOVERNANCE_TRUST_UNAVAILABLE",
                    "ANEKE governance projection trust is not configured.", Map.of());
        }
        DomainCapabilityPackageGovernanceProjectionIntegrity.Verification verification =
                projectionIntegrity.verify(candidate, trust);
        if (!verification.verified()) {
            throw badRequest(actor,
                    "RG.BUSINESS_MIRROR.GOVERNANCE_PROJECTION_INVALID",
                    "The Package governance projection failed integrity or trust verification.",
                    Map.of("reasonCode", verification.reasonCode()));
        }
        Instant now = now();
        if (now.isBefore(candidate.validFrom()) || !now.isBefore(candidate.expiresAt())) {
            throw stale(actor, packageId, "PROJECTION_WINDOW_INVALID");
        }
        CurrentClosure current = currentClosure(packageId, actor);
        if (!candidate.packageSnapshotRef().equals(current.receipt().snapshot().artifactRef())
                || !candidate.evidenceIndexRef().equals(current.index().artifactRef())
                || !candidate.registryIngestBundleRef().equals(current.bundle().artifactRef())) {
            throw stale(actor, packageId, "CURRENT_RESOURCE_GATEWAY_FACTS_DIFFER");
        }
        if (!now.isBefore(current.index().validUntil())) {
            throw stale(actor, packageId, "EVIDENCE_INDEX_EXPIRED");
        }
        try {
            PackageGovernanceProjectionRepository.AppendResult result =
                    projections.append(candidate);
            if (!result.replayed()) {
                appendEvent(result.projection());
            }
            return new PackageGovernanceProjectionReceipt("",
                    result.projection().artifactRef(),
                    result.projection().externalGeneration(), result.projection().status(),
                    now, result.replayed());
        } catch (PackageGovernanceProjectionRepository.Violation violation) {
            throw repositoryProblem(actor, violation);
        }
    }

    /** Joins current RG facts with the cached ANEKE projection and derives fail-closed freshness. */
    public DomainCapabilityPackageGovernanceView view(
            String packageId, IntegrationRequestContext identity) {
        IntegrationRequestContext actor = requireIdentity(identity);
        CurrentClosure current = currentClosure(packageId, actor);
        Optional<DomainCapabilityPackageGovernanceProjection> projection;
        try {
            projection = projections.findCurrent(scope(actor), current.receipt().packageId());
        } catch (PackageGovernanceProjectionRepository.Violation corrupt) {
            return view(current, null,
                    DomainCapabilityPackageGovernanceView.Freshness.UNVERIFIABLE,
                    "STORED_PROJECTION_INVALID", now());
        }
        Instant now = now();
        if (projection.isEmpty()) {
            return view(current, null,
                    DomainCapabilityPackageGovernanceView.Freshness.MISSING,
                    "GOVERNANCE_PROJECTION_MISSING", now);
        }
        DomainCapabilityPackageGovernanceProjection value = projection.orElseThrow();
        if (!trustAvailable()) {
            return view(current, value,
                    DomainCapabilityPackageGovernanceView.Freshness.UNVERIFIABLE,
                    "GOVERNANCE_TRUST_UNAVAILABLE", now);
        }
        DomainCapabilityPackageGovernanceProjectionIntegrity.Verification verification =
                projectionIntegrity.verify(value, trust);
        if (!verification.verified()) {
            return view(current, value,
                    DomainCapabilityPackageGovernanceView.Freshness.UNVERIFIABLE,
                    verification.reasonCode(), now);
        }
        if (now.isBefore(value.validFrom()) || !now.isBefore(value.expiresAt())) {
            return view(current, value,
                    DomainCapabilityPackageGovernanceView.Freshness.EXPIRED,
                    "PROJECTION_WINDOW_INVALID", now);
        }
        if (!now.isBefore(current.index().validUntil())) {
            return view(current, value,
                    DomainCapabilityPackageGovernanceView.Freshness.STALE,
                    "EVIDENCE_INDEX_EXPIRED", now);
        }
        if (!value.packageSnapshotRef().equals(current.receipt().snapshot().artifactRef())
                || !value.evidenceIndexRef().equals(current.index().artifactRef())
                || !value.registryIngestBundleRef().equals(current.bundle().artifactRef())) {
            return view(current, value,
                    DomainCapabilityPackageGovernanceView.Freshness.STALE,
                    "CURRENT_RESOURCE_GATEWAY_FACTS_DIFFER", now);
        }
        return view(current, value,
                DomainCapabilityPackageGovernanceView.Freshness.CURRENT, "", now);
    }

    /** @return whether signed ANEKE projections can currently be admitted */
    public boolean ingestionReady() {
        return trustAvailable();
    }

    private CurrentClosure currentClosure(
            String packageId, IntegrationRequestContext actor) {
        CapabilitySnapshot.Scope scope = scope(actor);
        PackageCompilationReceipt receipt = facts.findCurrent(scope, packageId)
                .orElseThrow(() -> notFound(actor,
                        "RG.BUSINESS_MIRROR.GOVERNANCE_PACKAGE_NOT_FOUND",
                        "The Package was not found in the authorized governance scope.",
                        Map.of("packageId", normalized(packageId))));
        PackageEvidenceIndex index = evidence.findCurrent(scope, receipt.packageId())
                .orElseThrow(() -> conflict(actor,
                        "RG.BUSINESS_MIRROR.GOVERNANCE_EVIDENCE_UNAVAILABLE",
                        "The current Package has no evidence index.",
                        Map.of("packageId", receipt.packageId(),
                                "compilationRevision", receipt.compilationRevision())));
        if (index.compilationRevision() != receipt.compilationRevision()
                || !index.packageSnapshotSource().fingerprint()
                .equals(receipt.snapshot().fingerprint())) {
            throw stale(actor, receipt.packageId(), "EVIDENCE_INDEX_BEHIND_COMPILATION");
        }
        return new CurrentClosure(receipt, index, bundle(receipt, index));
    }

    private PackageRegistryIngestBundle bundle(
            PackageCompilationReceipt receipt, PackageEvidenceIndex index) {
        if (receipt.snapshot() == null
                || receipt.compilationRevision() != index.compilationRevision()) {
            throw new IllegalStateException("Package registry bundle facts do not close");
        }
        Instant exportedAt = latest(receipt.completedAt(), receipt.snapshot().createdAt(),
                index.projectedAt());
        PackageRegistryIngestBundle material = new PackageRegistryIngestBundle("", "",
                "package-registry-ingest:" + receipt.packageId(),
                receipt.compilationRevision(), receipt.snapshot().scope(), receipt.snapshot(),
                receipt.readiness(), receipt.businessAssetLinkClosure(), index,
                receipt.snapshot().dependencyManifest(), exportedAt,
                "resource-gateway:business-mirror");
        PackageRegistryIngestBundle addressed = bundleIntegrity.address(material);
        if (!bundleIntegrity.canonicalVerified(addressed)) {
            throw new IllegalStateException("Package registry bundle integrity failed");
        }
        return addressed;
    }

    private DomainCapabilityPackageGovernanceView view(
            CurrentClosure current,
            DomainCapabilityPackageGovernanceProjection projection,
            DomainCapabilityPackageGovernanceView.Freshness freshness,
            String reason,
            Instant at) {
        return new DomainCapabilityPackageGovernanceView("", current.receipt().snapshot().scope(),
                current.receipt().packageId(), current.receipt().snapshot().artifactRef(),
                current.index().artifactRef(), current.bundle().artifactRef(), projection,
                freshness, reason, at);
    }

    private void appendEvent(DomainCapabilityPackageGovernanceProjection projection) {
        CapabilitySnapshot.Scope scope = projection.scope();
        outbox.append(IntegrationChangeEvent.pending(
                "DOMAIN_CAPABILITY_PACKAGE_GOVERNANCE_CHANGED", scope.tenantId(),
                scope.organizationId() + "/" + scope.projectId() + "@" + scope.region(),
                scope.environmentId(),
                new IntegrationChangeEvent.Aggregate(
                        "package-governance-projection",
                        projection.packageSnapshotRef().id(),
                        projection.externalGeneration(), projection.projectionFingerprint()),
                BusinessMirrorDeepLinks.packageLink(projection.packageSnapshotRef().id(),
                        projection.packageSnapshotRef().revision()), ""));
    }

    private boolean trustAvailable() {
        try {
            return trust.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant latest(Instant first, Instant second, Instant third) {
        Instant result = Objects.requireNonNull(first, "first");
        if (Objects.requireNonNull(second, "second").isAfter(result)) {
            result = second;
        }
        if (Objects.requireNonNull(third, "third").isAfter(result)) {
            result = third;
        }
        return result;
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

    private static IntegrationProblemException repositoryProblem(
            IntegrationRequestContext actor,
            PackageGovernanceProjectionRepository.Violation violation) {
        String code = "RG.BUSINESS_MIRROR.GOVERNANCE_PROJECTION_"
                + violation.reason().name();
        return conflict(actor, code,
                "The Package governance projection violates the durable generation protocol.",
                Map.of("reasonCode", violation.reason().name()));
    }

    private static IntegrationProblemException stale(
            IntegrationRequestContext actor, String packageId, String reasonCode) {
        return conflict(actor, "RG.GOVERNANCE.PROJECTION_STALE",
                "The ANEKE projection does not bind the current Resource Gateway facts.",
                Map.of("packageId", normalized(packageId), "reasonCode", reasonCode));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext actor, String code, String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, actor.correlationId(), details));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext actor, String code, String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, actor.correlationId(), details));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext actor, String code, String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                code, title, actor.correlationId(), details));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext actor, String code, String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, actor.correlationId(), details));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record CurrentClosure(
            PackageCompilationReceipt receipt,
            PackageEvidenceIndex index,
            PackageRegistryIngestBundle bundle) {
    }
}
