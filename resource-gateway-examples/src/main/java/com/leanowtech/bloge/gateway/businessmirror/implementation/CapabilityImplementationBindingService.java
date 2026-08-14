package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.CapabilityProposalDraftRepository;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredCapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationEvidence;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationRepository;
import com.leanowtech.bloge.gateway.businessmirror.simulation.StoredCapabilityProposalSimulation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Authenticated application boundary for immutable Proposal implementation binding. */
public final class CapabilityImplementationBindingService {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");

    private final CapabilityProposalDraftRepository proposals;
    private final CapabilityProposalSimulationRepository simulations;
    private final CapabilityImplementationBindingRepository bindings;
    private final CapabilityImplementationRuntimePort runtime;
    private final VisualEvidenceSigner signer;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CapabilityImplementationBindingService(
            CapabilityProposalDraftRepository proposals,
            CapabilityProposalSimulationRepository simulations,
            CapabilityImplementationBindingRepository bindings,
            CapabilityImplementationRuntimePort runtime,
            VisualEvidenceSigner signer,
            ObjectMapper mapper) {
        this(proposals, simulations, bindings, runtime, signer, mapper, Clock.systemUTC());
    }

    /** Full constructor with deterministic time for service tests. */
    public CapabilityImplementationBindingService(
            CapabilityProposalDraftRepository proposals,
            CapabilityProposalSimulationRepository simulations,
            CapabilityImplementationBindingRepository bindings,
            CapabilityImplementationRuntimePort runtime,
            VisualEvidenceSigner signer,
            ObjectMapper mapper,
            Clock clock) {
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.simulations = Objects.requireNonNull(simulations, "simulations");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Attests and immutably binds one exact runtime generation to a simulated Proposal. */
    public CapabilityImplementationBindingRepository.CreateResult bind(
            String proposalId,
            long proposalRevision,
            String bindingId,
            CapabilityImplementationBindingRequest request,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireIdentity(identity, "CAPABILITY_IMPLEMENTATION");
        String proposal = requireId(proposalId, "proposalId", identity);
        String id = requireId(bindingId, "Idempotency-Key", identity);
        if (proposalRevision < 1 || request == null) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_REQUEST_INVALID",
                    "A positive Proposal revision and binding command are required.");
        }
        StoredCapabilityProposalDraft storedProposal = proposals.findRevision(
                        scope, proposal, proposalRevision)
                .orElseThrow(() -> notFound(identity, "Proposal revision was not found."));
        String proposalFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, storedProposal.draft(), 4 * 1024 * 1024);
        if (!proposalFingerprint.equals(storedProposal.draftFingerprint())
                || !proposalFingerprint.equals(request.expectedProposalDraftFingerprint())) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.IMPLEMENTATION_PROPOSAL_STALE",
                    "The Proposal differs from the reviewed exact revision.");
        }
        if (!storedProposal.draft().readinessBlockers().isEmpty()) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.IMPLEMENTATION_PROPOSAL_NOT_READY",
                    "The Proposal still has authoring readiness blockers.");
        }
        StoredCapabilityProposalSimulation simulation = exactPassedSimulation(
                scope, proposal, proposalRevision, request, identity);
        String contractFingerprint = ProtocolFingerprint.of(
                mapper, storedProposal.draft().candidateContract());
        Instant now = clock.instant();
        if (!storedProposal.draft().expiresAt().isAfter(now)) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.IMPLEMENTATION_PROPOSAL_EXPIRED",
                    "The reviewed Proposal revision has expired.");
        }
        CapabilityImplementationRuntimePort.Descriptor descriptor = runtime.describe(
                        scope, request.runtimePortRef())
                .orElseThrow(() -> unavailable(identity,
                        "RG.BUSINESS_MIRROR.IMPLEMENTATION_RUNTIME_UNAVAILABLE",
                        "The requested implementation runtime port is unavailable."));
        verifyDescriptor(descriptor, request, contractFingerprint, scope, now, identity);
        Instant expiry = earlier(storedProposal.draft().expiresAt(), descriptor.expiresAt());
        CapabilityImplementationBinding binding = new CapabilityImplementationBinding("", id, 1,
                "", scope, new MirrorArtifactRef("CAPABILITY_PROPOSAL_DRAFT", proposal,
                proposalRevision, proposalFingerprint), simulation.evidence().artifactRef(),
                request.targetCapabilityRef(), contractFingerprint, descriptor.runtimePortRef(),
                descriptor.runtimePortFingerprint(), descriptor.implementationVersion(),
                descriptor.implementationFingerprint(), descriptor.runtimeOwner(),
                descriptor.allowedRegions(), descriptor.readOnly(), descriptor.stateless(),
                descriptor.attestedAt(), expiry, now).seal(mapper);
        String requestFingerprint = ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", CapabilityImplementationBindingRequest.SCHEMA_VERSION,
                "proposalId", proposal,
                "proposalRevision", proposalRevision,
                "bindingId", id,
                "request", request));
        VisualRunEvidenceSeal attestation = signer.seal(binding.fingerprint(),
                "proposal-implementation-binding:" + scope.tenantId() + ":" + id);
        if (!attestation.signed()
                || !signer.verify(attestation, binding.fingerprint()).valid()) {
            throw unavailable(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_SIGNER_UNAVAILABLE",
                    "The implementation binding could not be signed and verified.");
        }
        try {
            return bindings.create(new StoredCapabilityImplementationBinding("", requestFingerprint,
                    binding, attestation));
        } catch (IllegalArgumentException materialConflict) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_CONFLICT",
                    "The binding id is already bound to different immutable material.");
        }
    }

    /** Reads and independently verifies one exact implementation binding. */
    public StoredCapabilityImplementationBinding find(
            String bindingId, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireIdentity(identity,
                "CAPABILITY_IMPLEMENTATION",
                "CAPABILITY_CONFORMANCE",
                "GOVERNANCE_EVIDENCE_INGESTION");
        StoredCapabilityImplementationBinding stored = bindings.find(
                        scope, requireId(bindingId, "bindingId", identity))
                .orElseThrow(() -> notFound(identity, "Implementation binding was not found."));
        try {
            stored.verify(mapper, signer);
            return stored;
        } catch (RuntimeException invalid) {
            throw unavailable(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_INVALID",
                    "The stored implementation binding failed independent verification.");
        }
    }

    private StoredCapabilityProposalSimulation exactPassedSimulation(
            CapabilitySnapshot.Scope scope,
            String proposalId,
            long proposalRevision,
            CapabilityImplementationBindingRequest request,
            IntegrationRequestContext identity) {
        CapabilityProposalSimulationRepository.State state = simulations.find(
                        scope, proposalId, proposalRevision)
                .filter(value -> value.status()
                        == CapabilityProposalSimulationRepository.Status.COMPLETED)
                .orElseThrow(() -> conflict(identity,
                        "RG.BUSINESS_MIRROR.IMPLEMENTATION_SIMULATION_REQUIRED",
                        "A completed Proposal simulation is required before implementation binding."));
        StoredCapabilityProposalSimulation simulation = state.result();
        try {
            simulation.verify(mapper, signer);
        } catch (RuntimeException invalid) {
            throw unavailable(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_SIMULATION_INVALID",
                    "Proposal simulation evidence failed independent verification.");
        }
        CapabilityProposalSimulationEvidence evidence = simulation.evidence();
        if (!evidence.artifactRef().equals(request.simulationEvidenceRef())
                || evidence.status() != CapabilityProposalSimulationEvidence.Status.PASSED
                || !evidence.targetCapabilityRef().equals(request.targetCapabilityRef())) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.IMPLEMENTATION_SIMULATION_STALE",
                    "The selected simulation is failed or does not bind the requested target.");
        }
        return simulation;
    }

    private static void verifyDescriptor(
            CapabilityImplementationRuntimePort.Descriptor descriptor,
            CapabilityImplementationBindingRequest request,
            String contractFingerprint,
            CapabilitySnapshot.Scope scope,
            Instant now,
            IntegrationRequestContext identity) {
        if (descriptor == null
                || !request.runtimePortRef().equals(descriptor.runtimePortRef())
                || !request.expectedRuntimePortFingerprint()
                .equals(descriptor.runtimePortFingerprint())
                || !request.expectedImplementationVersion()
                .equals(descriptor.implementationVersion())
                || !request.expectedImplementationFingerprint()
                .equals(descriptor.implementationFingerprint())
                || !contractFingerprint.equals(descriptor.candidateContractFingerprint())
                || descriptor.runtimeOwner() == null || descriptor.runtimeOwner().isBlank()
                || !descriptor.readOnly() || !descriptor.stateless()
                || descriptor.attestedAt() == null || descriptor.attestedAt().isAfter(now)
                || descriptor.expiresAt() == null || !descriptor.expiresAt().isAfter(now)
                || !descriptor.allowedRegions().contains(scope.region())) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.IMPLEMENTATION_RUNTIME_DRIFT",
                    "The runtime descriptor is stale, unsafe, or differs from reviewed material.");
        }
    }

    private static CapabilitySnapshot.Scope requireIdentity(
            IntegrationRequestContext identity, String... allowedPurposes) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!Set.of("test", "staging").contains(identity.environmentId().toLowerCase())
                || !Set.of(allowedPurposes).contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_FORBIDDEN",
                    "Implementation binding requires an authorized test or staging workload.",
                    identity.correlationId(), Map.of()));
        }
        return new CapabilitySnapshot.Scope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static String requireId(
            String value, String field, IntegrationRequestContext identity) {
        String exact = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_ID_INVALID",
                    field + " is invalid.");
        }
        return exact;
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity, String title) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_NOT_FOUND",
                title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }
}
