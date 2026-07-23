package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMirrorOperationAuditRepositoryTest {
    private JdbcTemplate jdbc;
    private DatabaseMirrorOperationAuditRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:mirror-operation-audit-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseMirrorOperationAuditRepository(jdbc);
        repository.init();
    }

    @Test
    void appendsWithDatabaseCoordinatesAndSurvivesRepositoryRestart() {
        MirrorOperationAuditEvent first = repository.append(success(scope("org-a"), "plan-1"));
        MirrorOperationAuditEvent second = repository.append(failure(scope("org-a"), "plan-2"));

        assertThat(first.sequence()).isPositive();
        assertThat(first.occurredAt()).isNotNull();
        assertThat(second.sequence()).isGreaterThan(first.sequence());

        DatabaseMirrorOperationAuditRepository restarted =
                new DatabaseMirrorOperationAuditRepository(jdbc);
        restarted.init();
        assertThat(restarted.recent(scope("org-a"), 10))
                .extracting(MirrorOperationAuditEvent::planId)
                .containsExactly("plan-2", "plan-1");
        assertThat(restarted.recent(scope("org-a"), 1)).containsExactly(second);
    }

    @Test
    void readsOnlyTheCompleteEnterpriseScope() {
        repository.append(success(scope("org-a"), "plan-org-a"));
        repository.append(success(scope("org-b"), "plan-org-b"));
        repository.append(success(new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "billing", "test", "sg"), "plan-billing"));

        assertThat(repository.recent(scope("org-a"), 10))
                .extracting(MirrorOperationAuditEvent::planId)
                .containsExactly("plan-org-a");
        assertThat(repository.recent(scope("org-b"), 10))
                .extracting(MirrorOperationAuditEvent::planId)
                .containsExactly("plan-org-b");
        assertThat(repository.recent(new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "support", "test", "eu"), 10)).isEmpty();
    }

    @Test
    void schemaCanRepresentOnlyPayloadFreeBoundedAuditFacts() {
        repository.append(failure(scope("org-a"), "plan-1"));

        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_OPERATION_AUDIT'
                ORDER BY ORDINAL_POSITION
                """, String.class);

        assertThat(columns).containsExactly(
                "SEQUENCE", "OCCURRED_AT", "TENANT_ID", "ORGANIZATION_ID", "PROJECT_ID",
                "ENVIRONMENT_ID", "REGION", "CORRELATION_ID", "ACTOR_TYPE", "ACTOR_ID",
                "OPERATION", "OUTCOME", "REASON", "REASON_CODE", "REQUEST_ID", "PLAN_ID",
                "RUN_ID", "DURATION_MILLIS");
        assertThat(columns).noneMatch(column ->
                column.contains("PAYLOAD") || column.contains("CONTEXT")
                        || column.contains("FIXTURE") || column.contains("REPLAY")
                        || column.contains("INPUT") || column.contains("OUTPUT")
                        || column.contains("ERROR") || column.contains("STACK")
                        || column.contains("MESSAGE"));
    }

    @Test
    void eventRejectsInconsistentOrUnboundedFailureDimensions() {
        MirrorOperationAuditEvent event = failure(scope("org-a"), "plan-1");
        MirrorOperationAuditEvent namespaced = new MirrorOperationAuditEvent(
                0, null, event.tenantId(), event.organizationId(), event.projectId(),
                event.environmentId(), event.region(), event.correlationId(), event.actorType(),
                event.actorId(), event.operation(), MirrorOperationAuditEvent.Outcome.REJECTED,
                MirrorOperationAuditEvent.Reason.INVALID_REQUEST,
                "RG.MIRROR.SESSION.BINDING_REQUIRED",
                "", event.planId(), "", 1);

        assertThat(namespaced.reasonCode())
                .isEqualTo("RG.MIRROR.SESSION.BINDING_REQUIRED");
        assertThatThrownBy(() -> new MirrorOperationAuditEvent(
                0, null, event.tenantId(), event.organizationId(), event.projectId(),
                event.environmentId(), event.region(), event.correlationId(), event.actorType(),
                event.actorId(), event.operation(), MirrorOperationAuditEvent.Outcome.SUCCEEDED,
                MirrorOperationAuditEvent.Reason.CONFLICT, "RG.MIRROR.PLAN_CONFLICT",
                "", event.planId(), "", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
        assertThatThrownBy(() -> new MirrorOperationAuditEvent(
                0, null, event.tenantId(), event.organizationId(), event.projectId(),
                event.environmentId(), event.region(), event.correlationId(), event.actorType(),
                event.actorId(), event.operation(), MirrorOperationAuditEvent.Outcome.REJECTED,
                MirrorOperationAuditEvent.Reason.CONFLICT, "customer-secret-value",
                "", event.planId(), "", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason code");
    }

    private static MirrorOperationAuditEvent success(
            CapabilitySnapshot.Scope scope, String planId) {
        return event(scope, planId, MirrorOperationAuditEvent.Outcome.SUCCEEDED,
                MirrorOperationAuditEvent.Reason.NONE, "");
    }

    private static MirrorOperationAuditEvent failure(
            CapabilitySnapshot.Scope scope, String planId) {
        return event(scope, planId, MirrorOperationAuditEvent.Outcome.REJECTED,
                MirrorOperationAuditEvent.Reason.CONFLICT, "RG.MIRROR.PLAN_CONFLICT");
    }

    private static MirrorOperationAuditEvent event(
            CapabilitySnapshot.Scope scope,
            String planId,
            MirrorOperationAuditEvent.Outcome outcome,
            MirrorOperationAuditEvent.Reason reason,
            String reasonCode) {
        return new MirrorOperationAuditEvent(0, null,
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), "corr-1", "service", "test-client",
                MirrorOperationAuditEvent.Operation.PLAN_CREATE, outcome, reason, reasonCode,
                "", planId, "", 23);
    }

    private static CapabilitySnapshot.Scope scope(String organization) {
        return new CapabilitySnapshot.Scope(
                "tenant-a", organization, "support", "test", "sg");
    }
}
