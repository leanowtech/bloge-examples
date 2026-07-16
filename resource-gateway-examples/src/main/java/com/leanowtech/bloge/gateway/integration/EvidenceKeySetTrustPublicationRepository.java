package com.leanowtech.bloge.gateway.integration;

import java.util.List;
import java.util.Optional;

/** Append-only persistence boundary for externally authorized evidence trust publications. */
public interface EvidenceKeySetTrustPublicationRepository {

    /**
     * Atomically appends one successor or returns the byte-identical publication already at its sequence.
     *
     * @param publication fingerprint-verified, quorum-verified publication
     * @return durable publication at the requested sequence
     */
    EvidenceKeySetTrustPublication append(EvidenceKeySetTrustPublication publication);

    /** @return current head for one log, if any */
    Optional<EvidenceKeySetTrustPublication> latest(String logId);

    /**
     * Reads a bounded contiguous page after a caller checkpoint.
     *
     * @param logId trust log identity
     * @param afterSequence exclusive sequence cursor
     * @param limit page size
     * @return publications in ascending sequence order
     */
    List<EvidenceKeySetTrustPublication> readAfter(String logId, long afterSequence, int limit);

    /** @return current log sequence, or zero when empty */
    long highWaterSequence(String logId);

    /** @return whether durable append/read operations are available */
    default boolean available() {
        return true;
    }
}
