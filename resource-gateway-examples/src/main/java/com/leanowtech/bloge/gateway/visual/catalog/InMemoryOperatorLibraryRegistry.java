package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory visual operator library registry for the example application.
 */
public class InMemoryOperatorLibraryRegistry implements OperatorLibraryRegistry {

    private final Map<String, OperatorLibrary> libraries = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentHashMap<Long, OperatorLibraryRevision>> revisions = new ConcurrentHashMap<>();

    @Override
    public Collection<OperatorLibrary> all() {
        return libraries.values().stream()
                .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                .toList();
    }

    @Override
    public Optional<OperatorLibrary> find(String libraryId) {
        return Optional.ofNullable(libraries.get(libraryId));
    }

    @Override
    public List<OperatorLibraryRevision> revisions(String libraryId) {
        return revisions.getOrDefault(libraryId, new ConcurrentHashMap<>()).values().stream()
                .sorted(Comparator.comparingLong(OperatorLibraryRevision::revision).reversed())
                .toList();
    }

    @Override
    public Optional<OperatorLibraryRevision> findRevision(String libraryId, long revision) {
        return Optional.ofNullable(revisions.getOrDefault(libraryId, new ConcurrentHashMap<>()).get(revision));
    }

    @Override
    public synchronized OperatorLibrary upsert(OperatorLibrary library,
                                               OperatorLibraryRevision.RevisionMetadata metadata) {
        ensureNoDuplicateOperatorRefs(library);
        String action = libraries.containsKey(library.libraryId())
                ? OperatorLibraryRevision.ACTION_REPLACE
                : OperatorLibraryRevision.ACTION_CREATE;
        libraries.put(library.libraryId(), library);
        rememberRevision(OperatorLibraryRevision.record(library, nextRevision(library.libraryId()), action,
                metadata));
        return library;
    }

    @Override
    public synchronized OperatorLibrary restore(OperatorLibraryRevision revision,
                                                OperatorLibraryRevision.RevisionMetadata metadata) {
        OperatorLibrary library = requireRestorableLibrary(revision);
        ensureNoDuplicateOperatorRefs(library);
        libraries.put(library.libraryId(), library);
        rememberRevision(OperatorLibraryRevision.restore(library, nextRevision(library.libraryId()),
                revision.revision(), metadata));
        return library;
    }

    @Override
    public synchronized void delete(String libraryId, OperatorLibraryRevision.RevisionMetadata metadata) {
        OperatorLibrary library = libraries.remove(libraryId);
        if (library != null) {
            rememberRevision(OperatorLibraryRevision.record(library, nextRevision(libraryId),
                    OperatorLibraryRevision.ACTION_DELETE, metadata));
        }
    }

    private void ensureNoDuplicateOperatorRefs(OperatorLibrary library) {
        Map<String, String> ownerByOperatorRef = new LinkedHashMap<>();
        libraries.values().stream()
                .filter(existing -> !existing.libraryId().equals(library.libraryId()))
                .forEach(existing -> existing.operators().stream()
                        .filter(Objects::nonNull)
                        .forEach(operator -> ownerByOperatorRef.put(operator.operatorRef(), existing.libraryId())));
        for (OperatorDefinition operator : library.operators()) {
            if (operator == null) {
                continue;
            }
            String existingOwner = ownerByOperatorRef.get(operator.operatorRef());
            if (existingOwner != null) {
                throw new IllegalArgumentException("operatorRef '%s' already provided by library '%s'"
                        .formatted(operator.operatorRef(), existingOwner));
            }
        }
    }

    private long nextRevision(String libraryId) {
        return revisions.getOrDefault(libraryId, new ConcurrentHashMap<>()).keySet().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L) + 1;
    }

    private void rememberRevision(OperatorLibraryRevision revision) {
        revisions.computeIfAbsent(revision.libraryId(), ignored -> new ConcurrentHashMap<>())
                .put(revision.revision(), revision);
    }

    private static OperatorLibrary requireRestorableLibrary(OperatorLibraryRevision revision) {
        if (revision == null || revision.library() == null) {
            throw new IllegalArgumentException("Operator library revision cannot be restored because it has no library snapshot");
        }
        return revision.library();
    }
}
