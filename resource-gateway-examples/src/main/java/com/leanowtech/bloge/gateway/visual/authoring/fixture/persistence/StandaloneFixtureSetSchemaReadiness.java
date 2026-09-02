package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Read-only startup probe for the externally applied V016–V019 standalone Fixture schema. */
public final class StandaloneFixtureSetSchemaReadiness {
    /** Fails startup before authoring is exposed when any required authority column is absent. */
    public StandaloneFixtureSetSchemaReadiness(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        probe(jdbc, """
                SELECT tenant_id, project_id, environment_id, fixture_set_id
                  FROM rg_authoring_standalone_fixture_identities WHERE 1=0
                """);
        probe(jdbc, """
                SELECT fixture_set_id, revision, fixture_fingerprint,
                       subject_kind, subject_publication_id, subject_revision,
                       subject_member_id, subject_fingerprint, subject_runtime_fingerprint,
                       generated_json, strong_etag, committed_by
                  FROM rg_authoring_standalone_fixture_revisions WHERE 1=0
                """);
        probe(jdbc, """
                SELECT fixture_set_id, revision, fixture_fingerprint, strong_etag
                  FROM rg_authoring_standalone_fixture_heads WHERE 1=0
                """);
        probe(jdbc, """
                SELECT actor_id, fixture_set_id, idempotency_key, request_fingerprint,
                       expected_mode, expected_revision, committed_revision,
                       receipt_json, strong_etag
                  FROM rg_authoring_standalone_fixture_commands WHERE 1=0
                """);
        probe(jdbc, """
                SELECT actor_id, fixture_set_id, idempotency_key, request_fingerprint,
                       source_revision, source_fingerprint, source_status_revision,
                       source_strong_etag, committed_revision, receipt_json, strong_etag
                  FROM rg_authoring_fixture_share_commands WHERE 1=0
                """);
        probe(jdbc, """
                SELECT review_request_id, fixture_set_id, source_revision, source_fingerprint,
                       source_status_revision, source_strong_etag, derived_revision,
                       derived_fingerprint, derived_status_revision, derived_strong_etag,
                       policy_json, status, created_by, completed_revision,
                       completed_fingerprint, completed_strong_etag, completed_by, completed_at
                  FROM rg_authoring_fixture_review_requests WHERE 1=0
                """);
        probe(jdbc, """
                SELECT actor_id, fixture_set_id, idempotency_key, request_fingerprint,
                       review_request_id, source_revision, source_fingerprint,
                       source_status_revision, source_strong_etag, committed_revision,
                       receipt_json, strong_etag
                  FROM rg_authoring_fixture_review_commands WHERE 1=0
                """);
    }

    private static void probe(JdbcTemplate jdbc, String sql) {
        jdbc.query(sql, result -> { });
    }
}
