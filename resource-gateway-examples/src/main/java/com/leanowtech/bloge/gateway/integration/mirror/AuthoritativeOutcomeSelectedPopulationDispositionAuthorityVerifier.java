package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Independent trust boundary for legal dispositions of authoritative outcome selected members.
 *
 * <p>An implementation resolves the exact retention policy, deletion approval, and deletion
 * authority-set revision. Resource Gateway signatures attest custody and immutability; they do not
 * grant deletion authority.</p>
 */
public interface
AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier {
    /** @return whether the external disposition authority can currently be verified */
    boolean available();

    /**
     * Verifies one exact external legal-disposition closure.
     *
     * @param disposition structurally valid selected-member disposition
     * @throws RuntimeException when policy, approval, authority, scope, or lifecycle is rejected
     */
    void verify(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition);
}
