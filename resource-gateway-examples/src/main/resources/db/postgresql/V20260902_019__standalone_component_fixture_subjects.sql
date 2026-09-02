ALTER TABLE rg_authoring_standalone_fixture_revisions
    DROP CONSTRAINT rg_authoring_standalone_fixture_revisions_flow_version_fk;

ALTER TABLE rg_authoring_standalone_fixture_revisions
    ADD COLUMN subject_kind VARCHAR(32) NOT NULL DEFAULT 'FLOW_VERSION';

ALTER TABLE rg_authoring_standalone_fixture_revisions
    ADD COLUMN subject_member_id VARCHAR(128);

ALTER TABLE rg_authoring_standalone_fixture_revisions
    ADD COLUMN subject_runtime_fingerprint VARCHAR(71);

ALTER TABLE rg_authoring_standalone_fixture_revisions
    ADD CONSTRAINT rg_authoring_standalone_fixture_revisions_subject_shape_ck CHECK (
        (subject_kind IN ('FLOW_DRAFT', 'FLOW_VERSION')
            AND subject_member_id IS NULL
            AND subject_runtime_fingerprint IS NULL)
        OR (subject_kind = 'OPERATOR_VERSION'
            AND subject_member_id IS NOT NULL
            AND subject_runtime_fingerprint IS NULL)
        OR (subject_kind = 'BUILTIN_FUNCTION_VERSION'
            AND subject_member_id IS NOT NULL
            AND subject_runtime_fingerprint IS NOT NULL
            AND CHAR_LENGTH(subject_runtime_fingerprint) = 71
            AND subject_runtime_fingerprint LIKE 'sha256:%')
    );

CREATE INDEX rg_authoring_standalone_fixture_exact_subject_idx
    ON rg_authoring_standalone_fixture_revisions
        (tenant_id, project_id, environment_id, subject_kind,
         subject_publication_id, subject_revision, subject_member_id,
         subject_fingerprint, subject_runtime_fingerprint, fixture_set_id, revision);
