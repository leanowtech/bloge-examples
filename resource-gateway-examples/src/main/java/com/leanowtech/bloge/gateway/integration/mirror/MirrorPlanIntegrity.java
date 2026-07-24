package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical sealing and fail-closed semantic verification for {@link MirrorPlan}. */
public final class MirrorPlanIntegrity {
    /** Maximum canonical plan size admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES = 32 * 1024 * 1024;
    /** Maximum validity of one immutable execution generation. */
    public static final Duration MAXIMUM_PLAN_TTL = Duration.ofHours(24);

    private MirrorPlanIntegrity() {
    }

    /**
     * Validates and seals exact mirror-plan material.
     *
     * @param mapper application JSON mapper
     * @param plan plan with a blank or stale fingerprint
     * @return immutable plan carrying its canonical fingerprint
     */
    public static MirrorPlan seal(ObjectMapper mapper, MirrorPlan plan) {
        Objects.requireNonNull(mapper, "mapper");
        validate(mapper, plan);
        MirrorPlan material = plan.withFingerprint("");
        return material.withFingerprint(VisualBundleFingerprint.fromCanonicalValue(
                mapper, material, MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Verifies plan semantics and its detached canonical fingerprint.
     *
     * @param mapper application JSON mapper
     * @param plan sealed plan received from storage or another process
     * @throws IllegalArgumentException when plan material is incomplete, unsafe, expired, or modified
     */
    public static void verify(ObjectMapper mapper, MirrorPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.planFingerprint().isBlank()) {
            throw new IllegalArgumentException("mirror plan is not sealed");
        }
        String expected = seal(mapper, plan).planFingerprint();
        if (!expected.equals(plan.planFingerprint())) {
            throw new IllegalArgumentException("mirror plan fingerprint mismatch");
        }
    }

    private static void validate(ObjectMapper mapper, MirrorPlan plan) {
        Objects.requireNonNull(plan, "plan");
        CapabilityClosure closure = new CapabilityClosure(CapabilityClosure.SCHEMA_VERSION,
                plan.rootCapability(), plan.capabilityClosure(), plan.capabilityClosureFingerprint());
        CapabilityClosureIntegrity.verify(mapper, closure);
        Map<MirrorArtifactRef, CapabilitySnapshot> snapshots = new HashMap<>();
        closure.snapshots().forEach(snapshot -> snapshots.put(
                CapabilityClosureIntegrity.reference(snapshot), snapshot));
        CapabilitySnapshot root = snapshots.get(plan.rootCapability());
        if (!root.scope().equals(plan.scope())) {
            throw new IllegalArgumentException("mirror plan scope must match the root capability scope");
        }
        validateTime(plan);
        validateCapabilities(plan, snapshots.values());
        validateStateModels(plan, snapshots.values());
        validateExternalBindings(plan, snapshots);
        validateServingGeneration(mapper, plan);
    }

    private static void validateTime(MirrorPlan plan) {
        if (!plan.expiresAt().isAfter(plan.compiledAt())) {
            throw new IllegalArgumentException("mirror plan expiresAt must be after compiledAt");
        }
        if (Duration.between(plan.compiledAt(), plan.expiresAt()).compareTo(MAXIMUM_PLAN_TTL) > 0) {
            throw new IllegalArgumentException("mirror plan exceeds the maximum 24-hour TTL");
        }
        if (plan.executionServices().logicalClock().isAfter(plan.expiresAt())) {
            throw new IllegalArgumentException("logicalClock must not begin after plan expiry");
        }
        if (!plan.scope().region().isBlank()
                && !plan.policy().allowedRegions().contains(plan.scope().region())) {
            throw new IllegalArgumentException("mirror plan region is absent from policy allowedRegions");
        }
    }

    private static void validateCapabilities(MirrorPlan plan,
                                             Iterable<CapabilitySnapshot> snapshots) {
        for (CapabilitySnapshot snapshot : snapshots) {
            if (!snapshot.scope().equals(plan.scope())) {
                throw new IllegalArgumentException("mirror plan capability scopes must be identical");
            }
            if (snapshot.createdAt().isAfter(plan.compiledAt())
                    || snapshot.provenance().approvedAt() != null
                    && snapshot.provenance().approvedAt().isAfter(plan.compiledAt())) {
                throw new IllegalArgumentException(
                        "mirror plan cannot predate capability creation or approval");
            }
            if ((snapshot.kind() == CapabilitySnapshot.Kind.EXTERNAL
                    && snapshot.source().sourceKind() == CapabilitySnapshot.SourceKind.GRAPH)
                    || (snapshot.kind() == CapabilitySnapshot.Kind.COMPOSED
                    && snapshot.source().sourceKind() != CapabilitySnapshot.SourceKind.GRAPH)) {
                throw new IllegalArgumentException(
                        "capability kind and authoritative source kind are inconsistent");
            }
            if (!snapshot.provenance().purpose().equals(plan.policy().authorizedPurpose())) {
                throw new IllegalArgumentException(
                        "capability provenance purpose does not match the plan purpose");
            }
            if (!snapshot.provenance().revocationRef().isBlank()
                    || snapshot.lifecycle() == CapabilitySnapshot.Lifecycle.REVOKED
                    || snapshot.lifecycle() == CapabilitySnapshot.Lifecycle.STALE) {
                throw new IllegalArgumentException("revoked or stale capability cannot enter a mirror plan");
            }
            if (!plan.policy().allowedLifecycles().contains(snapshot.lifecycle())) {
                throw new IllegalArgumentException("capability lifecycle is not admitted by mirror policy");
            }
            Instant artifactExpiry = snapshot.provenance().expiresAt();
            if (artifactExpiry != null && artifactExpiry.isBefore(plan.expiresAt())) {
                throw new IllegalArgumentException("mirror plan outlives capability provenance");
            }
            CapabilityContract.SecurityContract security = snapshot.contract().security();
            if (security.classification().ordinal()
                    > plan.policy().maximumClassification().ordinal()) {
                throw new IllegalArgumentException(
                        "capability classification exceeds mirror-plan clearance");
            }
            boolean regionAdmitted = plan.scope().region().isBlank()
                    ? security.allowedRegions().stream()
                    .anyMatch(plan.policy().allowedRegions()::contains)
                    : security.allowedRegions().contains(plan.scope().region());
            if (security.allowedRegions().isEmpty() || !regionAdmitted) {
                throw new IllegalArgumentException(
                        "capability has no region admitted by the mirror plan");
            }
            if (snapshot.contract().effect().mode() == EffectContract.Mode.UNKNOWN) {
                throw new IllegalArgumentException("unknown capability effect cannot enter a mirror plan");
            }
        }
    }

    private static void validateStateModels(MirrorPlan plan,
                                            Iterable<CapabilitySnapshot> snapshots) {
        Set<MirrorArtifactRef> expected = new LinkedHashSet<>();
        for (CapabilitySnapshot snapshot : snapshots) {
            if (snapshot.contract().stateModelRef() != null) {
                expected.add(snapshot.contract().stateModelRef());
            }
        }
        if (!expected.equals(new LinkedHashSet<>(plan.stateModelRefs()))) {
            throw new IllegalArgumentException(
                    "stateModelRefs must exactly close capability state-model dependencies");
        }
    }

    private static void validateExternalBindings(
            MirrorPlan plan,
            Map<MirrorArtifactRef, CapabilitySnapshot> snapshots) {
        Set<ExternalEdge> expected = new HashSet<>();
        snapshots.forEach((parentRef, parent) -> {
            if (parent.kind() != CapabilitySnapshot.Kind.COMPOSED) {
                return;
            }
            for (CapabilitySnapshot.Dependency dependency : parent.dependencies()) {
                CapabilitySnapshot child = snapshots.get(dependency.capabilityRef());
                if (child != null && child.kind() == CapabilitySnapshot.Kind.EXTERNAL) {
                    expected.add(new ExternalEdge(parentRef, dependency.nodeId(),
                            dependency.capabilityRef()));
                }
            }
        });

        Set<ExternalEdge> actual = new HashSet<>();
        Set<String> invocationSites = new HashSet<>();
        for (MirrorPlan.ExternalBinding binding : plan.externalBindings()) {
            ExternalEdge edge = new ExternalEdge(binding.parentCapabilityRef(),
                    binding.dependencyNodeId(), binding.capabilityRef());
            if (!actual.add(edge)) {
                throw new IllegalArgumentException("mirror plan contains a duplicate external edge binding");
            }
            if (!invocationSites.add(binding.invocationSiteId())) {
                throw new IllegalArgumentException(
                        "one runtime invocation site cannot bind multiple external dependency edges");
            }
            CapabilitySnapshot child = snapshots.get(binding.capabilityRef());
            if (child == null || child.kind() != CapabilitySnapshot.Kind.EXTERNAL) {
                throw new IllegalArgumentException(
                        "external binding must resolve to an exact external capability snapshot");
            }
            if (child.source().sourceKind() != binding.sourceKind()
                    || !child.source().sourceRef().equals(binding.sourceRef())) {
                throw new IllegalArgumentException(
                        "external binding source does not match its capability snapshot");
            }
            boolean statefulInteraction =
                    child.contract().stateModelRef() != null
                            && (child.contract().effect().mode()
                            == EffectContract.Mode.READ_ONLY
                            || child.contract().effect().mode()
                            == EffectContract.Mode
                            .VIRTUAL_MUTATION);
            if (binding.resolverOrder().contains(
                    MirrorPlan.MirrorSource.SESSION_STATE)
                    != statefulInteraction) {
                throw new IllegalArgumentException(
                        "SESSION_STATE must exactly match state-model-backed interactions");
            }
            if (child.contract().effect().mode()
                    == EffectContract.Mode.VIRTUAL_MUTATION
                    && !binding.resolverOrder().equals(
                    List.of(
                            MirrorPlan.MirrorSource.SESSION_STATE,
                            MirrorPlan.MirrorSource.ABSTAINED))) {
                throw new IllegalArgumentException(
                        "virtual mutations require terminal Session-only resolution");
            }
        }
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "externalBindings must exactly cover every external capability dependency edge");
        }
    }

    private static void validateServingGeneration(
            ObjectMapper mapper, MirrorPlan plan) {
        boolean recordedCorpus = plan.externalBindings().stream()
                .flatMap(binding -> binding.resolverOrder().stream())
                .anyMatch(source ->
                        source == MirrorPlan.MirrorSource.RECORDED_EXACT
                                || source == MirrorPlan.MirrorSource.RECORDED_TRAJECTORY
                                || source == MirrorPlan.MirrorSource.RECORDED_CLUSTER);
        MirrorServingGenerationToken token = plan.servingGeneration();
        if (recordedCorpus != (token != null)) {
            throw new IllegalArgumentException(
                    "recorded corpus resolvers require exactly one servingGeneration");
        }
        if (token == null) {
            return;
        }
        new MirrorServingGenerationIntegrity(mapper).verifyContent(token);
        if (!plan.scope().equals(token.material().scope())) {
            throw new IllegalArgumentException(
                    "servingGeneration scope must match the mirror plan");
        }
        if (!plan.policy().authorizedPurpose().equals(
                token.material().authorizedPurpose())) {
            throw new IllegalArgumentException(
                    "servingGeneration purpose must match the mirror plan");
        }
        if (token.material().issuedAt().isAfter(plan.compiledAt())) {
            throw new IllegalArgumentException(
                    "mirror plan cannot predate servingGeneration issuance");
        }
        if (token.material().expiresAt().isBefore(plan.expiresAt())) {
            throw new IllegalArgumentException(
                    "mirror plan outlives servingGeneration authority");
        }
    }

    private record ExternalEdge(
            MirrorArtifactRef parentRef,
            String nodeId,
            MirrorArtifactRef childRef
    ) {
    }
}
