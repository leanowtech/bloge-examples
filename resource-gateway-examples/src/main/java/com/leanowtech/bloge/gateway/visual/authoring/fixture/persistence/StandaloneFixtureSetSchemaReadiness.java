package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Read-only startup probe for the externally applied V016 standalone Fixture schema. */
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
                       subject_publication_id, subject_revision, subject_fingerprint,
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
    }

    private static void probe(JdbcTemplate jdbc, String sql) {
        jdbc.query(sql, result -> { });
    }
}
