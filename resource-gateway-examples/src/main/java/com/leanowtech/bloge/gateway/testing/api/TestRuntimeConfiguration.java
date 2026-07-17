package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.TestabilityAvailability;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionCoordinator;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionPolicy;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionRetentionScheduler;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionTelemetry;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseFixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseReplayPayloadRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSecurityEventRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeSloControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl;
import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestRuntimeResources;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestCreationRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.CompiledTestRuntimeOptions;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestTerminalRecoveryRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.IndependentDurableTestEngineFactory;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.durable.codec.JacksonCheckpointCodec;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.UUID;

/** Profile-gated composition root for the isolated test control plane. */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
public class TestRuntimeConfiguration {

    /** Creates the profile-gated Spring composition root. */
    public TestRuntimeConfiguration() {
    }

    @Bean(destroyMethod = "close")
    TestRuntimeDatabase testRuntimeDatabase(
            @Value("${gateway.testing.store.jdbc-url:jdbc:h2:file:./data/resource-gateway-test-runtime;AUTO_SERVER=TRUE}")
            String jdbcUrl,
            @Value("${gateway.testing.store.username:sa}") String username,
            @Value("${gateway.testing.store.password:}") String password,
            @Value("${gateway.testing.store.maximum-pool-size:4}") int maximumPoolSize) {
        return new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                jdbcUrl, username, password, maximumPoolSize));
    }

    /** Computes nested content identities for composite durable-test checkpoints. */
    @Bean
    DurableTestExecutionCheckpointIntegrity durableTestExecutionCheckpointIntegrity(
            ObjectMapper objectMapper) {
        return new DurableTestExecutionCheckpointIntegrity(objectMapper);
    }

    /**
     * Stores control closure and transaction-participating BLOGE state under one fenced revision.
     */
    @Bean
    DurableTestExecutionCheckpointRepository durableTestExecutionCheckpointRepository(
            TestRuntimeDatabase database, ObjectMapper objectMapper,
            DurableTestExecutionCheckpointIntegrity integrity) {
        return new DatabaseDurableTestExecutionCheckpointRepository(
                database.jdbc(), database.transactionManager(), objectMapper, integrity);
    }

    /**
     * Creates exact-checkpoint quarantine maintenance after its automatic authority is initialized.
     */
    @Bean
    DatabaseDurableWorkerQuarantineControlPlane durableWorkerQuarantineControlPlane(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            DurableTestExecutionCheckpointRepository checkpointAuthority) {
        java.util.Objects.requireNonNull(checkpointAuthority, "checkpointAuthority");
        return new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper);
    }

    /** Assembles the scoped, authenticated, action-audited quarantine owner queue. */
    @Bean
    DurableWorkerQuarantineService durableWorkerQuarantineService(
            DatabaseDurableWorkerQuarantineControlPlane controlPlane,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            @Value("${gateway.testing.durable.worker-quarantines.required-group:resource-gateway-test-runtime-operators}")
            String requiredGroup,
            @Value("${gateway.testing.durable.worker-quarantines.required-clearance:RESTRICTED}")
            String requiredClearance) {
        return new DurableWorkerQuarantineService(
                controlPlane, securityEvents, objectMapper, requiredGroup, requiredClearance);
    }

    /**
     * Creates durable test resources without exposing the BLOGE store as a global autowire target.
     */
    @Bean
    DurableTestRuntimeResources durableTestRuntimeResources(
            TestRuntimeDatabase database, ObjectMapper objectMapper, OperatorRegistry operatorRegistry) {
        StagedBlogeDurableStateStore durableStateStore =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        durableStateStore.init();
        IndependentDurableTestEngineFactory engineFactory =
                new IndependentDurableTestEngineFactory(operatorRegistry,
                        new JacksonCheckpointCodec(objectMapper), durableStateStore);
        return new DurableTestRuntimeResources(engineFactory);
    }

    /** Creates the database-leased cursor and finding authority for projection anti-entropy. */
    @Bean
    DatabaseDurableStateProjectionControlPlane durableStateProjectionControlPlane(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            @Value("${gateway.testing.durable.projection-reconciliation.instance-id:}")
            String instanceId,
            @Value("${gateway.testing.durable.projection-reconciliation.lease-duration-seconds:120}")
            long leaseDurationSeconds) {
        String owner = instanceId == null || instanceId.isBlank()
                ? "projection-reconciler-" + UUID.randomUUID() : instanceId.trim();
        return new DatabaseDurableStateProjectionControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper, owner,
                Duration.ofSeconds(leaseDurationSeconds));
    }

    /** Registers projection metrics with fixed tag vocabularies and aggregate-only values. */
    @Bean
    DurableStateProjectionTelemetry durableStateProjectionTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new DurableStateProjectionTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Assembles the globally authorized, action-audited projection finding owner queue. */
    @Bean
    DurableStateProjectionFindingService durableStateProjectionFindingService(
            DatabaseDurableStateProjectionControlPlane controlPlane,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            @Value("${gateway.testing.durable.projection-findings.required-group:resource-gateway-test-runtime-operators}")
            String requiredGroup,
            @Value("${gateway.testing.durable.projection-findings.required-clearance:RESTRICTED}")
            String requiredClearance) {
        return new DurableStateProjectionFindingService(
                controlPlane, securityEvents, objectMapper, requiredGroup, requiredClearance);
    }

    /** Runs bounded projection anti-entropy only inside the isolated test/staging control plane. */
    @Bean
    DurableStateProjectionReconciliationScheduler durableStateProjectionReconciliationScheduler(
            DatabaseDurableStateProjectionControlPlane controlPlane,
            DurableStateProjectionTelemetry telemetry,
            @Value("${gateway.testing.durable.projection-reconciliation-page-size:100}")
            int pageSize,
            @Value("${gateway.testing.durable.projection-reconciliation-mode:REPAIR_DERIVED}")
            String repairMode) {
        return new DurableStateProjectionReconciliationScheduler(
                controlPlane, pageSize,
                DurableStateProjectionReconciler.RepairMode.parse(repairMode), telemetry);
    }

    /** Archives and purges resolved projection findings in bounded database-leased pages. */
    @Bean
    DurableStateProjectionFindingRetentionScheduler
            durableStateProjectionFindingRetentionScheduler(
                    DatabaseDurableStateProjectionControlPlane controlPlane,
                    DurableStateProjectionTelemetry telemetry,
                    @Value("${gateway.testing.durable.projection-findings.resolved-retention-days:30}")
                    long resolvedRetentionDays,
                    @Value("${gateway.testing.durable.projection-findings.archive-retention-days:365}")
                    long archiveRetentionDays,
                    @Value("${gateway.testing.durable.projection-findings.retention-page-size:100}")
                    int pageSize) {
        return new DurableStateProjectionFindingRetentionScheduler(
                controlPlane, Duration.ofDays(resolvedRetentionDays),
                Duration.ofDays(archiveRetentionDays), pageSize, telemetry);
    }

    /** Assesses durable projection freshness and backlog policy from the database clock. */
    @Bean
    DurableStateProjectionSloMonitor durableStateProjectionSloMonitor(
            DatabaseDurableStateProjectionControlPlane controlPlane,
            DurableStateProjectionTelemetry telemetry,
            @Value("${gateway.testing.durable.projection-findings.resolved-retention-days:30}")
            long resolvedRetentionDays,
            @Value("${gateway.testing.durable.projection-findings.archive-retention-days:365}")
            long archiveRetentionDays,
            @Value("${gateway.testing.durable.projection-slo.startup-grace-seconds:180}")
            long startupGraceSeconds,
            @Value("${gateway.testing.durable.projection-slo.max-reconciliation-staleness-seconds:180}")
            long reconciliationStalenessSeconds,
            @Value("${gateway.testing.durable.projection-slo.max-retention-staleness-seconds:10800}")
            long retentionStalenessSeconds,
            @Value("${gateway.testing.durable.projection-slo.max-unresolved-findings:0}")
            long maxUnresolvedFindings,
            @Value("${gateway.testing.durable.projection-slo.max-unresolved-age-seconds:3600}")
            long maxUnresolvedAgeSeconds,
            @Value("${gateway.testing.durable.projection-slo.max-overdue-resolved-findings:0}")
            long maxOverdueResolvedFindings,
            @Value("${gateway.testing.durable.projection-slo.max-overdue-archive-records:0}")
            long maxOverdueArchiveRecords) {
        return new DurableStateProjectionSloMonitor(controlPlane, telemetry,
                new DurableStateProjectionSloMonitor.Policy(
                        Duration.ofDays(resolvedRetentionDays),
                        Duration.ofDays(archiveRetentionDays),
                        Duration.ofSeconds(startupGraceSeconds),
                        Duration.ofSeconds(reconciliationStalenessSeconds),
                        Duration.ofSeconds(retentionStalenessSeconds),
                        maxUnresolvedFindings,
                        Duration.ofSeconds(maxUnresolvedAgeSeconds),
                        maxOverdueResolvedFindings,
                        maxOverdueArchiveRecords));
    }

    /** Registers global test-runtime metrics with closed status and queue vocabularies. */
    @Bean
    TestRuntimeSloTelemetry testRuntimeSloTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new TestRuntimeSloTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Creates the database-authoritative all-or-nothing quota and lease protocol. */
    @Bean
    DatabaseTestRuntimeAdmissionControl testRuntimeAdmissionControl(
            TestRuntimeDatabase database) {
        return new DatabaseTestRuntimeAdmissionControl(
                database.jdbc(), database.transactionManager());
    }

    /** Registers only fixed admission result and quota-dimension metric series. */
    @Bean
    TestRuntimeAdmissionTelemetry testRuntimeAdmissionTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new TestRuntimeAdmissionTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Builds the versioned cross-replica quota policy without silently normalizing bad values.
     */
    @Bean
    TestRuntimeAdmissionPolicy testRuntimeAdmissionPolicy(
            @Value("${gateway.testing.admission.policy-generation:1}") long generation,
            @Value("${gateway.testing.admission.tenant-max-active:16}") long tenantMaxActive,
            @Value("${gateway.testing.admission.suite-max-active:2}") long suiteMaxActive,
            @Value("${gateway.testing.admission.operator-max-active:8}") long operatorMaxActive,
            @Value("${gateway.testing.admission.dependency-max-active:4}") long dependencyMaxActive,
            @Value("${gateway.testing.admission.lease-duration-seconds:30}") long leaseSeconds,
            @Value("${gateway.testing.admission.heartbeat-interval-seconds:5}")
            long heartbeatSeconds) {
        return new TestRuntimeAdmissionPolicy(
                generation, tenantMaxActive, suiteMaxActive, operatorMaxActive,
                dependencyMaxActive, Duration.ofSeconds(leaseSeconds),
                Duration.ofSeconds(heartbeatSeconds));
    }

    /** Hashes scoped subjects and maintains exact renewable admission guards. */
    @Bean(destroyMethod = "close")
    TestRuntimeAdmissionCoordinator testRuntimeAdmissionCoordinator(
            DatabaseTestRuntimeAdmissionControl controlPlane,
            TestRuntimeAdmissionPolicy policy,
            ObjectMapper objectMapper,
            TestRuntimeAdmissionTelemetry telemetry,
            @Value("${gateway.testing.admission.instance-id:}") String instanceId) {
        return new TestRuntimeAdmissionCoordinator(
                controlPlane, policy, objectMapper, telemetry, instanceId);
    }

    /** Reclaims only bounded pages of expired permits; live capacity uses the database clock. */
    @Bean
    TestRuntimeAdmissionRetentionScheduler testRuntimeAdmissionRetentionScheduler(
            DatabaseTestRuntimeAdmissionControl controlPlane,
            @Value("${gateway.testing.admission.cleanup-batch-size:1000}") int batchSize) {
        return new TestRuntimeAdmissionRetentionScheduler(controlPlane, batchSize);
    }

    @Bean
    FixtureBundleRepository fixtureBundleRepository(TestRuntimeDatabase database, ObjectMapper objectMapper) {
        return new DatabaseFixtureBundleRepository(database.jdbc(), objectMapper);
    }

    /** @return governed replay payload vault isolated in the test-runtime database */
    @Bean
    ReplayPayloadRepository replayPayloadRepository(TestRuntimeDatabase database, ObjectMapper objectMapper) {
        return new DatabaseReplayPayloadRepository(database.jdbc(), objectMapper);
    }

    /** @return immutable suite registry isolated in the test-runtime database */
    @Bean
    TestSuiteRepository testSuiteRepository(TestRuntimeDatabase database, ObjectMapper objectMapper) {
        return new DatabaseTestSuiteRepository(database.jdbc(), objectMapper);
    }

    /** @return recoverable aggregate suite-run store isolated from production run tables */
    @Bean
    TestSuiteRunRepository testSuiteRunRepository(TestRuntimeDatabase database, ObjectMapper objectMapper) {
        return new DatabaseTestSuiteRunRepository(database.jdbc(), objectMapper);
    }

    @Bean
    TestRunRepository testRunRepository(TestRuntimeDatabase database, ObjectMapper objectMapper) {
        return new DatabaseTestRunRepository(database.jdbc(), objectMapper);
    }

    @Bean
    TestSecurityEventRepository testSecurityEventRepository(TestRuntimeDatabase database,
                                                            ObjectMapper objectMapper) {
        return new DatabaseTestSecurityEventRepository(database.jdbc(), objectMapper);
    }

    /**
     * Builds the aggregate SLO read model after every table-owning runtime component is ready.
     */
    @Bean
    DatabaseTestRuntimeSloControlPlane testRuntimeSloControlPlane(
            TestRuntimeDatabase database,
            TestRunRepository runRepository,
            TestSuiteRunRepository suiteRunRepository,
            DurableTestExecutionCheckpointRepository checkpointRepository,
            DatabaseDurableWorkerQuarantineControlPlane quarantineControlPlane,
            DurableTestRuntimeResources runtimeResources) {
        java.util.Objects.requireNonNull(runRepository, "runRepository");
        java.util.Objects.requireNonNull(suiteRunRepository, "suiteRunRepository");
        java.util.Objects.requireNonNull(checkpointRepository, "checkpointRepository");
        java.util.Objects.requireNonNull(quarantineControlPlane, "quarantineControlPlane");
        java.util.Objects.requireNonNull(runtimeResources, "runtimeResources");
        return new DatabaseTestRuntimeSloControlPlane(
                database.jdbc(), database.transactionManager());
    }

    /** Assesses evidence completeness, ownership queues, and retained-record capacity. */
    @Bean
    TestRuntimeSloMonitor testRuntimeSloMonitor(
            DatabaseTestRuntimeSloControlPlane controlPlane,
            TestRuntimeSloTelemetry telemetry,
            @Value("${gateway.testing.runtime-slo.outcome-lookback-seconds:900}")
            long outcomeLookbackSeconds,
            @Value("${gateway.testing.runtime-slo.execution-minimum-samples:20}")
            long executionMinimumSamples,
            @Value("${gateway.testing.runtime-slo.execution-max-incomplete-basis-points:0}")
            int executionMaxIncompleteBasisPoints,
            @Value("${gateway.testing.runtime-slo.suite-minimum-samples:5}")
            long suiteMinimumSamples,
            @Value("${gateway.testing.runtime-slo.suite-max-incomplete-basis-points:0}")
            int suiteMaxIncompleteBasisPoints,
            @Value("${gateway.testing.runtime-slo.suite-max-depth:100}")
            long suiteMaxDepth,
            @Value("${gateway.testing.runtime-slo.suite-max-expired-leases:0}")
            long suiteMaxExpiredLeases,
            @Value("${gateway.testing.runtime-slo.suite-max-oldest-age-seconds:120}")
            long suiteMaxOldestAgeSeconds,
            @Value("${gateway.testing.runtime-slo.creation-max-depth:100}")
            long creationMaxDepth,
            @Value("${gateway.testing.runtime-slo.creation-max-expired-leases:0}")
            long creationMaxExpiredLeases,
            @Value("${gateway.testing.runtime-slo.creation-max-oldest-age-seconds:180}")
            long creationMaxOldestAgeSeconds,
            @Value("${gateway.testing.runtime-slo.durable-max-depth:1000}")
            long durableMaxDepth,
            @Value("${gateway.testing.runtime-slo.durable-max-expired-leases:0}")
            long durableMaxExpiredLeases,
            @Value("${gateway.testing.runtime-slo.durable-max-oldest-age-seconds:180}")
            long durableMaxOldestAgeSeconds,
            @Value("${gateway.testing.runtime-slo.work-max-depth:10000}")
            long workMaxDepth,
            @Value("${gateway.testing.runtime-slo.work-max-expired-claims:0}")
            long workMaxExpiredClaims,
            @Value("${gateway.testing.runtime-slo.work-max-oldest-age-seconds:300}")
            long workMaxOldestAgeSeconds,
            @Value("${gateway.testing.runtime-slo.worker-backoff-max-active:1000}")
            long workerBackoffMaxActive,
            @Value("${gateway.testing.runtime-slo.worker-backoff-max-retry-due:100}")
            long workerBackoffMaxRetryDue,
            @Value("${gateway.testing.runtime-slo.worker-backoff-max-consecutive-failures:16}")
            long workerBackoffMaxConsecutiveFailures,
            @Value("${gateway.testing.runtime-slo.worker-backoff-max-oldest-age-seconds:3600}")
            long workerBackoffMaxOldestAgeSeconds,
            @Value("${gateway.testing.runtime-slo.worker-quarantine-max-records:100}")
            long workerQuarantineMaxRecords,
            @Value("${gateway.testing.runtime-slo.worker-quarantine-max-oldest-age-seconds:86400}")
            long workerQuarantineMaxOldestAgeSeconds,
            @Value("${gateway.testing.runtime-slo.worker-quarantine-max-expired-claims:0}")
            long workerQuarantineMaxExpiredClaims,
            @Value("${gateway.testing.runtime-slo.max-expired-execution-records:0}")
            long maxExpiredExecutionRecords,
            @Value("${gateway.testing.runtime-slo.max-expired-suite-records:0}")
            long maxExpiredSuiteRecords,
            @Value("${gateway.testing.runtime-slo.max-terminal-durable-executions:10000}")
            long maxTerminalDurableExecutions,
            @Value("${gateway.testing.runtime-slo.max-terminal-work-items:100000}")
            long maxTerminalWorkItems) {
        return new TestRuntimeSloMonitor(controlPlane, telemetry,
                new TestRuntimeSloMonitor.Policy(
                        Duration.ofSeconds(outcomeLookbackSeconds),
                        new TestRuntimeSloMonitor.EvidencePolicy(
                                executionMinimumSamples, executionMaxIncompleteBasisPoints),
                        new TestRuntimeSloMonitor.EvidencePolicy(
                                suiteMinimumSamples, suiteMaxIncompleteBasisPoints),
                        new TestRuntimeSloMonitor.QueuePolicy(
                                suiteMaxDepth, suiteMaxExpiredLeases,
                                Duration.ofSeconds(suiteMaxOldestAgeSeconds)),
                        new TestRuntimeSloMonitor.QueuePolicy(
                                creationMaxDepth, creationMaxExpiredLeases,
                                Duration.ofSeconds(creationMaxOldestAgeSeconds)),
                        new TestRuntimeSloMonitor.QueuePolicy(
                                durableMaxDepth, durableMaxExpiredLeases,
                                Duration.ofSeconds(durableMaxOldestAgeSeconds)),
                        new TestRuntimeSloMonitor.QueuePolicy(
                                workMaxDepth, workMaxExpiredClaims,
                                Duration.ofSeconds(workMaxOldestAgeSeconds)),
                        new TestRuntimeSloMonitor.WorkerCandidateDeferralPolicy(
                                workerBackoffMaxActive, workerBackoffMaxRetryDue,
                                workerBackoffMaxConsecutiveFailures,
                                Duration.ofSeconds(workerBackoffMaxOldestAgeSeconds)),
                        new TestRuntimeSloMonitor.WorkerCandidateQuarantinePolicy(
                                workerQuarantineMaxRecords,
                                Duration.ofSeconds(workerQuarantineMaxOldestAgeSeconds),
                                workerQuarantineMaxExpiredClaims),
                        new TestRuntimeSloMonitor.StoragePolicy(
                                maxExpiredExecutionRecords,
                                maxExpiredSuiteRecords,
                                maxTerminalDurableExecutions,
                                maxTerminalWorkItems)));
    }

    /** Captures the stable, fail-closed identity policy used by durable recovery authorization. */
    @Bean
    DurableTestRecoveryAuthority durableTestRecoveryAuthority(
            IntegrationRequestAuthenticator authenticator, ObjectMapper objectMapper) {
        return new DurableTestRecoveryAuthority(authenticator, objectMapper);
    }

    /** Projects integrity-verified durable checkpoints without exposing their hidden payloads. */
    @Bean
    DurableTestExecutionQueryService durableTestExecutionQueryService(
            DurableTestExecutionCheckpointRepository checkpoints) {
        return new DurableTestExecutionQueryService(checkpoints);
    }

    /** Rebuilds the exact target, fixture, replay, authority, and plan closure before lease claim. */
    @Bean
    DurableTestRecoveryAuthorizer durableTestRecoveryAuthorizer(
            GatewayGraphService graphService,
            OperatorRegistry operatorRegistry,
            ResourceRegistry resourceRegistry,
            FixtureBundleRepository fixtureRepository,
            TestReplayPayloadService replayPayloadService,
            DurableTestRecoveryAuthority authority,
            ObjectMapper objectMapper) {
        return new DurableTestRecoveryAuthorizer(graphService, operatorRegistry, resourceRegistry,
                fixtureRepository, replayPayloadService, authority, objectMapper);
    }

    /** Assembles the server-owned, idempotent durable lease ownership command boundary. */
    @Bean
    DurableTestOwnerClaimService durableTestOwnerClaimService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestRecoveryAuthorizer authorizer,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            @Value("${gateway.testing.durable.owner-claims.instance-id:}") String instanceId,
            @Value("${gateway.testing.durable.owner-claims.lease-duration-seconds:120}")
            long leaseDurationSeconds) {
        String owner = instanceId == null || instanceId.isBlank()
                ? "durable-recovery-" + UUID.randomUUID() : instanceId.trim();
        return new DurableTestOwnerClaimService(checkpoints, authorizer, securityEvents,
                objectMapper, owner, Duration.ofSeconds(leaseDurationSeconds));
    }

    /** Assembles the scoped, non-blocking and idempotent durable worker pull boundary. */
    @Bean
    DurableTestWorkerAcquisitionService durableTestWorkerAcquisitionService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestRecoveryAuthorizer authorizer,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            @Value("${gateway.testing.durable.owner-claims.instance-id:}") String instanceId,
            @Value("${gateway.testing.durable.owner-claims.lease-duration-seconds:120}")
            long leaseDurationSeconds,
            @Value("${gateway.testing.durable.worker-acquisitions.candidate-limit:32}")
            int candidateLimit,
            @Value("${gateway.testing.durable.worker-acquisitions.initial-backoff-seconds:5}")
            long initialBackoffSeconds,
            @Value("${gateway.testing.durable.worker-acquisitions.maximum-backoff-seconds:300}")
            long maximumBackoffSeconds,
            @Value("${gateway.testing.durable.worker-acquisitions.quarantine-threshold:32}")
            int quarantineThreshold) {
        String owner = instanceId == null || instanceId.isBlank()
                ? "durable-worker-" + UUID.randomUUID() : instanceId.trim();
        return new DurableTestWorkerAcquisitionService(
                checkpoints, authorizer, securityEvents, objectMapper, owner,
                Duration.ofSeconds(leaseDurationSeconds), candidateLimit,
                Duration.ofSeconds(initialBackoffSeconds),
                Duration.ofSeconds(maximumBackoffSeconds), quarantineThreshold);
    }

    /** Assembles authenticated heartbeat adaptation over issued internal recovery dispatches. */
    @Bean
    DurableTestRecoveryHeartbeatService durableTestRecoveryHeartbeatService(
            DurableTestExecutionCheckpointRepository checkpoints,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            @Value("${gateway.testing.durable.heartbeats.lease-duration-seconds:120}")
            long leaseDurationSeconds) {
        return new DurableTestRecoveryHeartbeatService(
                checkpoints, securityEvents, objectMapper,
                Duration.ofSeconds(leaseDurationSeconds));
    }

    /** Maintains a fresh issued dispatch while synchronous terminal recovery executes. */
    @Bean(destroyMethod = "close")
    DurableTestRecoveryLeaseCoordinator durableTestRecoveryLeaseCoordinator(
            DurableTestRecoveryHeartbeatService heartbeats,
            @Value("${gateway.testing.durable.recovery-worker.heartbeat-interval-seconds:0}")
            long heartbeatIntervalSeconds) {
        long leaseSeconds = heartbeats.leaseDuration().toSeconds();
        long heartbeatSeconds = heartbeatIntervalSeconds == 0
                ? Math.max(1, leaseSeconds / 3) : heartbeatIntervalSeconds;
        return new DurableTestRecoveryLeaseCoordinator(
                heartbeats, Duration.ofSeconds(heartbeatSeconds));
    }

    /** Builds the shared compiled operator and resource-fixture execution adapter. */
    @Bean
    CompiledTestRuntimeOptions compiledTestRuntimeOptions(
            ObjectMapper objectMapper,
            ResourceRegistry resourceRegistry,
            BlgeExpressionEvaluator expressionEvaluator) {
        return new CompiledTestRuntimeOptions(objectMapper,
                new ResourceFixtureRuntime(
                        resourceRegistry, expressionEvaluator, objectMapper));
    }

    /** Retains one fresh staged execution until its initial command transaction commits. */
    @Bean
    DurableTestCreationRuntime durableTestCreationRuntime(
            DurableTestRuntimeResources resources,
            CompiledTestRuntimeOptions runtimeOptions,
            ObjectMapper objectMapper) {
        return new DurableTestCreationRuntime(
                resources.engineFactory(), runtimeOptions, objectMapper);
    }

    /** Maintains database-fenced preparation ownership for fresh durable graph tests. */
    @Bean
    DurableTestCreationLeaseCoordinator durableTestCreationLeaseCoordinator(
            DurableTestExecutionCheckpointRepository checkpoints,
            @Value("${gateway.testing.durable.creation.instance-id:}") String instanceId,
            @Value("${gateway.testing.durable.creation.lease-duration-seconds:120}")
            long leaseDurationSeconds,
            @Value("${gateway.testing.durable.creation.heartbeat-interval-seconds:0}")
            long heartbeatIntervalSeconds) {
        long heartbeatSeconds = heartbeatIntervalSeconds == 0
                ? Math.max(1, leaseDurationSeconds / 3) : heartbeatIntervalSeconds;
        return new DurableTestCreationLeaseCoordinator(
                checkpoints, instanceId, Duration.ofSeconds(leaseDurationSeconds),
                Duration.ofSeconds(heartbeatSeconds));
    }

    /** Assembles authenticated, idempotent durable graph-test creation. */
    @Bean
    DurableTestExecutionCreationService durableTestExecutionCreationService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestRecoveryAuthorizer authorizer,
            DurableTestCreationRuntime runtime,
            DurableTestExecutionCheckpointIntegrity integrity,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            DurableTestCreationLeaseCoordinator leases,
            TestRuntimeAdmissionGate admissions) {
        return new DurableTestExecutionCreationService(
                checkpoints, authorizer, runtime, integrity, securityEvents, objectMapper,
                leases, admissions);
    }

    /** Retains staged BLOGE recovery state until the fenced repository commit consumes it. */
    @Bean
    DurableTestTerminalRecoveryRuntime durableTestTerminalRecoveryRuntime(
            DurableTestRuntimeResources resources,
            CompiledTestRuntimeOptions runtimeOptions,
            ObjectMapper objectMapper) {
        return new DurableTestTerminalRecoveryRuntime(
                resources.engineFactory(), runtimeOptions, objectMapper);
    }

    /** Assembles authenticated terminal execution over issued durable recovery dispatches. */
    @Bean
    DurableTestTerminalRecoveryService durableTestTerminalRecoveryService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestRecoveryAuthorizer authorizer,
            DurableTestTerminalRecoveryRuntime runtime,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            DurableTestRecoveryLeaseCoordinator leases,
            TestRuntimeAdmissionGate admissions) {
        return new DurableTestTerminalRecoveryService(
                checkpoints, authorizer, runtime, securityEvents, objectMapper, leases,
                admissions);
    }

    /** Reuses the configured local or managed signer for independently verifiable test evidence. */
    @Bean
    TestEvidenceIntegrityService testEvidenceIntegrityService(ObjectMapper objectMapper,
                                                               ObjectProvider<VisualEvidenceSigner> evidenceSigner) {
        return new TestEvidenceIntegrityService(objectMapper,
                evidenceSigner.getIfAvailable(VisualEvidenceSigner::unavailable));
    }

    /** Reuses the configured signing authority for aggregate suite checkpoints and evidence. */
    @Bean
    TestSuiteRunAttestationService testSuiteRunAttestationService(
            ObjectMapper objectMapper, ObjectProvider<VisualEvidenceSigner> evidenceSigner) {
        return new TestSuiteRunAttestationService(objectMapper,
                evidenceSigner.getIfAvailable(VisualEvidenceSigner::unavailable));
    }

    /** Captures exact sanitized outputs from signed run history into the isolated replay vault. */
    @Bean
    TestReplayPayloadService testReplayPayloadService(
            VisualGraphRunRepository visualGraphRunRepository,
            ReplayPayloadRepository replayPayloadRepository,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            @Value("${gateway.testing.replay-payloads.maximum-retention-days:30}") long retentionDays) {
        return new TestReplayPayloadService(visualGraphRunRepository, replayPayloadRepository,
                securityEvents, objectMapper,
                Duration.ofDays(Math.max(1, Math.min(365, retentionDays))));
    }

    /** Applies replay retention independently from visual run-history retention. */
    @Bean
    ReplayPayloadRetentionScheduler replayPayloadRetentionScheduler(
            ReplayPayloadRepository replayPayloadRepository,
            @Value("${gateway.testing.replay-payloads.sweep-batch-size:100}") int batchSize) {
        return new ReplayPayloadRetentionScheduler(replayPayloadRepository, batchSize);
    }

    @Bean
    TestExecutionApiService testExecutionApiService(
            GatewayGraphService graphService,
            OperatorRegistry operatorRegistry,
            ResourceRegistry resourceRegistry,
            BlgeExpressionEvaluator expressionEvaluator,
            ObjectMapper objectMapper,
            FixtureBundleRepository fixtureRepository,
            TestRunRepository runRepository,
            TestSecurityEventRepository securityEvents,
            TestReplayPayloadService replayPayloadService,
            TestEvidenceIntegrityService evidenceIntegrity,
            TestRuntimeAdmissionGate admissions,
            @Value("${gateway.testing.store.retention-days:30}") long retentionDays) {
        return new TestExecutionApiService(graphService, operatorRegistry, resourceRegistry,
                expressionEvaluator, objectMapper, fixtureRepository, runRepository, securityEvents,
                Duration.ofDays(Math.max(1, Math.min(3650, retentionDays))), replayPayloadService,
                evidenceIntegrity, admissions);
    }

    /** Assembles the dependency-validating immutable suite registry service. */
    @Bean
    TestSuiteRegistryService testSuiteRegistryService(
            GatewayGraphService graphService,
            OperatorRegistry operatorRegistry,
            ResourceRegistry resourceRegistry,
            ObjectMapper objectMapper,
            FixtureBundleRepository fixtureRepository,
            TestSuiteRepository suiteRepository,
            TestSecurityEventRepository securityEvents) {
        return new TestSuiteRegistryService(graphService, operatorRegistry, resourceRegistry, objectMapper,
                fixtureRepository, suiteRepository, securityEvents);
    }

    /** Assembles deterministic migration of the trusted legacy graph catalog. */
    @Bean
    TestSuiteCatalogMaterializationService testSuiteCatalogMaterializationService(
            TestExecutionApiService executionService,
            TestSuiteRegistryService suiteRegistry,
            GatewayGraphService graphService,
            ObjectMapper objectMapper) {
        return new TestSuiteCatalogMaterializationService(
                executionService, suiteRegistry, graphService, objectMapper);
    }

    /** Assembles the idempotent immutable-suite runner and coverage evaluator. */
    @Bean(destroyMethod = "close")
    TestSuiteRunLeaseCoordinator testSuiteRunLeaseCoordinator(
            TestSuiteRunRepository suiteRunRepository,
            @Value("${gateway.testing.suite-runs.instance-id:}") String instanceId,
            @Value("${gateway.testing.suite-runs.lease-duration-seconds:30}") long leaseSeconds,
            @Value("${gateway.testing.suite-runs.heartbeat-interval-seconds:5}") long heartbeatSeconds) {
        Duration lease = Duration.ofSeconds(Math.max(5, Math.min(3600, leaseSeconds)));
        Duration heartbeat = Duration.ofSeconds(Math.max(1,
                Math.min(Math.max(1, lease.toSeconds() - 1), heartbeatSeconds)));
        return new TestSuiteRunLeaseCoordinator(suiteRunRepository, instanceId, lease, heartbeat);
    }

    /** Builds the fail-closed transformer for expired RUNNING suite evidence. */
    @Bean
    TestSuiteRunReconciliationService testSuiteRunReconciliationService(
            TestSuiteRunRepository suiteRunRepository, ObjectMapper objectMapper,
            TestSuiteRunAttestationService attestations) {
        return new TestSuiteRunReconciliationService(suiteRunRepository, objectMapper, attestations);
    }

    /** Runs bounded anti-entropy sweeps only where the test runtime profile exists. */
    @Bean
    TestSuiteRunReconciliationScheduler testSuiteRunReconciliationScheduler(
            TestSuiteRunReconciliationService reconciliationService,
            @Value("${gateway.testing.suite-runs.reconciliation-batch-size:100}") int batchSize) {
        return new TestSuiteRunReconciliationScheduler(reconciliationService, batchSize);
    }

    /** Assembles the idempotent immutable-suite runner and coverage evaluator. */
    @Bean
    TestSuiteExecutionService testSuiteExecutionService(
            TestSuiteRegistryService suiteRegistry,
            TestExecutionApiService executionService,
            TestSuiteRunRepository suiteRunRepository,
            TestSuiteRunLeaseCoordinator leaseCoordinator,
            TestSuiteRunAttestationService attestations,
            TestRuntimeAdmissionGate admissions,
            ObjectMapper objectMapper,
            TestSecurityEventRepository securityEvents,
            @Value("${gateway.testing.store.retention-days:30}") long retentionDays) {
        return new TestSuiteExecutionService(suiteRegistry, executionService, suiteRunRepository,
                objectMapper, securityEvents,
                Duration.ofDays(Math.max(1, Math.min(3650, retentionDays))), leaseCoordinator,
                attestations, admissions);
    }

    /** Marker consumed by the unauthenticated capability probe. */
    @Bean
    TestabilityAvailability testabilityAvailability() {
        return new TestabilityAvailability(true);
    }
}
