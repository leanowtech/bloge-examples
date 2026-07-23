package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Fixed public-only interoperability fixture for isolation-authority key-set verification.
 *
 * @param publication detached threshold-signed publication
 * @param expectedBinding immutable local identity and policy binding
 * @param bootstrapRoots pinned public bootstrap-root keys
 * @param verificationTime deterministic fixture verification time
 */
public record MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture(
        JsonNode publication,
        MirrorDeploymentIsolationAuthorityKeySetBinding expectedBinding,
        List<MirrorDeploymentIsolationRootVerificationKey> bootstrapRoots,
        Instant verificationTime
) {
    /** Defensively copies mutable JSON and collection values. */
    public MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture {
        if (publication == null || expectedBinding == null || bootstrapRoots == null
                || bootstrapRoots.isEmpty() || verificationTime == null) {
            throw new IllegalArgumentException(
                    "isolation authority key-set compatibility fixture is incomplete");
        }
        publication = publication.deepCopy();
        bootstrapRoots = List.copyOf(bootstrapRoots);
    }

    /**
     * Returns a detached copy safe for caller mutation tests.
     *
     * @return independent fixture value
     */
    public MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture detachedCopy() {
        return new MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture(
                publication.deepCopy(), expectedBinding, bootstrapRoots, verificationTime);
    }
}
