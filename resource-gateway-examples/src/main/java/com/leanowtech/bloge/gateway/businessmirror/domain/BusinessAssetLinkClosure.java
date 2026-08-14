package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, content-addressed L0-L3 business relation closure for one Package revision.
 *
 * <p>The closure is independent from executable Graph edges. It proves that every declared
 * business asset belongs to one scope, every link resolves to a declared asset, and the directed
 * business composition is acyclic.</p>
 */
public record BusinessAssetLinkClosure(
        String schemaVersion,
        String closureId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        String packageId,
        List<BusinessAssetRef> assets,
        List<BusinessAssetLink> links,
        Instant createdAt
) {
    /** Current business-asset closure protocol. */
    public static final String SCHEMA_VERSION = "resourceGateway.businessAssetLinkClosure.v1";

    /** Normalizes deterministic collections and validates the complete relation graph. */
    public BusinessAssetLinkClosure {
        schemaVersion = BusinessMirrorProtocolSupport.version(schemaVersion, SCHEMA_VERSION);
        closureId = BusinessMirrorProtocolSupport.identifier(closureId, "closureId");
        if (revision < 1) {
            throw new IllegalArgumentException("business asset link closure revision must be positive");
        }
        fingerprint = BusinessMirrorProtocolSupport.optionalFingerprint(fingerprint, "fingerprint");
        scope = java.util.Objects.requireNonNull(scope, "scope");
        packageId = BusinessMirrorProtocolSupport.identifier(packageId, "packageId");
        assets = BusinessMirrorProtocolSupport.sortedUnique(assets, assetComparator(),
                BusinessAssetLinkClosure::assetCoordinate, "assets");
        links = BusinessMirrorProtocolSupport.sortedUnique(links, linkComparator(),
                BusinessAssetLinkClosure::linkCoordinate, "links");
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        validate(scope, assets, links);
    }

    /** @return exact reference to this sealed closure */
    public MirrorArtifactRef artifactRef() {
        if (fingerprint.isBlank()) {
            throw new IllegalStateException("Business asset link closure is not content-addressed");
        }
        return new MirrorArtifactRef("BUSINESS_ASSET_LINK_CLOSURE", closureId, revision, fingerprint);
    }

    /** @return identical closure with a replacement canonical fingerprint */
    public BusinessAssetLinkClosure withFingerprint(String value) {
        return new BusinessAssetLinkClosure(schemaVersion, closureId, revision, value, scope,
                packageId, assets, links, createdAt);
    }

    /** @return content-addressed closure */
    public BusinessAssetLinkClosure seal(ObjectMapper mapper) {
        return withFingerprint(ProtocolFingerprint.ofBounded(
                java.util.Objects.requireNonNull(mapper, "mapper"), withFingerprint(""),
                BusinessMirrorProtocolSupport.MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies this closure's content address. */
    public void verify(ObjectMapper mapper) {
        if (fingerprint.isBlank() || !fingerprint.equals(seal(mapper).fingerprint())) {
            throw new IllegalArgumentException("Business asset link closure fingerprint mismatch");
        }
    }

    private static void validate(CapabilitySnapshot.Scope scope,
                                 List<BusinessAssetRef> assets,
                                 List<BusinessAssetLink> links) {
        Set<BusinessAssetRef> declared = Set.copyOf(assets);
        for (BusinessAssetRef asset : assets) {
            if (!BusinessMirrorProtocolSupport.sameScope(scope, asset.scope())) {
                throw new IllegalArgumentException("business asset closure must not cross enterprise scope");
            }
        }
        Map<BusinessAssetRef, List<BusinessAssetRef>> adjacency = new HashMap<>();
        for (BusinessAssetLink link : links) {
            if (!BusinessMirrorProtocolSupport.sameScope(scope, link.sourceRef().scope())
                    || !declared.contains(link.sourceRef()) || !declared.contains(link.targetRef())) {
                throw new IllegalArgumentException("business asset link contains a dangling or cross-scope ref");
            }
            adjacency.computeIfAbsent(link.sourceRef(), ignored -> new java.util.ArrayList<>())
                    .add(link.targetRef());
        }
        requireAcyclic(assets, adjacency);
    }

    private static void requireAcyclic(List<BusinessAssetRef> assets,
                                       Map<BusinessAssetRef, List<BusinessAssetRef>> adjacency) {
        Set<BusinessAssetRef> visited = new HashSet<>();
        Set<BusinessAssetRef> visiting = new HashSet<>();
        for (BusinessAssetRef root : assets) {
            if (visited.contains(root)) {
                continue;
            }
            Deque<Visit> stack = new ArrayDeque<>();
            stack.push(new Visit(root, false));
            while (!stack.isEmpty()) {
                Visit current = stack.pop();
                if (current.expanded()) {
                    visiting.remove(current.asset());
                    visited.add(current.asset());
                    continue;
                }
                if (visited.contains(current.asset())) {
                    continue;
                }
                if (!visiting.add(current.asset())) {
                    throw new IllegalArgumentException("business asset link closure contains a cycle");
                }
                stack.push(new Visit(current.asset(), true));
                List<BusinessAssetRef> children = adjacency.getOrDefault(current.asset(), List.of());
                for (int index = children.size() - 1; index >= 0; index--) {
                    BusinessAssetRef child = children.get(index);
                    if (visiting.contains(child)) {
                        throw new IllegalArgumentException("business asset link closure contains a cycle");
                    }
                    if (!visited.contains(child)) {
                        stack.push(new Visit(child, false));
                    }
                }
            }
        }
    }

    private static Comparator<BusinessAssetRef> assetComparator() {
        return Comparator.comparing(BusinessAssetRef::layer)
                .thenComparing(BusinessAssetRef::kind)
                .thenComparing(BusinessAssetRef::id)
                .thenComparingLong(BusinessAssetRef::revision)
                .thenComparing(BusinessAssetRef::fingerprint)
                .thenComparing(BusinessAssetRef::authority);
    }

    private static Comparator<BusinessAssetLink> linkComparator() {
        return Comparator.comparing((BusinessAssetLink value) -> assetCoordinate(value.sourceRef()))
                .thenComparing(BusinessAssetLink::relation)
                .thenComparing(value -> assetCoordinate(value.targetRef()))
                .thenComparing(BusinessAssetLink::condition);
    }

    private static String assetCoordinate(BusinessAssetRef value) {
        return value.layer() + ":" + value.kind() + ":" + value.id() + ":"
                + value.revision() + ":" + value.fingerprint() + ":" + value.authority();
    }

    private static String linkCoordinate(BusinessAssetLink value) {
        return assetCoordinate(value.sourceRef()) + ":" + value.relation() + ":"
                + assetCoordinate(value.targetRef()) + ":" + value.condition();
    }

    private record Visit(BusinessAssetRef asset, boolean expanded) {
    }
}
