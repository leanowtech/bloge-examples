package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorPlanIntegrityTest {
    private static final Instant COMPILED_AT = Instant.parse("2026-07-22T08:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "support", "test", "sg");
    private static final String PURPOSE = "MIRROR_REHEARSAL";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sealsRoundTripsAndVerifiesACompletePayloadFreePlan() throws Exception {
        PlanMaterial material = material(readOnlyEffect(), null, null);

        MirrorPlan first = MirrorPlanIntegrity.seal(mapper, plan(material));
        MirrorPlan second = MirrorPlanIntegrity.seal(mapper, plan(material));
        MirrorPlan restored = mapper.readValue(mapper.writeValueAsBytes(first), MirrorPlan.class);

        MirrorPlanIntegrity.verify(mapper, restored);
        assertThat(first.planFingerprint()).isEqualTo(second.planFingerprint());
        assertThat(restored.externalBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.parentCapabilityRef()).isEqualTo(material.closure().rootRef());
            assertThat(binding.capabilityRef()).isEqualTo(material.childRef());
            assertThat(binding.resolverOrder()).containsExactly(
                    MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                    MirrorPlan.MirrorSource.ABSTAINED);
        });
        assertThat(restored.policy().realExternalCallsAllowed()).isFalse();
        assertThat(restored.policy().externalCredentialsAllowed()).isFalse();
        assertThat(restored.policy().networkEgressAllowed()).isFalse();
    }

    @Test
    void rejectsAPlanWhoseCapabilityClosureWasModified() {
        PlanMaterial material = material(readOnlyEffect(), null, null);
        MirrorPlan sealed = MirrorPlanIntegrity.seal(mapper, plan(material));
        CapabilitySnapshot child = CapabilitySnapshotIntegrity.seal(mapper, new CapabilitySnapshot(
                "", material.child().capabilityId(), material.child().revision(), "",
                material.child().kind(), material.child().scope(), material.child().source(),
                material.child().contract(), material.child().runtime(), material.child().dependencies(),
                new CapabilitySnapshot.Ownership("different-owner", "support", "pager"),
                material.child().lifecycle(), material.child().provenance(), material.child().createdAt()));
        MirrorPlan modified = copy(sealed, List.of(material.root(), child),
                sealed.externalBindings(), sealed.stateModelRefs(), sealed.expiresAt());

        assertRejected(modified, "capability closure");
    }

    @Test
    void rejectsMissingAndDuplicateExternalEdgeBindings() {
        PlanMaterial material = material(readOnlyEffect(), null, null);
        MirrorPlan source = plan(material);
        assertRejected(copy(source, source.capabilityClosure(), List.of(),
                source.stateModelRefs(), source.expiresAt()), "exactly cover");

        MirrorPlan.ExternalBinding duplicateSite = new MirrorPlan.ExternalBinding(
                material.closure().rootRef(), "anotherNode", material.childRef(),
                source.externalBindings().getFirst().invocationSiteId(), "/root",
                material.child().source().sourceKind(), material.child().source().sourceRef(),
                List.of(MirrorPlan.MirrorSource.ABSTAINED), List.of());
        CapabilitySnapshot rootWithSecondEdge = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", material.root().capabilityId(), material.root().revision(), "",
                        material.root().kind(), material.root().scope(), material.root().source(),
                        material.root().contract(), material.root().runtime(), List.of(
                        material.root().dependencies().getFirst(),
                        new CapabilitySnapshot.Dependency("anotherNode", material.childRef(), true, List.of())),
                        material.root().ownership(), material.root().lifecycle(),
                        material.root().provenance(), material.root().createdAt()));
        CapabilityClosure closure = CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(rootWithSecondEdge),
                List.of(rootWithSecondEdge, material.child()), ""));
        PlanMaterial twoEdges = new PlanMaterial(material.child(), rootWithSecondEdge,
                material.childRef(), closure, material.stateModelRef());
        MirrorPlan twoEdgePlan = plan(twoEdges);
        List<MirrorPlan.ExternalBinding> bindings = List.of(
                twoEdgePlan.externalBindings().getFirst(), duplicateSite);

        assertThatThrownBy(() -> MirrorPlanIntegrity.seal(mapper,
                copy(twoEdgePlan, twoEdgePlan.capabilityClosure(), bindings,
                        twoEdgePlan.stateModelRefs(), twoEdgePlan.expiresAt())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one runtime invocation site");
    }

    @Test
    void rejectsBindingWhoseExternalSourceDoesNotMatchTheSnapshot() {
        PlanMaterial material = material(readOnlyEffect(), null, null);
        MirrorPlan source = plan(material);
        MirrorPlan.ExternalBinding binding = source.externalBindings().getFirst();
        MirrorPlan.ExternalBinding changed = new MirrorPlan.ExternalBinding(
                binding.parentCapabilityRef(), binding.dependencyNodeId(), binding.capabilityRef(),
                binding.invocationSiteId(), binding.graphPath(), binding.sourceKind(), "other-resource",
                binding.resolverOrder(), binding.fixtureRuleRefs());

        assertRejected(copy(source, source.capabilityClosure(), List.of(changed),
                source.stateModelRefs(), source.expiresAt()), "source does not match");
    }

    @Test
    void rejectsNonCanonicalResolverOrderAndAnyRealAuthority() {
        PlanMaterial material = material(readOnlyEffect(), null, null);
        MirrorPlan.ExternalBinding source = plan(material).externalBindings().getFirst();

        assertThatThrownBy(() -> new MirrorPlan.ExternalBinding(
                source.parentCapabilityRef(), source.dependencyNodeId(), source.capabilityRef(),
                source.invocationSiteId(), source.graphPath(), source.sourceKind(), source.sourceRef(),
                List.of(MirrorPlan.MirrorSource.ABSTAINED,
                        MirrorPlan.MirrorSource.OWNER_SPECIFIED), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ABSTAINED");
        assertThatThrownBy(() -> policy(true, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deny real calls");
        assertThatThrownBy(() -> policy(false, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deny real calls");
        assertThatThrownBy(() -> policy(false, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deny real calls");
    }

    @Test
    void rejectsUnknownEffectsAndPlansThatOutliveCapabilityApproval() {
        PlanMaterial unknown = material(EffectContract.unknown("operator declaration missing"), null, null);
        assertRejected(plan(unknown), "unknown capability effect");

        Instant artifactExpiry = COMPILED_AT.plus(Duration.ofMinutes(30));
        PlanMaterial expiring = material(readOnlyEffect(), artifactExpiry, null);
        assertRejected(plan(expiring), "outlives capability provenance");
    }

    @Test
    void requiresTheExactStateModelClosure() {
        MirrorArtifactRef stateModel = ref("STATE_MODEL", "refund-world", 'f');
        EffectContract virtualMutation = new EffectContract("", EffectContract.Mode.VIRTUAL_MUTATION,
                List.of("refund:*"), List.of("refund:*"), List.of(), null, false,
                EffectContract.RiskLevel.MEDIUM, EffectContract.Derivation.DECLARED, List.of());
        PlanMaterial material = material(virtualMutation, null, stateModel);
        MirrorPlan source = plan(material);

        assertRejected(copy(source, source.capabilityClosure(), source.externalBindings(),
                List.of(), source.expiresAt()), "stateModelRefs");
        MirrorPlan sealed = MirrorPlanIntegrity.seal(mapper, source);
        assertThat(sealed.stateModelRefs()).containsExactly(stateModel);
    }

    @Test
    void enforcesHardExpiryAndAdmittedRegion() {
        PlanMaterial material = material(readOnlyEffect(), null, null);
        MirrorPlan source = plan(material);

        assertRejected(copy(source, source.capabilityClosure(), source.externalBindings(),
                source.stateModelRefs(), COMPILED_AT.plus(Duration.ofHours(25))), "24-hour TTL");
        MirrorPlan.ExecutionPolicy wrongRegion = new MirrorPlan.ExecutionPolicy(PURPOSE,
                false, false, false, false, true,
                MirrorPlan.UnmatchedResolution.ABSTAINED, 100, Duration.ofMinutes(5),
                CapabilityContract.DataClassification.CONFIDENTIAL, List.of("us"),
                List.of(CapabilitySnapshot.Lifecycle.ACTIVE));
        MirrorPlan changed = new MirrorPlan("", source.planId(), "", source.rootCapability(),
                source.capabilityClosureFingerprint(), source.capabilityClosure(), source.scope(),
                source.fixtureBundleRef(), source.executionControlFingerprint(),
                source.servingGeneration(), source.externalBindings(), null,
                source.stateModelRefs(),
                source.executionServices(), wrongRegion, source.compiledAt(), source.expiresAt());
        assertRejected(changed, "region");
    }

    @Test
    void rejectsCapabilitiesCreatedAfterCompilationAndRegionIntersectionWithoutExactResidency() {
        PlanMaterial future = material(readOnlyEffect(), null, null,
                "sg", COMPILED_AT.plusSeconds(1));
        assertRejected(plan(future), "cannot predate");

        PlanMaterial wrongResidency = material(readOnlyEffect(), null, null,
                "us", COMPILED_AT);
        assertRejected(plan(wrongResidency), "no region admitted");
    }

    private void assertRejected(MirrorPlan plan, String message) {
        assertThatThrownBy(() -> MirrorPlanIntegrity.seal(mapper, plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private PlanMaterial material(EffectContract effect, Instant provenanceExpiry,
                                  MirrorArtifactRef stateModelRef) {
        return material(effect, provenanceExpiry, stateModelRef, "sg", COMPILED_AT);
    }

    private PlanMaterial material(EffectContract effect, Instant provenanceExpiry,
                                  MirrorArtifactRef stateModelRef, String securityRegion,
                                  Instant createdAt) {
        CapabilityContract contract = contract(effect, stateModelRef, securityRegion);
        CapabilitySnapshot child = CapabilitySnapshotIntegrity.seal(mapper, new CapabilitySnapshot(
                "", "resource:customers.get", 4, "", CapabilitySnapshot.Kind.EXTERNAL, SCOPE,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.RESOURCE,
                        "customers.get", fingerprint('a')), contract,
                new CapabilitySnapshot.RuntimeBinding("HTTP_RESOURCE", "customers.get@4",
                        fingerprint('b'), true, List.of()), List.of(),
                new CapabilitySnapshot.Ownership("owner-a", "support", "pager"),
                CapabilitySnapshot.Lifecycle.ACTIVE, provenance(provenanceExpiry), createdAt));
        MirrorArtifactRef childRef = CapabilityClosureIntegrity.reference(child);
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(mapper, new CapabilitySnapshot(
                "", "graph:customerView", 7, "", CapabilitySnapshot.Kind.COMPOSED, SCOPE,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                        "customerView", fingerprint('c')), contract,
                new CapabilitySnapshot.RuntimeBinding("BLOGE_GRAPH", "customerView@7",
                        fingerprint('d'), true, List.of()),
                List.of(new CapabilitySnapshot.Dependency("loadCustomer", childRef, true, List.of())),
                child.ownership(), child.lifecycle(), child.provenance(), createdAt));
        CapabilityClosure closure = CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure(
                "", CapabilityClosureIntegrity.reference(root), List.of(root, child), ""));
        return new PlanMaterial(child, root, childRef, closure, stateModelRef);
    }

    private MirrorPlan plan(PlanMaterial material) {
        List<MirrorPlan.MirrorSource> resolverOrder =
                material.stateModelRef() == null
                        ? List.of(MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                        MirrorPlan.MirrorSource.ABSTAINED)
                        : List.of(MirrorPlan.MirrorSource.SESSION_STATE,
                        MirrorPlan.MirrorSource.ABSTAINED);
        MirrorPlan.ExternalBinding binding = new MirrorPlan.ExternalBinding(
                material.closure().rootRef(), "loadCustomer", material.childRef(),
                "/root/loadCustomer#RESOURCE", "/root",
                material.child().source().sourceKind(), material.child().source().sourceRef(),
                resolverOrder, List.of("customer-response"));
        List<MirrorArtifactRef> stateModels = material.stateModelRef() == null
                ? List.of() : List.of(material.stateModelRef());
        return new MirrorPlan("", "plan-customer-view", "", material.closure().rootRef(),
                material.closure().fingerprint(), material.closure().snapshots(), SCOPE,
                ref("FIXTURE_BUNDLE", "customer-fixture", 'e'), fingerprint('9'),
                null, List.of(binding), null,
                stateModels, new MirrorPlan.ExecutionServices(COMPILED_AT, 42L, null, null),
                policy(false, false, false), COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(1)));
    }

    private static MirrorPlan.ExecutionPolicy policy(boolean real, boolean credentials,
                                                     boolean egress) {
        return new MirrorPlan.ExecutionPolicy(PURPOSE, real, credentials, egress, false, true,
                MirrorPlan.UnmatchedResolution.ABSTAINED, 100, Duration.ofMinutes(5),
                CapabilityContract.DataClassification.CONFIDENTIAL, List.of("sg"),
                List.of(CapabilitySnapshot.Lifecycle.ACTIVE));
    }

    private static CapabilityContract contract(EffectContract effect,
                                               MirrorArtifactRef stateModelRef,
                                               String securityRegion) {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(),
                effect, CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true),
                stateModelRef, CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, false,
                        List.of(securityRegion), false), CapabilityContract.SloContract.unspecified());
    }

    private static EffectContract readOnlyEffect() {
        return EffectContract.readOnly(List.of("resource:customers.get"));
    }

    private static ArtifactProvenance provenance(Instant expiry) {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), PURPOSE, null, null, null, null, List.of(),
                "owner-a", COMPILED_AT.minus(Duration.ofDays(1)), expiry, "");
    }

    private static MirrorArtifactRef ref(String kind, String id, char fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(fingerprint));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static MirrorPlan copy(MirrorPlan source,
                                   List<CapabilitySnapshot> closure,
                                   List<MirrorPlan.ExternalBinding> bindings,
                                   List<MirrorArtifactRef> stateModels,
                                   Instant expiresAt) {
        return new MirrorPlan(source.schemaVersion(), source.planId(), "", source.rootCapability(),
                source.capabilityClosureFingerprint(), closure, source.scope(),
                source.fixtureBundleRef(), source.executionControlFingerprint(),
                source.servingGeneration(), bindings,
                source.scenarioPackRef(), stateModels,
                source.executionServices(), source.policy(), source.compiledAt(), expiresAt);
    }

    private record PlanMaterial(
            CapabilitySnapshot child,
            CapabilitySnapshot root,
            MirrorArtifactRef childRef,
            CapabilityClosure closure,
            MirrorArtifactRef stateModelRef
    ) {
    }
}
