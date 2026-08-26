package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/** JDBC persistence for server-issued approval and publication receipts. */
public final class DatabaseWorldDraftAuthorityReceiptRepository
        implements WorldDraftAuthorityReceiptRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean postgres;

    public DatabaseWorldDraftAuthorityReceiptRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        if (jdbc == null || jdbc.getDataSource() == null || mapper == null) throw invalid();
        this.jdbc = jdbc;
        this.mapper = mapper.copy();
        this.postgres = isPostgres(jdbc);
    }

    @Override public void saveApproval(WorldDraftApproval receipt, WorldDraftCandidateService.Access access) {
        authorize(access == null ? null : access.tenantId(), access);
        write("APPROVAL", receipt.candidateId(), receipt.fingerprint(), receipt, access);
    }

    @Override public Optional<WorldDraftApproval> findApproval(String tenantId, String candidateId,
                                                                String fingerprint,
                                                                WorldDraftCandidateService.Access access) {
        authorize(tenantId, access);
        return read("APPROVAL", tenantId, candidateId, fingerprint, WorldDraftApproval.class);
    }

    @Override public void savePublication(WorldDraftPublicationReceipt receipt,
                                          WorldDraftCandidateService.Access access) {
        authorize(receipt == null ? null : access == null ? null : access.tenantId(), access);
        if (receipt == null) throw invalid();
        write("PUBLICATION", receipt.candidateId(), InMemoryWorldDraftAssetRepository.receiptFingerprint(receipt),
                receipt, access);
    }

    @Override public Optional<WorldDraftPublicationReceipt> findPublication(String tenantId, String candidateId,
                                                                             String fingerprint,
                                                                             WorldDraftCandidateService.Access access) {
        authorize(tenantId, access);
        return read("PUBLICATION", tenantId, candidateId, fingerprint, WorldDraftPublicationReceipt.class);
    }

    private void write(String kind, String candidateId, String fingerprint, Object receipt,
                       WorldDraftCandidateService.Access access) {
        try {
            String json = mapper.writeValueAsString(receipt);
            jdbc.update("INSERT INTO rg_world_draft_authority_receipts"
                            + "(tenant_id,candidate_id,receipt_kind,receipt_fingerprint,canonical_json)"
                            + " VALUES (?,?,?,?,%s)".formatted(postgres ? "CAST(? AS JSONB)" : "?"),
                    access.tenantId(), candidateId, kind, fingerprint, json);
        } catch (DuplicateKeyException ignored) {
            // Receipt writes are idempotent; a subsequent exact read verifies the stored value.
        } catch (Exception failure) { throw invalid(); }
    }

    private <T> Optional<T> read(String kind, String tenantId, String candidateId, String fingerprint,
                                  Class<T> type) {
        if (candidateId == null || fingerprint == null) return Optional.empty();
        return jdbc.query("SELECT canonical_json FROM rg_world_draft_authority_receipts"
                        + " WHERE tenant_id=? AND candidate_id=? AND receipt_kind=? AND receipt_fingerprint=?",
                result -> result.next() ? Optional.of(decode(result.getString(1), type, candidateId, fingerprint))
                        : Optional.empty(),
                tenantId, candidateId, kind, fingerprint);
    }

    private <T> T decode(String json, Class<T> type, String candidateId, String fingerprint) {
        try {
            T receipt = mapper.readValue(json, type);
            if (receipt instanceof WorldDraftApproval approval
                    && (!candidateId.equals(approval.candidateId()) || !fingerprint.equals(approval.fingerprint()))) {
                throw invalid();
            }
            if (receipt instanceof WorldDraftPublicationReceipt publication
                    && (!candidateId.equals(publication.candidateId())
                    || !fingerprint.equals(InMemoryWorldDraftAssetRepository.receiptFingerprint(publication)))) {
                throw invalid();
            }
            return receipt;
        }
        catch (Exception failure) { throw invalid(); }
    }

    private static void authorize(String tenant, WorldDraftCandidateService.Access access) {
        if (tenant == null || access == null || !tenant.equals(access.tenantId())) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
    }
    private static boolean isPostgres(JdbcTemplate jdbc) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (Exception failure) { throw invalid(); }
    }
    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
    }
}
