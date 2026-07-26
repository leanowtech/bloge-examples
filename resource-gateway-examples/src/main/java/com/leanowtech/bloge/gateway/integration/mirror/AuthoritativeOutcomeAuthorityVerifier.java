package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Independent trust boundary for the business authorities named by an outcome observation.
 *
 * <p>An implementation resolves the exact authority-set revision, verifies complete member
 * watermarks and source records through its governed trust channel, and rejects missing,
 * unauthorized, revoked, or cross-scope material. Resource Gateway invokes this boundary before
 * signing and again whenever it consumes a persisted or decoded observation; its own detached seal
 * cannot make a business outcome authoritative.</p>
 */
public interface AuthoritativeOutcomeAuthorityVerifier {
    /** @return whether the exact authority-set trust chain can currently be verified */
    boolean available();

    /**
     * Verifies every external authority coordinate in one structurally valid observation.
     *
     * @param observation untrusted observation with exact authority-set lineage
     * @throws RuntimeException when any authority member, watermark, or fact is not independently
     *                          trusted
     */
    void verify(AuthoritativeOutcomeObservation observation);
}
