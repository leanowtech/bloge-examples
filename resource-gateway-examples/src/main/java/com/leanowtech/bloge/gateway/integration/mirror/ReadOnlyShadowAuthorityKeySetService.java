package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Root-trust admission boundary for managed Shadow authority key-set publications.
 *
 * <p>The service resolves local policy before append, verifies exact scope/kind/issuer binding,
 * threshold signatures, freshness, and the current durable floor, then delegates atomic chain and
 * irreversible key-lifecycle enforcement to the repository. Invalid material can never poison the
 * current head.</p>
 */
public final class ReadOnlyShadowAuthorityKeySetService {
    private final ReadOnlyShadowAuthorityKeySetRepository publications;
    private final ReadOnlyShadowAuthorityKeySetTrustPolicyProvider trustPolicies;
    private final ReadOnlyShadowAuthorityKeySetIntegrity integrity;
    private final Clock clock;

    /**
     * Creates the managed key-set admission service.
     *
     * @param publications durable key-set log and floor
     * @param trustPolicies independently governed bootstrap trust
     * @param integrity threshold-signature and binding verifier
     * @param clock trusted admission clock
     */
    public ReadOnlyShadowAuthorityKeySetService(
            ReadOnlyShadowAuthorityKeySetRepository publications,
            ReadOnlyShadowAuthorityKeySetTrustPolicyProvider trustPolicies,
            ReadOnlyShadowAuthorityKeySetIntegrity integrity,
            Clock clock) {
        this.publications = Objects.requireNonNull(publications, "publications");
        this.trustPolicies = Objects.requireNonNull(trustPolicies, "trustPolicies");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies and atomically appends one legal key-set generation.
     *
     * @param publication untrusted root-signed publication
     * @return committed publication or identical idempotent replay
     * @throws AdmissionRejected when bootstrap trust or signature verification fails
     */
    public ReadOnlyShadowAuthorityKeySetPublication publish(
            ReadOnlyShadowAuthorityKeySetPublication publication) {
        ReadOnlyShadowAuthorityKeySetPublication exact =
                Objects.requireNonNull(publication, "publication");
        var material = exact.material();
        ReadOnlyShadowAuthorityKeySetTrustPolicyProvider.TrustPolicy policy =
                resolvePolicy(material.scope(), material.publicationKind(), material.issuer())
                        .orElseThrow(() -> rejected(Reason.TRUST_POLICY_UNAVAILABLE));
        if (!policy.binding().matches(material)) {
            throw rejected(Reason.BINDING_MISMATCH);
        }
        var stream = ReadOnlyShadowAuthorityKeySetRepository.StreamIdentity.from(exact);
        var floor = publications.floor(stream).orElse(null);
        var verification = integrity.verify(
                exact, policy.binding(), policy.roots(), floor, clock.instant());
        if (!verification.verified()) {
            throw rejected(map(verification.outcome()));
        }
        return publications.append(exact);
    }

    /**
     * Reads and re-verifies the current trusted key-set head.
     *
     * @param scope complete enterprise scope
     * @param publicationKind exact authority protocol
     * @param issuer exact delegated authority
     * @return current trusted publication, or empty for an unknown stream
     */
    public Optional<ReadOnlyShadowAuthorityKeySetPublication> current(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
            String issuer) {
        CapabilitySnapshot.Scope exactScope =
                ReadOnlyShadowAuthoritySeal.scope(scope, "scope");
        ReadOnlyShadowAuthorityIntegrity.PublicationKind exactKind =
                Objects.requireNonNull(publicationKind, "publicationKind");
        String exactIssuer = ReadOnlyShadowAuthoritySeal.identifier(issuer, "issuer");
        Optional<ReadOnlyShadowAuthorityKeySetTrustPolicyProvider.TrustPolicy> policy =
                resolvePolicy(exactScope, exactKind, exactIssuer);
        if (policy.isEmpty()) {
            return Optional.empty();
        }
        var stream = new ReadOnlyShadowAuthorityKeySetRepository.StreamIdentity(
                exactScope, exactKind, exactIssuer, policy.get().binding().keySetId());
        ReadOnlyShadowAuthorityKeySetPublication publication =
                publications.latest(stream).orElse(null);
        var floor = publications.floor(stream).orElse(null);
        if (publication == null || floor == null
                || !integrity.verify(publication, policy.get().binding(), policy.get().roots(),
                floor, clock.instant()).verified()) {
            return Optional.empty();
        }
        return Optional.of(publication);
    }

    private Optional<ReadOnlyShadowAuthorityKeySetTrustPolicyProvider.TrustPolicy> resolvePolicy(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind kind,
            String issuer) {
        try {
            return trustPolicies.available()
                    ? trustPolicies.resolve(scope, kind, issuer) : Optional.empty();
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private static Reason map(ReadOnlyShadowAuthorityKeySetIntegrity.Outcome outcome) {
        return switch (outcome) {
            case INVALID -> Reason.SIGNATURE_INVALID;
            case ROOTS_UNAVAILABLE -> Reason.TRUST_POLICY_UNAVAILABLE;
            case POLICY_REJECTED -> Reason.ROOT_POLICY_REJECTED;
            case IDENTITY_MISMATCH -> Reason.BINDING_MISMATCH;
            case WINDOW_REJECTED -> Reason.WINDOW_REJECTED;
            case CHAIN_REJECTED -> Reason.CHAIN_REJECTED;
            case VERIFIED -> throw new IllegalArgumentException(
                    "verified publication cannot produce admission rejection");
        };
    }

    private static AdmissionRejected rejected(Reason reason) {
        return new AdmissionRejected(reason);
    }

    /** Stable payload-free admission rejection categories. */
    public enum Reason {
        /** Governed bootstrap trust is unavailable. */
        TRUST_POLICY_UNAVAILABLE,
        /** Publication scope, kind, issuer, stream, threshold, or policy drifted. */
        BINDING_MISMATCH,
        /** Canonical content or a bootstrap-root signature is invalid. */
        SIGNATURE_INVALID,
        /** Root lifecycle, identity, or threshold policy rejected the publication. */
        ROOT_POLICY_REJECTED,
        /** Publication is not active at the trusted admission time. */
        WINDOW_REJECTED,
        /** Publication conflicts with the durable anti-rollback floor. */
        CHAIN_REJECTED
    }

    /** Bounded admission failure without keys or publication material in its message. */
    public static final class AdmissionRejected extends RuntimeException {
        private final Reason reason;

        /** Creates one bounded key-set admission rejection. */
        public AdmissionRejected(Reason reason) {
            super("Read-only Shadow authority key-set admission rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable machine-readable rejection reason */
        public Reason reason() {
            return reason;
        }
    }
}
