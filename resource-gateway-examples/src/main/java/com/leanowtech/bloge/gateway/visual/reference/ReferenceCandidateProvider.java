package com.leanowtech.bloge.gateway.visual.reference;

import java.util.List;
import java.util.Objects;

/**
 * Provider SPI for an authoritative metadata catalog. The gateway owns no asset snapshot;
 * providers supply a generation-stamped view and perform exact permission-aware resolution.
 */
public interface ReferenceCandidateProvider {

    ProviderSnapshot snapshot(SearchRequest request);

    ProviderResolution resolve(ResolveRequest request);

    record ProviderSnapshot(long generation, List<ReferenceCandidate> candidates) {
        public ProviderSnapshot {
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    record ProviderResolution(ResolveResult.Status status, ReferenceCandidate candidate) {
        public ProviderResolution {
            Objects.requireNonNull(status, "status");
            if ((status == ResolveResult.Status.RESOLVED || status == ResolveResult.Status.DRIFTED)
                    && candidate == null) {
                throw new IllegalArgumentException("resolved or drifted provider result must contain a candidate");
            }
            if (status != ResolveResult.Status.RESOLVED
                    && status != ResolveResult.Status.DRIFTED
                    && candidate != null) {
                throw new IllegalArgumentException("not-found or forbidden provider result must not contain a candidate");
            }
        }
    }
}
