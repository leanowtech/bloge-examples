package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Separate lifecycle owner for expirable sanitized run payloads. */
public interface VisualRunPayloadRepository {

    Capture capture(VisualGraphRunRecord identifiedRecord);

    Access access(String runId, Instant observedAt);

    Optional<VisualRunPayloadStatus> status(String runId);

    List<VisualPayloadLifecycleEvent> events(String runId);

    VisualRunPayloadStatus placeHold(String runId, String requestId, String holdId, String actorId, String reason,
                                     Instant occurredAt);

    VisualRunPayloadStatus releaseHold(String runId, String requestId, String holdId, String actorId, String reason,
                                       Instant occurredAt);

    VisualRunPayloadStatus purge(String runId, String requestId, String actorId, String reason, Instant occurredAt);

    int purgeExpired(Instant observedAt, int limit);

    VisualPayloadGovernancePolicy.Descriptor policyDescriptor();

    record Capture(VisualPayloadRetentionDescriptor descriptor, VisualRunPayloadStatus status) {
    }

    record Access(VisualRunPayloadStatus status, VisualRunPayloadSnapshot payload) {
        public boolean readable() {
            return status != null && status.readable() && payload != null;
        }
    }
}
