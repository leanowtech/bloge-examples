package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.TestabilityAvailability;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionCoordinator;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionPolicy;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionRetentionScheduler;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionTelemetry;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityCrossRetentionTrendAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecycleAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityTrendAttestationService;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseFixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseReplayPayloadRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSecurityEventRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityAuthorityCohortRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityJobRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityServingInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityServingInventoryTrustRootFloor;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeSloControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;
import com.leanowtech.bloge.gateway.testing.persistence.RecoverySequenceRequestKeyProtector;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore;
import com.leanowtech.bloge.gateway.testing.persistence.TestSuiteStabilityJobRequestKeyProtector;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import com.leanowtech.bloge.gateway.testing.persistence.WorkerQuarantineClaimTokenProtector;
import com.leanowtech.bloge.gateway.testing.persistence.WorkerQuarantineRequestKeyProtector;
import com.leanowtech.bloge.gateway.testing.runtime.CompiledTestRuntimeOptions;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestCreationRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestRuntimeResources;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
            DurableTestExecutionCheckpointIntegrity integrity,
            RecoverySequenceRequestKeyProtector recoverySequenceRequestKeys,
            @Value("${gateway.testing.durable.recovery-sequences.retention-instance-id:}")
            String retentionInstanceId,
            @Value("${gateway.testing.durable.recovery-sequences.retention-lease-duration-seconds:120}")
            long retentionLeaseDurationSeconds,
            @Value("${gateway.testing.durable.recovery-sequences.command-retention-days:30}")
            long commandRetentionDays) {
        String retentionOwner = retentionInstanceId == null || retentionInstanceId.isBlank()
                ? "recovery-sequence-retention-" + UUID.randomUUID()
                : retentionInstanceId.trim();
        return new DatabaseDurableTestExecutionCheckpointRepository(
                database.jdbc(), database.transactionManager(), objectMapper, integrity,
                recoverySequenceRequestKeys, retentionOwner,
                Duration.ofSeconds(retentionLeaseDurationSeconds),
                Duration.ofDays(commandRetentionDays));
    }

    /** Builds the independently domain-separated HMAC authority for sequence tombstones. */
    @Bean
    RecoverySequenceRequestKeyProtector recoverySequenceRequestKeyProtector(
            @Value("${gateway.testing.durable.recovery-sequences.request-key-protection.active-key-id:local-recovery-sequence-v1}")
            String activeKeyId,
            @Value("${gateway.testing.durable.recovery-sequences.request-key-protection.key-ring:local-recovery-sequence-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=}")
            String keyRing) {
        return RecoverySequenceRequestKeyProtector.fromConfiguration(activeKeyId, keyRing);
    }

    /** Bounds recovery-sequence replay detail and derived child commands in leased pages. */
    @Bean
    DurableRecoverySequenceRetentionScheduler durableRecoverySequenceRetentionScheduler(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableRecoverySequenceRetentionTelemetry telemetry,
            @Value("${gateway.testing.durable.recovery-sequences.command-retention-days:30}")
            long commandRetentionDays,
            @Value("${gateway.testing.durable.recovery-sequences.tombstone-retention-days:365}")
            long tombstoneRetentionDays,
            @Value("${gateway.testing.durable.recovery-sequences.retention-page-size:100}")
            int pageSize,
            @Value("${gateway.testing.durable.recovery-sequences.retention-interval-ms:3600000}")
            long retentionIntervalMillis) {
        return new DurableRecoverySequenceRetentionScheduler(
                checkpoints, Duration.ofDays(commandRetentionDays),
                Duration.ofDays(tombstoneRetentionDays), pageSize, telemetry,
                Duration.ofMillis(retentionIntervalMillis));
    }

    /** Registers aggregate-only recovery-sequence retention metrics. */
    @Bean
    DurableRecoverySequenceRetentionTelemetry durableRecoverySequenceRetentionTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new DurableRecoverySequenceRetentionTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Fails readiness closed on stale sequence retention or overdue lifecycle backlog. */
    @Bean
    DurableRecoverySequenceRetentionSloMonitor
            durableRecoverySequenceRetentionSloMonitor(
                    DurableTestExecutionCheckpointRepository checkpoints,
                    DurableRecoverySequenceRetentionTelemetry telemetry,
                    @Value("${gateway.testing.durable.recovery-sequences.command-retention-days:30}")
                    long commandRetentionDays,
                    @Value("${gateway.testing.durable.recovery-sequences.slo.observation-interval-ms:30000}")
                    long observationIntervalMillis,
                    @Value("${gateway.testing.durable.recovery-sequences.slo.startup-grace-seconds:180}")
                    long startupGraceSeconds,
                    @Value("${gateway.testing.durable.recovery-sequences.slo.max-retention-staleness-seconds:10800}")
                    long maxRetentionStalenessSeconds,
                    @Value("${gateway.testing.durable.recovery-sequences.slo.max-overdue-sequences:0}")
                    long maxOverdueSequences,
                    @Value("${gateway.testing.durable.recovery-sequences.slo.max-oldest-overdue-sequence-age-seconds:3600}")
                    long maxOldestOverdueSequenceAgeSeconds,
                    @Value("${gateway.testing.durable.recovery-sequences.slo.max-expired-tombstones:0}")
                    long maxExpiredTombstones,
                    @Value("${gateway.testing.durable.recovery-sequences.slo.max-oldest-expired-tombstone-age-seconds:3600}")
                    long maxOldestExpiredTombstoneAgeSeconds) {
        return new DurableRecoverySequenceRetentionSloMonitor(
                checkpoints, telemetry,
                new DurableRecoverySequenceRetentionSloMonitor.Policy(
                        Duration.ofDays(commandRetentionDays),
                        Duration.ofMillis(observationIntervalMillis),
                        Duration.ofSeconds(startupGraceSeconds),
                        Duration.ofSeconds(maxRetentionStalenessSeconds),
                        maxOverdueSequences,
                        Duration.ofSeconds(maxOldestOverdueSequenceAgeSeconds),
                        maxExpiredTombstones,
                        Duration.ofSeconds(maxOldestExpiredTombstoneAgeSeconds)));
    }

    /**
     * Builds the shared, rotation-aware envelope authority for replayable quarantine claim tokens.
     */
    @Bean
    WorkerQuarantineClaimTokenProtector workerQuarantineClaimTokenProtector(
            @Value("${gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id}")
            String activeKeyId,
            @Value("${gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring}")
            String keyRing) {
        return WorkerQuarantineClaimTokenProtector.fromConfiguration(activeKeyId, keyRing);
    }

    /**
     * Builds the independent rotation-aware HMAC authority for retained request indexes.
     */
    @Bean
    WorkerQuarantineRequestKeyProtector workerQuarantineRequestKeyProtector(
            @Value("${gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id}")
            String activeKeyId,
            @Value("${gateway.testing.durable.worker-quarantines.request-key-protection.key-ring}")
            String keyRing) {
        return WorkerQuarantineRequestKeyProtector.fromConfiguration(activeKeyId, keyRing);
    }

    /**
     * Creates exact-checkpoint quarantine maintenance after its automatic authority is initialized.
     */
    @Bean
    DatabaseDurableWorkerQuarantineControlPlane durableWorkerQuarantineControlPlane(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            DurableTestExecutionCheckpointRepository checkpointAuthority,
            WorkerQuarantineClaimTokenProtector claimTokenProtector,
            WorkerQuarantineRequestKeyProtector requestKeyProtector,
            @Value("${gateway.testing.durable.worker-quarantines.request-key-protection.write-mode}")
            String requestIndexWriteMode,
            @Value("${gateway.testing.durable.worker-quarantines.retention-instance-id:}")
            String retentionInstanceId,
            @Value("${gateway.testing.durable.worker-quarantines.retention-lease-duration-seconds:120}")
            long retentionLeaseDurationSeconds) {
        java.util.Objects.requireNonNull(checkpointAuthority, "checkpointAuthority");
        String owner = retentionInstanceId == null || retentionInstanceId.isBlank()
                ? "worker-quarantine-retention-" + UUID.randomUUID()
                : retentionInstanceId.trim();
        return new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                claimTokenProtector, requestKeyProtector,
                WorkerQuarantineRequestIndexMode.parse(requestIndexWriteMode), owner,
                Duration.ofSeconds(retentionLeaseDurationSeconds));
    }

    /** Bounds quarantine command, approval, tombstone, and history storage in leased pages. */
    @Bean
    DurableWorkerQuarantineRetentionScheduler durableWorkerQuarantineRetentionScheduler(
            DatabaseDurableWorkerQuarantineControlPlane controlPlane,
            DurableWorkerQuarantineRetentionTelemetry telemetry,
            @Value("${gateway.testing.durable.worker-quarantines.command-retention-days:30}")
            long commandRetentionDays,
            @Value("${gateway.testing.durable.worker-quarantines.history-retention-days:365}")
            long historyRetentionDays,
            @Value("${gateway.testing.durable.worker-quarantines.tombstone-retention-days:365}")
            long tombstoneRetentionDays,
            @Value("${gateway.testing.durable.worker-quarantines.retention-page-size:100}")
            int pageSize,
            @Value("${gateway.testing.durable.worker-quarantines.retention-interval-ms:3600000}")
            long retentionIntervalMillis) {
        return new DurableWorkerQuarantineRetentionScheduler(controlPlane,
                Duration.ofDays(commandRetentionDays), Duration.ofDays(historyRetentionDays),
                Duration.ofDays(tombstoneRetentionDays), pageSize, telemetry,
                Duration.ofMillis(retentionIntervalMillis));
    }

    /** Registers fixed-cardinality quarantine-retention metrics with no request identity tags. */
    @Bean
    DurableWorkerQuarantineRetentionTelemetry durableWorkerQuarantineRetentionTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new DurableWorkerQuarantineRetentionTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Builds the independent public-key-only external change-authorization trust boundary.
     *
     * <p>An entirely absent test-profile configuration produces an explicit unavailable store so
     * non-destructive test-runtime functions can start while approval fails closed. Any partial or
     * malformed configuration aborts application startup. The staging profile requires every
     * property through its profile configuration.</p>
     */
    @Bean
    WorkerQuarantineChangeAuthorizationTrustStore
            workerQuarantineChangeAuthorizationTrustStore(
            ObjectMapper objectMapper,
            @Value("${gateway.testing.durable.worker-quarantines.change-authorization.trust-domain:}")
            String trustDomain,
            @Value("${gateway.testing.durable.worker-quarantines.change-authorization.accepted-policy-fingerprints:}")
            String acceptedPolicyFingerprints,
            @Value("${gateway.testing.durable.worker-quarantines.change-authorization.signature-threshold:0}")
            int signatureThreshold,
            @Value("${gateway.testing.durable.worker-quarantines.change-authorization.authority-keys-json:}")
            String authorityKeysJson) {
        boolean absent = (trustDomain == null || trustDomain.isBlank())
                && (acceptedPolicyFingerprints == null
                || acceptedPolicyFingerprints.isBlank())
                && signatureThreshold == 0
                && (authorityKeysJson == null || authorityKeysJson.isBlank());
        if (absent) {
            return WorkerQuarantineChangeAuthorizationTrustStore.unavailable();
        }
        return ConfiguredWorkerQuarantineChangeAuthorizationTrustStore.fromJson(
                objectMapper, trustDomain, acceptedPolicyFingerprints,
                signatureThreshold, authorityKeysJson);
    }

    /** Assembles the scoped, authenticated, action-audited quarantine owner queue. */
    @Bean
    DurableWorkerQuarantineService durableWorkerQuarantineService(
            DatabaseDurableWorkerQuarantineControlPlane controlPlane,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            WorkerQuarantineChangeAuthorizationTrustStore changeAuthorizationTrust,
            @Value("${gateway.testing.durable.worker-quarantines.required-group:resource-gateway-test-runtime-operators}")
            String requiredGroup,
            @Value("${gateway.testing.durable.worker-quarantines.required-approver-group:resource-gateway-test-runtime-quarantine-approvers}")
            String requiredApproverGroup,
            @Value("${gateway.testing.durable.worker-quarantines.required-clearance:RESTRICTED}")
            String requiredClearance) {
        return new DurableWorkerQuarantineService(
                controlPlane, securityEvents, objectMapper, changeAuthorizationTrust, requiredGroup,
                requiredApproverGroup, requiredClearance);
    }

    /** Assembles the signed, challenge-bound per-replica request-index rollout proof boundary. */
    @Bean
    WorkerQuarantineRequestIndexRolloutService workerQuarantineRequestIndexRolloutService(
            DatabaseDurableWorkerQuarantineControlPlane controlPlane,
            TestSecurityEventRepository securityEvents,
            VisualEvidenceSigner signer,
            ObjectMapper objectMapper,
            @Value("${gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id}")
            String instanceId,
            @Value("${gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint}")
            String artifactFingerprint,
            @Value("${gateway.testing.durable.worker-quarantines.request-index-rollout.proof-ttl-seconds:120}")
            long proofTtlSeconds,
            @Value("${gateway.testing.durable.worker-quarantines.required-group:resource-gateway-test-runtime-operators}")
            String requiredGroup,
            @Value("${gateway.testing.durable.worker-quarantines.required-clearance:RESTRICTED}")
            String requiredClearance) {
        return new WorkerQuarantineRequestIndexRolloutService(
                controlPlane, securityEvents, signer, objectMapper,
                new WorkerQuarantineRequestIndexRolloutService.Settings(
                        instanceId, UUID.randomUUID().toString(), artifactFingerprint,
                        Duration.ofSeconds(proofTtlSeconds), requiredGroup, requiredClearance));
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

    /** @return immutable terminal stability store isolated from production run tables */
    @Bean
    TestSuiteStabilityRunRepository testSuiteStabilityRunRepository(
            TestRuntimeDatabase database, ObjectMapper objectMapper) {
        return new DatabaseTestSuiteStabilityRunRepository(
                database.jdbc(), objectMapper, database.transactionManager());
    }

    /** Verifies every queue terminal against the retained signed parent stability run. */
    @Bean
    TestSuiteStabilityJobParentAuthority testSuiteStabilityJobParentAuthority(
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttestationService attestations) {
        return new RepositoryTestSuiteStabilityJobParentAuthority(
                repository, objectMapper, attestations);
    }

    /** Builds the independently domain-separated HMAC authority for retired job requests. */
    @Bean
    TestSuiteStabilityJobRequestKeyProtector testSuiteStabilityJobRequestKeyProtector(
            @Value("${gateway.testing.stability-jobs.retention.request-key-protection.active-key-id:local-stability-job-v1}")
            String activeKeyId,
            @Value("${gateway.testing.stability-jobs.retention.request-key-protection.key-ring:local-stability-job-v1=QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=}")
            String keyRing) {
        return TestSuiteStabilityJobRequestKeyProtector.fromConfiguration(
                activeKeyId, keyRing);
    }

    /** Creates the database-authoritative stability queue even while worker execution is off. */
    @Bean
    TestSuiteStabilityJobRepository testSuiteStabilityJobRepository(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            TestSuiteStabilityJobParentAuthority parentAuthority,
            TestSuiteStabilityJobRequestKeyProtector requestKeys,
            @Value("${gateway.testing.stability-jobs.retention.instance-id:}")
            String retentionInstanceId,
            @Value("${gateway.testing.stability-jobs.retention.lease-duration-seconds:120}")
            long retentionLeaseDurationSeconds) {
        String retentionOwner = retentionInstanceId == null || retentionInstanceId.isBlank()
                ? "stability-job-retention-" + UUID.randomUUID()
                : retentionInstanceId.trim();
        return new DatabaseTestSuiteStabilityJobRepository(
                database.jdbc(), objectMapper, parentAuthority, requestKeys,
                retentionOwner, Duration.ofSeconds(retentionLeaseDurationSeconds),
                database.transactionManager());
    }

    /** Runs one database-leased, bounded stability-job retention page per scheduled tick. */
    @Bean
    TestSuiteStabilityJobRetentionScheduler testSuiteStabilityJobRetentionScheduler(
            TestSuiteStabilityJobRepository repository,
            TestSuiteStabilityJobRetentionTelemetry telemetry,
            @Value("${gateway.testing.stability-jobs.retention.tombstone-retention-days:365}")
            long tombstoneRetentionDays,
            @Value("${gateway.testing.stability-jobs.retention.page-size:100}")
            int pageSize,
            @Value("${gateway.testing.stability-jobs.retention.interval-ms:3600000}")
            long intervalMillis) {
        return new TestSuiteStabilityJobRetentionScheduler(
                repository, Duration.ofDays(tombstoneRetentionDays), pageSize,
                telemetry, Duration.ofMillis(intervalMillis));
    }

    /** Registers aggregate-only stability-job retention metrics. */
    @Bean
    TestSuiteStabilityJobRetentionTelemetry testSuiteStabilityJobRetentionTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new TestSuiteStabilityJobRetentionTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Fails readiness closed on stale retention or overdue lifecycle backlog. */
    @Bean
    TestSuiteStabilityJobRetentionSloMonitor testSuiteStabilityJobRetentionSloMonitor(
            TestSuiteStabilityJobRepository repository,
            TestSuiteStabilityJobRetentionTelemetry telemetry,
            @Value("${gateway.testing.stability-jobs.retention.interval-ms:3600000}")
            long retentionIntervalMillis,
            @Value("${gateway.testing.stability-jobs.retention.lease-duration-seconds:120}")
            long retentionLeaseDurationSeconds,
            @Value("${gateway.testing.stability-jobs.retention.slo.observation-interval-ms:30000}")
            long observationIntervalMillis,
            @Value("${gateway.testing.stability-jobs.retention.slo.startup-grace-seconds:180}")
            long startupGraceSeconds,
            @Value("${gateway.testing.stability-jobs.retention.slo.max-retention-staleness-seconds:10800}")
            long maxRetentionStalenessSeconds,
            @Value("${gateway.testing.stability-jobs.retention.slo.max-overdue-jobs:0}")
            long maxOverdueJobs,
            @Value("${gateway.testing.stability-jobs.retention.slo.max-oldest-overdue-job-age-seconds:3600}")
            long maxOldestOverdueJobAgeSeconds,
            @Value("${gateway.testing.stability-jobs.retention.slo.max-expired-tombstones:0}")
            long maxExpiredTombstones,
            @Value("${gateway.testing.stability-jobs.retention.slo.max-oldest-expired-tombstone-age-seconds:3600}")
            long maxOldestExpiredTombstoneAgeSeconds) {
        Duration maximumStaleness = Duration.ofSeconds(maxRetentionStalenessSeconds);
        Duration maximumExpectedGap = Duration.ofMillis(retentionIntervalMillis)
                .plus(Duration.ofSeconds(retentionLeaseDurationSeconds));
        if (maximumStaleness.compareTo(maximumExpectedGap) < 0) {
            throw new IllegalArgumentException(
                    "Stability-job retention freshness SLO must cover one schedule and lease window");
        }
        return new TestSuiteStabilityJobRetentionSloMonitor(
                repository, telemetry,
                new TestSuiteStabilityJobRetentionSloMonitor.Policy(
                        Duration.ofMillis(observationIntervalMillis),
                        Duration.ofSeconds(startupGraceSeconds), maximumStaleness,
                        maxOverdueJobs, Duration.ofSeconds(maxOldestOverdueJobAgeSeconds),
                        maxExpiredTombstones,
                        Duration.ofSeconds(maxOldestExpiredTombstoneAgeSeconds)));
    }

    /**
     * Builds the exact cross-replica queue policy; invalid capacity or duration fails startup.
     */
    @Bean
    TestSuiteStabilityQueuePolicy testSuiteStabilityQueuePolicy(
            @Value("${gateway.testing.stability-jobs.queue.policy-generation:1}")
            long generation,
            @Value("${gateway.testing.stability-jobs.queue.maximum-queued:1000}")
            int maximumQueued,
            @Value("${gateway.testing.stability-jobs.queue.maximum-queued-per-tenant:100}")
            int maximumQueuedPerTenant,
            @Value("${gateway.testing.stability-jobs.queue.maximum-running:16}")
            int maximumRunning,
            @Value("${gateway.testing.stability-jobs.queue.maximum-running-per-tenant:4}")
            int maximumRunningPerTenant,
            @Value("${gateway.testing.stability-jobs.queue.lease-duration-seconds:30}")
            long leaseDurationSeconds,
            @Value("${gateway.testing.stability-jobs.queue.aging-interval-seconds:300}")
            long agingIntervalSeconds,
            @Value("${gateway.testing.stability-jobs.queue.initial-retry-delay-seconds:1}")
            long initialRetryDelaySeconds,
            @Value("${gateway.testing.stability-jobs.queue.maximum-retry-delay-seconds:60}")
            long maximumRetryDelaySeconds,
            @Value("${gateway.testing.stability-jobs.queue.maximum-retries:3}")
            int maximumRetries,
            @Value("${gateway.testing.stability-jobs.queue.maximum-deadline-horizon-days:7}")
            long maximumDeadlineHorizonDays,
            @Value("${gateway.testing.stability-jobs.queue.terminal-retention-days:30}")
            long terminalRetentionDays) {
        return new TestSuiteStabilityQueuePolicy(
                generation, maximumQueued, maximumQueuedPerTenant,
                maximumRunning, maximumRunningPerTenant,
                Duration.ofSeconds(leaseDurationSeconds),
                Duration.ofSeconds(agingIntervalSeconds),
                Duration.ofSeconds(initialRetryDelaySeconds),
                Duration.ofSeconds(maximumRetryDelaySeconds), maximumRetries,
                Duration.ofDays(maximumDeadlineHorizonDays),
                Duration.ofDays(terminalRetentionDays));
    }

    /**
     * Exposes query and cancellation throughout maintenance while gating only fresh submission on
     * the explicitly enabled worker runtime.
     */
    @Bean
    TestSuiteStabilityJobService testSuiteStabilityJobService(
            TestSuiteStabilityJobRepository repository,
            TestSuiteStabilityExecutionService executions,
            TestSuiteStabilityQueuePolicy policy,
            ObjectMapper objectMapper,
            TestSecurityEventRepository securityEvents,
            ObjectProvider<TestSuiteStabilityJobAuthorizer> authorizers,
            @Value("${gateway.testing.stability-jobs.worker.enabled:false}")
            boolean submissionEnabled,
            @Value("${gateway.testing.stability-jobs.api.retry-after-seconds:5}")
            long retryAfterSeconds) {
        return new TestSuiteStabilityJobService(
                repository, executions, policy, objectMapper, securityEvents, submissionEnabled,
                () -> currentAuthorityReady(authorizers),
                Duration.ofSeconds(retryAfterSeconds));
    }

    /** Registers only fixed environment/status/outcome stability queue metrics. */
    @Bean
    TestSuiteStabilityJobTelemetry testSuiteStabilityJobTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new TestSuiteStabilityJobTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Fails readiness closed on stale backlog, excessive depth, expired leases, or store outage. */
    @Bean
    TestSuiteStabilityJobSloMonitor testSuiteStabilityJobSloMonitor(
            TestSuiteStabilityJobRepository repository,
            TestSuiteStabilityJobTelemetry telemetry,
            TestSuiteStabilityQueuePolicy queuePolicy,
            @Value("${gateway.testing.stability-jobs.slo.environments:test}")
            String environments,
            @Value("${gateway.testing.stability-jobs.slo.observation-interval-ms:30000}")
            long observationIntervalMillis,
            @Value("${gateway.testing.stability-jobs.slo.maximum-queued-jobs:800}")
            long maximumQueuedJobs,
            @Value("${gateway.testing.stability-jobs.slo.maximum-oldest-queued-age-seconds:300}")
            long maximumOldestQueuedAgeSeconds,
            @Value("${gateway.testing.stability-jobs.slo.maximum-expired-live-leases:0}")
            long maximumExpiredLiveLeases) {
        if (maximumQueuedJobs > queuePolicy.maximumQueued()) {
            throw new IllegalArgumentException(
                    "Stability queue SLO depth cannot exceed hard queue capacity");
        }
        return new TestSuiteStabilityJobSloMonitor(
                repository, telemetry, stabilityJobEnvironments(environments),
                new TestSuiteStabilityJobSloMonitor.Policy(
                        Duration.ofMillis(observationIntervalMillis), maximumQueuedJobs,
                        Duration.ofSeconds(maximumOldestQueuedAgeSeconds),
                        maximumExpiredLiveLeases));
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
            @Value("${gateway.testing.runtime-slo.worker-quarantine-max-expired-discard-approvals:0}")
            long workerQuarantineMaxExpiredDiscardApprovals,
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
                                workerQuarantineMaxExpiredClaims,
                                workerQuarantineMaxExpiredDiscardApprovals),
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

    /** Orchestrates bounded multi-suspension recovery over the existing atomic child commands. */
    @Bean
    DurableTestRecoverySequenceService durableTestRecoverySequenceService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestOwnerClaimService ownerClaims,
            DurableTestTerminalRecoveryService recoverySteps,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper) {
        return new DurableTestRecoverySequenceService(
                checkpoints, ownerClaims, recoverySteps, securityEvents, objectMapper);
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

    /** Reuses the configured signing authority in a distinct stability-analysis domain. */
    @Bean
    TestSuiteStabilityAttestationService testSuiteStabilityAttestationService(
            ObjectMapper objectMapper, ObjectProvider<VisualEvidenceSigner> evidenceSigner) {
        return new TestSuiteStabilityAttestationService(objectMapper,
                evidenceSigner.getIfAvailable(VisualEvidenceSigner::unavailable));
    }

    /** Re-signs compact longitudinal observations in a source-independent signature domain. */
    @Bean
    TestSuiteStabilityObservationAttestationService
            testSuiteStabilityObservationAttestationService(
                    ObjectMapper objectMapper,
                    ObjectProvider<VisualEvidenceSigner> evidenceSigner,
                    TestSuiteStabilityAttestationService sourceAttestations) {
        return new TestSuiteStabilityObservationAttestationService(
                objectMapper,
                evidenceSigner.getIfAvailable(VisualEvidenceSigner::unavailable),
                sourceAttestations);
    }

    /** Signs exact compact-observation ranges in a distinct public trend domain. */
    @Bean
    TestSuiteStabilityCrossRetentionTrendAttestationService
            testSuiteStabilityCrossRetentionTrendAttestationService(
                    ObjectMapper objectMapper,
                    ObjectProvider<VisualEvidenceSigner> evidenceSigner) {
        return new TestSuiteStabilityCrossRetentionTrendAttestationService(
                objectMapper,
                evidenceSigner.getIfAvailable(VisualEvidenceSigner::unavailable));
    }

    /** Verifies signed floor retirements before exposing them in lifecycle pages. */
    @Bean
    TestSuiteStabilityObservationFloorRetirementAttestationService
            testSuiteStabilityObservationFloorRetirementAttestationService(
                    ObjectMapper objectMapper,
                    ObjectProvider<VisualEvidenceSigner> evidenceSigner) {
        return new TestSuiteStabilityObservationFloorRetirementAttestationService(
                objectMapper,
                evidenceSigner.getIfAvailable(VisualEvidenceSigner::unavailable));
    }

    /** Signs floor lifecycle pages in a distinct public preview domain. */
    @Bean
    TestSuiteStabilityObservationLedgerLifecycleAttestationService
            testSuiteStabilityObservationLedgerLifecycleAttestationService(
                    ObjectMapper objectMapper,
                    ObjectProvider<VisualEvidenceSigner> evidenceSigner) {
        return new TestSuiteStabilityObservationLedgerLifecycleAttestationService(
                objectMapper,
                evidenceSigner.getIfAvailable(VisualEvidenceSigner::unavailable));
    }

    /** Signs receipt-aware lifecycle pages in a distinct v2 public preview domain. */
    @Bean
    TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService
            testSuiteStabilityObservationLedgerLifecycleArchiveAttestationService(
                    ObjectMapper objectMapper,
                    ObjectProvider<VisualEvidenceSigner> evidenceSigner) {
        return new TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService(
                objectMapper,
                evidenceSigner.getIfAvailable(VisualEvidenceSigner::unavailable));
    }

    /** Reuses the evidence signer in the distinct retained-window trend signature domain. */
    @Bean
    TestSuiteStabilityTrendAttestationService testSuiteStabilityTrendAttestationService(
            ObjectMapper objectMapper, ObjectProvider<VisualEvidenceSigner> evidenceSigner) {
        return new TestSuiteStabilityTrendAttestationService(objectMapper,
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

    /** Assembles human-confirmed boundary-plan materialization into immutable v3 suites. */
    @Bean
    TestBoundarySuiteMaterializationService testBoundarySuiteMaterializationService(
            TestExecutionApiService executionService,
            TestSuiteRegistryService suiteRegistry,
            ObjectMapper objectMapper) {
        return new TestBoundarySuiteMaterializationService(
                executionService, suiteRegistry, objectMapper);
    }

    /** Assembles exact seeded-property-plan materialization into immutable V4 suites. */
    @Bean
    TestPropertySuiteMaterializationService testPropertySuiteMaterializationService(
            TestExecutionApiService executionService,
            TestSuiteRegistryService suiteRegistry,
            ObjectMapper objectMapper) {
        return new TestPropertySuiteMaterializationService(
                executionService, suiteRegistry, objectMapper);
    }

    /** Assembles exact pure-DSL mutation-plan materialization into immutable V5 suites. */
    @Bean
    TestMutationSuiteMaterializationService testMutationSuiteMaterializationService(
            TestExecutionApiService executionService,
            TestSuiteRegistryService suiteRegistry,
            ObjectMapper objectMapper) {
        return new TestMutationSuiteMaterializationService(
                executionService, suiteRegistry, objectMapper);
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

    /** Maintains one exact cross-replica owner for each synchronous stability horizon. */
    @Bean(destroyMethod = "close")
    TestSuiteStabilityLeaseCoordinator testSuiteStabilityLeaseCoordinator(
            TestSuiteStabilityRunRepository repository,
            @Value("${gateway.testing.stability-runs.instance-id:}") String instanceId,
            @Value("${gateway.testing.stability-runs.lease-duration-seconds:30}") long leaseSeconds,
            @Value("${gateway.testing.stability-runs.heartbeat-interval-seconds:5}")
            long heartbeatSeconds) {
        return new TestSuiteStabilityLeaseCoordinator(repository, instanceId,
                Duration.ofSeconds(leaseSeconds), Duration.ofSeconds(heartbeatSeconds));
    }

    /** Reclaims only a bounded oldest-first page of expired orphan stability leases. */
    @Bean
    TestSuiteStabilityLeaseRetentionScheduler testSuiteStabilityLeaseRetentionScheduler(
            TestSuiteStabilityRunRepository repository,
            @Value("${gateway.testing.stability-runs.lease-cleanup-batch-size:1000}")
            int batchSize) {
        return new TestSuiteStabilityLeaseRetentionScheduler(repository, batchSize);
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

    /** Assembles the isolated immutable mutation-suite runner and score evaluator. */
    @Bean
    TestMutationSuiteExecutionService testMutationSuiteExecutionService(
            TestSuiteRegistryService suiteRegistry,
            TestExecutionApiService executionService,
            TestSuiteRunRepository suiteRunRepository,
            TestSuiteRunLeaseCoordinator leaseCoordinator,
            TestSuiteRunAttestationService attestations,
            TestRuntimeAdmissionGate admissions,
            ObjectMapper objectMapper,
            TestSecurityEventRepository securityEvents,
            @Value("${gateway.testing.store.retention-days:30}") long retentionDays) {
        return new TestMutationSuiteExecutionService(
                suiteRegistry, executionService, suiteRunRepository, objectMapper, securityEvents,
                Duration.ofDays(Math.max(1, Math.min(3650, retentionDays))), leaseCoordinator,
                attestations, admissions);
    }

    /** Assembles bounded reruns over the ordinary suite runner and signed terminal store. */
    @Bean
    TestSuiteStabilityExecutionService testSuiteStabilityExecutionService(
            TestSuiteRegistryService suiteRegistry,
            TestSuiteExecutionService suiteExecutions,
            TestExecutionApiService childExecutions,
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttestationService attestations,
            TestSuiteStabilityObservationAttestationService observationAttestations,
            TestSuiteStabilityLeaseCoordinator leaseCoordinator,
            @Value("${gateway.testing.store.retention-days:30}") long retentionDays) {
        return new TestSuiteStabilityExecutionService(
                suiteRegistry, suiteExecutions, childExecutions, repository, objectMapper,
                attestations, observationAttestations, leaseCoordinator,
                Duration.ofDays(Math.max(1, Math.min(3650, retentionDays))));
    }

    /** Assembles signed retained-window longitudinal analysis over exact suite revisions. */
    @Bean
    TestSuiteStabilityTrendAnalysisService testSuiteStabilityTrendAnalysisService(
            TestSuiteRegistryService suiteRegistry,
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttestationService sourceAttestations,
            TestSuiteStabilityTrendAttestationService trendAttestations,
            @Value("${gateway.testing.store.retention-days:30}") long retentionDays) {
        return new TestSuiteStabilityTrendAnalysisService(
                suiteRegistry, repository, objectMapper, sourceAttestations, trendAttestations,
                Duration.ofDays(Math.max(1, Math.min(3650, retentionDays))));
    }

    /**
     * Configures strict multi-authority HTTPS WORM admission for explicit test/staging use.
     *
     * <p>Staging rejects loopback HTTP and requires at least two independent copies. Production
     * never loads this composition root; legal hold, backup/DR continuity, non-equivocation, and
     * scheduler gates must close before a production lifecycle capability can be advertised.</p>
     */
    @Bean
    @ConditionalOnMissingBean(TestSuiteStabilityObservationExternalArchiveAuthority.class)
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-observation-lifecycle.external-archive.http",
            name = "enabled", havingValue = "true")
    HttpTestSuiteStabilityObservationExternalArchiveAuthority
            testSuiteStabilityObservationExternalArchiveAuthority(
            ObjectMapper objectMapper,
            Environment environment,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.trust-domain:}")
            String trustDomain,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.archive-set-id:}")
            String archiveSetId,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.required-copies:0}")
            int requiredCopies,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.minimum-retention-days:0}")
            long minimumRetentionDays,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.authority-keys-json:[]}")
            String authorityKeysJson,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.endpoints-json:[]}")
            String endpointsJson,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.request-timeout-ms:3000}")
            long requestTimeoutMillis,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.maximum-receipt-lifetime-seconds:15}")
            long maximumReceiptLifetimeSeconds,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.maximum-inventory-snapshot-age-seconds:300}")
            long maximumInventorySnapshotAgeSeconds,
            @Value("${gateway.testing.stability-observation-lifecycle.external-archive.http.allow-insecure-loopback:false}")
            boolean allowInsecureLoopback) {
        boolean staging = Arrays.asList(environment.getActiveProfiles()).contains("staging");
        if (staging && (allowInsecureLoopback || requiredCopies < 2)) {
            throw new IllegalStateException(
                    "Staging external observation archive requires HTTPS and two copies");
        }
        return HttpTestSuiteStabilityObservationExternalArchiveAuthority.fromJson(
                objectMapper, trustDomain, archiveSetId, requiredCopies,
                Duration.ofDays(minimumRetentionDays), authorityKeysJson, endpointsJson,
                new HttpTestSuiteStabilityObservationExternalArchiveAuthority.Settings(
                        Duration.ofMillis(requestTimeoutMillis),
                        Duration.ofSeconds(maximumReceiptLifetimeSeconds),
                        Duration.ofSeconds(maximumInventorySnapshotAgeSeconds),
                        allowInsecureLoopback));
    }

    /**
     * Creates the database-leased signed inventory-cycle control plane only when the complete
     * reconciliation loop is explicitly enabled.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-observation-lifecycle.external-archive.reconciliation",
            name = "enabled", havingValue = "true")
    DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
            testSuiteStabilityObservationExternalArchiveReconciliationControlPlane(
                    TestRuntimeDatabase database,
                    ObjectMapper objectMapper,
                    TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.instance-id:}")
                    String instanceId,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.lease-duration-seconds:120}")
                    long leaseDurationSeconds,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.inventory-page-size:100}")
                    int pageSize) {
        String owner = requiredExternalReconciliationInstance(instanceId);
        return new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper, authority,
                new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .Settings(owner, Duration.ofSeconds(leaseDurationSeconds), pageSize));
    }

    /** Creates bounded frozen comparisons over completed external inventory cycles. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-observation-lifecycle.external-archive.reconciliation",
            name = "enabled", havingValue = "true")
    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
            testSuiteStabilityObservationExternalArchiveClassificationControlPlane(
                    TestRuntimeDatabase database,
                    ObjectMapper objectMapper,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.comparison-page-size:100}")
                    int pageSize) {
        return new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Settings(pageSize));
    }

    /** Creates replay-verified governed finding projection over completed comparisons. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-observation-lifecycle.external-archive.reconciliation",
            name = "enabled", havingValue = "true")
    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
            testSuiteStabilityObservationExternalArchiveFindingControlPlane(
                    TestRuntimeDatabase database,
                    ObjectMapper objectMapper,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.finding-page-size:100}")
                    int pageSize) {
        return new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .Settings(pageSize));
    }

    /** Creates the database-clock bounded lifecycle authority for derived finding evidence. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-observation-lifecycle.external-archive.reconciliation",
            name = "enabled", havingValue = "true")
    DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
            testSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane(
                    TestRuntimeDatabase database,
                    ObjectMapper objectMapper,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.instance-id:}")
                    String instanceId,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.retention-lease-duration-seconds:120}")
                    long leaseDurationSeconds) {
        return new DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                requiredExternalReconciliationInstance(instanceId),
                Duration.ofSeconds(leaseDurationSeconds));
    }

    /** Assembles downstream-first backpressure across inventory, comparison, and finding stages. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-observation-lifecycle.external-archive.reconciliation",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityObservationExternalArchiveReconciliationService
            testSuiteStabilityObservationExternalArchiveReconciliationService(
                    TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority,
                    DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                            inventories,
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            comparisons,
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                            findings) {
        return new TestSuiteStabilityObservationExternalArchiveReconciliationService(
                authority, inventories, comparisons, findings);
    }

    /** Runs one isolated, complete, protocol-bounded authority pass per scheduled tick. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-observation-lifecycle.external-archive.reconciliation",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityObservationExternalArchiveReconciliationScheduler
            testSuiteStabilityObservationExternalArchiveReconciliationScheduler(
                    TestSuiteStabilityObservationExternalArchiveReconciliationService service,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.initial-delay-ms:60000}")
                    long initialDelayMillis,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.interval-ms:300000}")
                    long intervalMillis) {
        validateExternalSchedule(initialDelayMillis, intervalMillis, "reconciliation");
        return new TestSuiteStabilityObservationExternalArchiveReconciliationScheduler(service);
    }

    /** Runs independently bounded derived finding/evidence retention under a database lease. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-observation-lifecycle.external-archive.reconciliation",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler
            testSuiteStabilityObservationExternalArchiveFindingRetentionScheduler(
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                            controlPlane,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.resolved-retention-seconds:2592000}")
                    long resolvedRetentionSeconds,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.archive-retention-seconds:31536000}")
                    long archiveRetentionSeconds,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.evidence-retention-seconds:31536000}")
                    long evidenceRetentionSeconds,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.retention-page-size:100}")
                    int pageSize,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.retention-initial-delay-ms:300000}")
                    long initialDelayMillis,
                    @Value("${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.retention-interval-ms:3600000}")
                    long intervalMillis) {
        validateExternalSchedule(initialDelayMillis, intervalMillis, "retention");
        return new TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler(
                controlPlane, Duration.ofSeconds(resolvedRetentionSeconds),
                Duration.ofSeconds(archiveRetentionSeconds),
                Duration.ofSeconds(evidenceRetentionSeconds), pageSize);
    }

    /** Exposes identity-free health for the configured external WORM copy set. */
    @Bean
    @ConditionalOnBean(TestSuiteStabilityObservationExternalArchiveAuthority.class)
    TestSuiteStabilityObservationExternalArchiveHealth
            testSuiteStabilityObservationExternalArchiveHealth(
            TestSuiteStabilityObservationExternalArchiveAuthority authority) {
        return new TestSuiteStabilityObservationExternalArchiveHealth(authority);
    }

    private static String requiredExternalReconciliationInstance(String value) {
        String exact = value == null ? "" : value.trim();
        if (exact.isEmpty()) {
            throw new IllegalStateException(
                    "External archive reconciliation requires a stable instance ID");
        }
        return exact;
    }

    private static void validateExternalSchedule(
            long initialDelayMillis,
            long intervalMillis,
            String name) {
        long maximum = Duration.ofDays(7).toMillis();
        if (initialDelayMillis < 0 || initialDelayMillis > maximum
                || intervalMillis < 1000 || intervalMillis > maximum) {
            throw new IllegalStateException("External archive " + name
                    + " schedule must use a 0..7 day initial delay and 1 second..7 day interval");
        }
    }

    /** Assembles the external-first retirement boundary only when WORM admission is configured. */
    @Bean
    @ConditionalOnBean(TestSuiteStabilityObservationExternalArchiveAuthority.class)
    TestSuiteStabilityObservationFloorRetirementService
            testSuiteStabilityObservationFloorRetirementService(
            ObjectMapper objectMapper,
            TestSuiteStabilityRunRepository repository,
            TestSuiteStabilityObservationFloorRetirementAttestationService attestations,
            TestSuiteStabilityObservationExternalArchiveAuthority authority) {
        return new TestSuiteStabilityObservationFloorRetirementService(
                objectMapper, repository, attestations, authority);
    }

    /** Assembles preview-only signed trends over exact compact-observation ledger ranges. */
    @Bean
    @ConditionalOnProperty(
            name = "gateway.testing.stability-cross-retention-preview-enabled",
            havingValue = "true")
    TestSuiteStabilityCrossRetentionTrendAnalysisService
            testSuiteStabilityCrossRetentionTrendAnalysisService(
                    TestSuiteRegistryService suiteRegistry,
                    TestSuiteStabilityRunRepository repository,
                    ObjectMapper objectMapper,
                    TestSuiteStabilityObservationAttestationService observationAttestations,
                    TestSuiteStabilityCrossRetentionTrendAttestationService trendAttestations) {
        return new TestSuiteStabilityCrossRetentionTrendAnalysisService(
                suiteRegistry, repository, objectMapper,
                observationAttestations, trendAttestations);
    }

    /** Assembles preview-only signed floor lifecycle pages over exact suite revisions. */
    @Bean
    @ConditionalOnProperty(
            name = "gateway.testing.stability-cross-retention-preview-enabled",
            havingValue = "true")
    TestSuiteStabilityObservationLedgerLifecyclePageService
            testSuiteStabilityObservationLedgerLifecyclePageService(
                    TestSuiteRegistryService suiteRegistry,
                    TestSuiteStabilityRunRepository repository,
                    ObjectMapper objectMapper,
                    TestSuiteStabilityObservationFloorRetirementAttestationService
                            retirementAttestations,
                    TestSuiteStabilityObservationLedgerLifecycleAttestationService
                            lifecycleAttestations) {
        return new TestSuiteStabilityObservationLedgerLifecyclePageService(
                suiteRegistry, repository, objectMapper,
                retirementAttestations, lifecycleAttestations);
    }

    /** Assembles preview-only lifecycle v2 pages carrying exact external archive receipt sets. */
    @Bean
    @ConditionalOnProperty(
            name = "gateway.testing.stability-cross-retention-preview-enabled",
            havingValue = "true")
    TestSuiteStabilityObservationLedgerLifecycleArchivePageService
            testSuiteStabilityObservationLedgerLifecycleArchivePageService(
                    TestSuiteStabilityObservationLedgerLifecyclePageService lifecyclePages,
                    TestSuiteStabilityRunRepository repository,
                    ObjectMapper objectMapper,
                    TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService
                            archiveAttestations) {
        return new TestSuiteStabilityObservationLedgerLifecycleArchivePageService(
                lifecyclePages, repository, objectMapper, archiveAttestations);
    }

    /**
     * Starts one shared heartbeat coordinator only for explicitly enabled background execution.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "gateway.testing.stability-jobs.worker",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityJobExecutionCoordinator testSuiteStabilityJobExecutionCoordinator(
            TestSuiteStabilityJobRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityQueuePolicy policy,
            @Value("${gateway.testing.stability-jobs.worker.heartbeat-interval-seconds:5}")
            long heartbeatIntervalSeconds) {
        Duration heartbeat = Duration.ofSeconds(heartbeatIntervalSeconds);
        if (heartbeat.multipliedBy(3).compareTo(policy.leaseDuration()) > 0) {
            throw new IllegalArgumentException(
                    "Stability job heartbeat must be at most one-third of its lease");
        }
        return new TestSuiteStabilityJobExecutionCoordinator(
                repository, objectMapper, heartbeat);
    }

    /**
     * Builds static public-key trust for signed current-authority decisions when no deployment
     * supplied dynamic trust store exists.
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.testing.stability-jobs.authority.http",
            name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "gateway.testing.stability-jobs.authority.http.jwks",
            name = "enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(TestSuiteStabilityAuthorityTrustStore.class)
    TestSuiteStabilityAuthorityTrustStore testSuiteStabilityAuthorityTrustStore(
            ObjectMapper objectMapper,
            @Value("${gateway.testing.stability-jobs.authority.http.expected-authority-id:}")
            String expectedAuthorityId,
            @Value("${gateway.testing.stability-jobs.authority.http.maximum-decision-lifetime-seconds:60}")
            long maximumDecisionLifetimeSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.clock-skew-seconds:5}")
            long clockSkewSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.minimum-remaining-validity-ms:100}")
            long minimumRemainingValidityMillis,
            @Value("${gateway.testing.stability-jobs.authority.http.authority-keys-json:[]}")
            String authorityKeysJson) {
        return ConfiguredTestSuiteStabilityAuthorityTrustStore.fromJson(
                objectMapper, expectedAuthorityId,
                Duration.ofSeconds(maximumDecisionLifetimeSeconds),
                Duration.ofSeconds(clockSkewSeconds),
                Duration.ofMillis(minimumRemainingValidityMillis), authorityKeysJson);
    }

    /**
     * Bootstraps and continuously refreshes the product Ed25519 authority JWKS.
     *
     * <p>The bean starts only when explicitly selected and is mutually exclusive with the static
     * key-ring fallback. A deployment-supplied trust store still takes precedence.</p>
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "gateway.testing.stability-jobs.authority.http",
            name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "gateway.testing.stability-jobs.authority.http.jwks",
            name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(TestSuiteStabilityAuthorityTrustStore.class)
    DynamicJwksTestSuiteStabilityAuthorityTrustStore
            dynamicJwksTestSuiteStabilityAuthorityTrustStore(
            ObjectMapper objectMapper,
            @Value("${gateway.testing.stability-jobs.authority.http.expected-authority-id:}")
            String expectedAuthorityId,
            @Value("${gateway.testing.stability-jobs.authority.http.maximum-decision-lifetime-seconds:60}")
            long maximumDecisionLifetimeSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.clock-skew-seconds:5}")
            long clockSkewSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.minimum-remaining-validity-ms:100}")
            long minimumRemainingValidityMillis,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.uri:}")
            String jwksUri,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.refresh-interval-seconds:30}")
            long refreshIntervalSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.unknown-key-refresh-interval-seconds:5}")
            long unknownKeyRefreshIntervalSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.request-timeout-ms:3000}")
            long requestTimeoutMillis,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.maximum-snapshot-age-seconds:60}")
            long maximumSnapshotAgeSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.allow-insecure-loopback:false}")
            boolean allowInsecureLoopback) {
        URI uri;
        try {
            uri = URI.create(jwksUri == null ? "" : jwksUri.trim());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Stability authority JWKS URI is invalid", invalid);
        }
        return new DynamicJwksTestSuiteStabilityAuthorityTrustStore(
                objectMapper, expectedAuthorityId,
                Duration.ofSeconds(maximumDecisionLifetimeSeconds),
                Duration.ofSeconds(clockSkewSeconds),
                Duration.ofMillis(minimumRemainingValidityMillis),
                new DynamicJwksTestSuiteStabilityAuthorityTrustStore.Settings(
                        uri, Duration.ofSeconds(refreshIntervalSeconds),
                        Duration.ofSeconds(unknownKeyRefreshIntervalSeconds),
                        Duration.ofMillis(requestTimeoutMillis),
                        Duration.ofSeconds(maximumSnapshotAgeSeconds),
                        allowInsecureLoopback));
    }

    /** Publishes payload-free Actuator health for the configured dynamic authority snapshot. */
    @Bean
    @ConditionalOnBean(DynamicJwksTestSuiteStabilityAuthorityTrustStore.class)
    TestSuiteStabilityAuthorityTrustHealth testSuiteStabilityAuthorityTrustHealth(
            DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore) {
        return new TestSuiteStabilityAuthorityTrustHealth(trustStore);
    }

    /** Verifies one static or dynamically witnessed deployment-signed serving inventory. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityServingInventoryAuthority
            testSuiteStabilityServingInventoryAuthority(
            ObjectMapper objectMapper,
            ObjectProvider<TestSuiteStabilityServingInventoryPublicationFloor> publicationFloors,
            ObjectProvider<DynamicTestSuiteStabilityServingInventoryTrustRootAuthority>
                    managedTrustRoots,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.trust-domain:}")
            String trustDomain,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.accepted-policy-fingerprints:}")
            String acceptedPolicyFingerprints,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.signature-threshold:0}")
            int signatureThreshold,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.authority-keys-json:[]}")
            String authorityKeysJson,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.inventory-json:}")
            String inventoryJson,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.enabled:false}")
            boolean remoteEnabled,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.required:false}")
            boolean remoteRequired,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.enabled:false}")
            boolean managedTrustRootsEnabled,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.required:false}")
            boolean managedTrustRootsRequired,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.uri:}")
            String remoteUri,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.refresh-interval-seconds:30}")
            long remoteRefreshIntervalSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.request-timeout-ms:3000}")
            long remoteRequestTimeoutMillis,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.maximum-snapshot-age-seconds:60}")
            long remoteMaximumSnapshotAgeSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.allow-insecure-loopback:false}")
            boolean remoteAllowInsecureLoopback,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.witness-domain:}")
            String witnessDomain,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.witness-signature-threshold:0}")
            int witnessSignatureThreshold,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.witness-authority-keys-json:[]}")
            String witnessAuthorityKeysJson,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.scope-id:}")
            String scopeId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.cohort-id:}")
            String cohortId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.instance-id:}")
            String instanceId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.artifact-fingerprint:}")
            String artifactFingerprint) {
        if (remoteRequired && !remoteEnabled) {
            throw new IllegalStateException(
                    "This profile requires dynamic witnessed serving inventory");
        }
        if (managedTrustRootsRequired && !managedTrustRootsEnabled) {
            throw new IllegalStateException(
                    "This profile requires managed serving-inventory trust roots");
        }
        if (managedTrustRootsEnabled && !remoteEnabled) {
            throw new IllegalStateException(
                    "Managed serving-inventory trust roots require remote inventory refresh");
        }
        if (remoteEnabled && inventoryJson != null && !inventoryJson.isBlank()) {
            throw new IllegalStateException(
                    "Dynamic serving inventory cannot also use a static inventory document");
        }
        ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding =
                new ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding(
                        scopeId, cohortId, artifactFingerprint,
                        ToolStudioResourceGatewayProtocol.VERSION, instanceId);
        if (remoteEnabled) {
            List<TestSuiteStabilityServingInventoryPublicationFloor> floors =
                    publicationFloors.orderedStream().toList();
            if (floors.size() != 1 || !floors.getFirst().durable()) {
                throw new IllegalStateException(
                        "Dynamic serving inventory requires one durable publication floor");
            }
            URI uri;
            try {
                uri = URI.create(remoteUri == null ? "" : remoteUri.trim());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "Serving-inventory publication URI is invalid", invalid);
            }
            List<DynamicTestSuiteStabilityServingInventoryTrustRootAuthority> roots =
                    managedTrustRoots.orderedStream().toList();
            if ((managedTrustRootsEnabled && roots.size() != 1)
                    || (!managedTrustRootsEnabled && !roots.isEmpty())) {
                throw new IllegalStateException(
                        "Managed serving-inventory trust roots require exactly one authority");
            }
            DynamicTestSuiteStabilityServingInventoryAuthority.Settings settings =
                    new DynamicTestSuiteStabilityServingInventoryAuthority.Settings(
                            uri, Duration.ofSeconds(remoteRefreshIntervalSeconds),
                            Duration.ofMillis(remoteRequestTimeoutMillis),
                            Duration.ofSeconds(remoteMaximumSnapshotAgeSeconds),
                            remoteAllowInsecureLoopback);
            if (managedTrustRootsEnabled) {
                if (signatureThreshold != 0 || witnessSignatureThreshold != 0
                        || trustDomain != null && !trustDomain.isBlank()
                        || witnessDomain != null && !witnessDomain.isBlank()
                        || !emptyJsonArray(objectMapper, authorityKeysJson)
                        || !emptyJsonArray(objectMapper, witnessAuthorityKeysJson)) {
                    throw new IllegalStateException(
                            "Managed serving-inventory trust roots forbid static runtime keys");
                }
                return new DynamicTestSuiteStabilityServingInventoryAuthority(
                        objectMapper,
                        ConfiguredTestSuiteStabilityServingInventoryAuthority.parsePolicies(
                                acceptedPolicyFingerprints),
                        binding, floors.getFirst(), roots.getFirst(), settings);
            }
            return DynamicTestSuiteStabilityServingInventoryAuthority.fromJson(
                    objectMapper, trustDomain, acceptedPolicyFingerprints,
                    signatureThreshold, authorityKeysJson, binding, floors.getFirst(),
                    witnessDomain, witnessSignatureThreshold, witnessAuthorityKeysJson, settings);
        }
        return ConfiguredTestSuiteStabilityServingInventoryAuthority.fromJson(
                objectMapper, trustDomain, acceptedPolicyFingerprints, signatureThreshold,
                authorityKeysJson, inventoryJson, binding);
    }

    /** Configures the external signed quorum shared by both mutable inventory chains. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote",
            name = "enabled", havingValue = "true")
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityExternalSequenceAnchor testSuiteStabilityExternalSequenceAnchor(
            ObjectMapper objectMapper,
            Environment environment,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.trust-domain:}")
            String trustDomain,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.anchor-set-id:}")
            String anchorSetId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.signature-threshold:0}")
            int signatureThreshold,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.maximum-faults:0}")
            int maximumFaults,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.minimum-faults:0}")
            int minimumFaults,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.authority-keys-json:[]}")
            String authorityKeysJson,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.endpoints-json:[]}")
            String endpointsJson,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.request-timeout-ms:3000}")
            long requestTimeoutMillis,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.clock-skew-seconds:5}")
            long clockSkewSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.maximum-receipt-lifetime-seconds:15}")
            long maximumReceiptLifetimeSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.allow-insecure-loopback:false}")
            boolean allowInsecureLoopback) {
        int profileMinimumFaults = Arrays.asList(environment.getActiveProfiles())
                .contains("staging") ? 1 : 0;
        int effectiveMinimumFaults = Math.max(minimumFaults, profileMinimumFaults);
        if (minimumFaults < 0 || minimumFaults > 10
                || maximumFaults < effectiveMinimumFaults) {
            throw new IllegalStateException(
                    "External sequence anchor does not meet the deployment fault policy");
        }
        return HttpTestSuiteStabilityExternalSequenceAnchor.fromJson(
                objectMapper, trustDomain, anchorSetId, signatureThreshold, maximumFaults,
                authorityKeysJson, endpointsJson,
                new HttpTestSuiteStabilityExternalSequenceAnchor.Settings(
                        Duration.ofMillis(requestTimeoutMillis),
                        Duration.ofSeconds(clockSkewSeconds),
                        Duration.ofSeconds(maximumReceiptLifetimeSeconds),
                        allowInsecureLoopback));
    }

    /** Exposes endpoint- and key-free health for the external non-equivocation quorum. */
    @Bean
    @ConditionalOnBean(TestSuiteStabilityExternalSequenceAnchor.class)
    TestSuiteStabilityExternalSequenceAnchorHealth
            testSuiteStabilityExternalSequenceAnchorHealth(
            TestSuiteStabilityExternalSequenceAnchor anchor) {
        return new TestSuiteStabilityExternalSequenceAnchorHealth(anchor);
    }

    /** Persists and externally anchors the managed dual runtime-key publication. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote",
            name = "enabled", havingValue = "true")
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityServingInventoryTrustRootFloor
            testSuiteStabilityServingInventoryTrustRootFloor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ObjectProvider<TestSuiteStabilityExternalSequenceAnchor> externalAnchors,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.scope-id:}")
            String scopeId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.trust-root-set-id:}")
            String trustRootSetId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.enabled:false}")
            boolean externalAnchorEnabled,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.required:false}")
            boolean externalAnchorRequired) {
        TestSuiteStabilityExternalSequenceAnchor externalAnchor = externalAnchor(
                externalAnchors, externalAnchorEnabled, externalAnchorRequired);
        TestSuiteStabilityServingInventoryTrustRootFloor local =
                new DatabaseTestSuiteStabilityServingInventoryTrustRootFloor(
                database.jdbc(), objectMapper, scopeId, trustRootSetId,
                database.transactionManager());
        return externalAnchor == null ? local
                : new ExternallyAnchoredTestSuiteStabilityServingInventoryTrustRootFloor(
                local, externalAnchor);
    }

    /** Bootstraps and refreshes one atomic dual-quorum serving-inventory runtime-key set. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote",
            name = "enabled", havingValue = "true")
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots",
            name = "enabled", havingValue = "true")
    DynamicTestSuiteStabilityServingInventoryTrustRootAuthority
            dynamicTestSuiteStabilityServingInventoryTrustRootAuthority(
            ObjectMapper objectMapper,
            TestSuiteStabilityServingInventoryTrustRootFloor floor,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.scope-id:}")
            String scopeId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.trust-root-set-id:}")
            String trustRootSetId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.accepted-policy-fingerprints:}")
            String acceptedPolicyFingerprints,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.deployment-root-domain:}")
            String deploymentRootDomain,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.deployment-root-signature-threshold:0}")
            int deploymentRootSignatureThreshold,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.deployment-root-authority-keys-json:[]}")
            String deploymentRootAuthorityKeysJson,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.witness-root-domain:}")
            String witnessRootDomain,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.witness-root-signature-threshold:0}")
            int witnessRootSignatureThreshold,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.witness-root-authority-keys-json:[]}")
            String witnessRootAuthorityKeysJson,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.uri:}")
            String remoteUri,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.refresh-interval-seconds:30}")
            long refreshIntervalSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.request-timeout-ms:3000}")
            long requestTimeoutMillis,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.unknown-key-refresh-interval-seconds:5}")
            long unknownKeyRefreshIntervalSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.maximum-snapshot-age-seconds:60}")
            long maximumSnapshotAgeSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.trust-roots.allow-insecure-loopback:false}")
            boolean allowInsecureLoopback,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.trust-domain:}")
            String legacyDeploymentDomain,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.signature-threshold:0}")
            int legacyDeploymentThreshold,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.authority-keys-json:[]}")
            String legacyDeploymentKeysJson,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.witness-domain:}")
            String legacyWitnessDomain,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.witness-signature-threshold:0}")
            int legacyWitnessThreshold,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.witness-authority-keys-json:[]}")
            String legacyWitnessKeysJson) {
        if (legacyDeploymentThreshold != 0 || legacyWitnessThreshold != 0
                || legacyDeploymentDomain != null && !legacyDeploymentDomain.isBlank()
                || legacyWitnessDomain != null && !legacyWitnessDomain.isBlank()
                || !emptyJsonArray(objectMapper, legacyDeploymentKeysJson)
                || !emptyJsonArray(objectMapper, legacyWitnessKeysJson)) {
            throw new IllegalStateException(
                    "Managed serving-inventory trust roots forbid static runtime keys");
        }
        URI uri;
        try {
            uri = URI.create(remoteUri == null ? "" : remoteUri.trim());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Serving-inventory trust-root URI is invalid", invalid);
        }
        var binding =
                new ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority.ExpectedBinding(
                        scopeId, trustRootSetId, ToolStudioResourceGatewayProtocol.VERSION,
                        deploymentRootDomain, witnessRootDomain);
        return DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.fromJson(
                objectMapper, binding, acceptedPolicyFingerprints,
                deploymentRootSignatureThreshold, deploymentRootAuthorityKeysJson,
                witnessRootSignatureThreshold, witnessRootAuthorityKeysJson, floor,
                new DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.Settings(
                        uri, Duration.ofSeconds(refreshIntervalSeconds),
                        Duration.ofMillis(requestTimeoutMillis),
                        Duration.ofSeconds(unknownKeyRefreshIntervalSeconds),
                        Duration.ofSeconds(maximumSnapshotAgeSeconds),
                        allowInsecureLoopback));
    }

    /** Exposes key-free health for the managed dual runtime-key source. */
    @Bean
    @ConditionalOnBean(DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.class)
    TestSuiteStabilityServingInventoryTrustRootHealth
            testSuiteStabilityServingInventoryTrustRootHealth(
            DynamicTestSuiteStabilityServingInventoryTrustRootAuthority authority) {
        return new TestSuiteStabilityServingInventoryTrustRootHealth(authority);
    }

    /** Persists the dynamic publication/witness chain head before local state publication. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityServingInventoryPublicationFloor
            testSuiteStabilityServingInventoryPublicationFloor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ObjectProvider<TestSuiteStabilityExternalSequenceAnchor> externalAnchors,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.scope-id:}")
            String scopeId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.enabled:false}")
            boolean externalAnchorEnabled,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor.required:false}")
            boolean externalAnchorRequired) {
        TestSuiteStabilityExternalSequenceAnchor externalAnchor = externalAnchor(
                externalAnchors, externalAnchorEnabled, externalAnchorRequired);
        TestSuiteStabilityServingInventoryPublicationFloor local =
                new DatabaseTestSuiteStabilityServingInventoryPublicationFloor(
                database.jdbc(), objectMapper, scopeId, database.transactionManager());
        return externalAnchor == null ? local
                : new ExternallyAnchoredTestSuiteStabilityServingInventoryPublicationFloor(
                objectMapper, local, externalAnchor);
    }

    /** Exposes key-free refresh health for the dynamic witnessed serving-inventory source. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityServingInventoryHealth testSuiteStabilityServingInventoryHealth(
            TestSuiteStabilityServingInventoryAuthority authority) {
        if (!(authority instanceof DynamicTestSuiteStabilityServingInventoryAuthority dynamic)) {
            throw new IllegalStateException(
                    "Dynamic serving-inventory health requires the dynamic authority");
        }
        return new TestSuiteStabilityServingInventoryHealth(dynamic);
    }

    /** Freezes one exact configured or externally attested serving cohort. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityAuthorityCohortPolicy testSuiteStabilityAuthorityCohortPolicy(
            ObjectProvider<TestSuiteStabilityServingInventoryAuthority> inventoryAuthorities,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.scope-id:}")
            String scopeId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.cohort-id:}")
            String cohortId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.instance-id:}")
            String instanceId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.artifact-fingerprint:}")
            String artifactFingerprint,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.expected-instance-ids:}")
            String expectedInstanceIds,
            @Value("${gateway.testing.stability-jobs.authority.http.expected-authority-id:}")
            String authorityId,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.heartbeat-interval-seconds:10}")
            long heartbeatIntervalSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.lease-duration-seconds:30}")
            long leaseDurationSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.record-retention-seconds:86400}")
            long recordRetentionSeconds,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.enabled:false}")
            boolean signedInventoryEnabled,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.required:false}")
            boolean signedInventoryRequired) {
        List<TestSuiteStabilityServingInventoryAuthority> authorities =
                inventoryAuthorities.orderedStream().toList();
        if (signedInventoryRequired && !signedInventoryEnabled) {
            throw new IllegalStateException(
                    "This profile requires deployment-signed serving inventory");
        }
        if (signedInventoryEnabled != (authorities.size() == 1)) {
            throw new IllegalStateException(
                    "Signed serving inventory requires exactly one configured authority");
        }
        Set<String> expected;
        TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation attestation;
        if (signedInventoryEnabled) {
            TestSuiteStabilityServingInventoryAuthority.Observation observed =
                    authorities.getFirst().observation();
            if (!observed.available() || !observed.externallyAttested()) {
                throw new IllegalStateException(
                        "Signed serving inventory must be current and verified");
            }
            expected = Set.copyOf(observed.expectedInstanceIds());
            if (expectedInstanceIds != null && !expectedInstanceIds.isBlank()
                    && !expected.equals(
                    stabilityAuthorityExpectedInstances(expectedInstanceIds))) {
                throw new IllegalStateException(
                        "Configured and signed serving inventories disagree");
            }
            attestation = TestSuiteStabilityAuthorityCohortPolicy
                    .ServingInventoryAttestation.external(observed);
        } else {
            expected = stabilityAuthorityExpectedInstances(expectedInstanceIds);
            attestation = TestSuiteStabilityAuthorityCohortPolicy
                    .ServingInventoryAttestation.localConfigured();
        }
        return new TestSuiteStabilityAuthorityCohortPolicy(
                scopeId, cohortId, instanceId, UUID.randomUUID().toString(), artifactFingerprint,
                expected, authorityId,
                ToolStudioResourceGatewayProtocol.VERSION,
                Duration.ofSeconds(heartbeatIntervalSeconds),
                Duration.ofSeconds(leaseDurationSeconds),
                Duration.ofSeconds(recordRetentionSeconds), attestation);
    }

    /** Creates the database-clock process-start lease and exact cohort inventory authority. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort",
            name = "enabled", havingValue = "true")
    DatabaseTestSuiteStabilityAuthorityCohortRepository
            testSuiteStabilityAuthorityCohortRepository(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            TestSuiteStabilityAuthorityCohortPolicy policy) {
        DatabaseTestSuiteStabilityAuthorityCohortRepository repository =
                new DatabaseTestSuiteStabilityAuthorityCohortRepository(
                        database.jdbc(), objectMapper, policy, database.transactionManager());
        return repository;
    }

    /** Publishes local dynamic trust and gates on exact database cohort convergence. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "gateway.testing.stability-jobs.authority.http.jwks.cohort",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityAuthorityCohortMonitor testSuiteStabilityAuthorityCohortMonitor(
            DatabaseTestSuiteStabilityAuthorityCohortRepository repository,
            DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore,
            ObjectProvider<TestSuiteStabilityServingInventoryAuthority> inventoryAuthorities,
            TestSuiteStabilityAuthorityCohortPolicy policy,
            ObjectMapper objectMapper) {
        List<TestSuiteStabilityServingInventoryAuthority> authorities =
                inventoryAuthorities.orderedStream().toList();
        boolean external = policy.servingInventory().externallyAttested();
        if (external != (authorities.size() == 1)) {
            throw new IllegalStateException(
                    "Cohort policy and serving-inventory authority disagree");
        }
        TestSuiteStabilityServingInventoryAuthority inventoryAuthority = external
                ? authorities.getFirst()
                : TestSuiteStabilityServingInventoryAuthority.localOnly();
        return new TestSuiteStabilityAuthorityCohortMonitor(
                repository, trustStore, inventoryAuthority, policy, objectMapper);
    }

    /** Exposes aggregate-only configured cohort convergence through Actuator. */
    @Bean
    @ConditionalOnBean(TestSuiteStabilityAuthorityCohortMonitor.class)
    TestSuiteStabilityAuthorityCohortHealth testSuiteStabilityAuthorityCohortHealth(
            TestSuiteStabilityAuthorityCohortMonitor monitor) {
        return new TestSuiteStabilityAuthorityCohortHealth(monitor);
    }

    /**
     * Creates the product HTTPS PDP adapter; the worker still enforces exactly one authorizer.
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.testing.stability-jobs.authority.http",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityJobAuthorizer httpTestSuiteStabilityJobAuthorizer(
            ObjectMapper objectMapper,
            TestSuiteStabilityAuthorityTrustStore trustStore,
            ObjectProvider<TestSuiteStabilityAuthorityCohortGate> cohortGates,
            @Value("${gateway.testing.stability-jobs.authority.http.base-uri:}")
            String baseUri,
            @Value("${gateway.testing.stability-jobs.authority.http.request-timeout-ms:3000}")
            long requestTimeoutMillis,
            @Value("${gateway.testing.stability-jobs.authority.http.allow-insecure-loopback:false}")
            boolean allowInsecureLoopback,
            @Value("${gateway.testing.stability-jobs.authority.http.jwks.cohort.enabled:false}")
            boolean cohortEnabled) {
        URI uri;
        try {
            uri = URI.create(baseUri == null ? "" : baseUri.trim());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Stability authority base URI is invalid", invalid);
        }
        List<TestSuiteStabilityAuthorityCohortGate> configuredGates =
                cohortGates.orderedStream().toList();
        if (cohortEnabled && configuredGates.size() != 1) {
            throw new IllegalStateException(
                    "Enabled stability authority cohort requires exactly one gate");
        }
        if (!cohortEnabled && !configuredGates.isEmpty()) {
            throw new IllegalStateException(
                    "Stability authority cohort gate requires its explicit switch");
        }
        TestSuiteStabilityAuthorityCohortGate cohortGate = cohortEnabled
                ? configuredGates.getFirst()
                : TestSuiteStabilityAuthorityCohortGate.localOnly();
        return new HttpTestSuiteStabilityJobAuthorizer(
                objectMapper, trustStore, cohortGate,
                new HttpTestSuiteStabilityJobAuthorizer.Settings(
                        uri, Duration.ofMillis(requestTimeoutMillis), allowInsecureLoopback));
    }

    /**
     * Creates the worker only when one unambiguous external current-authority provider exists.
     *
     * <p>No permissive local authorizer exists: enabling the worker without exactly one provider
     * fails application startup instead of treating the submission-time principal as perpetual
     * authority.</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.testing.stability-jobs.worker",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityJobWorker testSuiteStabilityJobWorker(
            TestSuiteStabilityJobRepository repository,
            TestSuiteStabilityExecutionService executions,
            TestSuiteStabilityJobExecutionCoordinator coordinator,
            ObjectProvider<TestSuiteStabilityJobAuthorizer> authorizers,
            TestSuiteStabilityQueuePolicy policy,
            @Value("${gateway.testing.stability-jobs.worker.instance-id:}") String instanceId,
            @Value("${gateway.testing.stability-jobs.worker.maximum-local-executions:4}")
            int maximumLocalExecutions) {
        List<TestSuiteStabilityJobAuthorizer> currentAuthorities =
                authorizers.orderedStream().toList();
        if (currentAuthorities.size() != 1) {
            throw new IllegalStateException(
                    "Enabled stability worker requires exactly one "
                            + "TestSuiteStabilityJobAuthorizer bean");
        }
        if (!currentAuthorityLocallyReady(currentAuthorities.getFirst().descriptor())) {
            throw new IllegalStateException(
                    "Enabled stability worker requires locally ready "
                            + "TestSuiteStabilityJobAuthorizer descriptor");
        }
        String ownerId = instanceId == null || instanceId.isBlank()
                ? "stability-worker-" + UUID.randomUUID()
                : instanceId.trim();
        return new TestSuiteStabilityJobWorker(
                repository, executions, coordinator, currentAuthorities.getFirst(),
                policy, ownerId, maximumLocalExecutions);
    }

    /** Starts bounded fixed-delay lanes after all worker safety dependencies are assembled. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "gateway.testing.stability-jobs.worker",
            name = "enabled", havingValue = "true")
    TestSuiteStabilityJobScheduler testSuiteStabilityJobScheduler(
            TestSuiteStabilityJobWorker worker,
            TestSuiteStabilityJobTelemetry telemetry,
            @Value("${gateway.testing.stability-jobs.worker.environments:test}")
            String environments,
            @Value("${gateway.testing.stability-jobs.worker.maximum-pollers:1}")
            int maximumPollers,
            @Value("${gateway.testing.stability-jobs.worker.initial-delay-ms:1000}")
            long initialDelayMillis,
            @Value("${gateway.testing.stability-jobs.worker.poll-interval-ms:1000}")
            long pollIntervalMillis,
            @Value("${gateway.testing.stability-jobs.worker.drain-timeout-seconds:30}")
            long drainTimeoutSeconds) {
        return new TestSuiteStabilityJobScheduler(
                worker, stabilityJobEnvironments(environments), maximumPollers,
                Duration.ofMillis(initialDelayMillis),
                Duration.ofMillis(pollIntervalMillis),
                Duration.ofSeconds(drainTimeoutSeconds), telemetry);
    }

    /** Marker consumed by the unauthenticated capability probe. */
    @Bean
    TestabilityAvailability testabilityAvailability(
            DatabaseDurableWorkerQuarantineControlPlane controlPlane,
            WorkerQuarantineChangeAuthorizationTrustStore changeAuthorizationTrust,
            ObjectProvider<TestSuiteStabilityJobAuthorizer> authorizers,
            @Value("${gateway.testing.stability-jobs.worker.enabled:false}")
            boolean suiteStabilityJobSubmissionEnabled) {
        List<TestSuiteStabilityJobAuthorizer> currentAuthorities =
                authorizers.orderedStream().toList();
        TestSuiteStabilityJobAuthorizer.Descriptor currentAuthority =
                currentAuthorities.size() == 1
                        ? currentAuthorities.getFirst().descriptor()
                        : new TestSuiteStabilityJobAuthorizer.Descriptor(
                        "", false, "UNAVAILABLE", "", java.util.Map.of());
        return new TestabilityAvailability(true, suiteStabilityJobSubmissionEnabled,
                controlPlane.requestIndexMode(),
                changeAuthorizationTrust.descriptor(), currentAuthority);
    }

    private static Set<String> stabilityJobEnvironments(String environments) {
        return new LinkedHashSet<>(Arrays.asList(
                environments == null ? new String[0] : environments.split(",", -1)));
    }

    private static boolean currentAuthorityReady(
            ObjectProvider<TestSuiteStabilityJobAuthorizer> authorizers) {
        try {
            List<TestSuiteStabilityJobAuthorizer> providers =
                    authorizers.orderedStream().toList();
            if (providers.size() != 1) {
                return false;
            }
            return providers.getFirst().descriptor().available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean currentAuthorityLocallyReady(
            TestSuiteStabilityJobAuthorizer.Descriptor descriptor) {
        return descriptor != null && (descriptor.available()
                || Boolean.TRUE.equals(descriptor.properties().get("trustLocalAvailable")));
    }

    private static Set<String> stabilityAuthorityExpectedInstances(String instances) {
        String[] values = instances == null ? new String[0] : instances.split(",", -1);
        LinkedHashSet<String> exact = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank() || !exact.add(normalized)) {
                throw new IllegalArgumentException(
                        "Stability authority cohort instance inventory is invalid");
            }
        }
        return Set.copyOf(exact);
    }

    private static boolean emptyJsonArray(ObjectMapper objectMapper, String value) {
        try {
            var parsed = objectMapper.readTree(value == null ? "" : value.trim());
            return parsed != null && parsed.isArray() && parsed.isEmpty();
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    private static TestSuiteStabilityExternalSequenceAnchor externalAnchor(
            ObjectProvider<TestSuiteStabilityExternalSequenceAnchor> anchors,
            boolean enabled,
            boolean required) {
        if (required && !enabled) {
            throw new IllegalStateException(
                    "This profile requires external serving-inventory non-equivocation");
        }
        List<TestSuiteStabilityExternalSequenceAnchor> configured =
                anchors.orderedStream().toList();
        if ((enabled && configured.size() != 1) || (!enabled && !configured.isEmpty())) {
            throw new IllegalStateException(
                    "External serving-inventory non-equivocation requires exactly one anchor");
        }
        if (!enabled) {
            return null;
        }
        TestSuiteStabilityExternalSequenceAnchor result = configured.getFirst();
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = result.descriptor();
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()) {
            throw new IllegalStateException(
                    "External serving-inventory non-equivocation anchor is unavailable");
        }
        return result;
    }
}
