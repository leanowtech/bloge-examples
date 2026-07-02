package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory graph draft repository for the example application.
 */
public class InMemoryGraphDraftRepository implements GraphDraftRepository {

    private final Map<String, GraphDraft> drafts = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentHashMap<Long, GraphDraft>> revisions = new ConcurrentHashMap<>();

    @Override
    public Collection<GraphDraft> all() {
        return drafts.values().stream()
                .sorted(Comparator.comparing(GraphDraft::draftId))
                .toList();
    }

    @Override
    public Optional<GraphDraft> find(String draftId) {
        return Optional.ofNullable(drafts.get(draftId));
    }

    @Override
    public List<GraphDraft> revisions(String draftId) {
        return revisions.getOrDefault(draftId, new ConcurrentHashMap<>()).values().stream()
                .sorted(Comparator.comparingLong(GraphDraft::revision).reversed())
                .toList();
    }

    @Override
    public Optional<GraphDraft> findRevision(String draftId, long revision) {
        return Optional.ofNullable(revisions.getOrDefault(draftId, new ConcurrentHashMap<>()).get(revision));
    }

    @Override
    public List<GraphDraftHistorySummary> history() {
        return revisionDraftIds().stream()
                .map(draftId -> GraphDraftHistorySummary.from(draftId, drafts.get(draftId), revisions(draftId)))
                .sorted(Comparator.comparingLong(GraphDraftHistorySummary::latestRevision).reversed()
                        .thenComparing(GraphDraftHistorySummary::draftId))
                .toList();
    }

    @Override
    public GraphDraft save(GraphDraft draft) {
        VisualSecretGuard.requireNoDraftSecrets(draft);
        String draftId = draft.draftId().isBlank() ? UUID.randomUUID().toString() : draft.draftId();
        GraphDraft current = drafts.get(draftId);
        GraphDraft previous = current == null ? latestRevision(draftId).orElse(null) : current;
        long previousRevision = previous == null ? 0 : previous.revision();
        long nextRevision = Math.max(draft.revision(), previousRevision) + 1;
        GraphDraft stored = draft.withIdentity(draftId, nextRevision)
                .withRevisionMetadata(draft.revisionMetadata().storedFrom(
                        previous == null ? null : previous.revisionMetadata(), "Saved draft."));
        drafts.put(draftId, stored);
        rememberRevision(stored);
        return stored;
    }

    @Override
    public synchronized Optional<GraphDraft> saveIfRevision(String draftId, long expectedRevision, GraphDraft draft) {
        VisualSecretGuard.requireNoDraftSecrets(draft);
        GraphDraft current = drafts.get(draftId);
        if (current == null || current.revision() != expectedRevision) {
            return Optional.empty();
        }
        GraphDraft stored = draft.withIdentity(draftId, expectedRevision + 1)
                .withRevisionMetadata(draft.revisionMetadata().storedFrom(
                        current.revisionMetadata(), "Patched draft."));
        drafts.put(draftId, stored);
        rememberRevision(stored);
        return Optional.of(stored);
    }

    @Override
    public synchronized void delete(String draftId, GraphDraft.RevisionMetadata metadata) {
        GraphDraft current = drafts.remove(draftId);
        if (current != null) {
            rememberRevision(current.withIdentity(draftId, current.revision() + 1)
                    .withRevisionMetadata((metadata == null ? GraphDraft.RevisionMetadata.empty() : metadata)
                            .storedFrom(current.revisionMetadata(), "Deleted draft.")));
        }
    }

    private void rememberRevision(GraphDraft draft) {
        revisions.computeIfAbsent(draft.draftId(), ignored -> new ConcurrentHashMap<>())
                .put(draft.revision(), draft);
    }

    private Optional<GraphDraft> latestRevision(String draftId) {
        return revisions(draftId).stream().findFirst();
    }

    private List<String> revisionDraftIds() {
        return revisions.keySet().stream()
                .sorted()
                .toList();
    }
}
