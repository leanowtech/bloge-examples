package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.DatabaseProtectedFixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies bounded rolling retention for ACTIVE and RETIRED Business GOLDEN material. */
class BusinessGoldenMaterialLifecycleServiceTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private FixtureMaterialRepository materials;
    private AuthoringFixturePayloadProtector protector;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-fixture-material-schema.sql")).execute(database);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        materials = new DatabaseProtectedFixtureMaterialRepository(new JdbcTemplate(database), mapper);
        protector = AuthoringFixturePayloadProtector.fromConfiguration(
                "business-golden-lifecycle", "business-golden-lifecycle=" + key);
    }

    @Test
    void renewsAnActiveReceiptBeforeExpiryAndDoesNotRewriteItAgain() throws Exception {
        JsonNode initial = writeReceipt("ACTIVE");
        Instant observed = CREATED.plus(340, ChronoUnit.DAYS);
        BusinessGoldenMaterialStore currentStore = storeAt(observed);
        BusinessGoldenMaterialLifecycleService lifecycle = new BusinessGoldenMaterialLifecycleService(
                states, currentStore, mapper, Clock.fixed(observed, ZoneOffset.UTC));

        BusinessGoldenMaterialLifecycleService.LifecycleReport first = lifecycle.reconcile(100);
        AgentTddStoredAsset renewedSet = caseSet();
        Receipt renewed = mapper.treeToValue(
                renewedSet.data().at("/rows/0/materialReceipt"), Receipt.class);

        assertThat(first).isEqualTo(new BusinessGoldenMaterialLifecycleService.LifecycleReport(
                1, 1, 0, 0, 0));
        assertThat(renewed.materialRef().revision()).isEqualTo(2);
        assertThat(renewed.retention().policyVersion()).isEqualTo("rg.businessGolden.lifecycle");
        assertThat(renewed.retention().expiresAt()).isEqualTo(observed.plus(365, ChronoUnit.DAYS));
        assertThat(renewedSet.data().toString()).doesNotContain("SECRET-GOLDEN-VALUE");
        assertThat(currentStore.read(mapper.valueToTree(renewed), identity()))
                .isEqualTo(mapper.valueToTree(Map.of("given", "SECRET-GOLDEN-VALUE")));

        assertThat(lifecycle.reconcile(100).renewed()).isZero();
        assertThat(caseSet().revision()).isEqualTo(renewedSet.revision());
        assertThat(initial).isNotEqualTo(mapper.valueToTree(renewed));
    }

    @Test
    void givesARetiredCaseOneThirtyDayRecoverySuccessor() throws Exception {
        writeReceipt("RETIRED");
        Instant observed = CREATED.plus(1, ChronoUnit.DAYS);
        BusinessGoldenMaterialLifecycleService lifecycle = new BusinessGoldenMaterialLifecycleService(
                states, storeAt(observed), mapper, Clock.fixed(observed, ZoneOffset.UTC));

        BusinessGoldenMaterialLifecycleService.LifecycleReport report = lifecycle.reconcile(100);
        Receipt retired = mapper.treeToValue(caseSet().data().at("/rows/0/materialReceipt"), Receipt.class);

        assertThat(report.retired()).isEqualTo(1);
        assertThat(retired.retention().policyVersion()).isEqualTo("rg.businessGolden.retired");
        assertThat(retired.retention().retentionDays()).isEqualTo(30);
        assertThat(retired.retention().expiresAt()).isEqualTo(observed.plus(30, ChronoUnit.DAYS));
        long revision = caseSet().revision();
        assertThat(lifecycle.reconcile(100).retired()).isZero();
        assertThat(caseSet().revision()).isEqualTo(revision);
    }

    private JsonNode writeReceipt(String lifecycle) {
        BusinessGoldenMaterialStore initialStore = storeAt(CREATED);
        JsonNode receipt = initialStore.write("solution:cancel", 1,
                "sha256:" + "a".repeat(64), "g1", "sha256:" + "b".repeat(64),
                "sha256:" + "c".repeat(64),
                mapper.valueToTree(Map.of("given", "SECRET-GOLDEN-VALUE")), identity());
        ObjectNode state = mapper.createObjectNode();
        ObjectNode row = state.putArray("rows").addObject();
        row.put("caseId", "g1");
        row.put("category", "GOLDEN");
        row.put("lifecycle", lifecycle);
        row.set("materialReceipt", receipt);
        states.save(scope(), AgentTddMutationService.CASE_SET, "cases:cancel", state);
        return receipt;
    }

    private BusinessGoldenMaterialStore storeAt(Instant instant) {
        return new BusinessGoldenMaterialStore(
                new FixtureMaterialService(materials, protector, mapper,
                        Clock.fixed(instant, ZoneOffset.UTC)),
                mapper, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private AgentTddStoredAsset caseSet() {
        return states.find(scope(), AgentTddMutationService.CASE_SET, "cases:cancel").orElseThrow();
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "USER", "reviewer-1", "", "SOLUTION_GOLDEN_REVIEW", "corr-lifecycle",
                java.util.Set.of("solution-golden-reviewers"), "RESTRICTED", "");
    }

    private static String scope() {
        return AgentTddMutationService.scopeKey(identity());
    }
}
