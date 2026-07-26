package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Detached candidate connector for one exact independently verified Mirror evidence bundle.
 *
 * <p>The connector never executes a plan and never resolves a mutable draft. It re-resolves the
 * source binding, fetches the exact candidate run, verifies nested content addresses and detached
 * signature, closes all binding coordinates, and then applies the exact built-in payload-free
 * normalization policy.</p>
 */
public final class DetachedReadOnlyShadowCandidateConnector
        implements ReadOnlyShadowCandidateConnector {
    private final ReadOnlyShadowSourceBindingService bindings;
    private final MirrorEvidenceRepository evidence;
    private final MirrorEvidenceIntegrityService integrity;
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy;
    private final Clock clock;

    /**
     * Creates the detached candidate connector.
     *
     * @param bindings exact signed source-binding resolver
     * @param evidence append-only signed Mirror evidence repository
     * @param integrity independent Mirror evidence verification boundary
     * @param policy built-in content-addressed normalization policy
     * @param clock trusted connector clock
     */
    public DetachedReadOnlyShadowCandidateConnector(
            ReadOnlyShadowSourceBindingService bindings,
            MirrorEvidenceRepository evidence,
            MirrorEvidenceIntegrityService integrity,
            PayloadFreeEqualityReadOnlyShadowPolicy policy,
            Clock clock) {
        this.bindings = Objects.requireNonNull(
                bindings, "bindings");
        this.evidence = Objects.requireNonNull(
                evidence, "evidence");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.policy = Objects.requireNonNull(
                policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean ready() {
        try {
            return bindings.ready()
                    && integrity.available()
                    && policy.ready();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public ReadOnlyShadowConnectorObservation observe(
            ReadOnlyShadowConnectorInvocation invocation) {
        Instant resolvedAt = clock.instant();
        ReadOnlyShadowSourceBinding binding =
                DetachedReadOnlyShadowSourceSupport.resolve(
                        invocation,
                        bindings,
                        policy,
                        resolvedAt);
        MirrorEvidenceBundle bundle =
                evidence.find(
                        binding.scope(),
                        binding.candidateEvidenceRef().id())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "detached candidate evidence does not exist"));
        MirrorEvidenceIntegrityService.VerifiedBundle verified =
                integrity.requireVerified(bundle);
        MirrorEvidenceBundle exactBundle = verified.bundle();
        MirrorRunEvidence source = exactBundle.evidence();
        MirrorArtifactRef exactRef = new MirrorArtifactRef(
                "MIRROR_EVIDENCE_BUNDLE",
                source.runId(),
                1,
                exactBundle.bundleFingerprint());
        if (!binding.candidateEvidenceRef().equals(exactRef)
                || !binding.scope().equals(source.scope())
                || !binding.candidatePlanRef().id().equals(
                source.planId())
                || !binding.candidatePlanRef().fingerprint().equals(
                source.planFingerprint())
                || !binding.targetCapabilityRef().equals(
                source.rootCapability())
                || !binding.requestContextFingerprint().equals(
                source.requestContextFingerprint())) {
            throw new IllegalArgumentException(
                    "detached candidate evidence differs from the source binding");
        }
        return new ReadOnlyShadowConnectorObservation(
                new ReadOnlyShadowComparison.SourceObservation(
                        ReadOnlyShadowComparison
                                .SourceRole.CANDIDATE,
                        exactRef,
                        source.scope(),
                        source.rootCapability(),
                        source.requestContextFingerprint(),
                        source.semanticResultFingerprint(),
                        resolvedAt,
                        source.evidenceClass(),
                        evidenceComplete(source.status())),
                binding.comparisonPolicyRef(),
                policy.normalize(source),
                false,
                0);
    }

    private static boolean evidenceComplete(
            MirrorRunEvidence.Status status) {
        return switch (status) {
            case EVIDENCE_INCOMPLETE,
                 CONTROL_PLAN_UNAVAILABLE -> false;
            default -> true;
        };
    }
}
