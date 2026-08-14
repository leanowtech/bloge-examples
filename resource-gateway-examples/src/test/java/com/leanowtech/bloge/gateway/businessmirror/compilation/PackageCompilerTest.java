package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredDomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageCompilerTest {
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "ride-hailing", "customer-service", "cancellation", "test", "sg");
    private static final Instant COMPILED_AT = Instant.parse("2026-08-14T04:00:00Z");

    @Test
    void compilesACompletePackageIntoVerifiableImmutableFacts() {
        StoredDomainCapabilityPackageDraft source = stored(completeDraft(List.of()));
        FrozenPackageDependencies frozen = frozen(source.draft(), observations(source.draft()), links());

        PackageCompilationResult result = compiler(frozen).compile(source, 3, COMPILED_AT);

        assertThat(result.compiled()).isTrue();
        assertThat(result.readiness().status()).isEqualTo(PackageReadinessReport.Status.READY);
        assertThat(result.readiness().findings()).isEmpty();
        assertThat(result.snapshot().sourceDraftFingerprint()).isEqualTo(source.draftFingerprint());
        assertThat(result.snapshot().dependencyManifest())
                .noneMatch(ref -> ref.kind().equals("GRAPH_DRAFT"))
                .anyMatch(ref -> ref.kind().equals("GRAPH_SNAPSHOT"));
        result.readiness().verify(MAPPER);
        result.businessAssetLinkClosure().verify(MAPPER);
        result.snapshot().verify(MAPPER);
    }

    @Test
    void producesIdenticalFingerprintsAcrossOneHundredInputPermutations() {
        StoredDomainCapabilityPackageDraft source = stored(completeDraft(List.of()));
        List<PackageDependencyObservation> canonicalObservations = observations(source.draft());
        List<BusinessAssetLink> canonicalLinks = links();
        String snapshotFingerprint = null;
        String readinessFingerprint = null;
        String closureFingerprint = null;
        for (int seed = 0; seed < 100; seed++) {
            List<PackageDependencyObservation> shuffledObservations =
                    new ArrayList<>(canonicalObservations);
            List<BusinessAssetLink> shuffledLinks = new ArrayList<>(canonicalLinks);
            Collections.shuffle(shuffledObservations, new Random(seed));
            Collections.shuffle(shuffledLinks, new Random(seed * 31L + 7));
            PackageCompilationResult result = compiler(frozen(
                    source.draft(), shuffledObservations, shuffledLinks))
                    .compile(source, 3, COMPILED_AT);
            if (snapshotFingerprint == null) {
                snapshotFingerprint = result.snapshot().fingerprint();
                readinessFingerprint = result.readiness().fingerprint();
                closureFingerprint = result.businessAssetLinkClosure().fingerprint();
            }
            assertThat(result.snapshot().fingerprint()).isEqualTo(snapshotFingerprint);
            assertThat(result.readiness().fingerprint()).isEqualTo(readinessFingerprint);
            assertThat(result.businessAssetLinkClosure().fingerprint()).isEqualTo(closureFingerprint);
        }
    }

    @Test
    void blocksMissingFingerprintAndCrossScopeDependenciesWithoutPublishingSnapshot() {
        StoredDomainCapabilityPackageDraft source = stored(completeDraft(List.of()));
        List<PackageDependencyObservation> values = new ArrayList<>(observations(source.draft()));
        MirrorArtifactRef contract = source.draft().packageContractRef();
        replace(values, contract, new PackageDependencyObservation(contract, null, null, null,
                PackageDependencyObservation.Status.FINGERPRINT_MISMATCH, List.of()));
        MirrorArtifactRef outcome = source.draft().outcomeDefinitionRefs().getFirst();
        replace(values, outcome, resolved(outcome, new CapabilitySnapshot.Scope(
                "ride-hailing", "other-org", "cancellation", "test", "sg")));

        PackageCompilationResult result = compiler(frozen(source.draft(), values, links()))
                .compile(source, 1, COMPILED_AT);

        assertThat(result.compiled()).isFalse();
        assertThat(result.readiness().status()).isEqualTo(PackageReadinessReport.Status.BLOCKED);
        assertThat(codes(result)).contains("DEPENDENCY_FINGERPRINT_MISMATCH",
                "DEPENDENCY_SCOPE_VIOLATION");
    }

    @Test
    void blocksUnprovenScenarioOutcomeEffectAndProposalIsolationSemantics() {
        MirrorArtifactRef proposal = ref("CAPABILITY_PROPOSAL", "trip-attribution-proposal", '8');
        StoredDomainCapabilityPackageDraft source = stored(completeDraft(List.of(proposal)));
        List<PackageDependencyObservation> values = new ArrayList<>(observations(source.draft()));
        for (int index = 0; index < values.size(); index++) {
            PackageDependencyObservation value = values.get(index);
            if (Set.of("SCENARIO_INVENTORY", "OUTCOME_DEFINITION", "EFFECT_CONTRACT",
                    "CAPABILITY_PROPOSAL").contains(value.sourceRef().kind())) {
                values.set(index, new PackageDependencyObservation(value.sourceRef(),
                        materialized(value.sourceRef()), value.sourceRef(), SCOPE,
                        PackageDependencyObservation.Status.RESOLVED,
                        List.of(PackageDependencyObservation.Assurance.SCHEMA_VALID)));
            }
        }

        PackageCompilationResult result = compiler(frozen(source.draft(), values, links()))
                .compile(source, 1, COMPILED_AT);

        assertThat(codes(result)).contains(
                "SCENARIO_DENOMINATOR_EMPTY", "OUTCOME_DEFINITION_UNPARSABLE",
                "HIGH_RISK_EFFECT_UNPROTECTED", "PROPOSAL_RESOLVER_UNBOUNDED",
                "PROPOSAL_REAL_CALL_GUARD_MISSING");
        assertThat(result.snapshot()).isNull();
    }

    @Test
    void blocksMutableMaterialAndUndeclaredAuthorityObservations() {
        StoredDomainCapabilityPackageDraft source = stored(completeDraft(List.of()));
        List<PackageDependencyObservation> values = new ArrayList<>(observations(source.draft()));
        MirrorArtifactRef graph = source.draft().graphRefs().getFirst();
        replace(values, graph, new PackageDependencyObservation(graph, graph, graph, SCOPE,
                PackageDependencyObservation.Status.RESOLVED,
                List.of(PackageDependencyObservation.Assurance.SCHEMA_VALID)));
        MirrorArtifactRef extra = ref("CONTRACT", "undeclared-contract", '9');
        values.add(resolved(extra, SCOPE));

        PackageCompilationResult result = compiler(frozen(source.draft(), values, links()))
                .compile(source, 1, COMPILED_AT);

        assertThat(codes(result)).contains("MUTABLE_DEPENDENCY_MATERIAL",
                "UNDECLARED_DEPENDENCY_OBSERVED");
        assertThat(result.snapshot()).isNull();
    }

    @Test
    void turnsInvalidOrMissingBusinessLinksIntoReadinessFindings() {
        StoredDomainCapabilityPackageDraft source = stored(completeDraft(List.of()));
        List<BusinessAssetLink> cyclic = new ArrayList<>(links());
        cyclic.add(link(source.draft().channelRefs().getFirst(),
                source.draft().solutionRefs().getFirst(), BusinessAssetLink.Relation.USES));

        PackageCompilationResult invalid = compiler(frozen(
                source.draft(), observations(source.draft()), cyclic))
                .compile(source, 1, COMPILED_AT);
        PackageCompilationResult missing = compiler(frozen(
                source.draft(), observations(source.draft()), List.of()))
                .compile(source, 1, COMPILED_AT);

        assertThat(codes(invalid)).contains("BUSINESS_ASSET_LINK_CLOSURE_INVALID");
        assertThat(codes(missing)).contains("BUSINESS_ASSET_LINKS_MISSING");
        assertThat(invalid.businessAssetLinkClosure().links()).isEmpty();
        assertThat(invalid.snapshot()).isNull();
    }

    @Test
    void admitsAuthorityOwnedL0AssetsWhenTheyConnectToAPackageRoot() {
        StoredDomainCapabilityPackageDraft source = stored(completeDraft(List.of()));
        BusinessAssetRef resource = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.RESOURCE, "trip-api", 'e');
        BusinessAssetRef operator = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.OPERATOR, "trip-query", 'f');
        List<BusinessAssetLink> complete = new ArrayList<>(links());
        complete.add(link(resource, operator, BusinessAssetLink.Relation.IMPLEMENTS));
        complete.add(link(operator, source.draft().solutionRefs().getFirst(),
                BusinessAssetLink.Relation.USES));

        PackageCompilationResult result = compiler(frozen(
                source.draft(), observations(source.draft()), complete))
                .compile(source, 1, COMPILED_AT);

        assertThat(result.compiled()).isTrue();
        assertThat(result.businessAssetLinkClosure().assets())
                .contains(resource, operator);
    }

    @Test
    void blocksAuthorityLinkIslandsThatDoNotBelongToThePackage() {
        StoredDomainCapabilityPackageDraft source = stored(completeDraft(List.of()));
        BusinessAssetRef resource = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.RESOURCE, "unrelated-api", 'e');
        BusinessAssetRef operator = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.OPERATOR, "unrelated-query", 'f');
        List<BusinessAssetLink> disconnected = new ArrayList<>(links());
        disconnected.add(link(resource, operator, BusinessAssetLink.Relation.IMPLEMENTS));

        PackageCompilationResult result = compiler(frozen(
                source.draft(), observations(source.draft()), disconnected))
                .compile(source, 1, COMPILED_AT);

        assertThat(result.compiled()).isFalse();
        assertThat(codes(result)).contains("BUSINESS_ASSET_LINK_CLOSURE_INVALID");
        assertThat(result.snapshot()).isNull();
    }

    @Test
    void fencesDependencyDriftAfterAllCompilationWork() {
        StoredDomainCapabilityPackageDraft source = stored(completeDraft(List.of()));
        FrozenPackageDependencies frozen = frozen(source.draft(), observations(source.draft()), links());
        AtomicBoolean rechecked = new AtomicBoolean();
        PackageCompilationAuthority authority = new PackageCompilationAuthority() {
            @Override
            public FrozenPackageDependencies freeze(StoredDomainCapabilityPackageDraft ignored) {
                return frozen;
            }

            @Override
            public void assertUnchanged(FrozenPackageDependencies ignored) {
                rechecked.set(true);
                throw new PackageDependencyDriftException("dependency generation advanced");
            }
        };

        assertThatThrownBy(() -> new PackageCompiler(MAPPER, authority)
                .compile(source, 1, COMPILED_AT))
                .isInstanceOf(PackageDependencyDriftException.class)
                .hasMessageContaining("advanced");
        assertThat(rechecked).isTrue();
    }

    @Test
    void rejectsTamperedStoredDraftBeforeConsultingDependencyAuthority() {
        StoredDomainCapabilityPackageDraft valid = stored(completeDraft(List.of()));
        StoredDomainCapabilityPackageDraft tampered = new StoredDomainCapabilityPackageDraft(
                valid.schemaVersion(), fingerprint('f'), valid.draft(), valid.createdAt(),
                valid.updatedAt(), valid.updatedBy());
        AtomicBoolean consulted = new AtomicBoolean();
        PackageCompilationAuthority authority = new PackageCompilationAuthority() {
            @Override
            public FrozenPackageDependencies freeze(StoredDomainCapabilityPackageDraft ignored) {
                consulted.set(true);
                throw new AssertionError("must not resolve a tampered source");
            }

            @Override
            public void assertUnchanged(FrozenPackageDependencies ignored) {
                throw new AssertionError("must not fence a tampered source");
            }
        };

        assertThatThrownBy(() -> new PackageCompiler(MAPPER, authority)
                .compile(tampered, 1, COMPILED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
        assertThat(consulted).isFalse();
    }

    @Test
    void linkClosureRejectsDanglingAssetsAndDetectsContentTampering() {
        DomainCapabilityPackageDraft draft = completeDraft(List.of());
        BusinessAssetRef undeclared = asset(BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                BusinessAssetRef.Kind.AGENT, "undeclared-agent", 'a');
        assertThatThrownBy(() -> new BusinessAssetLinkClosure("", "closure", 1, "", SCOPE,
                draft.packageId(), draft.solutionRefs(),
                List.of(link(draft.solutionRefs().getFirst(), undeclared,
                        BusinessAssetLink.Relation.DELIVERED_BY)), COMPILED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dangling");

        BusinessAssetLinkClosure sealed = new BusinessAssetLinkClosure("", "closure", 1, "", SCOPE,
                draft.packageId(), List.of(draft.solutionRefs().getFirst(), draft.carrierRefs().getFirst()),
                List.of(links().getFirst()), COMPILED_AT).seal(MAPPER);
        BusinessAssetLinkClosure tampered = new BusinessAssetLinkClosure("", "closure", 1,
                sealed.fingerprint(), SCOPE, draft.packageId(), sealed.assets(), List.of(), COMPILED_AT);
        assertThatThrownBy(() -> tampered.verify(MAPPER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    private static PackageCompiler compiler(FrozenPackageDependencies frozen) {
        return new PackageCompiler(MAPPER, new PackageCompilationAuthority() {
            @Override
            public FrozenPackageDependencies freeze(StoredDomainCapabilityPackageDraft ignored) {
                return frozen;
            }

            @Override
            public void assertUnchanged(FrozenPackageDependencies ignored) {
            }
        });
    }

    private static FrozenPackageDependencies frozen(
            DomainCapabilityPackageDraft draft,
            List<PackageDependencyObservation> observations,
            List<BusinessAssetLink> links) {
        return new FrozenPackageDependencies(SCOPE, "authority-generation-7", observations,
                ref("CAPABILITY_CLOSURE", "cancellation-closure", 'a'),
                List.of(ref("MIRROR_PLAN", "cancellation-plan", 'b')), links,
                List.of(ref("RUN_EVIDENCE_BUNDLE", "rehearsal", 'c')),
                ref("PACKAGE_COMPILATION_POLICY", "default", 'd'), COMPILED_AT.minusSeconds(1));
    }

    private static List<PackageDependencyObservation> observations(
            DomainCapabilityPackageDraft draft) {
        List<PackageDependencyObservation> values = new ArrayList<>();
        for (MirrorArtifactRef ref : allRefs(draft)) {
            List<PackageDependencyObservation.Assurance> assurances = new ArrayList<>();
            assurances.add(PackageDependencyObservation.Assurance.SCHEMA_VALID);
            switch (ref.kind()) {
                case "SCENARIO_INVENTORY" -> assurances.add(
                        PackageDependencyObservation.Assurance.NON_EMPTY_DENOMINATOR);
                case "OUTCOME_DEFINITION" -> assurances.add(
                        PackageDependencyObservation.Assurance.OUTCOME_PARSABLE);
                case "CAPABILITY_PROPOSAL" -> {
                    assurances.add(PackageDependencyObservation.Assurance.SIMULATION_BOUNDED);
                    assurances.add(PackageDependencyObservation.Assurance.REAL_EXTERNAL_CALLS_FORBIDDEN);
                }
                case "EFFECT_CONTRACT", "WRITE_EFFECT" -> assurances.add(
                        PackageDependencyObservation.Assurance.STATE_EFFECT_PROTECTED);
                default -> {
                }
            }
            values.add(new PackageDependencyObservation(ref, materialized(ref), ref, SCOPE,
                    PackageDependencyObservation.Status.RESOLVED, assurances));
        }
        return values;
    }

    private static PackageDependencyObservation resolved(
            MirrorArtifactRef ref, CapabilitySnapshot.Scope scope) {
        List<PackageDependencyObservation.Assurance> assurances = new ArrayList<>();
        assurances.add(PackageDependencyObservation.Assurance.SCHEMA_VALID);
        if (ref.kind().equals("OUTCOME_DEFINITION")) {
            assurances.add(PackageDependencyObservation.Assurance.OUTCOME_PARSABLE);
        }
        return new PackageDependencyObservation(ref, materialized(ref), ref, scope,
                PackageDependencyObservation.Status.RESOLVED, assurances);
    }

    private static MirrorArtifactRef materialized(MirrorArtifactRef ref) {
        return switch (ref.kind()) {
            case "GRAPH_DRAFT" -> new MirrorArtifactRef(
                    "GRAPH_SNAPSHOT", ref.id(), ref.revision(), ref.fingerprint());
            case "CAPABILITY_PROPOSAL" -> new MirrorArtifactRef(
                    "CAPABILITY_PROPOSAL_SNAPSHOT", ref.id(), ref.revision(), ref.fingerprint());
            default -> ref;
        };
    }

    private static Set<MirrorArtifactRef> allRefs(DomainCapabilityPackageDraft draft) {
        LinkedHashSet<MirrorArtifactRef> refs = new LinkedHashSet<>();
        refs.add(draft.businessDefinition().problemTaxonomyRef());
        refs.add(draft.packageContractRef());
        refs.addAll(draft.capabilityRefs());
        refs.addAll(draft.graphRefs());
        refs.addAll(draft.proposalRefs());
        refs.addAll(draft.stateModelRefs());
        refs.addAll(draft.effectModelRefs());
        refs.add(draft.scenarioInventoryRef());
        refs.addAll(draft.scenarioPackRefs());
        draft.solutionRefs().forEach(value -> refs.add(assetRef(value)));
        draft.carrierRefs().forEach(value -> refs.add(assetRef(value)));
        draft.channelRefs().forEach(value -> refs.add(assetRef(value)));
        refs.add(draft.fidelityInventoryRef());
        refs.addAll(draft.outcomeDefinitionRefs());
        return refs;
    }

    private static MirrorArtifactRef assetRef(BusinessAssetRef value) {
        return new MirrorArtifactRef(value.kind().name(), value.id(), value.revision(), value.fingerprint());
    }

    private static void replace(List<PackageDependencyObservation> values,
                                MirrorArtifactRef ref,
                                PackageDependencyObservation replacement) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).sourceRef().equals(ref)) {
                values.set(index, replacement);
                return;
            }
        }
        throw new AssertionError("missing fixture dependency " + ref);
    }

    private static List<String> codes(PackageCompilationResult result) {
        return result.readiness().findings().stream()
                .map(PackageReadinessReport.Finding::code).toList();
    }

    private static StoredDomainCapabilityPackageDraft stored(DomainCapabilityPackageDraft draft) {
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(MAPPER, draft, 8 * 1024 * 1024);
        return new StoredDomainCapabilityPackageDraft("", fingerprint, draft,
                COMPILED_AT.minusSeconds(3600), COMPILED_AT.minusSeconds(60), "alice");
    }

    private static DomainCapabilityPackageDraft completeDraft(List<MirrorArtifactRef> proposals) {
        return new DomainCapabilityPackageDraft("", "cancellation-fee-resolution", 1, SCOPE,
                new DomainCapabilityPackageDraft.BusinessDefinition("ride-cancellation",
                        ref("PROBLEM_TAXONOMY", "trip-problems", '1'),
                        "TRIP.CANCELLATION.FEE", "Resolve disputed cancellation fees",
                        "Return an explainable resolution", DomainCapabilityPackageDraft.RiskClass.HIGH,
                        "cancellation-owner", List.of("risk-owner")),
                ref("CONTRACT", "cancellation-contract", '2'),
                List.of(ref("CAPABILITY", "trip-query", '3')),
                List.of(ref("GRAPH_DRAFT", "cancellation-graph", '4')), proposals,
                List.of(ref("STATE_MODEL", "trip-state", '5')),
                List.of(ref("EFFECT_CONTRACT", "refund-effect", '6')),
                ref("SCENARIO_INVENTORY", "cancellation-denominator", '7'),
                List.of(ref("SCENARIO_PACK", "cancellation-cases", '8')),
                List.of(asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                        BusinessAssetRef.Kind.SOLUTION, "cancellation-solution", '9')),
                List.of(asset(BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                        BusinessAssetRef.Kind.WORKFLOW, "cancellation-workflow", 'a')),
                List.of(asset(BusinessAssetRef.Layer.L3_APPLICATION,
                        BusinessAssetRef.Kind.CHANNEL_APPLICATION, "support-console", 'b')),
                ref("DOMAIN_FIDELITY_INVENTORY", "cancellation-fidelity", 'c'),
                List.of(ref("OUTCOME_DEFINITION", "fair-resolution", 'd')),
                List.of("Cash refund remains virtual"), List.of("Fixture time is deterministic"),
                COMPILED_AT.plusSeconds(86_400), provenance(),
                DomainCapabilityPackageDraft.Lifecycle.READY_FOR_REVIEW);
    }

    private static List<BusinessAssetLink> links() {
        DomainCapabilityPackageDraft draft = completeDraft(List.of());
        return List.of(
                link(draft.solutionRefs().getFirst(), draft.carrierRefs().getFirst(),
                        BusinessAssetLink.Relation.DELIVERED_BY),
                link(draft.carrierRefs().getFirst(), draft.channelRefs().getFirst(),
                        BusinessAssetLink.Relation.EXPOSED_ON));
    }

    private static BusinessAssetLink link(
            BusinessAssetRef source, BusinessAssetRef target, BusinessAssetLink.Relation relation) {
        return new BusinessAssetLink("", source, target, relation, "", BusinessAssetLink.Risk.HIGH,
                "cancellation-owner", provenance());
    }

    private static BusinessAssetRef asset(
            BusinessAssetRef.Layer layer, BusinessAssetRef.Kind kind, String id, char value) {
        return new BusinessAssetRef(layer, kind, id, 1, fingerprint(value),
                "customer-service-registry", SCOPE);
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "business-mirror-compile-test", null, null, null, null,
                List.of(), "cancellation-owner", COMPILED_AT.minusSeconds(7200),
                COMPILED_AT.plusSeconds(86_400), "");
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
