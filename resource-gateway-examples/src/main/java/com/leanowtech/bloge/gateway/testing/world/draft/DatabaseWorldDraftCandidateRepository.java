package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;

/** JDBC candidate store with immutable revision rows and optimistic CAS heads. */
public final class DatabaseWorldDraftCandidateRepository implements WorldDraftCandidateRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorldDraftAuditSink audit;
    private final boolean postgres;

    public DatabaseWorldDraftCandidateRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, WorldDraftAuditSink.noop());
    }

    public DatabaseWorldDraftCandidateRepository(JdbcTemplate jdbc, ObjectMapper mapper,
                                                  WorldDraftAuditSink audit) {
        if (jdbc == null || jdbc.getDataSource() == null || mapper == null || audit == null) throw invalid();
        this.jdbc = jdbc; this.mapper = mapper.copy(); this.audit = audit;
        this.postgres = isPostgres(jdbc);
    }

    @Override public WorldDraftCandidate create(WorldDraftCandidate candidate) {
        if (candidate == null || candidate.revision() != 1
                || candidate.state() != WorldDraftState.CAPTURED) throw invalid();
        String json = encode(candidate);
        try {
            jdbc.update("INSERT INTO rg_world_draft_candidates(candidate_id,tenant_id,revision,candidate_fingerprint,canonical_json) VALUES (?,?,?,?,%s)".formatted(jsonParameter()),
                    candidate.candidateId(), candidate.tenantId(), candidate.revision(), seal(json), json);
            audit.record(candidate.tenantId(), candidate.candidateId(), "CREATE", candidate.revision(), true);
            return candidate;
        } catch (DuplicateKeyException failure) {
            audit.record(candidate.tenantId(), candidate.candidateId(), "CREATE", candidate.revision(), false);
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.CAS_CONFLICT);
        }
    }

    @Override public Optional<WorldDraftCandidate> find(String tenantId, String candidateId) {
        if (tenantId == null || tenantId.isBlank() || candidateId == null || candidateId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT canonical_json FROM rg_world_draft_candidates WHERE tenant_id=? AND candidate_id=?",
                rs -> rs.next() ? Optional.of(decode(rs.getString(1))) : Optional.empty(), tenantId, candidateId);
    }

    @Override public boolean compareAndSet(WorldDraftCandidate expected, WorldDraftCandidate replacement) {
        if (expected == null || replacement == null || !expected.tenantId().equals(replacement.tenantId())
                || !expected.candidateId().equals(replacement.candidateId())
                || replacement.revision() != expected.revision() + 1
                || !expected.state().mayAdvanceTo(replacement.state())) return false;
        String json = encode(replacement);
        int updated = jdbc.update(("UPDATE rg_world_draft_candidates SET tenant_id=?,revision=?,candidate_fingerprint=?,canonical_json=%s,updated_at=CURRENT_TIMESTAMP "
                + "WHERE tenant_id=? AND candidate_id=? AND revision=? AND candidate_fingerprint=?").formatted(jsonParameter()),
                replacement.tenantId(), replacement.revision(), seal(json), json, expected.tenantId(), expected.candidateId(),
                expected.revision(), seal(encode(expected)));
        audit.record(replacement.tenantId(), replacement.candidateId(), "CAS", replacement.revision(), updated == 1);
        return updated == 1;
    }

    private String encode(WorldDraftCandidate candidate) {
        try { return mapper.writeValueAsString(candidate); }
        catch (Exception failure) { throw invalid(); }
    }
    private WorldDraftCandidate decode(String json) {
        try { return mapper.readValue(json, WorldDraftCandidate.class); }
        catch (Exception failure) { throw invalid(); }
    }
    private static String seal(String json) { return VisualBundleFingerprint.fromMaterial(Map.of("json", json)); }
    private String jsonParameter() { return postgres ? "CAST(? AS JSONB)" : "?"; }
    private static boolean isPostgres(JdbcTemplate jdbc) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (Exception failure) {
            throw invalid();
        }
    }
    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
    }
}
