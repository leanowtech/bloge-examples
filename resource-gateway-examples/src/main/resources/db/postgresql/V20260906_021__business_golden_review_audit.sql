-- Independent human attribution for protected business GOLDEN review access.
-- Business values, protected receipts, credentials and exception text are forbidden.

CREATE TABLE IF NOT EXISTS rg_business_golden_review_audit (
    access_id VARCHAR(512) PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    case_set_ref VARCHAR(512) NOT NULL,
    case_id VARCHAR(512) NOT NULL,
    actor_id VARCHAR(512) NOT NULL,
    purpose VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(512) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS rg_business_golden_review_audit_coordinate_idx
    ON rg_business_golden_review_audit (
        tenant_id, organization_id, project_id, environment_id, region_id,
        case_set_ref, case_id, occurred_at
    );
