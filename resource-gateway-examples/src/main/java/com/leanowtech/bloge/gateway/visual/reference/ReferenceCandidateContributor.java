package com.leanowtech.bloge.gateway.visual.reference;

import java.util.List;

/**
 * Extension point for metadata-only catalogs that live outside Resource Gateway's core stores.
 * Implementations must return candidates for the requested scope and must not include payloads,
 * credentials, fixtures, or evidence data.
 */
public interface ReferenceCandidateContributor {

    /**
     * Stable ordering key used when more than one contributor emits the same coordinate.
     *
     * @return a stable, non-blank contributor id
     */
    default String contributorId() {
        return getClass().getName();
    }

    /**
     * Returns metadata candidates visible to the requested scope.
     *
     * @param scope authenticated request scope
     * @return zero or more metadata-only candidates
     */
    List<ReferenceCandidate> contribute(ReferenceScope scope);
}
