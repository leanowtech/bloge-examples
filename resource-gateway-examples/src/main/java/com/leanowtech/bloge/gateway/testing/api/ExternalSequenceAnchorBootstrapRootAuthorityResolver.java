package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal;

import java.util.List;
import java.util.Objects;

/**
 * Resolves runtime signing adapters for one already approved bootstrap-root proposal.
 *
 * <p>The resolver is called only after the recovery worker owns a database execution fence. It may
 * obtain short-lived provider clients or opaque credential references, but must return only signing
 * authority ports. The ceremony service recomputes preflight from their public descriptors and
 * compares it with the immutable approved proposal before requesting any signature, so a resolver
 * cannot silently narrow or replace the approved signer cohort.</p>
 *
 * <p>Authentication, secret retrieval, HSM/KMS attestation, and provider authorization remain the
 * embedding system's responsibility. Resolver failures are deliberately collapsed into a bounded
 * signer-binding failure and provider diagnostics are not retained by the ceremony journal.</p>
 */
@FunctionalInterface
public interface ExternalSequenceAnchorBootstrapRootAuthorityResolver {

    /**
     * Resolves both exact signer roles for one immutable approved proposal.
     *
     * @param proposal database-approved public-only proposal and frozen preflight
     * @return runtime authority ports for old-root authorization and incoming-root possession
     */
    AuthoritySet resolve(CeremonyProposal proposal);

    /**
     * Immutable pair of role-specific runtime authority ports.
     *
     * @param authorizingAuthorities old-root authorization signers
     * @param incomingAuthorities incoming-root possession signers
     */
    record AuthoritySet(
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> authorizingAuthorities,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> incomingAuthorities) {

        /** Defensively copies both role-specific authority collections. */
        public AuthoritySet {
            authorizingAuthorities = List.copyOf(Objects.requireNonNull(
                    authorizingAuthorities, "authorizingAuthorities"));
            incomingAuthorities = List.copyOf(Objects.requireNonNull(
                    incomingAuthorities, "incomingAuthorities"));
        }
    }
}
