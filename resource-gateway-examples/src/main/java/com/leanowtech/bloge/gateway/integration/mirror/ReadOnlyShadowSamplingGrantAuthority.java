package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * Online data-governance authority for one exact read-only sampling grant generation.
 *
 * <p>The durable request reserves an ordinal, but only this online authority can establish that
 * the referenced grant is still active, has the same total budget, and currently permits
 * pressure within the returned shared limits.</p>
 */
public interface ReadOnlyShadowSamplingGrantAuthority {
    /**
     * Resolves and independently verifies an exact grant generation.
     *
     * @param scope exact enterprise scope
     * @param grantRef exact grant revision and content address
     * @return current verified grant observation
     */
    Grant resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef grantRef);

    /** @return whether fresh grant resolution can currently be attempted */
    boolean available();

    /** Creates a fail-closed placeholder. */
    static ReadOnlyShadowSamplingGrantAuthority unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Current payload-free sampling decision.
     *
     * @param scope exact enterprise scope
     * @param grantRef exact owner-approved grant
     * @param maximumSamples exact logical-sample ceiling
     * @param validFrom inclusive validity start
     * @param expiresAt exclusive validity end
     * @param limits shared external-system pressure policy
     * @param authorityAttestationRef independently signed authority decision
     * @param observedAt authority observation time
     */
    record Grant(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef grantRef,
            long maximumSamples,
            Instant validFrom,
            Instant expiresAt,
            ReadOnlyShadowExecutionGuard.Limits limits,
            MirrorArtifactRef authorityAttestationRef,
            Instant observedAt
    ) {
        /** Validates a bounded, time-ordered, payload-free grant decision. */
        public Grant {
            scope = Objects.requireNonNull(scope, "scope");
            grantRef = kind(
                    grantRef,
                    "SHADOW_SAMPLING_GRANT",
                    "grantRef");
            validFrom = Objects.requireNonNull(
                    validFrom, "validFrom");
            expiresAt = Objects.requireNonNull(
                    expiresAt, "expiresAt");
            limits = Objects.requireNonNull(
                    limits, "limits");
            authorityAttestationRef = kind(
                    authorityAttestationRef,
                    "SHADOW_SAMPLING_GRANT_ATTESTATION",
                    "authorityAttestationRef");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            if (maximumSamples < 1
                    || maximumSamples > 1_000_000_000L
                    || !expiresAt.isAfter(validFrom)) {
                throw new IllegalArgumentException(
                        "read-only Shadow sampling grant is invalid");
            }
        }
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements ReadOnlyShadowSamplingGrantAuthority {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public Grant resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef grantRef) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(grantRef, "grantRef");
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
