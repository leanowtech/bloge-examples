package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.DatabaseProtectedFixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies business case payloads cross only the encrypted exact-receipt material boundary. */
class BusinessGoldenMaterialStoreTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private BusinessGoldenMaterialStore store;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-fixture-material-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        FixtureMaterialService materials = new FixtureMaterialService(
                new DatabaseProtectedFixtureMaterialRepository(jdbc, mapper),
                AuthoringFixturePayloadProtector.fromConfiguration("business-golden",
                        "business-golden=" + key), mapper);
        store = new BusinessGoldenMaterialStore(materials, mapper);
    }

    @Test
    void storesCiphertextAndResolvesOnlyThroughTheExactReceipt() {
        JsonNode payload = mapper.valueToTree(Map.of("given", Map.of("party", "passenger"),
                "expect", Map.of("decision", "UPHELD")));
        String fingerprint = "sha256:" + "a".repeat(64);

        JsonNode receipt = store.write("sol:cancel", 1, fingerprint, "g1", fingerprint,
                "sha256:" + "b".repeat(64), payload, identity());

        assertThat(receipt.has("payload")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT protected_payload FROM rg_fixture_material_v2_revisions", String.class))
                .doesNotContain("passenger", "UPHELD");
        assertThat(jdbc.queryForObject(
                "SELECT receipt_json FROM rg_fixture_material_v2_revisions", String.class))
                .doesNotContain("passenger", "UPHELD");
        assertThat(store.read(receipt, identity())).isEqualTo(payload);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_material_access_audit", Integer.class)).isEqualTo(2);
    }

    @Test
    void refusesPlaintextFallbackWhenTheVaultIsUnavailable() {
        BusinessGoldenMaterialStore unavailable = new BusinessGoldenMaterialStore(
                (FixtureMaterialService) null, mapper);

        assertThatThrownBy(() -> unavailable.write("sol:cancel", 1, "sha256:" + "a".repeat(64),
                "g1", "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64),
                mapper.createObjectNode(), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FIXTURE_MATERIAL_UNAVAILABLE"));
    }

    @Test
    void renewsDraftMaterialIntoARecoverableExactActiveRevision() throws Exception {
        JsonNode payload = mapper.valueToTree(Map.of("given", Map.of("party", "passenger"),
                "expect", Map.of("decision", "UPHELD")));
        String fingerprint = "sha256:" + "a".repeat(64);
        Receipt draft = mapper.treeToValue(store.write(
                "sol:cancel", 1, fingerprint, "g1", fingerprint,
                "sha256:" + "b".repeat(64), payload, identity()), Receipt.class);

        assertThat(draft.retention().policyVersion()).isEqualTo("rg.businessGolden.lifecycle");
        assertThat(draft.retention().retentionDays()).isEqualTo(365);

        Receipt active = mapper.treeToValue(store.renew(mapper.valueToTree(draft), identity()), Receipt.class);
        assertThat(active.materialRef().revision()).isEqualTo(2);
        assertThat(active.payloadFingerprint()).isEqualTo(draft.payloadFingerprint());
        assertThat(active.lineageRefs()).containsExactly(draft.materialRef());
        assertThat(active.retention().policyVersion()).isEqualTo("rg.businessGolden.lifecycle");
        assertThat(active.retention().retentionDays()).isEqualTo(365);
        assertThat(active.retention().expiresAt()).isAfter(Instant.now().plusSeconds(364L * 24 * 60 * 60));
        assertThat(store.read(mapper.valueToTree(active), identity())).isEqualTo(payload);

        Receipt recovered = mapper.treeToValue(
                store.renew(mapper.valueToTree(draft), identity()), Receipt.class);
        assertThat(recovered).isEqualTo(active);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "codex", "INTERNAL", "AGENT_TDD_AUTHORING", "corr-material");
    }
}
