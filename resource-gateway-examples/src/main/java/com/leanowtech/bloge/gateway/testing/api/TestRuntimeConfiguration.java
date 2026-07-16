package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.TestabilityAvailability;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseFixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseReplayPayloadRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSecurityEventRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestRuntimeResources;
import com.leanowtech.bloge.gateway.testing.runtime.CompiledTestRuntimeOptions;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestTerminalRecoveryRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.IndependentDurableTestEngineFactory;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.durable.codec.JacksonCheckpointCodec;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
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
            ObjectMapper objectMapper) {
        return new DurableTestTerminalRecoveryService(
                checkpoints, authorizer, runtime, securityEvents, objectMapper);
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
            @Value("${gateway.testing.store.retention-days:30}") long retentionDays) {
        return new TestExecutionApiService(graphService, operatorRegistry, resourceRegistry,
                expressionEvaluator, objectMapper, fixtureRepository, runRepository, securityEvents,
                Duration.ofDays(Math.max(1, Math.min(3650, retentionDays))), replayPayloadService,
                evidenceIntegrity);
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
            ObjectMapper objectMapper,
            TestSecurityEventRepository securityEvents,
            @Value("${gateway.testing.store.retention-days:30}") long retentionDays) {
        return new TestSuiteExecutionService(suiteRegistry, executionService, suiteRunRepository,
                objectMapper, securityEvents,
                Duration.ofDays(Math.max(1, Math.min(3650, retentionDays))), leaseCoordinator,
                attestations);
    }

    /** Marker consumed by the unauthenticated capability probe. */
    @Bean
    TestabilityAvailability testabilityAvailability() {
        return new TestabilityAvailability(true);
    }
}
