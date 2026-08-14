package com.leanowtech.bloge.gateway.integration.mirror;

/** Independent customer authority boundary for source pages and connector control commands. */
public interface AuthoritativeOutcomeSourceAuthorityVerifier {
    /** @return whether the external trust roots and revocation state are currently usable */
    boolean available();

    /** Verifies source identity, generation, page seal, chain policy, and revocation state. */
    void verifyPage(AuthoritativeOutcomeSourcePage page);

    /** Verifies business/data-owner authorization of a backfill or generation revocation. */
    void verifyCommand(AuthoritativeOutcomeConnectorControlCommand command);
}
