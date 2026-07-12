package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory governed payload vault used by tests and local overrides. */
public final class InMemoryVisualRunPayloadRepository implements VisualRunPayloadRepository {

    private final Map<String, VisualRunPayloadStatus> states = new LinkedHashMap<>();
    private final Map<String, VisualRunPayloadSnapshot> payloads = new LinkedHashMap<>();
    private final Map<String, List<VisualPayloadLifecycleEvent>> eventLog = new LinkedHashMap<>();
    private final VisualPayloadGovernancePolicy policy;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    public InMemoryVisualRunPayloadRepository(VisualPayloadGovernancePolicy policy,
                                              VisualEvidenceSigner signer) {
        this(policy, signer, Clock.systemUTC());
    }

    InMemoryVisualRunPayloadRepository(VisualPayloadGovernancePolicy policy,
                                       VisualEvidenceSigner signer,
                                       Clock clock) {
        this.policy = policy;
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public synchronized Capture capture(VisualGraphRunRecord record) {
        if (states.containsKey(record.runId())) {
            throw failure(VisualPayloadGovernanceException.Reason.ALREADY_EXISTS,
                    "Run payload already exists: " + record.runId());
        }
        Instant observedAt = record.createdAt().equals(Instant.EPOCH) ? clock.instant() : record.createdAt();
        VisualPayloadGovernancePolicy.Decision decision = policy.decide(record, observedAt);
        VisualRunPayloadSnapshot snapshot = VisualRunPayloadSnapshot.from(record);
        VisualPayloadRetentionDescriptor descriptor;
        String eventType;
        String state;
        if (decision.retain()) {
            descriptor = new VisualPayloadRetentionDescriptor("", decision.policyId(), decision.policyVersion(),
                    decision.classification(), decision.requiredClearance(), decision.requiredGroups(),
                    VisualPayloadRetentionDescriptor.RETAINED, "payload:" + record.runId(),
                    snapshot.payloadFingerprint(), observedAt, observedAt.plus(decision.retention()));
            payloads.put(record.runId(), snapshot);
            eventType = VisualPayloadLifecycleEvent.CAPTURED;
            state = VisualRunPayloadStatus.AVAILABLE;
        } else {
            descriptor = VisualPayloadRetentionDescriptor.notRetained(decision.policyId(),
                    decision.policyVersion(), decision.classification(), decision.requiredClearance(),
                    decision.requiredGroups(), observedAt);
            eventType = VisualPayloadLifecycleEvent.NOT_RETAINED;
            state = VisualRunPayloadStatus.NOT_RETAINED;
        }
        VisualPayloadLifecycleEvent event = signedEvent(record.runId(), "capture:" + record.runId(), 1, eventType, observedAt,
                "resource-gateway", "policy-decision", "", snapshot.payloadFingerprint(), "");
        VisualRunPayloadStatus status = new VisualRunPayloadStatus("", record.runId(), record.tenantId(),
                record.namespace(), record.environment(), state, 1, "", observedAt, descriptor, event);
        states.put(record.runId(), status);
        eventLog.put(record.runId(), new ArrayList<>(List.of(event)));
        return new Capture(descriptor, status);
    }

    @Override
    public synchronized Access access(String runId, Instant observedAt) {
        VisualRunPayloadStatus status = requireStatus(runId);
        Instant now = observedAt == null ? clock.instant() : observedAt;
        if (status.expiredAt(now)) {
            status = purge(runId, expiryRequest(status), "resource-gateway-retention", "retention-expired", now);
        }
        verify(status.latestEvent());
        VisualRunPayloadSnapshot payload = payloads.get(runId);
        if (payload != null && !payload.payloadFingerprint().equals(status.descriptor().payloadFingerprint())) {
            throw failure(VisualPayloadGovernanceException.Reason.CORRUPT,
                    "Run payload fingerprint no longer matches immutable evidence");
        }
        return new Access(status, payload);
    }

    @Override
    public synchronized Optional<VisualRunPayloadStatus> status(String runId) {
        return Optional.ofNullable(states.get(runId));
    }

    @Override
    public synchronized List<VisualPayloadLifecycleEvent> events(String runId) {
        return eventLog.getOrDefault(runId, List.of()).stream()
                .sorted(Comparator.comparingLong(VisualPayloadLifecycleEvent::revision)).toList();
    }

    @Override
    public synchronized VisualRunPayloadStatus placeHold(String runId,
                                                        String requestId,
                                                        String holdId,
                                                        String actorId,
                                                        String reason,
                                                        Instant occurredAt) {
        VisualRunPayloadStatus current = requireStatus(runId);
        VisualRunPayloadStatus replay = idempotent(current, requestId, VisualPayloadLifecycleEvent.HOLD_PLACED,
                holdId, actorId, reason);
        if (replay != null) return replay;
        requireText(holdId, "holdId");
        if (VisualRunPayloadStatus.LEGAL_HOLD.equals(current.state())) {
            if (current.activeHoldId().equals(holdId)) {
                return current;
            }
            throw failure(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT,
                    "A different legal hold is already active");
        }
        if (!VisualRunPayloadStatus.AVAILABLE.equals(current.state())) {
            throw failure(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT,
                    "Legal hold requires an available payload");
        }
        return transition(current, VisualRunPayloadStatus.LEGAL_HOLD, VisualPayloadLifecycleEvent.HOLD_PLACED,
                requestId, holdId, holdId, actorId, reason, occurredAt);
    }

    @Override
    public synchronized VisualRunPayloadStatus releaseHold(String runId,
                                                          String requestId,
                                                          String holdId,
                                                          String actorId,
                                                          String reason,
                                                          Instant occurredAt) {
        VisualRunPayloadStatus current = requireStatus(runId);
        VisualRunPayloadStatus replay = idempotent(current, requestId, VisualPayloadLifecycleEvent.HOLD_RELEASED,
                holdId, actorId, reason);
        if (replay != null) return replay;
        if (!VisualRunPayloadStatus.LEGAL_HOLD.equals(current.state())
                || !current.activeHoldId().equals(holdId)) {
            throw failure(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT,
                    "The requested legal hold is not active");
        }
        VisualRunPayloadStatus released = transition(current, VisualRunPayloadStatus.AVAILABLE,
                VisualPayloadLifecycleEvent.HOLD_RELEASED, requestId, "", holdId, actorId, reason, occurredAt);
        Instant now = occurredAt == null ? clock.instant() : occurredAt;
        return released.expiredAt(now)
                ? purge(runId, requestId + ":expiry-purge", actorId,
                "retention-expired-after-hold-release", now) : released;
    }

    @Override
    public synchronized VisualRunPayloadStatus purge(String runId,
                                                    String requestId,
                                                    String actorId,
                                                    String reason,
                                                    Instant occurredAt) {
        VisualRunPayloadStatus current = requireStatus(runId);
        VisualRunPayloadStatus replay = idempotent(current, requestId, VisualPayloadLifecycleEvent.PURGED,
                "", actorId, reason);
        if (replay != null) return replay;
        if (VisualRunPayloadStatus.LEGAL_HOLD.equals(current.state())) {
            throw failure(VisualPayloadGovernanceException.Reason.LEGAL_HOLD_ACTIVE,
                    "Payload cannot be purged while legal hold is active");
        }
        if (VisualRunPayloadStatus.PURGED.equals(current.state())
                || VisualRunPayloadStatus.NOT_RETAINED.equals(current.state())) {
            return current;
        }
        payloads.remove(runId);
        return transition(current, VisualRunPayloadStatus.PURGED, VisualPayloadLifecycleEvent.PURGED,
                requestId, "", "", actorId, reason, occurredAt);
    }

    @Override
    public synchronized int purgeExpired(Instant observedAt, int limit) {
        Instant now = observedAt == null ? clock.instant() : observedAt;
        int boundedLimit = Math.max(1, Math.min(limit, 1000));
        List<String> expired = states.values().stream().filter(status -> status.expiredAt(now))
                .map(VisualRunPayloadStatus::runId).limit(boundedLimit).toList();
        expired.forEach(runId -> {
            VisualRunPayloadStatus status = requireStatus(runId);
            purge(runId, expiryRequest(status), "resource-gateway-retention", "retention-expired", now);
        });
        return expired.size();
    }

    @Override
    public VisualPayloadGovernancePolicy.Descriptor policyDescriptor() {
        return policy.descriptor();
    }

    private VisualRunPayloadStatus transition(VisualRunPayloadStatus current,
                                              String newState,
                                              String eventType,
                                              String requestId,
                                              String activeHoldId,
                                              String eventHoldId,
                                              String actorId,
                                              String reason,
                                              Instant occurredAt) {
        Instant at = occurredAt == null ? clock.instant() : occurredAt;
        long revision = current.revision() + 1;
        VisualPayloadLifecycleEvent event = signedEvent(current.runId(), requestId, revision, eventType, at,
                actorId, reason, eventHoldId, current.descriptor().payloadFingerprint(),
                current.latestEvent().eventFingerprint());
        VisualRunPayloadStatus updated = new VisualRunPayloadStatus("", current.runId(), current.tenantId(),
                current.namespace(), current.environment(), newState, revision, activeHoldId, at,
                current.descriptor(), event);
        states.put(current.runId(), updated);
        eventLog.computeIfAbsent(current.runId(), ignored -> new ArrayList<>()).add(event);
        return updated;
    }

    private VisualPayloadLifecycleEvent signedEvent(String runId,
                                                    String requestId,
                                                    long revision,
                                                    String type,
                                                    Instant occurredAt,
                                                    String actorId,
                                                    String reason,
                                                    String holdId,
                                                    String payloadFingerprint,
                                                    String previousEventFingerprint) {
        VisualPayloadLifecycleEvent unsigned = new VisualPayloadLifecycleEvent("", UUID.randomUUID().toString(),
                requestId, runId, revision, type, occurredAt, actorId, reason, holdId, payloadFingerprint,
                previousEventFingerprint, VisualRunEvidenceSeal.unsigned());
        VisualRunEvidenceSeal seal = signer.seal(unsigned.eventFingerprint());
        if (!seal.signed()) {
            throw failure(VisualPayloadGovernanceException.Reason.SIGNING_UNAVAILABLE,
                    "Payload lifecycle signing authority is unavailable");
        }
        return unsigned.withEvidenceSeal(seal);
    }

    private void verify(VisualPayloadLifecycleEvent event) {
        if (event == null || !signer.verify(event.evidenceSeal(), event.eventFingerprint()).valid()) {
            throw failure(VisualPayloadGovernanceException.Reason.CORRUPT,
                    "Payload lifecycle signature verification failed");
        }
    }

    private VisualRunPayloadStatus requireStatus(String runId) {
        VisualRunPayloadStatus status = states.get(runId);
        if (status == null) {
            throw failure(VisualPayloadGovernanceException.Reason.NOT_FOUND,
                    "Run payload was not found: " + runId);
        }
        return status;
    }

    private VisualRunPayloadStatus idempotent(VisualRunPayloadStatus current,
                                              String requestId,
                                              String expectedType,
                                              String expectedHoldId,
                                              String expectedActorId,
                                              String expectedReason) {
        requireText(requestId, "requestId");
        VisualPayloadLifecycleEvent existing = eventLog.getOrDefault(current.runId(), List.of()).stream()
                .filter(event -> requestId.equals(event.requestId())).findFirst().orElse(null);
        if (existing == null) return null;
        if (expectedType.equals(existing.type())
                && expectedHoldId.equals(existing.holdId())
                && normalized(expectedActorId).equals(existing.actorId())
                && normalized(expectedReason).equals(existing.reason())) return current;
        throw failure(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT,
                "Payload lifecycle request id identifies different immutable content");
    }

    private static String expiryRequest(VisualRunPayloadStatus status) {
        return "expiry:" + status.runId() + ":" + status.descriptor().expiresAt();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static VisualPayloadGovernanceException failure(VisualPayloadGovernanceException.Reason reason,
                                                            String message) {
        return new VisualPayloadGovernanceException(reason, message);
    }
}
