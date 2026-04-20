package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.context.TenantContextHolder;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.model.RbacPolicy;
import com.leanowtech.bloge.graphengine.store.GraphEngineStores;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphDefinitionStore;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphDeploymentStore;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphInstanceStore;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphVersionStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-layer tests for the operator inventory query.
 */
class OperatorInventoryTest {

    private InMemoryGraphDefinitionStore definitionStore;
    private InMemoryGraphVersionStore versionStore;
    private DefaultOperatorRegistry registry;
    private DefaultGraphEngineService service;

    @BeforeEach
    void setUp() {
        definitionStore = new InMemoryGraphDefinitionStore();
        versionStore = new InMemoryGraphVersionStore();
        registry = new DefaultOperatorRegistry();
        GraphEngineStores stores = new GraphEngineStores(
                definitionStore,
                versionStore,
                new InMemoryGraphDeploymentStore(),
                new InMemoryGraphInstanceStore()
        );
        GraphEngineRuntimeSupport runtimeSupport = GraphEngineRuntimeSupport.builder()
                .operatorRegistry(registry)
                .build();
        service = new DefaultGraphEngineService(stores, runtimeSupport);
    }

    @AfterEach
    void tearDown() {
        CallerContextHolder.clear();
        service.close();
    }

    @Test
    void queryReturnsEmptyWhenNoOperatorsRegistered() {
        TenantContextHolder.runWith(new TenantContext("default", "default"), () -> {
            List<OperatorInventoryEntry> entries = service.queryOperatorInventory(
                    new OperatorInventoryQuery("*", "default", "default")
            );
            assertTrue(entries.isEmpty());
        });
    }

    @Test
    void queryReturnsSingleOperatorWithMetadata() {
        registry.register("validateOrder", new ValidateOrderOperator());

        TenantContextHolder.runWith(new TenantContext("default", "default"), () -> {
            List<OperatorInventoryEntry> entries = service.queryOperatorInventory(
                    new OperatorInventoryQuery("*", "default", "default")
            );

            assertEquals(1, entries.size());
            OperatorInventoryEntry entry = entries.getFirst();
            assertEquals("validateOrder", entry.name());
            assertEquals("Validates incoming orders", entry.description());
            assertEquals("order-team", entry.owner());
            assertEquals(List.of("validation", "orders"), entry.tags());
            assertEquals("java.lang.String", entry.inputType());
            assertEquals("java.lang.Boolean", entry.outputType());
            assertNotNull(entry.inputSchema());
            assertNotNull(entry.outputSchema());
            assertEquals(0, entry.usage().definitionCount());
            assertEquals(0, entry.usage().versionCount());
        });
    }

    @Test
    void queryFiltersOperatorsByPattern() {
        registry.register("validateOrder", new ValidateOrderOperator());
        registry.register("enrichOrder", (Operator<String, String>) (input, ctx) -> input);
        registry.register("notifyCustomer", (Operator<String, String>) (input, ctx) -> input);

        TenantContextHolder.runWith(new TenantContext("default", "default"), () -> {
            List<OperatorInventoryEntry> entries = service.queryOperatorInventory(
                    new OperatorInventoryQuery("*Order", "default", "default")
            );

            assertEquals(2, entries.size());
            assertTrue(entries.stream().anyMatch(e -> "validateOrder".equals(e.name())));
            assertTrue(entries.stream().anyMatch(e -> "enrichOrder".equals(e.name())));
            assertFalse(entries.stream().anyMatch(e -> "notifyCustomer".equals(e.name())));
        });
    }

    @Test
    void queryIncludesUsageFromVisibleVersions() {
        registry.register("validateOrder", new ValidateOrderOperator());
        registry.register("enrichOrder", (Operator<String, String>) (input, ctx) -> input);

        GraphDefinition definition = createDefinition("def-1", "order-flow");
        createVersion(definition, "ver-1", "1.0.0", GraphVersionStatus.PUBLISHED,
                List.of("validateOrder", "enrichOrder"));
        createVersion(definition, "ver-2", "2.0.0", GraphVersionStatus.DRAFT,
                List.of("validateOrder"));

        TenantContextHolder.runWith(new TenantContext("default", "default"), () -> {
            List<OperatorInventoryEntry> entries = service.queryOperatorInventory(
                    new OperatorInventoryQuery("*", "default", "default")
            );

            OperatorInventoryEntry validateEntry = entries.stream()
                    .filter(e -> "validateOrder".equals(e.name()))
                    .findFirst().orElseThrow();
            assertEquals(1, validateEntry.usage().definitionCount());
            assertEquals(2, validateEntry.usage().versionCount());
            assertEquals(2, validateEntry.usage().references().size());

            OperatorInventoryEntry enrichEntry = entries.stream()
                    .filter(e -> "enrichOrder".equals(e.name()))
                    .findFirst().orElseThrow();
            assertEquals(1, enrichEntry.usage().definitionCount());
            assertEquals(1, enrichEntry.usage().versionCount());
        });
    }

    @Test
    void queryCountsMultipleDefinitions() {
        registry.register("validateOrder", new ValidateOrderOperator());

        GraphDefinition def1 = createDefinition("def-1", "order-flow");
        createVersion(def1, "ver-1", "1.0.0", GraphVersionStatus.PUBLISHED,
                List.of("validateOrder"));

        GraphDefinition def2 = createDefinition("def-2", "payment-flow");
        createVersion(def2, "ver-2", "1.0.0", GraphVersionStatus.PUBLISHED,
                List.of("validateOrder"));

        TenantContextHolder.runWith(new TenantContext("default", "default"), () -> {
            List<OperatorInventoryEntry> entries = service.queryOperatorInventory(
                    new OperatorInventoryQuery("*", "default", "default")
            );

            assertEquals(1, entries.size());
            OperatorInventoryEntry entry = entries.getFirst();
            assertEquals(2, entry.usage().definitionCount());
            assertEquals(2, entry.usage().versionCount());
            assertEquals(2, entry.usage().references().size());
        });
    }

    @Test
    void queryExcludesUsageFromInaccessibleDefinitions() {
        registry.register("validateOrder", new ValidateOrderOperator());

        GraphDefinition visible = createDefinition("def-visible", "visible-flow", null);
        createVersion(visible, "ver-visible", "1.0.0", GraphVersionStatus.PUBLISHED, List.of("validateOrder"));

        GraphDefinition restricted = createDefinition(
                "def-restricted",
                "restricted-flow",
                new RbacPolicy(Set.of("viewer"), Set.of(), Set.of(), Set.of())
        );
        createVersion(restricted, "ver-restricted", "1.0.0", GraphVersionStatus.PUBLISHED, List.of("validateOrder"));

        CallerContextHolder.set(CallerContext.ANONYMOUS);

        TenantContextHolder.runWith(new TenantContext("default", "default"), () -> {
            List<OperatorInventoryEntry> entries = service.queryOperatorInventory(
                    new OperatorInventoryQuery("*", "default", "default")
            );

            assertEquals(1, entries.size());
            OperatorInventoryEntry entry = entries.getFirst();
            assertEquals(1, entry.usage().definitionCount());
            assertEquals(1, entry.usage().versionCount());
            assertEquals(List.of("visible-flow"), entry.usage().references().stream()
                    .map(OperatorUsageReference::definitionKey)
                    .distinct()
                    .toList());
        });
    }

    @Test
    void queryDefaultPatternIsWildcard() {
        registry.register("echo", (Operator<String, String>) (input, ctx) -> input);

        TenantContextHolder.runWith(new TenantContext("default", "default"), () -> {
            List<OperatorInventoryEntry> entries = service.queryOperatorInventory(
                    new OperatorInventoryQuery(null, "default", "default")
            );

            assertEquals(1, entries.size());
            assertEquals("echo", entries.getFirst().name());
        });
    }

    @Test
    void queryUsageReferencesIncludeVersionStatus() {
        registry.register("echo", (Operator<String, String>) (input, ctx) -> input);

        GraphDefinition definition = createDefinition("def-1", "echo-flow");
        createVersion(definition, "ver-1", "1.0.0", GraphVersionStatus.PUBLISHED, List.of("echo"));
        createVersion(definition, "ver-2", "2.0.0", GraphVersionStatus.DRAFT, List.of("echo"));

        TenantContextHolder.runWith(new TenantContext("default", "default"), () -> {
            List<OperatorInventoryEntry> entries = service.queryOperatorInventory(
                    new OperatorInventoryQuery("*", "default", "default")
            );

            OperatorInventoryEntry entry = entries.getFirst();
            List<OperatorUsageReference> refs = entry.usage().references();
            assertEquals(2, refs.size());
            assertTrue(refs.stream().anyMatch(r ->
                    "1.0.0".equals(r.version()) && r.status() == GraphVersionStatus.PUBLISHED));
            assertTrue(refs.stream().anyMatch(r ->
                    "2.0.0".equals(r.version()) && r.status() == GraphVersionStatus.DRAFT));
        });
    }

    private GraphDefinition createDefinition(String definitionId, String definitionKey) {
        return createDefinition(definitionId, definitionKey, null);
    }

    private GraphDefinition createDefinition(String definitionId, String definitionKey, RbacPolicy rbacPolicy) {
        GraphDefinition definition = new GraphDefinition(
                definitionId,
                definitionKey,
                "default",
                "default",
                definitionKey,
                null,
                null,
                Map.of(),
                null,
                rbacPolicy,
                GraphDefinitionStatus.ACTIVE,
                1,
                Instant.now(),
                Instant.now()
        );
        definitionStore.create(definition);
        return definition;
    }

    private void createVersion(GraphDefinition definition, String versionId, String version,
                               GraphVersionStatus status, List<String> operatorRefs) {
        GraphVersionMetadata metadata = new GraphVersionMetadata(
                null, operatorRefs, Map.of(), null, null, Map.of(), Map.of()
        );
        GraphVersion graphVersion = new GraphVersion(
                versionId,
                definition.definitionId(),
                version,
                "hash-" + version,
                "graph test {}",
                null,
                metadata,
                null,
                null,
                status,
                1,
                status == GraphVersionStatus.PUBLISHED ? Instant.now() : null,
                Instant.now(),
                Instant.now()
        );
        versionStore.create(graphVersion);
    }

    @OperatorMeta(
            description = "Validates incoming orders",
            owner = "order-team",
            tags = {"validation", "orders"}
    )
    private static final class ValidateOrderOperator implements Operator<String, Boolean> {
        @Override
        public Boolean execute(String input, OperatorContext ctx) {
            return true;
        }
    }
}
