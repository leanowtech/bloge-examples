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
     * Lists immutable registry snapshots for a library, newest first.
     *
     * @param libraryId library id
     * @return recorded revision snapshots
     */
    List<OperatorLibraryRevision> revisions(String libraryId);

    /**
     * Finds one immutable registry snapshot.
     *
     * @param libraryId library id
     * @param revision revision number
     * @return matching revision when present
     */
    Optional<OperatorLibraryRevision> findRevision(String libraryId, long revision);

    /**
     * Registers or replaces a library.
     *
     * @param library library to store
     * @return stored library
     */
    default OperatorLibrary upsert(OperatorLibrary library) {
        return upsert(library, OperatorLibraryRevision.RevisionMetadata.empty());
    }

    /**
     * Registers or replaces a library with audit metadata.
     *
     * @param library library to store
     * @param metadata change audit metadata
     * @return stored library
     */
    OperatorLibrary upsert(OperatorLibrary library, OperatorLibraryRevision.RevisionMetadata metadata);

    /**
     * Restores a previously recorded library snapshot as the latest current library.
     *
     * @param revision revision snapshot to restore
     * @return restored library
     */
    default OperatorLibrary restore(OperatorLibraryRevision revision) {
        return restore(revision, OperatorLibraryRevision.RevisionMetadata.empty());
    }

    /**
     * Restores a previously recorded library snapshot with audit metadata.
     *
     * @param revision revision snapshot to restore
     * @param metadata change audit metadata
     * @return restored library
     */
    OperatorLibrary restore(OperatorLibraryRevision revision, OperatorLibraryRevision.RevisionMetadata metadata);

    /**
     * Deletes a library.
     *
     * @param libraryId library id
     */
    default void delete(String libraryId) {
        delete(libraryId, OperatorLibraryRevision.RevisionMetadata.empty());
    }

    /**
     * Deletes a library with audit metadata.
     *
     * @param libraryId library id
     * @param metadata change audit metadata
     */
    void delete(String libraryId, OperatorLibraryRevision.RevisionMetadata metadata);

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
            public List<OperatorLibraryRevision> revisions(String libraryId) {
                return List.of();
            }

            @Override
            public Optional<OperatorLibraryRevision> findRevision(String libraryId, long revision) {
                return Optional.empty();
            }

            @Override
            public OperatorLibrary upsert(OperatorLibrary library, OperatorLibraryRevision.RevisionMetadata metadata) {
                throw new UnsupportedOperationException("empty registry is read-only");
            }

            @Override
            public OperatorLibrary restore(OperatorLibraryRevision revision,
                                           OperatorLibraryRevision.RevisionMetadata metadata) {
                throw new UnsupportedOperationException("empty registry is read-only");
            }

            @Override
            public void delete(String libraryId, OperatorLibraryRevision.RevisionMetadata metadata) {
                throw new UnsupportedOperationException("empty registry is read-only");
            }
        };
    }
}
