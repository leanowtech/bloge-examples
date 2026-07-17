package com.leanowtech.bloge.gateway.testing.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionIntent;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.Kind;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRuntimeAdmissionCoordinatorTest {

    private JdbcTemplate jdbc;
    private TestRuntimeAdmissionCoordinator coordinator;
    private IntegrationRequestContext identity;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        jdbc = new JdbcTemplate(dataSource);
        DatabaseTestRuntimeAdmissionControl control =
                new DatabaseTestRuntimeAdmissionControl(
                        jdbc, new DataSourceTransactionManager(dataSource));
        coordinator = new TestRuntimeAdmissionCoordinator(
                control,
                new TestRuntimeAdmissionPolicy(
                        1, 2, 1, 1, 1,
                        Duration.ofSeconds(30), Duration.ofSeconds(5)),
                new ObjectMapper().findAndRegisterModules(),
                TestRuntimeAdmissionTelemetry.noop(), "replica-a");
        identity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD",
                "runner", "", "TEST_EXECUTION", "correlation-a",
                Set.of("test-operators"), "CONFIDENTIAL", "");
    }

    @AfterEach
    void tearDown() {
        coordinator.close();
    }

    @Test
    void hashesSubjectsRejectsTheSaturatedDimensionAndRecoversAfterRelease() {
        AdmissionGuard first = coordinator.admit(identity,
                intent(Kind.GRAPH, "run-a", "", Set.of("operator-a"), Set.of("resource-a")));

        assertThatThrownBy(() -> coordinator.admit(identity,
                intent(Kind.GRAPH, "run-b", "", Set.of("operator-a"), Set.of("resource-b"))))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(429);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.ADMISSION_QUOTA_EXCEEDED");
                    assertThat(failure.problem().details())
                            .containsEntry("dimension", "OPERATOR")
                            .containsEntry("scope", "OPERATOR")
                            .containsEntry("maxActive", 1L)
                            .containsEntry("active", 1L)
                            .containsKey("retryAfterSeconds");
                    assertThat(failure.problem().toString())
                            .doesNotContain("operator-a", "resource-a", "tenant-a");
                });

        List<String> subjectKeys = jdbc.queryForList(
                "SELECT subject_key FROM rg_test_admission_subject_policies", String.class);
        assertThat(subjectKeys).hasSize(4)
                .allMatch(value -> value.matches("sha256:[a-f0-9]{64}"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_admission_claims", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_admission_leases", Long.class)).isEqualTo(1);

        first.close();
        try (AdmissionGuard admitted = coordinator.admit(identity,
                intent(Kind.GRAPH, "run-b", "", Set.of("operator-a"), Set.of("resource-b")))) {
            admitted.checkpoint();
        }
    }

    @Test
    void suiteReservesTenantSuiteOperatorAndDependencyAsOnePermit() {
        try (AdmissionGuard guard = coordinator.admit(identity,
                intent(Kind.SUITE, "suite-request", "suite-a",
                        Set.of("operator-a"), Set.of("resource-a")))) {
            assertThat(jdbc.queryForList(
                    "SELECT dimension FROM rg_test_admission_claims ORDER BY dimension",
                    String.class)).containsExactly("DEPENDENCY", "OPERATOR", "SUITE", "TENANT");
            guard.checkpoint();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_admission_claims", Long.class)).isZero();
    }

    @Test
    void stableRequestKeyCannotBeReboundToAnotherIntentWhileLive() {
        try (AdmissionGuard ignored = coordinator.admit(identity,
                intent(Kind.DURABLE_CREATION, "create-a", "",
                        Set.of("operator-a"), Set.of()))) {
            assertThatThrownBy(() -> coordinator.admit(identity,
                    intent(Kind.DURABLE_CREATION, "create-a", "",
                            Set.of("operator-b"), Set.of())))
                    .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                        assertThat(failure.problem().status()).isEqualTo(409);
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.TEST.ADMISSION_IDEMPOTENCY_CONFLICT");
                        assertThat(failure.problem().details()).isEmpty();
                    });
        }
    }

    @Test
    void invalidReplicaIdentityFailsAtCompositionTime() {
        assertThatThrownBy(() -> new TestRuntimeAdmissionCoordinator(
                org.mockito.Mockito.mock(DatabaseTestRuntimeAdmissionControl.class),
                new TestRuntimeAdmissionPolicy(
                        1, 1, 1, 1, 1,
                        Duration.ofSeconds(30), Duration.ofSeconds(5)),
                new ObjectMapper(), TestRuntimeAdmissionTelemetry.noop(), "bad owner value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance-id");
    }

    @Test
    void shutdownInvalidatesAndReleasesEveryLocalPermit() {
        AdmissionGuard guard = coordinator.admit(identity,
                intent(Kind.GRAPH, "shutdown-a", "",
                        Set.of("operator-a"), Set.of("resource-a")));

        coordinator.close();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_admission_claims", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_admission_leases", Long.class)).isZero();
        assertThatThrownBy(guard::checkpoint)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.ADMISSION_LEASE_LOST");
                });
        assertThatThrownBy(() -> coordinator.admit(identity,
                intent(Kind.GRAPH, "shutdown-b", "",
                        Set.of("operator-b"), Set.of())))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.ADMISSION_COORDINATOR_CLOSED");
                });
    }

    private static AdmissionIntent intent(
            Kind kind,
            String stableKey,
            String suiteRef,
            Set<String> operatorRefs,
            Set<String> dependencyRefs) {
        return new AdmissionIntent(
                kind, stableKey,
                ProtocolFingerprint.ofText(kind + ":" + stableKey + ":"
                        + operatorRefs + ":" + dependencyRefs),
                suiteRef, operatorRefs, dependencyRefs);
    }
}
