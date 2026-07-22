package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityClosureIntegrityTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-22T08:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sealsAndVerifiesDeterministicCompleteClosure() {
        CapabilitySnapshot child = sealedExternal("resource:customers.get", scope("project-a"));
        CapabilitySnapshot root = sealedComposed("graph:customerView", scope("project-a"),
                List.of(dependency("load", child)));

        CapabilityClosure first = CapabilityClosureIntegrity.seal(mapper, closure(root, child));
        CapabilityClosure second = CapabilityClosureIntegrity.seal(mapper,
                new CapabilityClosure("", CapabilityClosureIntegrity.reference(root), List.of(child, root), ""));

        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.snapshots()).extracting(CapabilitySnapshot::capabilityId)
                .containsExactly("graph:customerView", "resource:customers.get");
        CapabilityClosureIntegrity.verify(mapper, first);
    }

    @Test
    void rejectsMissingAndUnreachableSnapshots() {
        CapabilitySnapshot child = sealedExternal("resource:customers.get", scope("project-a"));
        CapabilitySnapshot root = sealedComposed("graph:customerView", scope("project-a"),
                List.of(dependency("load", child)));

        assertThatThrownBy(() -> CapabilityClosureIntegrity.seal(mapper,
                new CapabilityClosure("", CapabilityClosureIntegrity.reference(root), List.of(root), "")))
                .hasMessageContaining("missing an exact dependency");

        CapabilitySnapshot orphan = sealedExternal("resource:orders.get", scope("project-a"));
        assertThatThrownBy(() -> CapabilityClosureIntegrity.seal(mapper, closure(root, child, orphan)))
                .hasMessageContaining("unreachable snapshots");
    }

    @Test
    void rejectsCrossScopeSnapshotsAndTampering() {
        CapabilitySnapshot child = sealedExternal("resource:customers.get", scope("project-b"));
        CapabilitySnapshot root = sealedComposed("graph:customerView", scope("project-a"),
                List.of(dependency("load", child)));

        assertThatThrownBy(() -> CapabilityClosureIntegrity.seal(mapper, closure(root, child)))
                .hasMessageContaining("share the root scope");

        CapabilitySnapshot sameScopeChild = sealedExternal("resource:customers.get", scope("project-a"));
        CapabilitySnapshot sameScopeRoot = sealedComposed("graph:customerView", scope("project-a"),
                List.of(dependency("load", sameScopeChild)));
        CapabilityClosure sealed = CapabilityClosureIntegrity.seal(mapper, closure(sameScopeRoot, sameScopeChild));
        CapabilityClosure tampered = sealed.withFingerprint("sha256:" + "f".repeat(64));
        assertThatThrownBy(() -> CapabilityClosureIntegrity.verify(mapper, tampered))
                .hasMessageContaining("fingerprint mismatch");
    }

    @Test
    void rejectsRecursiveComposedCapabilityCycleBeforePlanning() {
        CapabilitySnapshot.Scope scope = scope("project-a");
        String aFingerprint = "sha256:" + "a".repeat(64);
        String bFingerprint = "sha256:" + "b".repeat(64);
        MirrorArtifactRef aRef = new MirrorArtifactRef("CAPABILITY", "graph:a", 1, aFingerprint);
        MirrorArtifactRef bRef = new MirrorArtifactRef("CAPABILITY", "graph:b", 1, bFingerprint);
        CapabilitySnapshot a = composedWithAttachedFingerprint("graph:a", scope,
                List.of(new CapabilitySnapshot.Dependency("toB", bRef, true, List.of())), aFingerprint);
        CapabilitySnapshot b = composedWithAttachedFingerprint("graph:b", scope,
                List.of(new CapabilitySnapshot.Dependency("toA", aRef, true, List.of())), bFingerprint);

        assertThatThrownBy(() -> CapabilityClosureIntegrity.seal(mapper,
                new CapabilityClosure("", aRef, List.of(a, b), "")))
                .hasMessageContaining("dependency cycle");
    }

    @Test
    void rejectsConflictingContentForOneCapabilityRevision() {
        CapabilitySnapshot child = sealedExternal("resource:customers.get", scope("project-a"));
        CapabilitySnapshot conflictingChild = sealedExternalWithSourceFingerprint(
                "resource:customers.get", scope("project-a"), "sha256:" + "e".repeat(64));
        CapabilitySnapshot root = sealedComposed("graph:customerView", scope("project-a"),
                List.of(dependency("load", child), dependency("loadConflict", conflictingChild)));

        assertThatThrownBy(() -> CapabilityClosureIntegrity.seal(mapper,
                closure(root, child, conflictingChild)))
                .hasMessageContaining("conflicting fingerprints for one snapshot revision");
    }

    @Test
    void rejectsClosuresAboveTheWireContractSnapshotLimit() {
        CapabilitySnapshot child = sealedExternal("resource:customers.get", scope("project-a"));

        assertThatThrownBy(() -> new CapabilityClosure("", CapabilityClosureIntegrity.reference(child),
                java.util.Collections.nCopies(CapabilityClosure.MAXIMUM_SNAPSHOTS + 1, child), ""))
                .hasMessageContaining("snapshot limit");
    }

    @Test
    void verifiesDeepAcyclicClosureWithoutRecursiveStackGrowth() {
        CapabilitySnapshot.Scope scope = scope("project-a");
        java.util.ArrayList<CapabilitySnapshot> snapshots = new java.util.ArrayList<>();
        CapabilitySnapshot current = sealedExternal("resource:leaf", scope);
        snapshots.add(current);
        for (int depth = 0; depth < 1_024; depth++) {
            current = sealedComposed("graph:level" + depth, scope,
                    List.of(dependency("level" + depth, current)));
            snapshots.add(current);
        }

        CapabilityClosure closure = CapabilityClosureIntegrity.seal(mapper,
                new CapabilityClosure("", CapabilityClosureIntegrity.reference(current), snapshots, ""));

        assertThat(closure.snapshots()).hasSize(1_025);
        CapabilityClosureIntegrity.verify(mapper, closure);
    }

    private CapabilityClosure closure(CapabilitySnapshot root, CapabilitySnapshot... children) {
        java.util.ArrayList<CapabilitySnapshot> snapshots = new java.util.ArrayList<>(List.of(children));
        snapshots.add(root);
        return new CapabilityClosure("", CapabilityClosureIntegrity.reference(root), snapshots, "");
    }

    private CapabilitySnapshot sealedExternal(String id, CapabilitySnapshot.Scope scope) {
        return sealedExternalWithSourceFingerprint(id, scope, "sha256:" + "c".repeat(64));
    }

    private CapabilitySnapshot sealedExternalWithSourceFingerprint(String id,
                                                                    CapabilitySnapshot.Scope scope,
                                                                    String sourceFingerprint) {
        CapabilitySnapshot snapshot = new CapabilitySnapshot("", id, 1, "", CapabilitySnapshot.Kind.EXTERNAL,
                scope, source(CapabilitySnapshot.SourceKind.RESOURCE, id, sourceFingerprint), contract(),
                readyRuntime(id), List.of(), ownership(), CapabilitySnapshot.Lifecycle.DRAFT,
                provenance(scope.tenantId()), CREATED_AT);
        return CapabilitySnapshotIntegrity.seal(mapper, snapshot);
    }

    private CapabilitySnapshot sealedComposed(String id,
                                               CapabilitySnapshot.Scope scope,
                                               List<CapabilitySnapshot.Dependency> dependencies) {
        return CapabilitySnapshotIntegrity.seal(mapper,
                composedWithAttachedFingerprint(id, scope, dependencies, ""));
    }

    private CapabilitySnapshot composedWithAttachedFingerprint(String id,
                                                               CapabilitySnapshot.Scope scope,
                                                               List<CapabilitySnapshot.Dependency> dependencies,
                                                               String fingerprint) {
        return new CapabilitySnapshot("", id, 1, fingerprint, CapabilitySnapshot.Kind.COMPOSED,
                scope, source(CapabilitySnapshot.SourceKind.GRAPH, id), contract(), readyRuntime(id),
                dependencies, ownership(), CapabilitySnapshot.Lifecycle.DRAFT,
                provenance(scope.tenantId()), CREATED_AT);
    }

    private static CapabilitySnapshot.Dependency dependency(String nodeId, CapabilitySnapshot child) {
        return new CapabilitySnapshot.Dependency(nodeId, CapabilityClosureIntegrity.reference(child), true,
                List.of());
    }

    private static CapabilityContract contract() {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(),
                EffectContract.readOnly(List.of("resource:test")),
                CapabilityContract.Determinism.DETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.INTERNAL, false, List.of("sg"), false),
                CapabilityContract.SloContract.unspecified());
    }

    private static CapabilitySnapshot.Source source(CapabilitySnapshot.SourceKind kind, String ref) {
        return source(kind, ref, "sha256:" + "c".repeat(64));
    }

    private static CapabilitySnapshot.Source source(CapabilitySnapshot.SourceKind kind,
                                                     String ref,
                                                     String fingerprint) {
        return new CapabilitySnapshot.Source(kind, ref, fingerprint);
    }

    private static CapabilitySnapshot.RuntimeBinding readyRuntime(String ref) {
        return new CapabilitySnapshot.RuntimeBinding("TEST", ref, "sha256:" + "d".repeat(64), true,
                List.of());
    }

    private static CapabilitySnapshot.Scope scope(String project) {
        return new CapabilitySnapshot.Scope("tenant-a", "org-a", project, "test", "sg");
    }

    private static CapabilitySnapshot.Ownership ownership() {
        return new CapabilitySnapshot.Ownership("owner-a", "team-a", "pager-a");
    }

    private static ArtifactProvenance provenance(String tenantId) {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(), tenantId,
                "MIRROR_REHEARSAL", null, null, null, null, List.of(), "", null, null, "");
    }
}
