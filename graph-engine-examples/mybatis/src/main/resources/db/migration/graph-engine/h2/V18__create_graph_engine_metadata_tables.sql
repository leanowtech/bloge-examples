CREATE TABLE ge_definition (
    definition_id    VARCHAR(64)  NOT NULL,
    definition_key   VARCHAR(256) NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
    namespace        VARCHAR(128) NOT NULL DEFAULT 'default',
    display_name     VARCHAR(512),
    description      TEXT,
    category         VARCHAR(32)  NOT NULL DEFAULT 'PIPELINE',
    labels_json      TEXT,
    owner_team       VARCHAR(256),
    rbac_policy_json TEXT,
    status           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    revision         BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    PRIMARY KEY (definition_id),
    CONSTRAINT uq_ge_definition_key UNIQUE (tenant_id, namespace, definition_key)
);

CREATE TABLE ge_version (
    version_id            VARCHAR(64)  NOT NULL,
    definition_id         VARCHAR(64)  NOT NULL,
    version               VARCHAR(64)  NOT NULL,
    content_hash          VARCHAR(128) NOT NULL,
    dsl_source            TEXT         NOT NULL,
    visual_layout         TEXT,
    metadata_json         TEXT,
    compiled_artifact_ref VARCHAR(128),
    migration_policy      VARCHAR(32)  NOT NULL DEFAULT 'PIN_VERSION',
    status                VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    revision              BIGINT       NOT NULL DEFAULT 0,
    published_at          TIMESTAMP,
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL,
    PRIMARY KEY (version_id),
    CONSTRAINT uq_ge_version_semver UNIQUE (definition_id, version)
);

CREATE TABLE ge_deployment (
    deployment_id       VARCHAR(64)  NOT NULL,
    definition_key      VARCHAR(256) NOT NULL,
    tenant_id           VARCHAR(64)  NOT NULL DEFAULT 'default',
    namespace           VARCHAR(128) NOT NULL DEFAULT 'default',
    environment         VARCHAR(64)  NOT NULL,
    routing_policy_json TEXT,
    operator_plane_json TEXT,
    is_active           BOOLEAN      NOT NULL DEFAULT FALSE,
    revision            BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    PRIMARY KEY (deployment_id)
);

CREATE TABLE ge_instance (
    instance_id     VARCHAR(64)  NOT NULL,
    definition_key  VARCHAR(256) NOT NULL,
    version_id      VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
    namespace       VARCHAR(128) NOT NULL DEFAULT 'default',
    business_key    VARCHAR(256),
    execution_mode  VARCHAR(32)  NOT NULL DEFAULT 'GRAPH',
    status          VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
    initiator       VARCHAR(256),
    variables_json  TEXT,
    revision        BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    completed_at    TIMESTAMP,
    PRIMARY KEY (instance_id)
);

CREATE INDEX idx_ge_definition_status ON ge_definition (tenant_id, namespace, status);
CREATE INDEX idx_ge_definition_category ON ge_definition (category, status);
CREATE INDEX idx_ge_version_status ON ge_version (definition_id, status, published_at);
CREATE INDEX idx_ge_deployment_active ON ge_deployment (tenant_id, namespace, definition_key, environment, is_active);
CREATE INDEX idx_ge_instance_status ON ge_instance (tenant_id, namespace, definition_key, status);
CREATE INDEX idx_ge_instance_business_key ON ge_instance (tenant_id, namespace, business_key);
