package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of user-provided visual operator libraries.
 */
public interface OperatorLibraryRegistry {

    /**
     * @return all registered libraries
     */
    Collection<OperatorLibrary> all();

    /**
     * Finds one library.
     *
     * @param libraryId library id
     * @return library when present
     */
    Optional<OperatorLibrary> find(String libraryId);

    /**
     * Registers or replaces a library.
     *
     * @param library library to store
     * @return stored library
     */
    OperatorLibrary upsert(OperatorLibrary library);

    /**
     * Deletes a library.
     *
     * @param libraryId library id
     */
    void delete(String libraryId);

    /**
     * @param includeDeprecated include deprecated libraries
     * @return all operators contributed by catalog-visible libraries
     */
    default List<OperatorDefinition> operators(boolean includeDeprecated) {
        return all().stream()
                .filter(library -> library.visibleInCatalog(includeDeprecated))
                .flatMap(library -> library.operators().stream())
                .filter(Objects::nonNull)
                .filter(OperatorLibraryRegistry::hasConcretePorts)
                .toList();
    }

    private static boolean hasConcretePorts(OperatorDefinition operator) {
        return operator.ports().inputs().stream().allMatch(Objects::nonNull)
                && operator.ports().outputs().stream().allMatch(Objects::nonNull);
    }

    /**
     * @return empty registry for tests
     */
    static OperatorLibraryRegistry empty() {
        return new OperatorLibraryRegistry() {
            @Override
            public Collection<OperatorLibrary> all() {
                return List.of();
            }

            @Override
            public Optional<OperatorLibrary> find(String libraryId) {
                return Optional.empty();
            }

            @Override
            public OperatorLibrary upsert(OperatorLibrary library) {
                throw new UnsupportedOperationException("empty registry is read-only");
            }

            @Override
            public void delete(String libraryId) {
                throw new UnsupportedOperationException("empty registry is read-only");
            }
        };
    }
}
