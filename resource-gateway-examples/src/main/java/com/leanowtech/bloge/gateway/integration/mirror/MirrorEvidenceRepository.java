package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Append-only persistence boundary for independently verified, payload-free mirror evidence.
 *
 * <p>The repository stores only {@link MirrorEvidenceBundle} values governed by the mandatory
 * {@link MirrorEvidenceBundle.PayloadPolicy#HASH_ONLY} policy. Fixture values, replay payloads,
 * graph contexts, and runtime results are deliberately outside this boundary.</p>
 */
public interface MirrorEvidenceRepository {
    /**
     * Persists a verified terminal bundle or returns an identical already-stored run.
     *
     * @param bundle signed, independently verifiable payload-free evidence
     * @return persisted evidence bundle
     */
    MirrorEvidenceBundle create(MirrorEvidenceBundle bundle);

    /**
     * Finds one verified terminal bundle inside an exact enterprise scope.
     *
     * @param scope full authenticated enterprise scope
     * @param runId terminal mirror run identity
     * @return verified evidence when present
     */
    Optional<MirrorEvidenceBundle> find(CapabilitySnapshot.Scope scope, String runId);
}
