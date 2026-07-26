package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Online current-head source for signed read-only Shadow authority publications.
 *
 * <p>The source returns only the current head for a complete enterprise scope and stream id.
 * Historical lookup is deliberately absent from the runtime interface: accepting a caller-pinned
 * historical generation would let stale grants or positive kill-switch observations survive a
 * successor publication.</p>
 */
public interface ReadOnlyShadowAuthorityPublicationSource {
    /**
     * Resolves the current sampling-grant stream head.
     *
     * @param scope exact execution scope
     * @param grantId stable grant stream identity
     * @return current signed publication
     */
    Optional<ReadOnlyShadowSamplingGrantPublication>
    currentSamplingGrant(
            CapabilitySnapshot.Scope scope,
            String grantId);

    /**
     * Resolves the current kill-switch stream head.
     *
     * @param scope exact execution scope
     * @param switchId stable switch stream identity
     * @return current signed publication
     */
    Optional<ReadOnlyShadowKillSwitchPublication>
    currentKillSwitch(
            CapabilitySnapshot.Scope scope,
            String switchId);

    /**
     * Resolves the current shared guard-policy stream head.
     *
     * @param guardScope exact authority-owned pressure scope
     * @param policyId stable guard-policy stream identity
     * @return current signed publication
     */
    Optional<ReadOnlyShadowGuardPolicyPublication>
    currentGuardPolicy(
            CapabilitySnapshot.Scope guardScope,
            String policyId);

    /** @return whether fresh current-head lookup can currently be attempted */
    boolean available();

    /** Creates a fail-closed publication source. */
    static ReadOnlyShadowAuthorityPublicationSource
    unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements ReadOnlyShadowAuthorityPublicationSource {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public Optional<ReadOnlyShadowSamplingGrantPublication>
        currentSamplingGrant(
                CapabilitySnapshot.Scope scope,
                String grantId) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(grantId, "grantId");
            return Optional.empty();
        }

        @Override
        public Optional<ReadOnlyShadowKillSwitchPublication>
        currentKillSwitch(
                CapabilitySnapshot.Scope scope,
                String switchId) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(switchId, "switchId");
            return Optional.empty();
        }

        @Override
        public Optional<ReadOnlyShadowGuardPolicyPublication>
        currentGuardPolicy(
                CapabilitySnapshot.Scope guardScope,
                String policyId) {
            Objects.requireNonNull(
                    guardScope, "guardScope");
            Objects.requireNonNull(policyId, "policyId");
            return Optional.empty();
        }

        @Override
        public boolean available() {
            return false;
        }
    }
}
