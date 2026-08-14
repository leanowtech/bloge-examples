package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.CapabilityProposalDraftRepository;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredCapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationEvidence;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationRepository;
import com.leanowtech.bloge.gateway.businessmirror.simulation.StoredCapabilityProposalSimulation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityImplementationBindingServiceTest {
    private static final Instant AT = Instant.parse("2026-08-14T10:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant", "customer-service", "refund", "test", "sg");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private CapabilityProposalDraftRepository proposals;
    private CapabilityProposalSimulationRepository simulations;
    private InMemoryBindingRepository bindings;
    private CapabilityImplementationRuntimePort runtime;
    private VisualEvidenceSigner signer;
    private StoredCapabilityProposalDraft proposal;
    private CapabilityProposalSimulationEvidence evidence;
    private CapabilityImplementationBindingRequest request;

    @BeforeEach
    void setUp() {
        proposal = proposal();
        proposals = mock(CapabilityProposalDraftRepository.class);
        when(proposals.findRevision(SCOPE, proposal.proposalId(), proposal.revision()))
                .thenReturn(Optional.of(proposal));
        simulations = mock(CapabilityProposalSimulationRepository.class);
        evidence = mock(CapabilityProposalSimulationEvidence.class);
        MirrorArtifactRef evidenceRef = ref(
                "PROPOSAL_SIMULATION_EVIDENCE", "simulation-1", '7');
        MirrorArtifactRef targetRef = ref("CAPABILITY", "refund-lookup", '8');
        when(evidence.artifactRef()).thenReturn(evidenceRef);
        when(evidence.status()).thenReturn(CapabilityProposalSimulationEvidence.Status.PASSED);
        when(evidence.targetCapabilityRef()).thenReturn(targetRef);
        StoredCapabilityProposalSimulation simulation = mock(
                StoredCapabilityProposalSimulation.class);
        when(simulation.evidence()).thenReturn(evidence);
        when(simulations.find(SCOPE, proposal.proposalId(), proposal.revision()))
                .thenReturn(Optional.of(new CapabilityProposalSimulationRepository.State(
                        new CapabilityProposalSimulationRepository.Registration(
                                SCOPE, "simulation-1", proposal.proposalId(), proposal.revision(),
                                fingerprint('9')),
                        CapabilityProposalSimulationRepository.Status.COMPLETED, "", 1, AT,
                        simulation, "", AT, AT)));
        runtime = mock(CapabilityImplementationRuntimePort.class);
        when(runtime.describe(SCOPE, "runtime:refund:v1"))
                .thenReturn(Optional.of(descriptor()));
        signer = mock(VisualEvidenceSigner.class);
        when(signer.seal(any(), any())).thenAnswer(invocation ->
                new VisualRunEvidenceSeal("", invocation.getArgument(0), "TEST", "key-1",
                        AT, "signature"));
        when(signer.verify(any(), any())).thenReturn(
                new VisualEvidenceSigner.Verification(true, "VERIFIED", ""));
        bindings = new InMemoryBindingRepository();
        request = new CapabilityImplementationBindingRequest("", proposal.draftFingerprint(),
                evidenceRef, targetRef, "runtime:refund:v1", fingerprint('a'), "1.0.0",
                fingerprint('b'));
    }

    @Test
    void createsServerAttestedBindingAndExactlyReplaysTheCommand() {
        CapabilityImplementationBindingService service = service();

        var created = service.bind(
                proposal.proposalId(), proposal.revision(), "binding-1", request, identity());
        var replay = service.bind(
                proposal.proposalId(), proposal.revision(), "binding-1", request, identity());

        assertThat(created.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.binding()).isEqualTo(created.binding());
        assertThat(created.binding().binding()).satisfies(binding -> {
            assertThat(binding.proposalDraftRef().fingerprint())
                    .isEqualTo(proposal.draftFingerprint());
            assertThat(binding.simulationEvidenceRef()).isEqualTo(evidence.artifactRef());
            assertThat(binding.runtimePortFingerprint()).isEqualTo(fingerprint('a'));
            assertThat(binding.implementationFingerprint()).isEqualTo(fingerprint('b'));
            assertThat(binding.readOnly()).isTrue();
            assertThat(binding.stateless()).isTrue();
        });
    }

    @Test
    void rejectsFailedSimulationAndRuntimeDescriptorDrift() {
        when(evidence.status()).thenReturn(CapabilityProposalSimulationEvidence.Status.FAILED);
        assertThatThrownBy(() -> service().bind(
                proposal.proposalId(), 1, "binding-failed", request, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(failure -> ((IntegrationProblemException) failure).problem().code())
                .isEqualTo("RG.BUSINESS_MIRROR.IMPLEMENTATION_SIMULATION_STALE");

        when(evidence.status()).thenReturn(CapabilityProposalSimulationEvidence.Status.PASSED);
        var drift = descriptor();
        when(runtime.describe(SCOPE, "runtime:refund:v1")).thenReturn(Optional.of(
                new CapabilityImplementationRuntimePort.Descriptor(drift.runtimePortRef(),
                        fingerprint('c'), drift.implementationVersion(),
                        drift.implementationFingerprint(), drift.candidateContractFingerprint(),
                        drift.runtimeOwner(), drift.allowedRegions(), true, true,
                        drift.attestedAt(), drift.expiresAt())));
        assertThatThrownBy(() -> service().bind(
                proposal.proposalId(), 1, "binding-drift", request, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(failure -> ((IntegrationProblemException) failure).problem().code())
                .isEqualTo("RG.BUSINESS_MIRROR.IMPLEMENTATION_RUNTIME_DRIFT");
    }

    @Test
    void failsClosedWhenRuntimeOrSignerIsUnavailable() {
        when(runtime.describe(SCOPE, "runtime:refund:v1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().bind(
                proposal.proposalId(), 1, "binding-no-runtime", request, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(failure -> ((IntegrationProblemException) failure).problem().code())
                .isEqualTo("RG.BUSINESS_MIRROR.IMPLEMENTATION_RUNTIME_UNAVAILABLE");

        when(runtime.describe(SCOPE, "runtime:refund:v1"))
                .thenReturn(Optional.of(descriptor()));
        when(signer.seal(any(), any())).thenReturn(VisualRunEvidenceSeal.unsigned());
        assertThatThrownBy(() -> service().bind(
                proposal.proposalId(), 1, "binding-no-signer", request, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(failure -> ((IntegrationProblemException) failure).problem().code())
                .isEqualTo("RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_SIGNER_UNAVAILABLE");
    }

    @Test
    void rejectsUnrecognizedCallerPurposeOnBindingReads() {
        IntegrationRequestContext unauthorized = new IntegrationRequestContext(
                SCOPE.tenantId(), SCOPE.organizationId(), SCOPE.projectId(),
                SCOPE.environmentId(), SCOPE.region(), "WORKLOAD", "reader", "",
                "ARBITRARY_SELF_ASSERTED_PURPOSE", "correlation-2", Set.of("readers"),
                "CONFIDENTIAL", "");

        assertThatThrownBy(() -> service().find("binding-1", unauthorized))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(failure -> ((IntegrationProblemException) failure).problem().code())
                .isEqualTo("RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_FORBIDDEN");
    }

    @Test
    void returnsStableConflictWhenBindingIdIsReusedForAnotherImplementation() {
        CapabilityImplementationBindingService service = service();
        service.bind(proposal.proposalId(), proposal.revision(), "binding-conflict",
                request, identity());
        CapabilityImplementationBindingRequest changed =
                new CapabilityImplementationBindingRequest("", proposal.draftFingerprint(),
                        evidence.artifactRef(), evidence.targetCapabilityRef(),
                        "runtime:refund:v1", fingerprint('a'), "1.0.1", fingerprint('c'));
        var current = descriptor();
        when(runtime.describe(SCOPE, "runtime:refund:v1")).thenReturn(Optional.of(
                new CapabilityImplementationRuntimePort.Descriptor(current.runtimePortRef(),
                        current.runtimePortFingerprint(), "1.0.1", fingerprint('c'),
                        current.candidateContractFingerprint(), current.runtimeOwner(),
                        current.allowedRegions(), true, true,
                        current.attestedAt(), current.expiresAt())));

        assertThatThrownBy(() -> service.bind(proposal.proposalId(), proposal.revision(),
                "binding-conflict", changed, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(failure -> ((IntegrationProblemException) failure).problem().code())
                .isEqualTo("RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_CONFLICT");
    }

    private CapabilityImplementationBindingService service() {
        return new CapabilityImplementationBindingService(proposals, simulations, bindings,
                runtime, signer, mapper, Clock.fixed(AT, ZoneOffset.UTC));
    }

    private CapabilityImplementationRuntimePort.Descriptor descriptor() {
        return new CapabilityImplementationRuntimePort.Descriptor("runtime:refund:v1",
                fingerprint('a'), "1.0.0", fingerprint('b'),
                ProtocolFingerprint.of(mapper, proposal.draft().candidateContract()),
                "trip-platform", List.of("sg"), true, true, AT.minusSeconds(1),
                AT.plus(Duration.ofHours(1)));
    }

    private StoredCapabilityProposalDraft proposal() {
        CapabilityProposalDraft draft = new CapabilityProposalDraft("", "refund-proposal", 1,
                SCOPE, new CapabilityProposalDraft.BusinessIntent(
                "Refund lookup is missing", "Validate refund decisions",
                List.of(ref("SCENARIO_CASE", "refund-approved", '1')),
                List.of(ref("DOMAIN_CAPABILITY_PACKAGE", "refund-package", '2')),
                List.of(ref("GRAPH_DRAFT", "refund-graph", '3')), "service-owner"),
                contract(), List.of(ref("FIXTURE_BUNDLE", "refund-fixture", '4')),
                List.of(ref("TEST_SUITE", "refund-suite", '5')),
                new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                        ref("FIXTURE_RESOLVER_POLICY", "fixture-only", '6'),
                        false, false, false), List.of(), List.of(),
                AT.plus(Duration.ofDays(7)), provenance(), CapabilityProposalDraft.Lifecycle.DRAFT);
        return new StoredCapabilityProposalDraft("",
                VisualBundleFingerprint.fromCanonicalValue(mapper, draft, 4 * 1024 * 1024),
                draft, AT.minusSeconds(60), AT.minusSeconds(60), "service-owner");
    }

    private CapabilityContract contract() {
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
                        Duration.ofSeconds(2), 0.999d, 500L, "trip-platform"));
    }

    private ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "implementation-binding-test", null, null, null, null,
                List.of(), "", null, null, "");
    }

    private IntegrationRequestContext identity() {
        return new IntegrationRequestContext(SCOPE.tenantId(), SCOPE.organizationId(),
                SCOPE.projectId(), SCOPE.environmentId(), SCOPE.region(), "WORKLOAD",
                "implementer", "", "CAPABILITY_IMPLEMENTATION", "correlation-1",
                Set.of("capability-implementers"), "CONFIDENTIAL", "");
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class InMemoryBindingRepository
            implements CapabilityImplementationBindingRepository {
        private final Map<String, StoredCapabilityImplementationBinding> values =
                new LinkedHashMap<>();

        @Override
        public CreateResult create(StoredCapabilityImplementationBinding binding) {
            String key = binding.binding().scope() + "\u0000" + binding.binding().bindingId();
            StoredCapabilityImplementationBinding existing = values.putIfAbsent(key, binding);
            if (existing != null && !existing.equals(binding)) {
                throw new IllegalArgumentException("different command material");
            }
            return new CreateResult(existing == null ? binding : existing, existing == null);
        }

        @Override
        public Optional<StoredCapabilityImplementationBinding> find(
                CapabilitySnapshot.Scope scope, String bindingId) {
            return Optional.ofNullable(values.get(scope + "\u0000" + bindingId));
        }
    }
}
