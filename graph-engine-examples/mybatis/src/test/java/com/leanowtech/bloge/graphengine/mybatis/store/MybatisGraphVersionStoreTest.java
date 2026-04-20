package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.runtime.registry.GraphMigrationPolicy;
import com.leanowtech.bloge.core.schema.FieldDescriptor;
import com.leanowtech.bloge.core.schema.StructuredSchema;
import com.leanowtech.bloge.core.schema.TypedSchema;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;
import com.leanowtech.bloge.graphengine.model.TaskDefinition;
import com.leanowtech.bloge.graphengine.store.GraphVersionStore;
import com.leanowtech.bloge.graphengine.store.contract.ContractTestSupport;
import com.leanowtech.bloge.graphengine.store.contract.GraphVersionStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Contract test suite for {@link MybatisGraphVersionStore}.
 */
class MybatisGraphVersionStoreTest extends GraphVersionStoreContract {
    private final MybatisContractTestBase db = new MybatisContractTestBase();

    @BeforeEach
    void setUp() {
        db.setUp();
    }

    @Override
    protected GraphVersionStore createStore() {
        return new MybatisGraphVersionStore(db.sessionManager(), MybatisContractTestBase.CODEC, null);
    }

    @Test
    void roundTripsRichMetadata() {
        GraphVersionStore store = createStore();
        GraphVersion version = new GraphVersion(
                "ver-rich",
                "def-1",
                "1.2.0",
                "hash-rich",
                "graph sample { node a : noop {} }",
                "{\"x\":1}",
                new GraphVersionMetadata(
                        GraphExecutionMode.SESSION,
                        List.of("noop", "userTask"),
                        Map.of("approve", "fp-approve"),
                        new StructuredSchema(List.of(new FieldDescriptor("orderId", String.class))),
                        new TypedSchema(String.class),
                        Map.of("approve", new TaskDefinition(
                                "approve",
                                "approval",
                                "form://approval",
                                "alice",
                                List.of("ops"),
                                List.of("approver"),
                                new TypedSchema(String.class)
                        )),
                        Map.of("strategy", "safe")
                ),
                "artifact-rich",
                GraphMigrationPolicy.MIGRATE_ON_RESUME,
                null,
                0,
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        store.create(version);

        GraphVersion loaded = store.get("ver-rich").orElseThrow();
        assertEquals(GraphExecutionMode.SESSION, loaded.metadata().executionMode());
        assertEquals("artifact-rich", loaded.compiledArtifactRef());
        assertEquals("approval", loaded.metadata().taskDefinitions().get("approve").taskType());
        assertInstanceOf(StructuredSchema.class, loaded.metadata().inputSchema());
    }
}
