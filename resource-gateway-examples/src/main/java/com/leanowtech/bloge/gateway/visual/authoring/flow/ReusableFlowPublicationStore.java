package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.List;
import java.util.Optional;

/** Atomic authority for immutable Flow versions and publication idempotency. */
public interface ReusableFlowPublicationStore {
    /** Publishes one exact draft or returns its exact committed replay. */
    ReusableFlowPublishResult publish(ReusableFlowPublishIntent intent);

    /** Reads one exact immutable catalog version. */
    Optional<ReusableFlowVersion> findVersion(
            AuthoringScope scope, String publicationId, int revision);

    /** Reads the latest immutable version for one stable Flow identity. */
    Optional<ReusableFlowVersion> findLatestVersion(AuthoringScope scope, String flowId);

    /** Lists one latest immutable version per Flow for bounded catalog selection. */
    default List<ReusableFlowVersion> listLatestVersions(AuthoringScope scope, int limit) {
        return List.of();
    }
}
