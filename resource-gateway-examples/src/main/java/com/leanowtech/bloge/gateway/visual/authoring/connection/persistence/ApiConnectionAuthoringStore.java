package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringCommandClaimStore;

/**
 * Lifecycle-complete Connection authoring boundary.
 *
 * <p>A facade accepts this single seam rather than unrelated claim and
 * projection stores. Implementations therefore own one command authority for
 * claim, stage, commit, failure, and historical reads; a JDBC implementation
 * must construct all delegates over the same {@code DataSource}. This shape
 * prevents a valid claim from being paired accidentally with a different
 * in-memory journal.</p>
 */
public interface ApiConnectionAuthoringStore extends ApiConnectionCommitStore, AuthoringCommandClaimStore {
}
