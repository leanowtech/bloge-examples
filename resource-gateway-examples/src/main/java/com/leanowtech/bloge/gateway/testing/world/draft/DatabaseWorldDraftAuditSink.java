package com.leanowtech.bloge.gateway.testing.world.draft;

import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC payload-free audit adapter for draft persistence operations. */
public final class DatabaseWorldDraftAuditSink implements WorldDraftAuditSink {
    private final JdbcTemplate jdbc;
    public DatabaseWorldDraftAuditSink(JdbcTemplate jdbc) { if (jdbc == null) throw invalid(); this.jdbc = jdbc; }
    @Override public void record(String tenantId, String candidateId, String operation,
                                 long revision, boolean success) {
        jdbc.update("INSERT INTO rg_world_draft_audit(tenant_id,candidate_id,operation,revision,success) VALUES (?,?,?,?,?)",
                tenantId, candidateId, operation, revision, success);
    }
    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
    }
}
