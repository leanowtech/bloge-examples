package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Detached baseline connector backed only by one exact signed source binding.
 *
 * <p>The historical {@code observedAt} remains signed inside the nested baseline artifact. The
 * returned source {@code completedAt} is the terminal time of this exact repository resolution,
 * which keeps the current online authority window truthful.</p>
 */
public final class DetachedReadOnlyShadowBaselineConnector
        implements ReadOnlyShadowBaselineConnector {
    private final ReadOnlyShadowSourceBindingService bindings;
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy;
    private final Clock clock;

    /**
     * Creates the detached baseline connector.
     *
     * @param bindings exact signed source-binding resolver
     * @param policy built-in content-addressed normalization policy
     * @param clock trusted connector clock
     */
    public DetachedReadOnlyShadowBaselineConnector(
            ReadOnlyShadowSourceBindingService bindings,
            PayloadFreeEqualityReadOnlyShadowPolicy policy,
            Clock clock) {
        this.bindings = Objects.requireNonNull(
                bindings, "bindings");
        this.policy = Objects.requireNonNull(
                policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean ready() {
        try {
            return bindings.ready() && policy.ready();
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
        ReadOnlyShadowSourceBinding.BaselineObservation source =
                binding.baseline();
        return new ReadOnlyShadowConnectorObservation(
                new ReadOnlyShadowComparison.SourceObservation(
                        ReadOnlyShadowComparison
                                .SourceRole.BASELINE,
                        binding.baselineArtifactRef(),
                        binding.scope(),
                        binding.targetCapabilityRef(),
                        binding.requestContextFingerprint(),
                        source.semanticResultFingerprint(),
                        resolvedAt,
                        source.evidenceClass(),
                        source.evidenceComplete()),
                binding.comparisonPolicyRef(),
                source.normalizedFactFingerprints(),
                source.writeCredentialExposed(),
                source.writeAttemptCount());
    }
}
