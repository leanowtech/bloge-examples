package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * Online authority for an exact read-only Shadow kill-switch generation.
 *
 * <p>The switch is observed both before and after paired execution. A stale cached {@code true}
 * value is not sufficient: implementations must independently verify the exact generation and
 * fail closed when the authority cannot establish current state.</p>
 */
public interface ReadOnlyShadowKillSwitchAuthority {
    /**
     * Resolves the current state of one exact switch generation.
     *
     * @param scope exact enterprise scope
     * @param killSwitchRef exact switch revision and content address
     * @return current verified state
     */
    State resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef killSwitchRef);

    /** @return whether fresh switch resolution can currently be attempted */
    boolean available();

    /** Creates a fail-closed placeholder. */
    static ReadOnlyShadowKillSwitchAuthority unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Current payload-free switch decision.
     *
     * @param scope exact enterprise scope
     * @param killSwitchRef exact switch generation
     * @param enabled whether new and in-flight read-only Shadow work remains allowed
     * @param effectiveAt inclusive decision time
     * @param expiresAt exclusive authority freshness deadline
     * @param authorityAttestationRef independently signed switch decision
     * @param observedAt authority observation time
     */
    record State(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef killSwitchRef,
            boolean enabled,
            Instant effectiveAt,
            Instant expiresAt,
            MirrorArtifactRef authorityAttestationRef,
            Instant observedAt
    ) {
        /** Validates one time-ordered payload-free switch decision. */
        public State {
            scope = Objects.requireNonNull(scope, "scope");
            killSwitchRef = kind(
                    killSwitchRef,
                    "SHADOW_KILL_SWITCH_STATE",
                    "killSwitchRef");
            effectiveAt = Objects.requireNonNull(
                    effectiveAt, "effectiveAt");
            expiresAt = Objects.requireNonNull(
                    expiresAt, "expiresAt");
            authorityAttestationRef = kind(
                    authorityAttestationRef,
                    "SHADOW_KILL_SWITCH_ATTESTATION",
                    "authorityAttestationRef");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            if (!expiresAt.isAfter(effectiveAt)
                    || !killSwitchRef.id().equals(
                    authorityAttestationRef.id())
                    || killSwitchRef.revision()
                    != authorityAttestationRef.revision()) {
                throw new IllegalArgumentException(
                        "read-only Shadow kill-switch state is invalid");
            }
        }
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements ReadOnlyShadowKillSwitchAuthority {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public State resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef killSwitchRef) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(
                    killSwitchRef, "killSwitchRef");
            throw new ReadOnlyShadowDataPlane.Failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!expected.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " has an invalid artifact kind");
        }
        return exact;
    }
}
