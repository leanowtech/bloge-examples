package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Material;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureMaterialServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T10:00:00Z");

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DatabaseProtectedFixtureMaterialRepository repository;
    private FixtureMaterialService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-fixture-material-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseProtectedFixtureMaterialRepository(jdbc, mapper);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        service = new FixtureMaterialService(
                repository,
                AuthoringFixturePayloadProtector.fromConfiguration("fixture-v2", "fixture-v2=" + key),
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void writesOnlyCiphertextAndReturnsPayloadFreeReceiptThenAuditedMaterial() throws Exception {
        Receipt receipt = service.write(request(0), identity(
                FixtureMaterialService.WRITE_PURPOSE, "RESTRICTED", "tenant-a"));

        assertThat(receipt.materialRef().revision()).isEqualTo(1);
        assertThat(receipt.payloadReturned()).isFalse();
        assertThat(receipt.redaction().redactedPaths())
                .containsExactly("/credentialToken", "/customer/phone");
        String stored = jdbc.queryForObject(
                "SELECT protected_payload FROM rg_fixture_material_v2_revisions", String.class);
        assertThat(stored).startsWith("v1.fixture-v2.")
                .doesNotContain("13800138000", "top-secret", "Alice");
        String receiptJson = jdbc.queryForObject(
                "SELECT receipt_json FROM rg_fixture_material_v2_revisions", String.class);
        assertThat(receiptJson).doesNotContain("13800138000", "top-secret", "Alice");
        assertThat(mapper.readTree(receiptJson).has("payload")).isFalse();

        Material material = service.read(
                receipt.fixtureAssetId(), receipt.materialRef().revision(),
                receipt.materialRef().fingerprint(),
                identity(FixtureMaterialService.READ_PURPOSE, "RESTRICTED", "tenant-a"));

        assertThat(material.payloadReturned()).isTrue();
        assertThat(material.payload()).isEqualTo(Map.of(
                "credentialToken", "[REDACTED]",
                "customer", Map.of("name", "Alice", "phone", "[REDACTED]"),
                "score", 760));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_material_access_audit", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT action FROM rg_fixture_material_access_audit ORDER BY occurred_at, action",
                String.class)).containsExactlyInAnyOrder("WRITE", "READ");
    }

    @Test
    void rejectsWrongPurposeScopeClearanceFingerprintAndStaleWrites() {
        assertThatThrownBy(() -> service.write(
                request(0), identity("CORRECTNESS_WRITE", "RESTRICTED", "tenant-a")))
                .isInstanceOf(FixtureMaterialCommandException.class)
                .extracting(error -> ((FixtureMaterialCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_PURPOSE_FORBIDDEN");
        Receipt receipt = service.write(request(0), identity(
                FixtureMaterialService.WRITE_PURPOSE, "RESTRICTED", "tenant-a"));
        assertThatThrownBy(() -> service.write(request(0), identity(
                FixtureMaterialService.WRITE_PURPOSE, "RESTRICTED", "tenant-a")))
                .isInstanceOf(FixtureMaterialCommandException.class)
                .extracting(error -> ((FixtureMaterialCommandException) error).status())
                .isEqualTo(409);
        assertThatThrownBy(() -> service.read(
                receipt.fixtureAssetId(), 1, receipt.materialRef().fingerprint(),
                identity(FixtureMaterialService.READ_PURPOSE, "RESTRICTED", "tenant-b")))
                .isInstanceOf(FixtureMaterialCommandException.class)
                .extracting(error -> ((FixtureMaterialCommandException) error).status())
                .isEqualTo(404);
        assertThatThrownBy(() -> service.read(
                receipt.fixtureAssetId(), 1, receipt.materialRef().fingerprint(),
                identity(FixtureMaterialService.READ_PURPOSE, "INTERNAL", "tenant-a")))
                .isInstanceOf(FixtureMaterialCommandException.class)
                .extracting(error -> ((FixtureMaterialCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_CLEARANCE_FORBIDDEN");
        assertThatThrownBy(() -> service.read(
                receipt.fixtureAssetId(), 1, fingerprint('f'),
                identity(FixtureMaterialService.READ_PURPOSE, "RESTRICTED", "tenant-a")))
                .isInstanceOf(FixtureMaterialCommandException.class)
                .extracting(error -> ((FixtureMaterialCommandException) error).status())
                .isEqualTo(409);
    }

    @Test
    void aadAndRecordFingerprintDetectCiphertextOrCoordinateTampering() {
        Receipt receipt = service.write(request(0), identity(
                FixtureMaterialService.WRITE_PURPOSE, "RESTRICTED", "tenant-a"));
        jdbc.update("UPDATE rg_fixture_material_v2_revisions SET classification = 'PUBLIC'");

        assertThatThrownBy(() -> service.read(
                receipt.fixtureAssetId(), 1, receipt.materialRef().fingerprint(),
                identity(FixtureMaterialService.READ_PURPOSE, "RESTRICTED", "tenant-a")))
                .isInstanceOf(FixtureMaterialCommandException.class)
                .extracting(error -> ((FixtureMaterialCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_INTEGRITY_INVALID");

        setUp();
        Receipt ciphertextReceipt = service.write(request(0), identity(
                FixtureMaterialService.WRITE_PURPOSE, "RESTRICTED", "tenant-a"));
        jdbc.update("UPDATE rg_fixture_material_v2_revisions SET protected_payload = 'v1.invalid.x.y'");
        String exactFingerprint = ciphertextReceipt.materialRef().fingerprint();
        assertThatThrownBy(() -> service.read(
                "prime-applicant", 1, exactFingerprint,
                identity(FixtureMaterialService.READ_PURPOSE, "RESTRICTED", "tenant-a")))
                .isInstanceOf(FixtureMaterialCommandException.class)
                .extracting(error -> ((FixtureMaterialCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_INTEGRITY_INVALID");
    }

    @Test
    void expiryDestroysCiphertextAndAuditFailurePreventsPayloadReturn() {
        Receipt receipt = service.write(request(0), identity(
                FixtureMaterialService.WRITE_PURPOSE, "RESTRICTED", "tenant-a"));
        assertThat(repository.expireDue(NOW.plusSeconds(86_401), 100)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT protected_payload FROM rg_fixture_material_v2_revisions", String.class))
                .isNull();
        assertThatThrownBy(() -> service.read(
                receipt.fixtureAssetId(), 1, receipt.materialRef().fingerprint(),
                identity(FixtureMaterialService.READ_PURPOSE, "RESTRICTED", "tenant-a")))
                .isInstanceOf(FixtureMaterialCommandException.class)
                .extracting(error -> ((FixtureMaterialCommandException) error).status())
                .isEqualTo(410);

        setUp();
        Receipt auditReceipt = service.write(request(0), identity(
                FixtureMaterialService.WRITE_PURPOSE, "RESTRICTED", "tenant-a"));
        jdbc.execute("DROP TABLE rg_fixture_material_access_audit");
        String exactFingerprint = auditReceipt.materialRef().fingerprint();
        assertThatThrownBy(() -> service.read(
                "prime-applicant", 1, exactFingerprint,
                identity(FixtureMaterialService.READ_PURPOSE, "RESTRICTED", "tenant-a")))
                .isInstanceOf(FixtureMaterialCommandException.class)
                .extracting(error -> ((FixtureMaterialCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_AUDIT_UNAVAILABLE");
    }

    @Test
    void writeAndAuditAreAtomicAndProductionMigrationDeclaresNoPlaintextColumn() throws Exception {
        jdbc.execute("DROP TABLE rg_fixture_material_access_audit");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                service.write(request(0), identity(
                        FixtureMaterialService.WRITE_PURPOSE, "RESTRICTED", "tenant-a"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_material_v2_revisions", Integer.class)).isZero();

        String migration = Files.readString(Path.of(
                "src", "main", "resources", "db", "postgresql",
                "V20260815_006__correctness_fixture_material_vault.sql"));
        assertThat(migration).contains(
                "rg_fixture_material_v2_revisions",
                "rg_fixture_material_access_audit",
                "protected_payload TEXT",
                "state = 'EXPIRED' AND protected_payload IS NULL");
        assertThat(migration).doesNotContain(
                "plaintext_payload", "payload_json", "request_payload", "response_payload");
    }

    private WriteRequest request(long expectedRevision) {
        return new WriteRequest(
                "", "prime-applicant", expectedRevision,
                new FixtureSource(SourceKind.SAMPLE, null), FixtureSubject.GRAPH,
                new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 7, fingerprint('a')),
                new ExactSchemaRef("loan-request", 3, fingerprint('b')),
                "RESTRICTED",
                new RetentionDescriptor("retention-v2", 1, NOW.plusSeconds(86_400)),
                new RedactionDescriptor("redaction-v2", List.of("/customer/phone"), true),
                Map.of(
                        "customer", Map.of("name", "Alice", "phone", "13800138000"),
                        "credentialToken", "top-secret",
                        "score", 760));
    }

    private static IntegrationRequestContext identity(
            String purpose, String clearance, String tenant) {
        return new IntegrationRequestContext(
                tenant, "org-a", "credit", "test", "sg", "USER", "author-a", "",
                purpose, "correlation-a", java.util.Set.of("credit"), clearance, "");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
