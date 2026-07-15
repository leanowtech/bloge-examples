package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.TestabilityAvailability;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseFixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseReplayPayloadRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSecurityEventRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

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

    /** Reuses the configured local or managed signer for independently verifiable test evidence. */
    @Bean
    TestEvidenceIntegrityService testEvidenceIntegrityService(ObjectMapper objectMapper,
                                                               ObjectProvider<VisualEvidenceSigner> evidenceSigner) {
        return new TestEvidenceIntegrityService(objectMapper,
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
            TestSuiteRunRepository suiteRunRepository, ObjectMapper objectMapper) {
        return new TestSuiteRunReconciliationService(suiteRunRepository, objectMapper);
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
            ObjectMapper objectMapper,
            TestSecurityEventRepository securityEvents,
            @Value("${gateway.testing.store.retention-days:30}") long retentionDays) {
        return new TestSuiteExecutionService(suiteRegistry, executionService, suiteRunRepository,
                objectMapper, securityEvents,
                Duration.ofDays(Math.max(1, Math.min(3650, retentionDays))), leaseCoordinator);
    }

    /** Marker consumed by the unauthenticated capability probe. */
    @Bean
    TestabilityAvailability testabilityAvailability() {
        return new TestabilityAvailability(true);
    }
}
