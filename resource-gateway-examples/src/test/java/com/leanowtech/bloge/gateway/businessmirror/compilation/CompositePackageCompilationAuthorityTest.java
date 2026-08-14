package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredDomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositePackageCompilationAuthorityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "support", "mirror", "test", "sg");
    private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");

    @Test
    void freezesResolvedAndUnsupportedKindsWithoutFallbackPrecedence() {
        MirrorArtifactRef contract = ref("CONTRACT", "contract-a", 1, 'a');
        MirrorArtifactRef material = ref("CONTRACT", "contract-a-snapshot", 1, 'b');
        MutableAdapter adapter = new MutableAdapter("contract-authority", Set.of("CONTRACT"),
                resolved(contract, material, null));
        CompositePackageCompilationAuthority authority =
                new CompositePackageCompilationAuthority(MAPPER, List.of(adapter));

        StoredDomainCapabilityPackageDraft source = storedDraft(contract,
                List.of(ref("CAPABILITY", "unsupported-capability", 1, 'c')),
                List.of());
        FrozenPackageDependencies frozen = authority.freeze(source);

        assertThat(authority.ready()).isTrue();
        assertThat(authority.supportedSourceKinds()).containsExactly("CONTRACT");
        assertThat(frozen.observations()).extracting(PackageDependencyObservation::status)
                .containsExactly(PackageDependencyObservation.Status.MISSING,
                        PackageDependencyObservation.Status.RESOLVED);
        assertThat(frozen.policyGenerationRef()).isNotNull();
        assertThat(frozen.policyGenerationRef().kind()).isEqualTo("PACKAGE_COMPILATION_POLICY");
        assertThat(frozen.authorityGeneration()).startsWith("composite-authority-v1:");
        authority.assertUnchanged(frozen);
    }

    @Test
    void detectsMaterializedHeadDriftBeforePublication() {
        MirrorArtifactRef contract = ref("CONTRACT", "contract-a", 1, 'a');
        MutableAdapter adapter = new MutableAdapter("contract-authority", Set.of("CONTRACT"),
                resolved(contract, ref("CONTRACT", "contract-a-snapshot", 1, 'b'), null));
        CompositePackageCompilationAuthority authority =
                new CompositePackageCompilationAuthority(MAPPER, List.of(adapter));
        FrozenPackageDependencies frozen = authority.freeze(storedDraft(contract, List.of(), List.of()));

        adapter.resolution.set(resolved(
                contract, ref("CONTRACT", "contract-a-snapshot", 2, 'c'), null));

        assertThatThrownBy(() -> authority.assertUnchanged(frozen))
                .isInstanceOf(PackageDependencyDriftException.class);
    }

    @Test
    void refusesAmbiguousKindOwnershipAndMultiplePackageRoots() {
        MirrorArtifactRef graphA = ref("GRAPH_DRAFT", "graph-a", 1, 'a');
        MirrorArtifactRef graphB = ref("GRAPH_DRAFT", "graph-b", 1, 'b');
        PackageDependencyAuthorityAdapter adapter = new PackageDependencyAuthorityAdapter() {
            @Override
            public String adapterId() {
                return "graph-authority";
            }

            @Override
            public Set<String> sourceKinds() {
                return Set.of("GRAPH_DRAFT");
            }

            @Override
            public PackageDependencyResolution resolve(
                    CapabilitySnapshot.Scope scope, MirrorArtifactRef sourceRef) {
                MirrorArtifactRef closure = ref("CAPABILITY_CLOSURE", sourceRef.id(), 1,
                        sourceRef.id().equals("graph-a") ? 'c' : 'd');
                return resolved(sourceRef,
                        ref("CAPABILITY", sourceRef.id(), 1, 'e'), closure);
            }
        };
        CompositePackageCompilationAuthority authority =
                new CompositePackageCompilationAuthority(MAPPER, List.of(adapter));

        FrozenPackageDependencies frozen = authority.freeze(
                storedDraft(null, List.of(), List.of(graphA, graphB)));

        assertThat(frozen.observations()).allMatch(value ->
                value.status() == PackageDependencyObservation.Status.RESOLVED);
        assertThat(frozen.capabilityClosureRef()).isNull();
        assertThatThrownBy(() -> new CompositePackageCompilationAuthority(MAPPER, List.of(
                adapter, new MutableAdapter("other", Set.of("GRAPH_DRAFT"),
                PackageDependencyResolution.missing(graphA)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple Package authority adapters");
    }

    private static PackageDependencyResolution resolved(
            MirrorArtifactRef source,
            MirrorArtifactRef material,
            MirrorArtifactRef closure) {
        return new PackageDependencyResolution(new PackageDependencyObservation(
                source, material, source, SCOPE, PackageDependencyObservation.Status.RESOLVED,
                List.of(PackageDependencyObservation.Assurance.SCHEMA_VALID)),
                closure, List.of(), List.of(), List.of());
    }

    private static StoredDomainCapabilityPackageDraft storedDraft(
            MirrorArtifactRef contract,
            List<MirrorArtifactRef> capabilities,
            List<MirrorArtifactRef> graphs) {
        DomainCapabilityPackageDraft draft = new DomainCapabilityPackageDraft(
                "", "package-a", 1, SCOPE,
                DomainCapabilityPackageDraft.BusinessDefinition.empty(), contract,
                capabilities, graphs, List.of(), List.of(), List.of(), null, List.of(),
                List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(),
                null, new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER,
                List.of(), SCOPE.tenantId(), "TEST_EXECUTION", null, null, null, null,
                List.of(), "", null, null, ""), DomainCapabilityPackageDraft.Lifecycle.DRAFT);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(MAPPER, draft, 1024 * 1024);
        return new StoredDomainCapabilityPackageDraft("", fingerprint, draft, NOW, NOW, "tester");
    }

    private static MirrorArtifactRef ref(
            String kind, String id, long revision, char fingerprintDigit) {
        return new MirrorArtifactRef(
                kind, id, revision, "sha256:" + String.valueOf(fingerprintDigit).repeat(64));
    }

    private static final class MutableAdapter implements PackageDependencyAuthorityAdapter {
        private final String id;
        private final Set<String> kinds;
        private final AtomicReference<PackageDependencyResolution> resolution;

        private MutableAdapter(
                String id, Set<String> kinds, PackageDependencyResolution resolution) {
            this.id = id;
            this.kinds = kinds;
            this.resolution = new AtomicReference<>(resolution);
        }

        @Override
        public String adapterId() {
            return id;
        }

        @Override
        public Set<String> sourceKinds() {
            return kinds;
        }

        @Override
        public PackageDependencyResolution resolve(
                CapabilitySnapshot.Scope scope, MirrorArtifactRef sourceRef) {
            return resolution.get();
        }
    }
}
