package com.leanowtech.bloge.gateway.solution.journey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import static com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenReviewAuditRepository.BusinessGoldenReviewAccess;

/** JDBC append-only implementation of the payload-free human GOLDEN review audit. */
@Repository
public class DatabaseBusinessGoldenReviewAuditRepository
        implements BusinessGoldenReviewAuditRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** Creates the production audit writer with server time. */
    @Autowired
    public DatabaseBusinessGoldenReviewAuditRepository(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    /** Creates a focused audit writer with deterministic server time. */
    DatabaseBusinessGoldenReviewAuditRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public BusinessGoldenReviewAccess append(BusinessGoldenReviewAccess event) {
        Objects.requireNonNull(event, "event");
        if (event.occurredAt() != null) {
            throw new IllegalArgumentException("New GOLDEN review audit events cannot supply occurrence time");
        }
        Instant occurredAt = clock.instant();
        jdbc.update("""
                        INSERT INTO rg_business_golden_review_audit (
                            access_id, tenant_id, organization_id, project_id,
                            environment_id, region_id, case_set_ref, case_id,
                            actor_id, purpose, action, outcome, correlation_id, occurred_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.accessId(), event.tenantId(), event.organizationId(), event.projectId(),
                event.environmentId(), event.region(), event.caseSetRef(), event.caseId(),
                event.actorId(), event.purpose(), event.action(), event.outcome(),
                event.correlationId(), occurredAt);
        return new BusinessGoldenReviewAccess(event.accessId(), event.tenantId(),
                event.organizationId(), event.projectId(), event.environmentId(), event.region(),
                event.caseSetRef(), event.caseId(), event.actorId(), event.purpose(), event.action(),
                event.outcome(), event.correlationId(), occurredAt);
    }
}
