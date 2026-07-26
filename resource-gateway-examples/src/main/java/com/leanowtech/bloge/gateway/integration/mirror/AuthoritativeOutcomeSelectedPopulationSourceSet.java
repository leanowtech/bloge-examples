package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.List;
import java.util.Objects;

/**
 * Canonical source-set addressing shared by completeness projection and durable cut validation.
 */
final class AuthoritativeOutcomeSelectedPopulationSourceSet {
    static final String OBSERVATION_DOMAIN =
            "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_CURRENT_HEAD_SET_V1";
    static final String DISPOSITION_DOMAIN =
            "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_LEGAL_DISPOSITION_SET_V1";
    private static final int MAXIMUM_SOURCE_SET_BYTES =
            32 * 1024 * 1024;

    private AuthoritativeOutcomeSelectedPopulationSourceSet() {
    }

    /**
     * Addresses one member-ordered exact artifact set.
     *
     * @param mapper canonical protocol mapper
     * @param domain closed observation or disposition domain
     * @param populationRef exact selected-population root
     * @param entries entries ordered by unique global member ordinal
     * @return canonical set fingerprint
     */
    static String fingerprint(
            ObjectMapper mapper,
            String domain,
            MirrorArtifactRef populationRef,
            List<Entry> entries) {
        String exactDomain = Objects.requireNonNull(
                domain, "domain");
        if (!OBSERVATION_DOMAIN.equals(exactDomain)
                && !DISPOSITION_DOMAIN.equals(exactDomain)) {
            throw new IllegalArgumentException(
                    "unsupported selected-population source-set domain");
        }
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new SourceSet(
                        exactDomain,
                        Objects.requireNonNull(
                                populationRef,
                                "populationRef"),
                        entries == null
                                ? List.of()
                                : List.copyOf(entries)),
                MAXIMUM_SOURCE_SET_BYTES);
    }

    /** Exact member position and immutable source reference. */
    record Entry(
            long globalOrdinal,
            MirrorArtifactRef reference
    ) {
        /** Requires one positive position and concrete source reference. */
        Entry {
            if (globalOrdinal < 1) {
                throw new IllegalArgumentException(
                        "selected-population source ordinal must be positive");
            }
            reference = Objects.requireNonNull(
                    reference, "reference");
        }
    }

    private record SourceSet(
            String domain,
            MirrorArtifactRef populationRef,
            List<Entry> entries
    ) {
    }
}
