package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredCapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshotIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure compiler that overlays one Proposal contract into an exact capability closure. */
public final class CapabilityProposalSimulationCompiler {
    private final ObjectMapper mapper;

    public CapabilityProposalSimulationCompiler(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Replaces one external capability and reseals every affected ancestor without modifying DSL.
     */
    public Result compile(
            StoredCapabilityProposalDraft proposal,
            CapabilityClosure baseClosure,
            MirrorArtifactRef targetRef,
            String runtimeGraphName,
            String runtimeGraphFingerprint) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(baseClosure, "baseClosure");
        Objects.requireNonNull(targetRef, "targetRef");
        CapabilityClosureIntegrity.verify(mapper, baseClosure);
        CapabilityProposalDraft draft = proposal.draft();
        requireSimulationContract(draft);

        Map<MirrorArtifactRef, CapabilitySnapshot> original = new LinkedHashMap<>();
        baseClosure.snapshots().forEach(snapshot -> original.put(
                CapabilityClosureIntegrity.reference(snapshot), snapshot));
        CapabilitySnapshot target = original.get(targetRef);
        if (target == null || target.kind() != CapabilitySnapshot.Kind.EXTERNAL) {
            throw new IllegalArgumentException(
                    "Proposal target must resolve to one external capability in the base closure");
        }
        if (!draft.scope().equals(target.scope())) {
            throw new IllegalArgumentException("Proposal and target capability Scope differ");
        }

        CapabilitySnapshot temporary = temporaryCapability(proposal, target);
        Map<MirrorArtifactRef, CapabilitySnapshot> rewritten = new HashMap<>();
        rewritten.put(targetRef, temporary);
        CapabilitySnapshot root = rewrite(baseClosure.rootRef(), baseClosure.rootRef(), original,
                rewritten, runtimeGraphName, runtimeGraphFingerprint, proposal);
        MirrorArtifactRef rootRef = CapabilityClosureIntegrity.reference(root);

        Map<MirrorArtifactRef, CapabilitySnapshot> materialized = new LinkedHashMap<>(original);
        rewritten.values().forEach(snapshot -> materialized.put(
                CapabilityClosureIntegrity.reference(snapshot), snapshot));
        List<CapabilitySnapshot> reachable = new ArrayList<>();
        collect(rootRef, materialized, new java.util.HashSet<>(), reachable);
        CapabilityClosure simulated = CapabilityClosureIntegrity.seal(mapper,
                new CapabilityClosure("", rootRef, reachable, ""));
        simulated.snapshots().forEach(snapshot -> {
            if (snapshot.contract().effect().mode() != EffectContract.Mode.READ_ONLY) {
                throw new IllegalArgumentException(
                        "Proposal simulation v1 admits only read-only capability closures");
            }
        });
        return new Result(baseClosure, simulated, targetRef,
                CapabilityClosureIntegrity.reference(temporary));
    }

    private CapabilitySnapshot rewrite(
            MirrorArtifactRef ref,
            MirrorArtifactRef rootRef,
            Map<MirrorArtifactRef, CapabilitySnapshot> original,
            Map<MirrorArtifactRef, CapabilitySnapshot> rewritten,
            String runtimeGraphName,
            String runtimeGraphFingerprint,
            StoredCapabilityProposalDraft proposal) {
        CapabilitySnapshot cached = rewritten.get(ref);
        if (cached != null) {
            return cached;
        }
        CapabilitySnapshot source = original.get(ref);
        if (source == null) {
            throw new IllegalArgumentException("base closure has an unresolved dependency");
        }
        boolean changed = ref.equals(rootRef);
        List<CapabilitySnapshot.Dependency> dependencies = new ArrayList<>();
        for (CapabilitySnapshot.Dependency dependency : source.dependencies()) {
            CapabilitySnapshot child = rewrite(dependency.capabilityRef(), rootRef, original,
                    rewritten, runtimeGraphName, runtimeGraphFingerprint, proposal);
            MirrorArtifactRef childRef = CapabilityClosureIntegrity.reference(child);
            changed = changed || !childRef.equals(dependency.capabilityRef());
            dependencies.add(new CapabilitySnapshot.Dependency(dependency.nodeId(), childRef,
                    dependency.required(), dependency.conditions()));
        }
        if (!changed) {
            rewritten.put(ref, source);
            return source;
        }
        CapabilitySnapshot.Source assetSource = source.source();
        if (ref.equals(rootRef)) {
            assetSource = new CapabilitySnapshot.Source(
                    CapabilitySnapshot.SourceKind.GRAPH,
                    required(runtimeGraphName, "runtimeGraphName"),
                    required(runtimeGraphFingerprint, "runtimeGraphFingerprint"));
        }
        CapabilitySnapshot material = new CapabilitySnapshot("", source.capabilityId(),
                source.revision(), "", source.kind(), source.scope(), assetSource,
                source.contract(), source.runtime(), dependencies, source.ownership(),
                CapabilitySnapshot.Lifecycle.DRAFT, proposal.draft().provenance(),
                proposal.updatedAt());
        CapabilitySnapshot sealed = CapabilitySnapshotIntegrity.seal(mapper, material);
        rewritten.put(ref, sealed);
        return sealed;
    }

    private CapabilitySnapshot temporaryCapability(
            StoredCapabilityProposalDraft proposal, CapabilitySnapshot target) {
        CapabilityProposalDraft draft = proposal.draft();
        MirrorArtifactRef policy = draft.simulationRuntimeBinding().resolverPolicyRef();
        String suffix = target.fingerprint().substring("sha256:".length(),
                "sha256:".length() + 12);
        String prefix = draft.proposalId();
        int maximumPrefix = Math.max(1, 470 - suffix.length());
        if (prefix.length() > maximumPrefix) {
            prefix = prefix.substring(0, maximumPrefix);
        }
        String capabilityId = "proposal:" + prefix + ":r" + draft.revision() + ":" + suffix;
        CapabilitySnapshot.RuntimeBinding runtime = new CapabilitySnapshot.RuntimeBinding(
                "SIMULATION_ONLY", policy.id(), policy.fingerprint(), true,
                List.of("No production implementation is bound to this Proposal."));
        CapabilitySnapshot.Ownership ownership = new CapabilitySnapshot.Ownership(
                draft.businessIntent().owner(), target.ownership().team(),
                target.ownership().escalation());
        CapabilitySnapshot material = new CapabilitySnapshot("", capabilityId, 1, "",
                CapabilitySnapshot.Kind.EXTERNAL, draft.scope(), target.source(),
                draft.candidateContract(), runtime, List.of(), ownership,
                CapabilitySnapshot.Lifecycle.DRAFT, draft.provenance(), proposal.updatedAt());
        return CapabilitySnapshotIntegrity.seal(mapper, material);
    }

    private static void collect(
            MirrorArtifactRef ref,
            Map<MirrorArtifactRef, CapabilitySnapshot> materialized,
            java.util.Set<MirrorArtifactRef> visited,
            List<CapabilitySnapshot> result) {
        CapabilitySnapshot snapshot = materialized.get(ref);
        if (snapshot == null || !visited.add(ref)) {
            return;
        }
        result.add(snapshot);
        snapshot.dependencies().forEach(dependency -> collect(
                dependency.capabilityRef(), materialized, visited, result));
    }

    private static void requireSimulationContract(CapabilityProposalDraft draft) {
        if (!draft.readinessBlockers().isEmpty()) {
            throw new IllegalArgumentException("Proposal is not ready for simulation");
        }
        if (draft.candidateContract().effect().mode() != EffectContract.Mode.READ_ONLY
                || draft.candidateContract().stateModelRef() != null
                || draft.candidateContract().security().requiresSecrets()
                || !draft.candidateContract().security().allowedRegions()
                .contains(draft.scope().region())) {
            throw new IllegalArgumentException(
                    "Proposal simulation v1 requires read-only, stateless, secret-free regional contracts");
        }
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return exact;
    }

    /** Immutable base and overlay identities consumed by planning and evidence projection. */
    public record Result(
            CapabilityClosure baseClosure,
            CapabilityClosure simulatedClosure,
            MirrorArtifactRef targetCapabilityRef,
            MirrorArtifactRef temporaryCapabilityRef
    ) {
    }
}
