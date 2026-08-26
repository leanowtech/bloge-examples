package com.leanowtech.bloge.gateway.testing.world.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseGovernedCatalogRepositoryTest {
    private static final String FRAGMENT = """
            graph customerWorld {
              decision_table response(type = ctx.type) hit=first -> String {
                rule (type: type == "vip") -> "priority"
                otherwise -> "standard"
              }
            }
            """;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private GovernedCatalogCodec codec;
    private DatabaseGovernedCatalogRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource h2 = new DriverManagerDataSource();
        h2.setDriverClassName("org.h2.Driver");
        h2.setUrl("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        h2.setUsername("sa");
        dataSource = h2;
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260826_001__world_governed_catalog.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();
        codec = new GovernedCatalogCodec(mapper);
        repository = new DatabaseGovernedCatalogRepository(jdbc, codec);
    }

    @Test
    void roundTripsAllThreeKindsThroughCanonicalJson() {
        LogicalResourceContract contract = contract();
        ResourceWorldModel world = world(1, "tenant-a");
        Scenario scenario = scenario(world, 1, "tenant-a");

        GovernedResourceRef contractRef = repository.create("tenant-a",
                GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT, contract.contractId(), contract);
        GovernedResourceRef worldRef = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world);
        GovernedResourceRef scenarioRef = repository.create("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario);

        assertThat(repository.findExact(contractRef).orElseThrow().value())
                .isInstanceOf(LogicalResourceContract.class)
                .extracting(value -> ((LogicalResourceContract) value).contractFingerprint())
                .isEqualTo(contract.contractFingerprint());
        assertThat(repository.findExact(worldRef).orElseThrow().value())
                .isInstanceOf(ResourceWorldModel.class)
                .extracting(value -> ((ResourceWorldModel) value).fingerprint())
                .isEqualTo(world.fingerprint());
        assertThat(repository.findExact(scenarioRef).orElseThrow().value())
                .isInstanceOf(Scenario.class)
                .extracting(value -> ((Scenario) value).fingerprint())
                .isEqualTo(scenario.fingerprint());
    }

    @Test
    void createUpdateStaleCasAndImmutableHistoryAreEnforced() {
        ResourceWorldModel first = world(1, "tenant-a");
        GovernedResourceRef revisionOne = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, first.worldModelId(), first);
        ResourceWorldModel second = world(2, "tenant-a");
        GovernedResourceRef revisionTwo = repository.update(revisionOne, second);

        assertThatThrownBy(() -> repository.update(revisionOne, world(2, "tenant-a")))
                .isInstanceOf(GovernedCatalogConflictException.class);
        assertThat(repository.findExact(revisionOne).orElseThrow().value())
                .extracting(value -> ((ResourceWorldModel) value).revision()).isEqualTo(1L);
        assertThat(repository.history("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL, "world-1"))
                .extracting(GovernedCatalogRevision::ref)
                .containsExactly(revisionTwo, revisionOne);
    }

    @Test
    void wrongFingerprintIsAnExactMissAndTenantAndKindAreIsolated() {
        LogicalResourceContract contract = contract();
        GovernedResourceRef tenantA = repository.create("tenant-a",
                GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT, contract.contractId(), contract);
        GovernedResourceRef tenantB = repository.create("tenant-b",
                GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT, contract.contractId(), contract);

        GovernedResourceRef wrongFingerprint = new GovernedResourceRef("tenant-a", tenantA.kind(),
                tenantA.id(), tenantA.revision(), "sha256:" + "0".repeat(64));
        GovernedResourceRef wrongKind = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.SCENARIO, tenantA.id(), tenantA.revision(), tenantA.fingerprint());
        assertThat(repository.findExact(wrongFingerprint)).isEmpty();
        assertThat(repository.findExact(wrongKind)).isEmpty();
        assertThat(repository.findExact(tenantA)).isPresent();
        assertThat(repository.findExact(tenantB)).isPresent();
    }

    @Test
    void fingerprintsAndHistoryOrderAreDeterministicAcrossRepositoryInstances() {
        ResourceWorldModel first = world(1, "tenant-a");
        ResourceWorldModel same = world(1, "tenant-a");
        assertThat(first.fingerprint()).isEqualTo(same.fingerprint());

        GovernedResourceRef ref = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, first.worldModelId(), first);
        DatabaseGovernedCatalogRepository second = new DatabaseGovernedCatalogRepository(
                jdbc, new ObjectMapper());
        assertThat(second.findExact(ref).orElseThrow().ref()).isEqualTo(ref);
        assertThat(second.history("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL, "world-1"))
                .hasSize(1).first().extracting(GovernedCatalogRevision::ref).isEqualTo(ref);
    }

    @Test
    void detectsTamperedJsonAndFingerprintWithoutEmittingPayload() {
        LogicalResourceContract contract = contract();
        GovernedResourceRef ref = repository.create("tenant-a",
                GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT, contract.contractId(), contract);
        String json = jdbc.queryForObject("""
                SELECT canonical_json FROM rg_world_catalog_heads
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, String.class, ref.tenantId(), ref.kind().name(), ref.id());
        jdbc.update("""
                UPDATE rg_world_catalog_heads SET canonical_json = ?
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, json.replace("\"contractId\":\"contract-1\"", "\"contractId\":\"tampered\""),
                ref.tenantId(), ref.kind().name(), ref.id());
        assertThatThrownBy(() -> repository.findExact(ref))
                .isInstanceOf(GovernedCatalogIntegrityException.class)
                .hasMessage("RG.WORLD.CATALOG.INTEGRITY")
                .hasMessageNotContaining("tampered");

        setUp();
        GovernedResourceRef fingerprintRef = repository.create("tenant-a",
                GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT,
                contract.contractId(), contract);
        jdbc.update("""
                UPDATE rg_world_catalog_heads SET fingerprint = ?
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, "sha256:" + "f".repeat(64), fingerprintRef.tenantId(), fingerprintRef.kind().name(),
                fingerprintRef.id());
        assertThatThrownBy(() -> repository.findExact(fingerprintRef))
                .isInstanceOf(GovernedCatalogIntegrityException.class)
                .hasMessage("RG.WORLD.CATALOG.INTEGRITY");
    }

    @Test
    void recordFingerprintDetectsBindingFieldOutsideWorldFingerprint() throws Exception {
        ResourceWorldModel world = world(1, "tenant-a");
        GovernedResourceRef ref = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world);
        ObjectNode envelope = headEnvelope(ref);
        ((ObjectNode) envelope.path("payload").path("slices").get(0).path("binding"))
                .put("providerOutputFingerprint", "sha256:" + "f".repeat(64));
        replaceHeadJson(ref, codec.canonicalize(envelope.toString()));

        assertThatThrownBy(() -> repository.findExact(ref))
                .isInstanceOf(GovernedCatalogIntegrityException.class)
                .hasMessage("RG.WORLD.CATALOG.INTEGRITY");
    }

    @Test
    void strictEnvelopeRejectsMissingAndExtraFieldsEvenWithRecomputedRecordSeal() throws Exception {
        LogicalResourceContract contract = contract();
        GovernedResourceRef ref = repository.create("tenant-a",
                GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT, contract.contractId(), contract);
        GovernedResourceRef missingRef = ref;
        ObjectNode missing = headEnvelope(missingRef);
        missing.remove("domainVersionIndependent");
        replaceHeadJsonAndSeal(missingRef, missing);
        assertThatThrownBy(() -> repository.findExact(missingRef))
                .isInstanceOf(GovernedCatalogIntegrityException.class);

        setUp();
        ref = repository.create("tenant-a", GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT,
                contract.contractId(), contract);
        GovernedResourceRef extraRef = ref;
        ObjectNode extra = headEnvelope(extraRef);
        extra.put("unexpected", true);
        replaceHeadJsonAndSeal(extraRef, extra);
        assertThatThrownBy(() -> repository.findExact(extraRef))
                .isInstanceOf(GovernedCatalogIntegrityException.class);
    }

    @Test
    void recordFingerprintDetectsTamperedRevisionHistory() throws Exception {
        ResourceWorldModel first = world(1, "tenant-a");
        GovernedResourceRef revisionOne = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, first.worldModelId(), first);
        GovernedResourceRef revisionTwo = repository.update(revisionOne, world(2, "tenant-a"));
        ObjectNode tampered = revisionEnvelope(revisionOne);
        tampered.put("fingerprint", "sha256:" + "0".repeat(64));
        String json = codec.canonicalize(tampered.toString());
        jdbc.update("""
                UPDATE rg_world_catalog_revisions SET canonical_json = ?
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ? AND revision = ?
                """, json, revisionOne.tenantId(), revisionOne.kind().name(), revisionOne.id(),
                revisionOne.revision());

        assertThat(repository.findExact(revisionTwo)).isPresent();
        assertThatThrownBy(() -> repository.findExact(revisionOne))
                .isInstanceOf(GovernedCatalogIntegrityException.class);
        assertThatThrownBy(() -> repository.history(revisionOne.tenantId(), revisionOne.kind(), revisionOne.id()))
                .isInstanceOf(GovernedCatalogIntegrityException.class);
    }

    @Test
    void concurrentUpdatesFromSameExpectedRefHaveOneWinnerAndOneCasConflict() throws Exception {
        ResourceWorldModel first = world(1, "tenant-a");
        GovernedResourceRef expected = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, first.worldModelId(), first);
        DatabaseGovernedCatalogRepository firstRepository = new DatabaseGovernedCatalogRepository(
                jdbc, new ObjectMapper());
        DatabaseGovernedCatalogRepository secondRepository = new DatabaseGovernedCatalogRepository(
                jdbc, new ObjectMapper());
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<GovernedResourceRef> firstUpdate = executor.submit(() -> {
                barrier.await();
                return firstRepository.update(expected, world(2, "tenant-a"));
            });
            Future<GovernedResourceRef> secondUpdate = executor.submit(() -> {
                barrier.await();
                return secondRepository.update(expected, world(2, "tenant-a"));
            });

            int successes = 0;
            int conflicts = 0;
            for (Future<GovernedResourceRef> update : List.of(firstUpdate, secondUpdate)) {
                try {
                    update.get(10, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(GovernedCatalogConflictException.class);
                    conflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertThat(repository.history("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL, "world-1"))
                .hasSize(2)
                .extracting(GovernedCatalogRevision::ref)
                .extracting(GovernedResourceRef::revision)
                .containsExactly(2L, 1L);
        assertThat(repository.findExact(new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, "world-1", 2,
                world(2, "tenant-a").fingerprint()))).isPresent();
    }

    @Test
    void rejectsWorldAndScenarioCandidatesWhoseInternalCoordinatesDoNotMatch() {
        assertThatThrownBy(() -> repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, "world-1", world(1, "tenant-b")))
                .isInstanceOf(GovernedCatalogIntegrityException.class);
        ResourceWorldModel world = world(1, "tenant-a");
        assertThatThrownBy(() -> repository.create("tenant-a", GovernedCatalogKind.SCENARIO,
                "scenario-1", scenario(world, 2, "tenant-a")))
                .isInstanceOf(GovernedCatalogIntegrityException.class);
    }

    private static LogicalResourceContract contract() {
        return new LogicalResourceContract("contract-1", SchemaEnvelope.object(
                Map.of("id", Map.of("type", "string")), List.of("id")), SchemaEnvelope.object(
                Map.of("result", Map.of("type", "string")), List.of("result")),
                ResponseSemantics.confirmed("http.status in 200..299", Map.of(),
                        ResponseSemantics.Idempotency.IDEMPOTENT,
                        ResponseSemantics.Retryability.CONDITIONAL));
    }

    private static ResourceWorldModel world(long revision, String tenant) {
        LogicalResourceContract contract = contract();
        LogicalResourceBinding binding = LogicalResourceBinding.bind("provider", "v1",
                new ResourceDesignContract(contract.contractId(), "resource-1", "Resource", "",
                        List.of(), contract.inputShape(), contract.outputShape(), Map.of(), "ACTIVE"),
                new VisualResourceDescriptor("resource-1", "https://example.test/{id}", "GET", Map.of(),
                        null, Duration.ofSeconds(2), new VisualResourceParameterMapping(Map.of(), Map.of(), null),
                        new VisualResourceResponseProtocol.HttpStatus(), "data"), contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration(tenant, "provider", "v1",
                        contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("world.bloge", FRAGMENT), StateSpec.empty());
        return new ResourceWorldModel("world-1", tenant, revision, List.of(slice));
    }

    private static Scenario scenario(ResourceWorldModel world, long revision, String tenant) {
        return new Scenario("scenario-1", tenant, revision,
                new Scenario.TargetRef("GRAPH", "graph-1", "sha256:" + "a".repeat(64)), world,
                Map.of("input", "value"), Scenario.WorldStateInit.EMPTY,
                List.of(new Scenario.Expectation("OUTPUT_PATH", "", "/result", "EQUALS", "ok", null)));
    }

    private ObjectNode headEnvelope(GovernedResourceRef ref) throws Exception {
        return (ObjectNode) mapper.readTree(jdbc.queryForObject("""
                SELECT canonical_json FROM rg_world_catalog_heads
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, String.class, ref.tenantId(), ref.kind().name(), ref.id()));
    }

    private ObjectNode revisionEnvelope(GovernedResourceRef ref) throws Exception {
        return (ObjectNode) mapper.readTree(jdbc.queryForObject("""
                SELECT canonical_json FROM rg_world_catalog_revisions
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ? AND revision = ?
                """, String.class, ref.tenantId(), ref.kind().name(), ref.id(), ref.revision()));
    }

    private void replaceHeadJson(GovernedResourceRef ref, String json) {
        jdbc.update("""
                UPDATE rg_world_catalog_heads SET canonical_json = ?
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, json, ref.tenantId(), ref.kind().name(), ref.id());
    }

    private void replaceHeadJsonAndSeal(GovernedResourceRef ref, ObjectNode envelope) {
        String json = codec.canonicalize(envelope.toString());
        jdbc.update("""
                UPDATE rg_world_catalog_heads SET canonical_json = ?, record_fingerprint = ?
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, json, codec.recordFingerprint(json), ref.tenantId(), ref.kind().name(), ref.id());
    }
}
