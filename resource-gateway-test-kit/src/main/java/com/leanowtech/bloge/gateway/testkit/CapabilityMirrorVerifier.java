package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-light offline verifier for capability snapshots and dependency-closed capability
 * closures exported by Resource Gateway.
 *
 * <p>Verification is registry-free and payload-safe. It applies the packaged strict JSON Schema,
 * re-derives canonical fingerprints, enforces enterprise scope closure, resolves every exact
 * dependency, and rejects cycles, duplicates, conflicting revisions, and unreachable snapshots.
 * Failures contain stable reason codes only.</p>
 */
public final class CapabilityMirrorVerifier {

    /** Maximum canonical bytes accepted for one capability snapshot. */
    public static final int MAXIMUM_SNAPSHOT_BYTES = 2 * 1024 * 1024;
    /** Maximum canonical bytes accepted for one capability closure. */
    public static final int MAXIMUM_CLOSURE_BYTES = 16 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private CapabilityMirrorVerifier() {
    }

    /**
     * Verifies one decoded sealed capability snapshot.
     *
     * @param snapshot decoded snapshot JSON
     * @return payload-free verified artifact identity
     * @throws IllegalArgumentException when schema, semantic, size, or fingerprint checks fail
     */
    public static VerifiedArtifact verifySnapshot(JsonNode snapshot) {
        CapabilityMirrorSchemaValidator.require(snapshot,
                CapabilityMirrorProtocol.CAPABILITY_SNAPSHOT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SNAPSHOT_SCHEMA_INVALID");
        verifySnapshotSemantics(snapshot);
        verifySnapshotFingerprint(snapshot);
        return verifiedArtifact(snapshot);
    }

    /**
     * Verifies one decoded sealed capability closure without consulting a mutable registry.
     *
     * @param closure decoded closure JSON
     * @return payload-free root and closure identity
     * @throws IllegalArgumentException when schema, graph, scope, size, or fingerprint checks fail
     */
    public static VerifiedClosure verifyClosure(JsonNode closure) {
        CapabilityMirrorSchemaValidator.require(closure,
                CapabilityMirrorProtocol.CAPABILITY_CLOSURE_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.CLOSURE_SCHEMA_INVALID");

        Map<ArtifactRef, JsonNode> snapshots = new LinkedHashMap<>();
        Map<Coordinate, String> coordinateFingerprints = new LinkedHashMap<>();
        for (JsonNode snapshot : closure.path("snapshots")) {
            ArtifactRef ref = snapshotRef(snapshot);
            if (snapshots.putIfAbsent(ref, snapshot) != null) {
                throw invalid("RG.MIRROR.CLIENT.CLOSURE_DUPLICATE_SNAPSHOT");
            }
            Coordinate coordinate = new Coordinate(ref.id(), ref.revision());
            String previous = coordinateFingerprints.putIfAbsent(coordinate, ref.fingerprint());
            if (previous != null && !previous.equals(ref.fingerprint())) {
                throw invalid("RG.MIRROR.CLIENT.CLOSURE_CONFLICTING_REVISION");
            }
        }

        ArtifactRef rootRef = artifactRef(closure.path("rootRef"));
        JsonNode root = snapshots.get(rootRef);
        if (root == null) {
            throw invalid("RG.MIRROR.CLIENT.CLOSURE_ROOT_UNRESOLVED");
        }
        if (!"COMPOSED".equals(root.path("kind").asText())) {
            throw invalid("RG.MIRROR.CLIENT.CLOSURE_ROOT_NOT_COMPOSED");
        }
        JsonNode rootScope = root.path("scope");
        for (JsonNode snapshot : snapshots.values()) {
            if (!rootScope.equals(snapshot.path("scope"))) {
                throw invalid("RG.MIRROR.CLIENT.CLOSURE_SCOPE_MISMATCH");
            }
            verifySnapshotSemantics(snapshot);
        }

        Set<ArtifactRef> reachable = visit(rootRef, snapshots);
        if (reachable.size() != snapshots.size()) {
            throw invalid("RG.MIRROR.CLIENT.CLOSURE_UNREACHABLE_SNAPSHOT");
        }
        for (JsonNode snapshot : snapshots.values()) {
            verifySnapshotFingerprint(snapshot);
        }
        verifyFingerprint(closure, MAXIMUM_CLOSURE_BYTES,
                "RG.MIRROR.CLIENT.CLOSURE_TOO_LARGE",
                "RG.MIRROR.CLIENT.CLOSURE_FINGERPRINT_MISMATCH");
        return new VerifiedClosure(closure.path("schemaVersion").asText(), rootRef.id(),
                rootRef.revision(), rootRef.fingerprint(), closure.path("fingerprint").asText(),
                snapshots.size());
    }

    private static Set<ArtifactRef> visit(ArtifactRef root,
                                          Map<ArtifactRef, JsonNode> snapshots) {
        Set<ArtifactRef> visited = new HashSet<>();
        Set<ArtifactRef> visiting = new HashSet<>();
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
                throw invalid("RG.MIRROR.CLIENT.CLOSURE_DEPENDENCY_CYCLE");
            }
            JsonNode snapshot = snapshots.get(frame.ref());
            if (snapshot == null) {
                throw invalid("RG.MIRROR.CLIENT.CLOSURE_DEPENDENCY_MISSING");
            }
            stack.push(new VisitFrame(frame.ref(), true));
            List<ArtifactRef> dependencies = new ArrayList<>();
            snapshot.path("dependencies").forEach(dependency ->
                    dependencies.add(artifactRef(dependency.path("capabilityRef"))));
            for (int index = dependencies.size() - 1; index >= 0; index--) {
                ArtifactRef dependency = dependencies.get(index);
                if (visiting.contains(dependency)) {
                    throw invalid("RG.MIRROR.CLIENT.CLOSURE_DEPENDENCY_CYCLE");
                }
                if (!visited.contains(dependency)) {
                    stack.push(new VisitFrame(dependency, false));
                }
            }
        }
        return visited;
    }

    private static void verifySnapshotSemantics(JsonNode snapshot) {
        String kind = snapshot.path("kind").asText();
        int dependencies = snapshot.path("dependencies").size();
        if ("EXTERNAL".equals(kind) && dependencies != 0) {
            throw invalid("RG.MIRROR.CLIENT.SNAPSHOT_EXTERNAL_HAS_DEPENDENCIES");
        }
        if ("COMPOSED".equals(kind) && dependencies == 0) {
            throw invalid("RG.MIRROR.CLIENT.SNAPSHOT_COMPOSED_WITHOUT_DEPENDENCY");
        }
        if (!snapshot.path("scope").path("tenantId").asText()
                .equals(snapshot.path("provenance").path("tenantId").asText())) {
            throw invalid("RG.MIRROR.CLIENT.SNAPSHOT_PROVENANCE_SCOPE_MISMATCH");
        }
    }

    private static void verifySnapshotFingerprint(JsonNode snapshot) {
        verifyFingerprint(snapshot, MAXIMUM_SNAPSHOT_BYTES,
                "RG.MIRROR.CLIENT.SNAPSHOT_TOO_LARGE",
                "RG.MIRROR.CLIENT.SNAPSHOT_FINGERPRINT_MISMATCH");
    }

    private static void verifyFingerprint(JsonNode artifact,
                                          int maximumBytes,
                                          String tooLargeCode,
                                          String mismatchCode) {
        ObjectNode material = ((ObjectNode) artifact).deepCopy();
        String attached = material.path("fingerprint").asText();
        material.put("fingerprint", "");
        String expected = canonicalFingerprint(material, maximumBytes, tooLargeCode);
        if (!expected.equals(attached)) {
            throw invalid(mismatchCode);
        }
    }

    private static String canonicalFingerprint(JsonNode material,
                                               int maximumBytes,
                                               String tooLargeCode) {
        try {
            byte[] canonical = JSON.writeValueAsBytes(canonical(material));
            if (canonical.length > maximumBytes) {
                throw invalid(tooLargeCode);
            }
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | GeneralSecurityException failure) {
            throw invalid("RG.MIRROR.CLIENT.CANONICALIZATION_FAILED");
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private static ArtifactRef snapshotRef(JsonNode snapshot) {
        return new ArtifactRef("CAPABILITY", snapshot.path("capabilityId").asText(),
                snapshot.path("revision").asLong(), snapshot.path("fingerprint").asText());
    }

    private static ArtifactRef artifactRef(JsonNode ref) {
        return new ArtifactRef(ref.path("kind").asText(), ref.path("id").asText(),
                ref.path("revision").asLong(), ref.path("fingerprint").asText());
    }

    private static VerifiedArtifact verifiedArtifact(JsonNode snapshot) {
        return new VerifiedArtifact(snapshot.path("schemaVersion").asText(),
                snapshot.path("capabilityId").asText(), snapshot.path("revision").asLong(),
                snapshot.path("fingerprint").asText(), snapshot.path("kind").asText());
    }

    private static IllegalArgumentException invalid(String reasonCode) {
        return new IllegalArgumentException(reasonCode);
    }

    private record ArtifactRef(String kind, String id, long revision, String fingerprint) {
    }

    private record Coordinate(String id, long revision) {
    }

    private record VisitFrame(ArtifactRef ref, boolean expanded) {
    }

    /**
     * Payload-free identity of a verified capability snapshot.
     *
     * @param schemaVersion verified snapshot protocol version
     * @param capabilityId stable capability identifier
     * @param revision exact immutable revision
     * @param fingerprint verified canonical fingerprint
     * @param kind external or composed capability kind
     */
    public record VerifiedArtifact(
            String schemaVersion,
            String capabilityId,
            long revision,
            String fingerprint,
            String kind
    ) {
    }

    /**
     * Payload-free identity and cardinality of a verified capability closure.
     *
     * @param schemaVersion verified closure protocol version
     * @param rootCapabilityId stable root capability identifier
     * @param rootRevision exact root revision
     * @param rootFingerprint verified root snapshot fingerprint
     * @param fingerprint verified canonical closure fingerprint
     * @param snapshotCount number of dependency-closed snapshots
     */
    public record VerifiedClosure(
            String schemaVersion,
            String rootCapabilityId,
            long rootRevision,
            String rootFingerprint,
            String fingerprint,
            int snapshotCount
    ) {
    }
}
