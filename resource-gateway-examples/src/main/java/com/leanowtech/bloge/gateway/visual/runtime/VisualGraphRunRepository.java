package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.Collection;
import java.util.Optional;

/**
 * Repository for persisted visual graph run history records.
 */
public interface VisualGraphRunRepository {

    /**
     * @return all run records, newest first
     */
    Collection<VisualGraphRunRecord> all();

    /**
     * Queries run records.
     *
     * @param query run history query
     * @return matching run records, newest first
     */
    default Collection<VisualGraphRunRecord> query(VisualGraphRunQuery query) {
        return VisualGraphRunQuery.apply(all(), query);
    }

    /**
     * Finds a run record.
     *
     * @param runId run id
     * @return run record when present
     */
    Optional<VisualGraphRunRecord> find(String runId);

    /**
     * Creates a new immutable run record.
     *
     * @param record run record
     * @return stored run record with assigned id
     */
    VisualGraphRunRecord create(VisualGraphRunRecord record);

    /** Signing authority used for records created by this repository. */
    default VisualEvidenceSigner evidenceSigner() {
        return VisualEvidenceSigner.unavailable();
    }
}
