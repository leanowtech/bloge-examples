package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory graph draft repository for the example application.
 */
public class InMemoryGraphDraftRepository implements GraphDraftRepository {

    private final Map<String, GraphDraft> drafts = new ConcurrentHashMap<>();

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
    public GraphDraft save(GraphDraft draft) {
        VisualSecretGuard.requireNoDraftSecrets(draft);
        String draftId = draft.draftId().isBlank() ? UUID.randomUUID().toString() : draft.draftId();
        long nextRevision = Math.max(draft.revision(), drafts.getOrDefault(draftId,
                draft.withIdentity(draftId, 0)).revision()) + 1;
        GraphDraft stored = draft.withIdentity(draftId, nextRevision);
        drafts.put(draftId, stored);
        return stored;
    }

    @Override
    public synchronized Optional<GraphDraft> saveIfRevision(String draftId, long expectedRevision, GraphDraft draft) {
        VisualSecretGuard.requireNoDraftSecrets(draft);
        GraphDraft current = drafts.get(draftId);
        if (current == null || current.revision() != expectedRevision) {
            return Optional.empty();
        }
        GraphDraft stored = draft.withIdentity(draftId, expectedRevision + 1);
        drafts.put(draftId, stored);
        return Optional.of(stored);
    }

    @Override
    public void delete(String draftId) {
        drafts.remove(draftId);
    }
}
