package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
        ensureNoDuplicateOperatorRefs(library);
        libraries.put(library.libraryId(), library);
        return library;
    }

    @Override
    public void delete(String libraryId) {
        libraries.remove(libraryId);
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
}
