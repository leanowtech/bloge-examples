package com.leanowtech.bloge.gateway.testing.world.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.ScenarioException;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.StateSpecV2;
import com.leanowtech.bloge.gateway.testing.world.StateKeySpec;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.testing.world.WorldStateSpec;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private static final Instant TEST_NOW = Instant.parse("2026-08-26T00:00:00Z");
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
                "db/postgresql/V20260826_001__world_governed_catalog.sql"),
                new ClassPathResource(
                        "db/postgresql/V20260826_002__world_asset_governance.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();
        codec = new GovernedCatalogCodec(mapper);
        repository = new DatabaseGovernedCatalogRepository(jdbc, codec,
                Clock.fixed(TEST_NOW, ZoneOffset.UTC));
    }

    @Test
    void initUpgradesEmptyLegacySchemaAndMakesGovernanceColumnsUsable() {
        JdbcTemplate legacyJdbc = legacyD1Jdbc();
        DatabaseGovernedCatalogRepository legacyRepository = new DatabaseGovernedCatalogRepository(
                legacyJdbc, codec, Clock.fixed(TEST_NOW, ZoneOffset.UTC));

        legacyRepository.init();

        assertThat(legacyJdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_world_catalog_heads
                WHERE governance_fingerprint IS NOT NULL
                """, Integer.class)).isZero();
        GovernedResourceRef ref = legacyRepository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, "world-1", world(1, "tenant-a"));
        assertThat(legacyRepository.findMetadata(ref).orElseThrow().exactRef()).isEqualTo(ref);
    }

    @Test
    void initRejectsPopulatedLegacyRowWithoutAuthoritativeGovernanceSeal() {
        JdbcTemplate legacyJdbc = legacyD1Jdbc();
        String fingerprint = "sha256:" + "a".repeat(64);
        legacyJdbc.update("""
                INSERT INTO rg_world_catalog_heads
                    (tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint, canonical_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, "tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL.name(), "world-legacy", 1,
                fingerprint, fingerprint, "{}");
        DatabaseGovernedCatalogRepository legacyRepository = new DatabaseGovernedCatalogRepository(
                legacyJdbc, codec, Clock.fixed(TEST_NOW, ZoneOffset.UTC));

        assertThatThrownBy(legacyRepository::init)
                .isInstanceOf(GovernedCatalogIntegrityException.class)
                .hasMessage("RG.WORLD.CATALOG.INTEGRITY");
    }

    private JdbcTemplate legacyD1Jdbc() {
        DriverManagerDataSource legacy = new DriverManagerDataSource();
        legacy.setDriverClassName("org.h2.Driver");
        legacy.setUrl("jdbc:h2:mem:legacy-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        legacy.setUsername("sa");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260826_001__world_governed_catalog.sql")).execute(legacy);
        return new JdbcTemplate(legacy);
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
    void writesAndReadsVersionedWorldStateWithoutChangingLegacyEmptyRead() throws Exception {
        WorldStateSpec state = StateSpecV2.of(List.of(
                new StateKeySpec("/balance", StateKeySpec.Access.WRITE,
                        Map.of("type", "integer"), 100),
                new StateKeySpec("/status", StateKeySpec.Access.READ_WRITE,
                        Map.of("type", "string"), "OPEN")));
        ResourceWorldModel world = world(1, "tenant-a", state);
        GovernedResourceRef ref = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world);

        ObjectNode envelope = headEnvelope(ref);
        assertThat(envelope.at("/payload/slices/0/state").asText()).isEqualTo("V2");
        assertThat(envelope.at("/payload/slices/0/stateSpec/schemaVersion").asText())
                .isEqualTo(StateSpecV2.SCHEMA_VERSION);
        assertThat(repository.findExact(ref).orElseThrow().value())
                .isInstanceOf(ResourceWorldModel.class)
                .extracting(value -> ((ResourceWorldModel) value).slices().getFirst().worldStateSpec())
                .isEqualTo(state);
    }

    @Test
    void roundTripsNullStateDefaultAndItsFingerprint() {
        WorldStateSpec state = StateSpecV2.of(List.of(new StateKeySpec("/optional",
                StateKeySpec.Access.WRITE, Map.of("type", "null"), null)));
        ResourceWorldModel world = world(1, "tenant-a", state);
        GovernedResourceRef ref = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world);

        ResourceWorldModel restored = (ResourceWorldModel) repository.findExact(ref).orElseThrow().value();
        assertThat(restored.stateSpec().fingerprint()).isEqualTo(state.fingerprint());
        assertThat(restored.stateSpec().declarations().getFirst().defaultValue()).isNull();
    }

    @Test
    void roundTripsScenarioStateOverridesAndRejectsSchemaMismatch() throws Exception {
        WorldStateSpec state = StateSpecV2.of(List.of(new StateKeySpec("/balance",
                StateKeySpec.Access.WRITE, Map.of("type", "integer"), 100)));
        ResourceWorldModel world = world(1, "tenant-a", state);
        GovernedResourceRef worldRef = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world);
        Scenario scenario = new Scenario("scenario-state", "tenant-a", 1,
                new Scenario.TargetRef("GRAPH", "graph-1", "sha256:" + "a".repeat(64)),
                world, Map.of("input", "value"),
                Scenario.WorldStateInit.of(Map.of("/balance", 50)), List.of());
        GovernedResourceRef scenarioRef = repository.create("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario);

        Scenario restored = (Scenario) repository.findExact(scenarioRef).orElseThrow().value();
        assertThat(restored.stateInit().overrides()).containsEntry("/balance", 50);
        assertThat(restored.fingerprint()).isEqualTo(scenario.fingerprint());

        ObjectNode baseline = headEnvelope(scenarioRef);
        ObjectNode tampered = baseline.deepCopy();
        ObjectNode worldStateInit = (ObjectNode) tampered.at("/payload/worldStateInit");
        worldStateInit.putNull("overrides");
        replaceHeadJson(scenarioRef, mapper.writeValueAsString(tampered));
        assertThatThrownBy(() -> repository.findExact(scenarioRef))
                .isInstanceOf(GovernedCatalogIntegrityException.class);

        tampered = baseline.deepCopy();
        worldStateInit = (ObjectNode) tampered.at("/payload/worldStateInit");
        worldStateInit.putObject("overrides");
        replaceHeadJson(scenarioRef, mapper.writeValueAsString(tampered));
        assertThatThrownBy(() -> repository.findExact(scenarioRef))
                .isInstanceOf(GovernedCatalogIntegrityException.class);
        assertThat(worldRef).isNotNull();
        assertThatThrownBy(() -> new Scenario("bad-state", "tenant-a", 1,
                new Scenario.TargetRef("GRAPH", "graph-1", "sha256:" + "a".repeat(64)),
                world, Map.of(), Scenario.WorldStateInit.of(Map.of("/balance", "bad")), List.of()))
                .isInstanceOf(ScenarioException.class);
    }

    @Test
    void validatesIndependentOriginsAndClassificationsAndRealWriteRequirements() {
        for (GovernedPayloadOrigin origin : GovernedPayloadOrigin.values()) {
            for (GovernedSecurityClassification classification : GovernedSecurityClassification.values()) {
                GovernedAssetGovernance governance = new GovernedAssetGovernance(origin, classification,
                        origin == GovernedPayloadOrigin.REAL ? TEST_NOW.plusSeconds(60) : null,
                        "policy:" + origin.name().toLowerCase(),
                        origin == GovernedPayloadOrigin.REAL ? "approval:1" : null);
                assertThat(governance.payloadOrigin()).isEqualTo(origin);
                assertThat(governance.securityClassification()).isEqualTo(classification);
            }
        }
        assertThatThrownBy(() -> new GovernedAssetGovernance(GovernedPayloadOrigin.REAL,
                GovernedSecurityClassification.PUBLIC, TEST_NOW.plusSeconds(60), "policy:1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GovernedAssetGovernance(GovernedPayloadOrigin.REAL,
                GovernedSecurityClassification.PUBLIC, TEST_NOW.plusSeconds(60), "", "approval:1"))
                .isInstanceOf(IllegalArgumentException.class);
        GovernedAssetGovernance expired = new GovernedAssetGovernance(GovernedPayloadOrigin.REAL,
                GovernedSecurityClassification.PUBLIC, TEST_NOW.minusSeconds(1), "policy:1", "approval:1");
        DatabaseGovernedCatalogRepository fixedRepository = new DatabaseGovernedCatalogRepository(
                jdbc, codec, Clock.fixed(TEST_NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> fixedRepository.create("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL,
                "world-1", world(1, "tenant-a"), expired)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistsExactMetadataPreservesItOnCompatibilityUpdateAndSealsHistory() {
        GovernedAssetGovernance firstGovernance = new GovernedAssetGovernance(
                GovernedPayloadOrigin.REDACTED, GovernedSecurityClassification.CONFIDENTIAL, null,
                "policy:redacted", null);
        GovernedResourceRef first = repository.create("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL,
                "world-1", world(1, "tenant-a"), firstGovernance);

        GovernedAssetMetadata firstMetadata = repository.findMetadata(first).orElseThrow();
        assertThat(firstMetadata.exactRef()).isEqualTo(first);
        assertThat(firstMetadata.governance()).isEqualTo(firstGovernance);
        GovernedResourceRef second = repository.update(first, world(2, "tenant-a"));
        assertThat(repository.findMetadata(second).orElseThrow().governance()).isEqualTo(firstGovernance);

        GovernedAssetGovernance realGovernance = new GovernedAssetGovernance(
                GovernedPayloadOrigin.REAL, GovernedSecurityClassification.RESTRICTED,
                TEST_NOW.plusSeconds(600), "policy:real", "approval:real-1");
        GovernedResourceRef third = repository.update(second, world(3, "tenant-a"), realGovernance);
        assertThat(repository.findMetadata(third).orElseThrow().governance()).isEqualTo(realGovernance);
        assertThat(repository.history("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL, "world-1"))
                .extracting(GovernedCatalogRevision::metadata)
                .extracting(GovernedAssetMetadata::governance)
                .containsExactly(realGovernance, firstGovernance, firstGovernance);
    }

    @Test
    void metadataProjectionDoesNotParseCorruptedCanonicalJson() {
        GovernedResourceRef ref = repository.create("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL,
                "world-1", world(1, "tenant-a"));
        jdbc.update("""
                UPDATE rg_world_catalog_heads SET canonical_json = ?
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, "{corrupted", ref.tenantId(), ref.kind().name(), ref.id());

        assertThat(repository.findMetadata(ref).orElseThrow().exactRef()).isEqualTo(ref);
        assertThatThrownBy(() -> repository.findExact(ref))
                .isInstanceOf(GovernedCatalogIntegrityException.class);
    }

    @Test
    void governanceSealTamperIsSanitizedIntegrityFailure() {
        GovernedResourceRef ref = repository.create("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL,
                "world-1", world(1, "tenant-a"));
        jdbc.update("""
                UPDATE rg_world_catalog_heads SET governance_fingerprint = ?
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, "sha256:" + "f".repeat(64), ref.tenantId(), ref.kind().name(), ref.id());

        assertThatThrownBy(() -> repository.findMetadata(ref))
                .isInstanceOf(GovernedCatalogIntegrityException.class)
                .hasMessage("RG.WORLD.CATALOG.INTEGRITY");
    }

    @Test
    void compatibilityRevisionConstructorProducesExactSealedMetadata() {
        ResourceWorldModel world = world(1, "tenant-a");
        GovernedResourceRef ref = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world);

        GovernedCatalogRevision revision = new GovernedCatalogRevision(ref, world);
        GovernedAssetGovernance governance = GovernedAssetGovernance.safeDefaults();
        assertThat(revision.metadata().exactRef()).isEqualTo(ref);
        assertThat(revision.metadata().governanceFingerprint())
                .isEqualTo(GovernedAssetMetadata.fingerprint(ref, governance));
    }

    @Test
    void callerSuppliedDependencyResolverReceivesExactWorldReferenceBeforeScenarioCompletes() {
        ResourceWorldModel world = world(1, "tenant-a");
        Scenario scenario = scenario(world, 1, "tenant-a");
        GovernedResourceRef worldRef = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world);
        GovernedResourceRef scenarioRef = repository.create("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario);
        List<GovernedResourceRef> dependencies = new java.util.ArrayList<>();

        GovernedCatalogRevision resolved = repository.findExact(scenarioRef, exactWorldRef -> {
            dependencies.add(exactWorldRef);
            return repository.findExact(exactWorldRef)
                    .map(entry -> (ResourceWorldModel) entry.value())
                    .orElseThrow(GovernedCatalogIntegrityException::new);
        }).orElseThrow();

        assertThat(dependencies).containsExactly(worldRef);
        assertThat(resolved.value()).isInstanceOf(Scenario.class);
        assertThat(repository.findExact(scenarioRef).orElseThrow().value())
                .isInstanceOf(Scenario.class);
    }

    @Test
    void wrongScenarioFingerprintIsAnExactMissBeforeDependencyResolution() {
        ResourceWorldModel world = world(1, "tenant-a");
        Scenario scenario = scenario(world, 1, "tenant-a");
        repository.create("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL,
                world.worldModelId(), world);
        GovernedResourceRef scenarioRef = repository.create("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario);
        GovernedResourceRef wrongFingerprint = new GovernedResourceRef(scenarioRef.tenantId(),
                scenarioRef.kind(), scenarioRef.id(), scenarioRef.revision(),
                "sha256:" + "0".repeat(64));
        List<GovernedResourceRef> dependencies = new java.util.ArrayList<>();

        assertThat(repository.findExact(wrongFingerprint, exactWorldRef -> {
            dependencies.add(exactWorldRef);
            throw new AssertionError("dependency resolver must not be invoked");
        })).isEmpty();
        assertThat(dependencies).isEmpty();
    }

    @Test
    void scenarioRowFingerprintColumnTamperIsIntegrityNotAnExactMiss() {
        ResourceWorldModel world = world(1, "tenant-a");
        Scenario scenario = scenario(world, 1, "tenant-a");
        repository.create("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL,
                world.worldModelId(), world);
        GovernedResourceRef scenarioRef = repository.create("tenant-a",
                GovernedCatalogKind.SCENARIO, scenario.scenarioId(), scenario);
        jdbc.update("""
                UPDATE rg_world_catalog_heads SET fingerprint = ?
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, "sha256:" + "f".repeat(64), scenarioRef.tenantId(),
                scenarioRef.kind().name(), scenarioRef.id());

        assertThatThrownBy(() -> repository.findExact(scenarioRef, exactWorldRef -> {
            throw new AssertionError("dependency resolver must not be invoked");
        })).isInstanceOf(GovernedCatalogIntegrityException.class);
    }

    @Test
    void historicalScenarioResolutionDoesNotDecodeCurrentHeadWorldDependency() {
        ResourceWorldModel firstWorld = world(1, "tenant-a");
        GovernedResourceRef firstWorldRef = repository.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, firstWorld.worldModelId(), firstWorld);
        ResourceWorldModel secondWorld = world(2, "tenant-a");
        GovernedResourceRef secondWorldRef = repository.update(firstWorldRef, secondWorld);
        Scenario firstScenario = scenario(firstWorld, 1, "tenant-a");
        GovernedResourceRef firstScenarioRef = repository.create("tenant-a",
                GovernedCatalogKind.SCENARIO, firstScenario.scenarioId(), firstScenario);
        repository.update(firstScenarioRef, scenario(secondWorld, 2, "tenant-a"));
        List<GovernedResourceRef> dependencies = new java.util.ArrayList<>();

        assertThat(repository.findExact(firstScenarioRef, exactWorldRef -> {
            dependencies.add(exactWorldRef);
            return repository.findExact(exactWorldRef)
                    .map(entry -> (ResourceWorldModel) entry.value())
                    .orElseThrow(GovernedCatalogIntegrityException::new);
        })).isPresent();

        assertThat(dependencies).containsExactly(firstWorldRef);
        assertThat(secondWorldRef).isNotEqualTo(firstWorldRef);
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
        return world(revision, tenant, StateSpec.empty());
    }

    private static ResourceWorldModel world(long revision, String tenant, WorldStateSpec state) {
        LogicalResourceContract contract = contract();
        LogicalResourceBinding binding = LogicalResourceBinding.bind("provider", "v1",
                new ResourceDesignContract(contract.contractId(), "resource-1", "Resource", "",
                        List.of(), contract.inputShape(), contract.outputShape(), Map.of(), "ACTIVE"),
                new VisualResourceDescriptor("resource-1", "https://example.test/{id}", "GET", Map.of(),
                        null, Duration.ofSeconds(2), new VisualResourceParameterMapping(Map.of(), Map.of(), null),
                        new VisualResourceResponseProtocol.HttpStatus(), "data"), contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration(tenant, "provider", "v1",
                        contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("world.bloge", FRAGMENT), state);
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
