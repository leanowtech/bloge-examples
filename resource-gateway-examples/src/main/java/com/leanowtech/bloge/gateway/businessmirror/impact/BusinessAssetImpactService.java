package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationProjection;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEvent;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Authenticated impact query, durable projection admission, and bounded rebuild boundary. */
public class BusinessAssetImpactService implements PackageCompilationProjection {
    private final BusinessAssetImpactRepository impacts;
    private final PackageCompilationFactRepository facts;
    private final IntegrationChangeEventOutbox outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public BusinessAssetImpactService(
            BusinessAssetImpactRepository impacts,
            PackageCompilationFactRepository facts,
            IntegrationChangeEventOutbox outbox,
            ObjectMapper mapper,
            Clock clock) {
        this.impacts = Objects.requireNonNull(impacts, "impacts");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Transactionally admits a newly compiled Snapshot to the projection outbox. */
    @Override
    public void enqueue(CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt) {
        if (impacts.enqueue(scope, receipt)) {
            appendEvent("DOMAIN_CAPABILITY_PACKAGE_SNAPSHOT_COMPILED", scope, receipt.packageId(),
                    receipt.compilationRevision(), receipt.snapshot().fingerprint(),
                    compilationRef(receipt), "business-mirror-package");
        }
    }

    /** Claims one projection command; the short lease transaction never performs compilation work. */
    @Transactional
    public Optional<BusinessAssetImpactRepository.ProjectionLease> claim(
            String leaseOwner, Duration leaseDuration) {
        return impacts.claim(leaseOwner, leaseDuration);
    }

    /** Builds one projection and atomically commits its impact event and outbox completion. */
    @Transactional
    public BusinessAssetImpactRepository.ProjectionResult consume(
            BusinessAssetImpactRepository.ProjectionLease lease) {
        Objects.requireNonNull(lease, "lease");
        PackageCompilationReceipt receipt = facts.find(
                        lease.scope(), lease.packageId(), lease.compilationRevision())
                .orElseThrow(() -> new IllegalStateException(
                        "Impact outbox lost its immutable Package compilation facts"));
        if (receipt.snapshot() == null
                || !receipt.snapshot().fingerprint().equals(lease.snapshotFingerprint())) {
            throw new IllegalStateException(
                    "Impact outbox Snapshot coordinate differs from immutable facts");
        }
        BusinessAssetImpactRepository.ProjectionResult result = project(lease.scope(), receipt);
        if (!impacts.complete(lease)) {
            throw new IllegalStateException("Impact projection lease expired before commit");
        }
        return result;
    }

    /** Releases one failed attempt into bounded retry or terminal quarantine. */
    @Transactional
    public BusinessAssetImpactRepository.ProjectionRelease release(
            BusinessAssetImpactRepository.ProjectionLease lease,
            String failureCode,
            int maximumAttempts) {
        return impacts.release(lease, failureCode, maximumAttempts);
    }

    /** Reads a bounded report for every exact source revision matching one logical asset. */
    public BusinessAssetImpactReport query(
            String kind,
            String id,
            String authority,
            String afterPackageId,
            int limit,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        if (limit < 1 || limit > 200) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.IMPACT_PAGE_INVALID",
                    "Impact page limit must be between 1 and 200.", Map.of("limit", limit));
        }
        BusinessAssetSelector selector;
        try {
            selector = new BusinessAssetSelector(BusinessAssetRef.Kind.valueOf(
                    normalized(kind).toUpperCase(Locale.ROOT)), id, authority);
        } catch (IllegalArgumentException failure) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.IMPACT_SELECTOR_INVALID",
                    "Business asset kind, id, or authority is invalid.", Map.of());
        }
        CapabilitySnapshot.Scope exactScope = scope(identity);
        BusinessAssetImpactRepository.ImpactQuery query = impacts.query(
                exactScope, selector, normalized(afterPackageId), limit);
        List<BusinessAssetImpactReport.PackageImpact> items = query.items().stream()
                .map(item -> packageImpact(exactScope, item)).toList();
        BusinessAssetImpactReport.Status status = query.stalePackageIds().isEmpty()
                && !query.stalePackageIdsTruncated()
                ? BusinessAssetImpactReport.Status.CURRENT
                : BusinessAssetImpactReport.Status.STALE;
        return new BusinessAssetImpactReport("", exactScope, selector, status,
                query.stalePackageIds(), query.stalePackageIdsTruncated(), items,
                query.nextCursor(), query.projectedThrough(), "").seal(mapper);
    }

    /** Rebuilds a bounded page of stale projections from authoritative immutable facts. */
    @Transactional
    public BusinessAssetImpactRebuildReport rebuild(
            String afterPackageId, int limit, IntegrationRequestContext identity) {
        requireIdentity(identity);
        if (limit < 1 || limit > 200) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.IMPACT_REBUILD_PAGE_INVALID",
                    "Impact rebuild limit must be between 1 and 200.", Map.of("limit", limit));
        }
        CapabilitySnapshot.Scope exactScope = scope(identity);
        List<BusinessAssetImpactRepository.SnapshotCoordinate> stale = impacts.staleSnapshots(
                exactScope, normalized(afterPackageId), limit + 1);
        boolean more = stale.size() > limit;
        List<BusinessAssetImpactRepository.SnapshotCoordinate> page = more
                ? List.copyOf(stale.subList(0, limit)) : stale;
        int projected = 0;
        int replayed = 0;
        List<String> packageIds = new ArrayList<>();
        for (BusinessAssetImpactRepository.SnapshotCoordinate coordinate : page) {
            PackageCompilationReceipt receipt = facts.find(exactScope, coordinate.packageId(),
                            coordinate.compilationRevision())
                    .orElseThrow(() -> new IllegalStateException(
                            "Stale impact coordinate lost its immutable compilation facts"));
            BusinessAssetImpactRepository.ProjectionResult result =
                    project(exactScope, receipt);
            packageIds.add(coordinate.packageId());
            if (result.replayed()) {
                replayed++;
            } else {
                projected++;
            }
        }
        Instant completedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return new BusinessAssetImpactRebuildReport("", projected, replayed, packageIds,
                more ? page.getLast().packageId() : "", completedAt);
    }

    private BusinessAssetImpactRepository.ProjectionResult project(
            CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt) {
        if (receipt.snapshot() == null) {
            throw new IllegalArgumentException("A blocked Package compilation has no impact projection");
        }
        BusinessAssetImpactRepository.ProjectionResult result = impacts.project(scope, receipt);
        if (!result.replayed()) {
            appendEvent("BUSINESS_ASSET_IMPACT_CHANGED", scope, receipt.packageId(),
                    receipt.compilationRevision(), result.closureFingerprint(),
                    BusinessMirrorDeepLinks.packageLink(
                            receipt.packageId(), receipt.compilationRevision()),
                    "business-asset-impact");
        }
        return result;
    }

    private BusinessAssetImpactReport.PackageImpact packageImpact(
            CapabilitySnapshot.Scope scope,
            BusinessAssetImpactRepository.StoredPackageImpact item) {
        List<BusinessAssetImpactReport.SourceMatch> matches = item.matches().stream()
                .map(match -> new BusinessAssetImpactReport.SourceMatch(match.sourceRef(),
                        match.paths().stream().map(path -> new BusinessAssetImpactReport.ImpactPath(
                                path.impactedRef(), path.depth(), path.pathCount(), path.highestRisk(),
                                path.representativePath(), BusinessMirrorDeepLinks.assetLink(
                                item.packageId(), item.compilationRevision(), path.impactedRef())))
                                .toList(),
                        BusinessMirrorDeepLinks.assetLink(item.packageId(),
                                item.compilationRevision(), match.sourceRef())))
                .toList();
        return new BusinessAssetImpactReport.PackageImpact(scope, item.packageId(),
                item.compilationRevision(), item.packageSnapshotRef(),
                item.businessAssetLinkClosureRef(), matches,
                BusinessMirrorDeepLinks.packageLink(item.packageId(), item.compilationRevision()));
    }

    private void appendEvent(
            String eventType,
            CapabilitySnapshot.Scope scope,
            String id,
            long revision,
            String fingerprint,
            String payloadRef,
            String kind) {
        outbox.append(IntegrationChangeEvent.pending(eventType, scope.tenantId(),
                namespace(scope), scope.environmentId(),
                new IntegrationChangeEvent.Aggregate(kind, id, revision, fingerprint),
                payloadRef, ""));
    }

    private static String compilationRef(PackageCompilationReceipt receipt) {
        return "/api/business-mirror/packages/" + encodedPath(receipt.packageId())
                + "/compilations/" + receipt.compilationRevision();
    }

    private static String encodedPath(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String namespace(CapabilitySnapshot.Scope scope) {
        return scope.organizationId() + "/" + scope.projectId() + "@" + scope.region();
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title, Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
    }

    private static CapabilitySnapshot.Scope scope(IntegrationRequestContext identity) {
        return new CapabilitySnapshot.Scope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
