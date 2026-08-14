package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Deterministic, bounded transitive impact projection derived only from one sealed Closure. */
public final class BusinessAssetImpactProjection {
    /** Maximum transitive source/target entries admitted for one Package snapshot. */
    public static final int MAXIMUM_IMPACT_ENTRIES = 100_000;
    /** Maximum affected assets exposed for one source before a paged v2 protocol is required. */
    public static final int MAXIMUM_SOURCE_IMPACT_ENTRIES = 4_096;
    /** Maximum representative path depth admitted by the L0-L3 projection. */
    public static final int MAXIMUM_PATH_DEPTH = 64;

    private BusinessAssetImpactProjection() {
    }

    /**
     * Computes one canonical shortest representative path and a saturated path count per pair.
     *
     * @param closure verified acyclic business relation closure
     * @return source projections in canonical asset order
     * @throws ProjectionLimitException when the transitive projection exceeds a hard bound
     */
    public static List<SourceImpact> compile(BusinessAssetLinkClosure closure) {
        BusinessAssetLinkClosure exact = java.util.Objects.requireNonNull(closure, "closure");
        Comparator<BusinessAssetRef> assets = assetComparator();
        Map<BusinessAssetRef, List<BusinessAssetLink>> adjacency = new HashMap<>();
        Map<BusinessAssetRef, Integer> indegree = new HashMap<>();
        exact.assets().forEach(asset -> indegree.put(asset, 0));
        for (BusinessAssetLink link : exact.links()) {
            adjacency.computeIfAbsent(link.sourceRef(), ignored -> new ArrayList<>()).add(link);
            indegree.compute(link.targetRef(), (ignored, value) -> value == null ? 1 : value + 1);
        }
        adjacency.values().forEach(values -> values.sort(linkComparator()));
        List<BusinessAssetRef> topological = topological(indegree, adjacency, assets);

        List<SourceImpact> projections = new ArrayList<>();
        int entries = 0;
        for (BusinessAssetRef source : exact.assets()) {
            Map<BusinessAssetRef, Aggregate> reachable = new LinkedHashMap<>();
            reachable.put(source, Aggregate.root());
            for (BusinessAssetRef current : topological) {
                Aggregate currentAggregate = reachable.get(current);
                if (currentAggregate == null) {
                    continue;
                }
                for (BusinessAssetLink link : adjacency.getOrDefault(current, List.of())) {
                    int depth = currentAggregate.representativePath().size() + 1;
                    if (depth > MAXIMUM_PATH_DEPTH) {
                        throw new ProjectionLimitException("BUSINESS_ASSET_IMPACT_DEPTH_EXCEEDED");
                    }
                    List<BusinessAssetLink> candidatePath = new ArrayList<>(
                            currentAggregate.representativePath());
                    candidatePath.add(link);
                    Aggregate candidate = new Aggregate(currentAggregate.pathCount(),
                            highest(currentAggregate.highestRisk(), link.risk()),
                            List.copyOf(candidatePath));
                    reachable.merge(link.targetRef(), candidate,
                            BusinessAssetImpactProjection::merge);
                }
            }
            List<ImpactPath> paths = reachable.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(source))
                    .sorted(Map.Entry.comparingByKey(assets))
                    .map(entry -> new ImpactPath(entry.getKey(),
                            entry.getValue().representativePath().size(),
                            entry.getValue().pathCount(), entry.getValue().highestRisk(),
                            entry.getValue().representativePath()))
                    .toList();
            if (paths.size() > MAXIMUM_SOURCE_IMPACT_ENTRIES) {
                throw new ProjectionLimitException("BUSINESS_ASSET_IMPACT_SOURCE_LIMIT_EXCEEDED");
            }
            entries += paths.size();
            if (entries > MAXIMUM_IMPACT_ENTRIES) {
                throw new ProjectionLimitException("BUSINESS_ASSET_IMPACT_ENTRY_LIMIT_EXCEEDED");
            }
            projections.add(new SourceImpact(source, paths));
        }
        return List.copyOf(projections);
    }

    private static Aggregate merge(Aggregate left, Aggregate right) {
        long count = saturatedAdd(left.pathCount(), right.pathCount());
        BusinessAssetLink.Risk risk = highest(left.highestRisk(), right.highestRisk());
        List<BusinessAssetLink> representative = pathComparator().compare(
                left.representativePath(), right.representativePath()) <= 0
                ? left.representativePath() : right.representativePath();
        return new Aggregate(count, risk, representative);
    }

    private static List<BusinessAssetRef> topological(
            Map<BusinessAssetRef, Integer> indegree,
            Map<BusinessAssetRef, List<BusinessAssetLink>> adjacency,
            Comparator<BusinessAssetRef> comparator) {
        PriorityQueue<BusinessAssetRef> ready = new PriorityQueue<>(comparator);
        indegree.forEach((asset, degree) -> {
            if (degree == 0) {
                ready.add(asset);
            }
        });
        List<BusinessAssetRef> result = new ArrayList<>(indegree.size());
        while (!ready.isEmpty()) {
            BusinessAssetRef current = ready.remove();
            result.add(current);
            for (BusinessAssetLink link : adjacency.getOrDefault(current, List.of())) {
                int remaining = indegree.compute(link.targetRef(),
                        (ignored, degree) -> java.util.Objects.requireNonNull(degree) - 1);
                if (remaining == 0) {
                    ready.add(link.targetRef());
                }
            }
        }
        if (result.size() != indegree.size()) {
            throw new IllegalArgumentException("business asset impact projection requires an acyclic Closure");
        }
        return List.copyOf(result);
    }

    private static Comparator<List<BusinessAssetLink>> pathComparator() {
        return Comparator.<List<BusinessAssetLink>>comparingInt(List::size)
                .thenComparing(path -> path.stream()
                        .map(BusinessAssetImpactProjection::linkCoordinate)
                        .reduce((left, right) -> left + '\u0000' + right).orElse(""));
    }

    private static Comparator<BusinessAssetRef> assetComparator() {
        return Comparator.comparing(BusinessAssetImpactProjection::assetCoordinate);
    }

    private static Comparator<BusinessAssetLink> linkComparator() {
        return Comparator.comparing(BusinessAssetImpactProjection::linkCoordinate);
    }

    private static String assetCoordinate(BusinessAssetRef value) {
        return value.layer() + ":" + value.kind() + ":" + value.id() + ":"
                + value.authority() + ":" + value.revision() + ":" + value.fingerprint();
    }

    private static String linkCoordinate(BusinessAssetLink value) {
        return assetCoordinate(value.sourceRef()) + ":" + value.relation() + ":"
                + assetCoordinate(value.targetRef()) + ":" + value.condition();
    }

    private static BusinessAssetLink.Risk highest(
            BusinessAssetLink.Risk left, BusinessAssetLink.Risk right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /** Exact source and its canonical downstream impact paths. */
    public record SourceImpact(BusinessAssetRef sourceRef, List<ImpactPath> paths) {
        public SourceImpact {
            sourceRef = java.util.Objects.requireNonNull(sourceRef, "sourceRef");
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    /** Bounded reachability aggregate for one affected asset. */
    public record ImpactPath(
            BusinessAssetRef impactedRef,
            int depth,
            long pathCount,
            BusinessAssetLink.Risk highestRisk,
            List<BusinessAssetLink> representativePath) {
        public ImpactPath {
            impactedRef = java.util.Objects.requireNonNull(impactedRef, "impactedRef");
            highestRisk = java.util.Objects.requireNonNull(highestRisk, "highestRisk");
            representativePath = representativePath == null
                    ? List.of() : List.copyOf(representativePath);
            if (depth < 1 || depth != representativePath.size() || pathCount < 1) {
                throw new IllegalArgumentException("business asset impact path is inconsistent");
            }
        }
    }

    /** Stable fail-closed signal for a Closure whose transitive index is too large. */
    public static final class ProjectionLimitException extends IllegalArgumentException {
        public ProjectionLimitException(String message) {
            super(message);
        }
    }

    private record Aggregate(
            long pathCount,
            BusinessAssetLink.Risk highestRisk,
            List<BusinessAssetLink> representativePath) {
        private static Aggregate root() {
            return new Aggregate(1, BusinessAssetLink.Risk.LOW, List.of());
        }
    }
}
