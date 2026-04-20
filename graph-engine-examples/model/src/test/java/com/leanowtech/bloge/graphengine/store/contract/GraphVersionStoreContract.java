package com.leanowtech.bloge.graphengine.store.contract;

import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import com.leanowtech.bloge.graphengine.store.GraphVersionQuery;
import com.leanowtech.bloge.graphengine.store.GraphVersionStore;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.leanowtech.bloge.graphengine.store.contract.ContractTestSupport.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral contract for all {@link GraphVersionStore} implementations.
 */
public abstract class GraphVersionStoreContract {

    protected abstract GraphVersionStore createStore();

    @Test
    void createGetAndGetByDefinitionAndVersionRoundTrip() {
        GraphVersionStore store = createStore();
        GraphVersion version = version("ver-1", "def-1", "1.0.0");
        store.create(version);

        assertEquals("1.0.0", store.get("ver-1").orElseThrow().version());
        assertEquals("ver-1", store.getByDefinitionAndVersion("def-1", "1.0.0").orElseThrow().versionId());
    }

    @Test
    void queryFiltersByStatus() {
        GraphVersionStore store = createStore();
        store.create(version("ver-1", "def-1", "1.0.0"));
        GraphVersion published = store.updateStatus("ver-1", GraphVersionStatus.PUBLISHED, 0);
        store.create(version("ver-2", "def-1", "1.1.0"));

        assertEquals(1, store.query(new GraphVersionQuery("def-1", Set.of(GraphVersionStatus.PUBLISHED), 0, 10)).size());
        assertNotNull(published.publishedAt());
    }

    @Test
    void updateStatusIncrementsRevision() {
        GraphVersionStore store = createStore();
        store.create(version("ver-1", "def-1", "1.0.0"));

        GraphVersion published = store.updateStatus("ver-1", GraphVersionStatus.PUBLISHED, 0);
        assertEquals(1, published.revision());
        assertEquals(GraphVersionStatus.PUBLISHED, published.status());
    }

    @Test
    void duplicateSemanticVersionIsRejected() {
        GraphVersionStore store = createStore();
        store.create(version("ver-1", "def-1", "1.0.0"));

        GraphEngineStoreException error = assertThrows(GraphEngineStoreException.class,
                () -> store.create(version("ver-2", "def-1", "1.0.0")));
        assertEquals(GraphEngineErrorCode.DUPLICATE, error.errorCode());
    }

    @Test
    void versionMismatchIsRejected() {
        GraphVersionStore store = createStore();
        store.create(version("ver-1", "def-1", "1.0.0"));

        GraphEngineStoreException error = assertThrows(GraphEngineStoreException.class,
                () -> store.updateStatus("ver-1", GraphVersionStatus.PUBLISHED, 1));
        assertEquals(GraphEngineErrorCode.VERSION_CONFLICT, error.errorCode());
    }

    @Test
    void findLatestPublishedReturnsNewestPublishedVersion() {
        GraphVersionStore store = createStore();
        store.create(version("ver-1", "def-1", "1.0.0"));
        store.updateStatus("ver-1", GraphVersionStatus.PUBLISHED, 0);
        store.create(version("ver-2", "def-1", "2.0.0"));
        store.updateStatus("ver-2", GraphVersionStatus.PUBLISHED, 0);

        assertEquals("ver-2",
                store.findLatestPublished("def-1").orElseThrow().versionId());
    }

    @Test
    void findLatestPublishedIgnoresDraftsAndArchived() {
        GraphVersionStore store = createStore();
        store.create(version("ver-1", "def-1", "1.0.0"));

        assertTrue(store.findLatestPublished("def-1").isEmpty());
    }

    @Test
    void findLatestPublishedReturnsEmptyForUnknownDefinition() {
        GraphVersionStore store = createStore();
        assertTrue(store.findLatestPublished("nonexistent").isEmpty());
    }
}
