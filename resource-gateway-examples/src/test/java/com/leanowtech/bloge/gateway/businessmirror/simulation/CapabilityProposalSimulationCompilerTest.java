package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredCapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshotIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityProposalSimulationCompilerTest {
    private static final Instant AT = Instant.parse("2026-08-14T10:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant", "customer-service", "refund", "test", "sg");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityProposalSimulationCompiler compiler =
            new CapabilityProposalSimulationCompiler(mapper);

    @Test
    void overlaysOnlyTheTargetAndResealsAffectedAncestors() {
        CapabilityClosure base = baseClosure();
        MirrorArtifactRef target = base.snapshots().stream()
                .filter(value -> value.kind() == CapabilitySnapshot.Kind.EXTERNAL)
                .map(CapabilityClosureIntegrity::reference).findFirst().orElseThrow();

        CapabilityProposalSimulationCompiler.Result result = compiler.compile(
                proposal(readOnlyContract()), base, target, "refundGraph", fingerprint('9'));

        assertThat(result.baseClosure()).isEqualTo(base);
        assertThat(result.simulatedClosure().fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(result.targetCapabilityRef()).isEqualTo(target);
        assertThat(result.temporaryCapabilityRef().id()).startsWith("proposal:refund-proposal:r1:");
        CapabilitySnapshot temporary = result.simulatedClosure().snapshots().stream()
                .filter(value -> CapabilityClosureIntegrity.reference(value)
                        .equals(result.temporaryCapabilityRef()))
                .findFirst().orElseThrow();
        assertThat(temporary.runtime().kind()).isEqualTo("SIMULATION_ONLY");
        assertThat(temporary.contract()).isEqualTo(readOnlyContract());
        CapabilitySnapshot root = result.simulatedClosure().snapshots().stream()
                .filter(value -> CapabilityClosureIntegrity.reference(value)
                        .equals(result.simulatedClosure().rootRef()))
                .findFirst().orElseThrow();
        assertThat(root.source().sourceRef()).isEqualTo("refundGraph");
        assertThat(root.source().sourceFingerprint()).isEqualTo(fingerprint('9'));
        assertThat(root.dependencies()).extracting(CapabilitySnapshot.Dependency::capabilityRef)
                .containsExactly(result.temporaryCapabilityRef());
        CapabilityClosureIntegrity.verify(mapper, result.simulatedClosure());
    }

    @Test
    void producesTheSameOverlayForTheSameExactInputs() {
        CapabilityClosure base = baseClosure();
        MirrorArtifactRef target = base.snapshots().stream()
                .filter(value -> value.kind() == CapabilitySnapshot.Kind.EXTERNAL)
                .map(CapabilityClosureIntegrity::reference).findFirst().orElseThrow();

        CapabilityProposalSimulationCompiler.Result first = compiler.compile(
                proposal(readOnlyContract()), base, target, "refundGraph", fingerprint('9'));
        CapabilityProposalSimulationCompiler.Result second = compiler.compile(
                proposal(readOnlyContract()), base, target, "refundGraph", fingerprint('9'));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void rejectsWriteOrSecretBearingProposalContracts() {
        CapabilityClosure base = baseClosure();
        MirrorArtifactRef target = base.snapshots().stream()
                .filter(value -> value.kind() == CapabilitySnapshot.Kind.EXTERNAL)
                .map(CapabilityClosureIntegrity::reference).findFirst().orElseThrow();
        CapabilityContract unsafe = new CapabilityContract("", SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(), List.of(), new EffectContract("",
                EffectContract.Mode.EXTERNAL_MUTATION, List.of(), List.of("refund/*"),
                List.of(), null, true, EffectContract.RiskLevel.HIGH,
                EffectContract.Derivation.DECLARED, List.of()),
                CapabilityContract.Determinism.DETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, true,
                        List.of("sg"), false),
                new CapabilityContract.SloContract(
                        Duration.ofSeconds(2), 0.99, 500L, "owner"));

        assertThatThrownBy(() -> compiler.compile(
                proposal(unsafe), base, target, "refundGraph", fingerprint('9')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only, stateless, secret-free");
    }

    @Test
    void rejectsTargetsOutsideTheExactBaseClosure() {
        CapabilityClosure base = baseClosure();

        assertThatThrownBy(() -> compiler.compile(proposal(readOnlyContract()), base,
                ref("CAPABILITY", "missing", '8'), "refundGraph", fingerprint('9')))
                .hasMessageContaining("must resolve");
    }

    private CapabilityClosure baseClosure() {
        CapabilitySnapshot child = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "operator:refundLookup", 1, "",
                        CapabilitySnapshot.Kind.EXTERNAL, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.OPERATOR,
                                "refundLookup", fingerprint('1')),
                        readOnlyContract(), runtime("refundLookup"), List.of(),
                        ownership(), CapabilitySnapshot.Lifecycle.DRAFT, provenance(), AT));
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "graph:refundGraph", 1, "",
                        CapabilitySnapshot.Kind.COMPOSED, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                                "built-in:refundGraph", fingerprint('2')),
                        readOnlyContract(), runtime("refundGraph"),
                        List.of(new CapabilitySnapshot.Dependency("lookupRefund",
                                CapabilityClosureIntegrity.reference(child), true, List.of())),
                        ownership(), CapabilitySnapshot.Lifecycle.DRAFT, provenance(), AT));
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), List.of(root, child), ""));
    }

    private StoredCapabilityProposalDraft proposal(CapabilityContract contract) {
        CapabilityProposalDraft draft = new CapabilityProposalDraft("", "refund-proposal", 1,
                SCOPE, new CapabilityProposalDraft.BusinessIntent(
                "Refund lookup is missing", "Resolve refunds without live dependencies",
                List.of(ref("SCENARIO_CASE", "refund-approved", '3')),
                List.of(ref("DOMAIN_CAPABILITY_PACKAGE", "refund-package", '4')),
                List.of(ref("GRAPH_DRAFT", "built-in:refundGraph", '5')), "owner"),
                contract, List.of(ref("FIXTURE_BUNDLE", "refund-fixture", '6')),
                List.of(ref("TEST_SUITE", "refund-suite", '7')),
                new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                        ref("FIXTURE_RESOLVER_POLICY", "fixture-only", '8'),
                        false, false, false), List.of(), List.of(),
                Instant.parse("2026-09-14T10:00:00Z"), provenance(),
                CapabilityProposalDraft.Lifecycle.DRAFT);
        return new StoredCapabilityProposalDraft("", fingerprint('a'), draft, AT, AT, "owner");
    }

    private static CapabilityContract readOnlyContract() {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(), EffectContract.readOnly(List.of("refund/*")),
                CapabilityContract.Determinism.DETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, false,
                        List.of("sg"), false),
                new CapabilityContract.SloContract(
                        Duration.ofSeconds(2), 0.99, 500L, "owner"));
    }

    private static CapabilitySnapshot.RuntimeBinding runtime(String id) {
        return new CapabilitySnapshot.RuntimeBinding(
                "TEST", id, fingerprint('b'), true, List.of());
    }

    private static CapabilitySnapshot.Ownership ownership() {
        return new CapabilitySnapshot.Ownership("owner", "team", "on-call");
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "proposal-test", null, null, null, null,
                List.of(), "", null, null, "");
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
