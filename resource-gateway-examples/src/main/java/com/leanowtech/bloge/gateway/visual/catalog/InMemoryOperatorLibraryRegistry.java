package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory visual operator library registry for the example application.
 */
public class InMemoryOperatorLibraryRegistry implements OperatorLibraryRegistry {

    private final Map<String, OperatorLibrary> libraries = new ConcurrentHashMap<>();

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
    public OperatorLibrary upsert(OperatorLibrary library) {
        libraries.put(library.libraryId(), library);
        return library;
    }

    @Override
    public void delete(String libraryId) {
        libraries.remove(libraryId);
    }
}
