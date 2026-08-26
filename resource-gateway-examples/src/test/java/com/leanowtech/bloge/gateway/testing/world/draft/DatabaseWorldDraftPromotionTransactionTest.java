package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseWorldDraftPromotionTransactionTest {
    @Test
    void receiptCatalogAssetAndCandidateCasRollbackTogetherAndRetry() {
        for (Failure failure : Failure.values()) {
            Fixture fixture = fixture(failure);
            assertThatThrownBy(() -> fixture.transaction.promote(fixture.current, fixture.access))
                    .isInstanceOf(WorldDraftCandidateException.class);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM promotion_receipt", Integer.class)).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM promotion_catalog", Integer.class)).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT status FROM promotion_asset", String.class))
                    .isEqualTo("DRAFT");
            WorldDraftAssetRepository.StoredAsset draft = fixture.assets.find(fixture.access.tenantId(),
                    fixture.current.candidateId(), fixture.current.materializationFingerprint(), fixture.access).orElseThrow();
            WorldDraftRedactedPayloadVault.PublishedBinding binding = new WorldDraftRedactedPayloadVault.PublishedBinding(
                    fixture.access.tenantId(), fixture.current.candidateId(), fixture.current.redactedPayloadRef().artifactRevision(),
                    draft.worldModel().fingerprint(), draft.rule().fingerprint(), fixture.receiptFingerprint);
            assertThat(fixture.vault.readPublished(fixture.current.redactedPayloadRef(), binding,
                    fixture.access)).isEmpty();
            assertThat(fixture.candidates.find(fixture.access.tenantId(), fixture.current.candidateId()))
                    .contains(fixture.current);

            WorldDraftCandidate published = fixture.transaction.promote(fixture.current, fixture.access);
            assertThat(published.state()).isEqualTo(WorldDraftState.PUBLISHED);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM promotion_receipt", Integer.class)).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM promotion_catalog", Integer.class)).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("SELECT status FROM promotion_asset", String.class))
                    .isEqualTo("PUBLISHED");
            assertThat(fixture.vault.readPublished(fixture.current.redactedPayloadRef(), binding,
                    fixture.access)).isPresent();
        }
    }

    private static Fixture fixture(Failure failure) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:promotion-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE promotion_receipt (id INT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE promotion_catalog (id INT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE promotion_asset (status VARCHAR(16))");
        jdbc.update("INSERT INTO promotion_asset(status) VALUES ('DRAFT')");

        WorldDraftCandidateService.Access access = WorldDraftTestSupport.ACCESS;
        WorldDraftSourceRef source = WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                access.tenantId(), "database-promotion-source");
        WorldDraftRedactedPayloadRef payload = WorldDraftRedactedPayloadRef.of(access.tenantId(),
                "database-promotion", 1, new WorldDraftRedactedPayload(Map.of("request", "A"), Map.of("result", "B")));
        WorldDraftRule rule = new WorldDraftRule(WorldDraftTestSupport.fp("schema"),
                payload.requestFingerprint(), payload.responseFingerprint(), null, payload);
        WorldDraftCandidate approved = new WorldDraftCandidate("database-promotion", 3,
                WorldDraftState.APPROVED, access.tenantId(), source, WorldDraftTestSupport.fp("metadata"),
                WorldDraftTestSupport.fp("schema"), WorldDraftTestSupport.fp("policy"),
                WorldDraftTestSupport.fp("request"), WorldDraftTestSupport.fp("response"), payload,
                WorldDraftTestSupport.fp("report"), WorldDraftRedactionReport.notProcessed(),
                WorldDraftTestSupport.fp("approval"), rule.fingerprint());
        WorldDraftCandidate current = approved.next(WorldDraftState.MATERIALIZED_DRAFT,
                approved.approvalFingerprint(), rule.fingerprint(), payload,
                approved.redactionReportFingerprint(), approved.redactionReport());
        WorldDraftAssetRepository.StoredAsset asset = new WorldDraftAssetRepository.StoredAsset(
                new WorldDraftMaterializer.MaterializedDraft(approved,
                        WorldDraftTestSupport.world("database-world", 2), rule, false));
        SqlCandidates candidates = new SqlCandidates(current, jdbc, failure == Failure.CANDIDATE_CAS);
        SqlAssets assets = new SqlAssets(asset, jdbc, failure == Failure.CATALOG);
        SqlReceipts receipts = new SqlReceipts(jdbc, failure == Failure.RECEIPT);
        InMemoryWorldDraftRedactedPayloadVault vault = new InMemoryWorldDraftRedactedPayloadVault();
        vault.put(payload, new WorldDraftRedactedPayload(Map.of("request", "A"), Map.of("result", "B")), access);
        WorldDraftPublicationAuthority authority = (candidate, ignored) -> new WorldDraftPublicationReceipt(
                candidate.candidateId(), candidate.revision(), candidate.materializationFingerprint(), "database-publish");
        DatabaseWorldDraftPromotionTransaction transaction = new DatabaseWorldDraftPromotionTransaction(
                dataSource, candidates, assets, receipts, authority, vault);
        String receiptFingerprint = InMemoryWorldDraftAssetRepository.receiptFingerprint(
                new WorldDraftPublicationReceipt(current.candidateId(), current.revision(),
                        current.materializationFingerprint(), "database-publish"));
        return new Fixture(access, current, candidates, assets, vault, transaction, jdbc, receiptFingerprint);
    }

    private enum Failure { RECEIPT, CATALOG, CANDIDATE_CAS }

    private record Fixture(WorldDraftCandidateService.Access access, WorldDraftCandidate current,
                           SqlCandidates candidates, SqlAssets assets,
                           InMemoryWorldDraftRedactedPayloadVault vault,
                           DatabaseWorldDraftPromotionTransaction transaction,
                           JdbcTemplate jdbc, String receiptFingerprint) { }

    private static final class SqlCandidates implements WorldDraftCandidateRepository {
        private final AtomicReference<WorldDraftCandidate> head;
        private final JdbcTemplate jdbc;
        private final AtomicBoolean failCas;
        private SqlCandidates(WorldDraftCandidate head, JdbcTemplate jdbc, boolean failCas) {
            this.head = new AtomicReference<>(head); this.jdbc = jdbc; this.failCas = new AtomicBoolean(failCas);
        }
        public WorldDraftCandidate create(WorldDraftCandidate candidate) { return head.updateAndGet(value -> value == null ? candidate : value); }
        public Optional<WorldDraftCandidate> find(String tenant, String id) { return Optional.of(head.get()); }
        public boolean compareAndSet(WorldDraftCandidate expected, WorldDraftCandidate replacement) {
            if (failCas.getAndSet(false)) return false;
            return head.compareAndSet(expected, replacement);
        }
    }

    private static final class SqlAssets implements WorldDraftAssetRepository {
        private final StoredAsset draft;
        private final JdbcTemplate jdbc;
        private final AtomicBoolean failCatalog;
        private SqlAssets(StoredAsset draft, JdbcTemplate jdbc, boolean failCatalog) {
            this.draft = draft; this.jdbc = jdbc; this.failCatalog = new AtomicBoolean(failCatalog);
        }
        public StoredAsset saveDraft(WorldDraftMaterializer.MaterializedDraft value, WorldDraftCandidateService.Access access) { return draft; }
        public Optional<StoredAsset> find(String tenant, String id, String fp, WorldDraftCandidateService.Access access) {
            return Optional.of(draft);
        }
        public StoredAsset publish(StoredAsset asset, WorldDraftCandidate candidate,
                                   WorldDraftPublicationReceipt receipt, WorldDraftCandidateService.Access access) {
            jdbc.update("INSERT INTO promotion_catalog(id) VALUES (1)");
            if (failCatalog.getAndSet(false)) throw new IllegalStateException("catalog failure");
            jdbc.update("UPDATE promotion_asset SET status='PUBLISHED'");
            return asset.asPublished(InMemoryWorldDraftAssetRepository.receiptFingerprint(receipt));
        }
    }

    private static final class SqlReceipts implements WorldDraftAuthorityReceiptRepository {
        private final JdbcTemplate jdbc;
        private final AtomicBoolean fail;
        private WorldDraftPublicationReceipt stored;
        private SqlReceipts(JdbcTemplate jdbc, boolean fail) { this.jdbc = jdbc; this.fail = new AtomicBoolean(fail); }
        public void saveApproval(WorldDraftApproval receipt, WorldDraftCandidateService.Access access) { }
        public Optional<WorldDraftApproval> findApproval(String tenant, String id, String fp, WorldDraftCandidateService.Access access) { return Optional.empty(); }
        public void savePublication(WorldDraftPublicationReceipt receipt, WorldDraftCandidateService.Access access) {
            jdbc.update("INSERT INTO promotion_receipt(id) VALUES (1)");
            stored = receipt;
            if (fail.getAndSet(false)) throw new IllegalStateException("receipt failure");
        }
        public Optional<WorldDraftPublicationReceipt> findPublication(String tenant, String id, String fp, WorldDraftCandidateService.Access access) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM promotion_receipt", Integer.class) == 1
                    ? Optional.of(stored) : Optional.empty();
        }
    }
}
