package com.leanowtech.bloge.gateway.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Thread-safe in-memory trust log for focused tests and embedded development. */
public final class InMemoryEvidenceKeySetTrustPublicationRepository
        implements EvidenceKeySetTrustPublicationRepository {
    private final Map<String, TreeMap<Long, EvidenceKeySetTrustPublication>> logs = new HashMap<>();
    private final Map<String, Set<String>> revoked = new HashMap<>();

    @Override
    public synchronized EvidenceKeySetTrustPublication append(
            EvidenceKeySetTrustPublication publication) {
        TreeMap<Long, EvidenceKeySetTrustPublication> log = logs.computeIfAbsent(
                publication.logId(), ignored -> new TreeMap<>());
        EvidenceKeySetTrustPublication existing = log.get(publication.sequence());
        if (existing != null) {
            if (existing.publicationFingerprint().equals(publication.publicationFingerprint())) {
                return existing;
            }
            throw new EvidenceKeySetTrustChain.ChainViolation(
                    EvidenceKeySetTrustChain.Reason.SEQUENCE_FORK);
        }
        EvidenceKeySetTrustPublication previous = log.isEmpty() ? null : log.lastEntry().getValue();
        Set<String> revokedPins = revoked.computeIfAbsent(publication.logId(), ignored -> new HashSet<>());
        EvidenceKeySetTrustChain.requireNext(previous, publication, revokedPins);
        log.put(publication.sequence(), publication);
        publication.pins().stream()
                .filter(pin -> pin.state() == EvidenceKeySetTrustPublication.PinState.REVOKED)
                .map(EvidenceKeySetTrustPublication.SnapshotPin::snapshotFingerprint)
                .forEach(revokedPins::add);
        return publication;
    }

    @Override
    public synchronized Optional<EvidenceKeySetTrustPublication> latest(String logId) {
        TreeMap<Long, EvidenceKeySetTrustPublication> log = logs.get(normalized(logId));
        return log == null || log.isEmpty() ? Optional.empty() : Optional.of(log.lastEntry().getValue());
    }

    @Override
    public synchronized List<EvidenceKeySetTrustPublication> readAfter(
            String logId, long afterSequence, int limit) {
        TreeMap<Long, EvidenceKeySetTrustPublication> log = logs.get(normalized(logId));
        if (log == null) {
            return List.of();
        }
        return new ArrayList<>(log.tailMap(Math.max(0, afterSequence), false).values()).stream()
                .limit(Math.max(1, Math.min(EvidenceKeySetTrustBundle.MAX_PUBLICATIONS, limit)))
                .toList();
    }

    @Override
    public synchronized long highWaterSequence(String logId) {
        return latest(logId).map(EvidenceKeySetTrustPublication::sequence).orElse(0L);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
