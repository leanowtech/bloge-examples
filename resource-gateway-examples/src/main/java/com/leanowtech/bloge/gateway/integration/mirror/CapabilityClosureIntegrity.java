package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical sealing and complete graph validation for {@link CapabilityClosure}. */
public final class CapabilityClosureIntegrity {
    /** Maximum canonical closure size admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES = 16 * 1024 * 1024;

    private CapabilityClosureIntegrity() {
    }

    /**
     * Validates and seals an exact capability closure.
     *
     * @param mapper application JSON mapper
     * @param closure closure with a blank or stale fingerprint
     * @return sealed closure
     */
    public static CapabilityClosure seal(ObjectMapper mapper, CapabilityClosure closure) {
        Objects.requireNonNull(mapper, "mapper");
        validate(mapper, closure);
        CapabilityClosure material = closure.withFingerprint("");
        return material.withFingerprint(VisualBundleFingerprint.fromCanonicalValue(
                mapper, material, MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Verifies closure structure, every child snapshot, and the closure fingerprint.
     *
     * @param mapper application JSON mapper
     * @param closure sealed closure
     * @throws IllegalArgumentException when the closure is incomplete, cyclic, orphaned, or modified
     */
    public static void verify(ObjectMapper mapper, CapabilityClosure closure) {
        Objects.requireNonNull(closure, "closure");
        if (closure.fingerprint().isBlank()) {
            throw new IllegalArgumentException("capability closure is not sealed");
        }
        String expected = seal(mapper, closure).fingerprint();
        if (!expected.equals(closure.fingerprint())) {
            throw new IllegalArgumentException("capability closure fingerprint mismatch");
        }
    }

    private static void validate(ObjectMapper mapper, CapabilityClosure closure) {
        Objects.requireNonNull(closure, "closure");
        Map<MirrorArtifactRef, CapabilitySnapshot> snapshots = new HashMap<>();
        Map<SnapshotCoordinate, String> coordinateFingerprints = new HashMap<>();
        for (CapabilitySnapshot snapshot : closure.snapshots()) {
            MirrorArtifactRef ref = reference(snapshot);
            if (snapshots.putIfAbsent(ref, snapshot) != null) {
                throw new IllegalArgumentException("capability closure contains a duplicate snapshot reference");
            }
            SnapshotCoordinate coordinate = new SnapshotCoordinate(snapshot.capabilityId(), snapshot.revision());
            String previousFingerprint = coordinateFingerprints.putIfAbsent(coordinate, snapshot.fingerprint());
            if (previousFingerprint != null && !previousFingerprint.equals(snapshot.fingerprint())) {
                throw new IllegalArgumentException(
                        "capability closure contains conflicting fingerprints for one snapshot revision");
            }
        }
        CapabilitySnapshot root = snapshots.get(closure.rootRef());
        if (root == null) {
            throw new IllegalArgumentException("capability closure rootRef does not resolve exactly");
        }
        if (root.kind() != CapabilitySnapshot.Kind.COMPOSED) {
            throw new IllegalArgumentException("capability closure root must be COMPOSED");
        }
        for (CapabilitySnapshot snapshot : snapshots.values()) {
            if (!root.scope().equals(snapshot.scope())) {
                throw new IllegalArgumentException("capability closure snapshots must share the root scope");
            }
        }
        Set<MirrorArtifactRef> visited = visit(closure.rootRef(), snapshots);
        if (visited.size() != snapshots.size()) {
            throw new IllegalArgumentException("capability closure contains unreachable snapshots");
        }
        snapshots.values().forEach(snapshot -> CapabilitySnapshotIntegrity.verify(mapper, snapshot));
    }

    private static Set<MirrorArtifactRef> visit(MirrorArtifactRef root,
                                                Map<MirrorArtifactRef, CapabilitySnapshot> snapshots) {
        Set<MirrorArtifactRef> visited = new HashSet<>();
        Set<MirrorArtifactRef> visiting = new HashSet<>();
        Deque<VisitFrame> stack = new ArrayDeque<>();
        stack.push(new VisitFrame(root, false));
        while (!stack.isEmpty()) {
            VisitFrame frame = stack.pop();
            if (frame.expanded()) {
                visiting.remove(frame.ref());
                visited.add(frame.ref());
                continue;
            }
            if (visited.contains(frame.ref())) {
                continue;
            }
            if (!visiting.add(frame.ref())) {
                throw new IllegalArgumentException("capability closure contains a dependency cycle");
            }
            CapabilitySnapshot snapshot = snapshots.get(frame.ref());
            if (snapshot == null) {
                throw new IllegalArgumentException("capability closure is missing an exact dependency snapshot");
            }
            stack.push(new VisitFrame(frame.ref(), true));
            List<CapabilitySnapshot.Dependency> dependencies = snapshot.dependencies();
            for (int index = dependencies.size() - 1; index >= 0; index--) {
                MirrorArtifactRef dependency = dependencies.get(index).capabilityRef();
                if (visiting.contains(dependency)) {
                    throw new IllegalArgumentException("capability closure contains a dependency cycle");
                }
                if (!visited.contains(dependency)) {
                    stack.push(new VisitFrame(dependency, false));
                }
            }
        }
        return visited;
    }

    /**
     * Creates the exact reference used by closure roots and dependency edges.
     *
     * @param snapshot sealed capability snapshot
     * @return exact capability artifact reference
     */
    public static MirrorArtifactRef reference(CapabilitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new MirrorArtifactRef("CAPABILITY", snapshot.capabilityId(), snapshot.revision(),
                snapshot.fingerprint());
    }

    private record SnapshotCoordinate(String capabilityId, long revision) {
    }

    private record VisitFrame(MirrorArtifactRef ref, boolean expanded) {
    }
}
