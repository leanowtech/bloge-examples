package com.leanowtech.bloge.gateway.visual.publication;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory publication repository for tests and local overrides.
 */
public class InMemoryVisualGraphPublicationRepository implements VisualGraphPublicationRepository {

    private final Map<String, VisualGraphPublication> publications = new ConcurrentHashMap<>();

    @Override
    public Collection<VisualGraphPublication> all() {
        return publications.values().stream()
                .sorted(Comparator.comparing(VisualGraphPublication::createdAt)
                        .thenComparing(VisualGraphPublication::publicationId))
                .toList();
    }

    @Override
    public Optional<VisualGraphPublication> find(String publicationId) {
        return Optional.ofNullable(publications.get(publicationId));
    }

    @Override
    public VisualGraphPublication create(VisualGraphPublication publication) {
        String publicationId = publication.publicationId().isBlank()
                ? UUID.randomUUID().toString()
                : publication.publicationId();
        VisualGraphPublication stored = publication.withIdentity(publicationId, Instant.now());
        VisualGraphPublication previous = publications.putIfAbsent(publicationId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Publication already exists: " + publicationId);
        }
        return stored;
    }
}
