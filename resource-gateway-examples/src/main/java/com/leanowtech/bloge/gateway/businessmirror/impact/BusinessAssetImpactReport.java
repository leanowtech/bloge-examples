package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Versioned, content-addressed reverse-impact query result.
 *
 * <p>The report is a rebuildable query projection. Every item points back to the immutable
 * Package Snapshot and Link Closure that remain authoritative.</p>
 */
public record BusinessAssetImpactReport(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        BusinessAssetSelector selector,
        Status status,
        List<String> stalePackageIds,
        boolean stalePackageIdsTruncated,
        List<PackageImpact> items,
        String nextCursor,
        Instant projectedThrough,
        String fingerprint
) {
    public static final String SCHEMA_VERSION = "resourceGateway.businessAssetImpactReport.v1";
    private static final int MAXIMUM_CANONICAL_BYTES = 8 * 1_048_576;
    private static final int MAXIMUM_PAGE_ITEMS = 200;
    private static final int MAXIMUM_STALE_PACKAGE_IDS = 200;
    private static final int MAXIMUM_SOURCE_MATCHES = 64;
    private static final int MAXIMUM_IMPACT_PATHS = 4096;
    private static final int MAXIMUM_PATH_DEPTH = 64;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Freshness of the reverse index relative to immutable Package Snapshots. */
    public enum Status {
        CURRENT,
        STALE
    }

    public BusinessAssetImpactReport {
        schemaVersion = version(schemaVersion);
        scope = java.util.Objects.requireNonNull(scope, "scope");
        selector = java.util.Objects.requireNonNull(selector, "selector");
        status = java.util.Objects.requireNonNull(status, "status");
        stalePackageIds = normalizedStrings(
                stalePackageIds, MAXIMUM_STALE_PACKAGE_IDS, "stalePackageIds");
        items = items == null ? List.of() : items.stream()
                .sorted(Comparator.comparing(PackageImpact::packageId))
                .toList();
        nextCursor = optionalIdentifier(nextCursor, "nextCursor");
        fingerprint = optionalFingerprint(fingerprint);
        requireBoundedUnique(items, MAXIMUM_PAGE_ITEMS, PackageImpact::packageId, "impact packages");
        if ((status == Status.STALE) != (!stalePackageIds.isEmpty() || stalePackageIdsTruncated)) {
            throw new IllegalArgumentException("business asset impact freshness is inconsistent");
        }
        for (PackageImpact item : items) {
            if (!scope.equals(item.scope())) {
                throw new IllegalArgumentException("business asset impact item crosses enterprise scope");
            }
            for (SourceMatch match : item.matches()) {
                if (match.sourceRef().kind() != selector.kind()
                        || !match.sourceRef().id().equals(selector.id())
                        || !selector.authority().isBlank()
                        && !match.sourceRef().authority().equals(selector.authority())) {
                    throw new IllegalArgumentException("business asset impact item does not match selector");
                }
            }
        }
    }

    /** Returns an identical report with a replacement canonical fingerprint. */
    public BusinessAssetImpactReport withFingerprint(String value) {
        return new BusinessAssetImpactReport(schemaVersion, scope, selector, status,
                stalePackageIds, stalePackageIdsTruncated, items, nextCursor,
                projectedThrough, value);
    }

    /** Seals this query response so offline consumers can detect material drift. */
    public BusinessAssetImpactReport seal(ObjectMapper mapper) {
        return withFingerprint(ProtocolFingerprint.ofBounded(
                java.util.Objects.requireNonNull(mapper, "mapper"), withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies this response's content address. */
    public void verify(ObjectMapper mapper) {
        if (fingerprint.isBlank() || !fingerprint.equals(seal(mapper).fingerprint())) {
            throw new IllegalArgumentException("Business asset impact report fingerprint mismatch");
        }
    }

    /** One affected Package and all exact source revisions matching the logical selector. */
    public record PackageImpact(
            CapabilitySnapshot.Scope scope,
            String packageId,
            long compilationRevision,
            MirrorArtifactRef packageSnapshotRef,
            MirrorArtifactRef businessAssetLinkClosureRef,
            List<SourceMatch> matches,
            String deepLink
    ) {
        public PackageImpact {
            scope = java.util.Objects.requireNonNull(scope, "scope");
            packageId = required(packageId, "packageId");
            if (compilationRevision < 1) {
                throw new IllegalArgumentException("impact compilation revision must be positive");
            }
            packageSnapshotRef = exactRef(
                    packageSnapshotRef, "DOMAIN_CAPABILITY_PACKAGE", "packageSnapshotRef");
            businessAssetLinkClosureRef = exactRef(businessAssetLinkClosureRef,
                    "BUSINESS_ASSET_LINK_CLOSURE", "businessAssetLinkClosureRef");
            matches = matches == null ? List.of() : matches.stream()
                    .sorted(Comparator.comparing(value -> coordinate(value.sourceRef())))
                    .toList();
            deepLink = BusinessAssetImpactReport.deepLink(deepLink);
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("impact package requires at least one source match");
            }
            if (!packageSnapshotRef.id().equals(packageId)
                    || packageSnapshotRef.revision() != compilationRevision) {
                throw new IllegalArgumentException(
                        "impact package Snapshot coordinate does not match package coordinate");
            }
            requireBoundedUnique(matches, MAXIMUM_SOURCE_MATCHES,
                    value -> coordinate(value.sourceRef()), "impact source matches");
            for (SourceMatch match : matches) {
                if (!scope.equals(match.sourceRef().scope())) {
                    throw new IllegalArgumentException("impact source match crosses enterprise scope");
                }
            }
        }
    }

    /** One exact source revision and its complete bounded downstream projection. */
    public record SourceMatch(
            BusinessAssetRef sourceRef,
            List<ImpactPath> paths,
            String deepLink
    ) {
        public SourceMatch {
            sourceRef = java.util.Objects.requireNonNull(sourceRef, "sourceRef");
            paths = paths == null ? List.of() : paths.stream()
                    .sorted(Comparator.comparing(value -> coordinate(value.impactedRef())))
                    .toList();
            deepLink = BusinessAssetImpactReport.deepLink(deepLink);
            requireBoundedUnique(paths, MAXIMUM_IMPACT_PATHS,
                    value -> coordinate(value.impactedRef()), "impact paths");
            for (ImpactPath path : paths) {
                if (!sourceRef.scope().equals(path.impactedRef().scope())
                        || path.representativePath().isEmpty()
                        || !path.representativePath().getFirst().sourceRef().equals(sourceRef)
                        || !path.representativePath().getLast().targetRef()
                        .equals(path.impactedRef())) {
                    throw new IllegalArgumentException("impact path does not connect source to target");
                }
            }
        }
    }

    /** Conservative reachability summary with one deterministic representative path. */
    public record ImpactPath(
            BusinessAssetRef impactedRef,
            int depth,
            long pathCount,
            BusinessAssetLink.Risk highestRisk,
            List<BusinessAssetLink> representativePath,
            String deepLink
    ) {
        public ImpactPath {
            impactedRef = java.util.Objects.requireNonNull(impactedRef, "impactedRef");
            highestRisk = java.util.Objects.requireNonNull(highestRisk, "highestRisk");
            representativePath = representativePath == null
                    ? List.of() : List.copyOf(representativePath);
            deepLink = BusinessAssetImpactReport.deepLink(deepLink);
            if (depth < 1 || depth > MAXIMUM_PATH_DEPTH
                    || depth != representativePath.size() || pathCount < 1) {
                throw new IllegalArgumentException("business asset impact path is inconsistent");
            }
            BusinessAssetLink.Risk actualRisk = representativePath.stream()
                    .map(BusinessAssetLink::risk)
                    .max(Comparator.comparingInt(Enum::ordinal))
                    .orElseThrow();
            if (actualRisk != highestRisk) {
                throw new IllegalArgumentException("business asset impact risk summary drifted");
            }
            for (int index = 1; index < representativePath.size(); index++) {
                if (!representativePath.get(index - 1).targetRef()
                        .equals(representativePath.get(index).sourceRef())) {
                    throw new IllegalArgumentException("business asset impact path is disconnected");
                }
            }
        }
    }

    private static MirrorArtifactRef exactRef(
            MirrorArtifactRef value, String kind, String name) {
        if (value == null || !kind.equals(value.kind()) || value.revision() < 1
                || !isFingerprint(value.fingerprint())) {
            throw new IllegalArgumentException(name + " must be an exact " + kind + " ref");
        }
        return value;
    }

    private static List<String> normalizedStrings(
            List<String> values, int maximum, String name) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " exceeds its protocol limit");
        }
        List<String> normalized = values.stream()
                .map(value -> required(value, name)).sorted().toList();
        if (new HashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException(name + " contains duplicate coordinates");
        }
        return normalized;
    }

    private static String optionalIdentifier(String value, String name) {
        String exact = normalized(value);
        return exact.isBlank() ? "" : required(exact, name);
    }

    private static <T> void requireBoundedUnique(
            List<T> values,
            int maximum,
            java.util.function.Function<T, String> coordinate,
            String name) {
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " exceeds its protocol limit");
        }
        Set<String> coordinates = new HashSet<>();
        for (T value : values) {
            if (!coordinates.add(coordinate.apply(value))) {
                throw new IllegalArgumentException(name + " contains a duplicate coordinate");
            }
        }
    }

    private static String required(String value, String name) {
        String exact = normalized(value);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return exact;
    }

    private static String deepLink(String value) {
        String exact = normalized(value);
        if (!exact.startsWith("/business-mirror/?") || exact.indexOf('\n') >= 0
                || exact.indexOf('\r') >= 0 || exact.length() > 4096) {
            throw new IllegalArgumentException("business mirror deep link is invalid");
        }
        return exact;
    }

    private static String version(String value) {
        String exact = normalized(value);
        if (exact.isBlank()) {
            return SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException("unsupported business asset impact report version");
        }
        return exact;
    }

    private static String optionalFingerprint(String value) {
        String exact = normalized(value);
        if (!exact.isBlank() && !isFingerprint(exact)) {
            throw new IllegalArgumentException("business asset impact fingerprint is invalid");
        }
        return exact;
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("sha256:[a-f0-9]{64}");
    }

    private static String coordinate(BusinessAssetRef value) {
        return value.layer() + ":" + value.kind() + ":" + value.id() + ":"
                + value.authority() + ":" + value.revision() + ":" + value.fingerprint();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
