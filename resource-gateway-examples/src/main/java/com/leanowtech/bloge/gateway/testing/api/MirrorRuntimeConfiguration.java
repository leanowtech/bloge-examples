package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorFixtureScopeRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorOperationAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorDeploymentIsolationAttestationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorRunRequestRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseDomainFidelityRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseReadOnlyShadowAuthorityPublicationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseReadOnlyShadowAuthorityKeySetRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseReadOnlyShadowJobRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseReadOnlyShadowSourceBindingRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseReadOnlyShadowSourceResolutionAttestationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseCompiledScenarioRehearsalPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioArtifactRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalBatchEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalBatchLifecycleAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalBatchRetentionRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalBatchRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalRemediationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalLifecycleAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalRetentionRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalRunRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationPayloadReferenceVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseCapabilityObservationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusGovernancePolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusGovernanceService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusServingService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterGovernanceService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterValidationAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusTrajectoryGovernanceService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusTrajectoryRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityRetryPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusSourceVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusPayloadAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationReviewRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseCapabilityCorpusRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseCapabilityCorpusClusterRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseCapabilityCorpusTrajectoryRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseCapabilityObservationReviewRepository;
import com.leanowtech.bloge.gateway.integration.mirror.AgentBackedMirrorDeploymentIsolationRunTrustAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorFixtureScopeRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationFailureAuditService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationObservability;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityKeySetIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityPublicationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityTrustPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityPublicationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationAdmissionPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationBundleIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationRunTrustAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationTrustAgent;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunRequestRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityMeasurementSource;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeAuthorityVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeDomainFidelitySource;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeObservationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfileIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityService;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelitySourceAvailability;
import com.leanowtech.bloge.gateway.integration.mirror.CompiledScenarioRehearsalPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioArtifactRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalCompiler;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioHandlingAssertionEvaluator;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchEvidencePublisher;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationHealthPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationHealthTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationScheduler;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationSloMonitor;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationWorker;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchLifecycleAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchCompiler;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRetentionRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchScheduler;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchWorker;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchWorkbookService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchTransactionalAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRuntimeService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalDomainFidelitySource;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalLifecycleAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRetentionRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRunRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowComparisonIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseReadOnlyShadowExecutionGuard;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowComparisonEngine;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAccessAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetService;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetTrustPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityPublicationSource;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityTrustStore;
import com.leanowtech.bloge.gateway.integration.mirror.ManagedReadOnlyShadowAuthorityTrustStore;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowBaselineConnector;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowCandidateConnector;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowDataPlane;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowExecutionGuard;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowKillSwitchAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSamplingGrantAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.SignedReadOnlyShadowKillSwitchAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.SignedReadOnlyShadowSamplingGrantAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingService;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionAttestationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionAttestationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionAttestationService;
import com.leanowtech.bloge.gateway.integration.mirror.DetachedReadOnlyShadowBaselineConnector;
import com.leanowtech.bloge.gateway.integration.mirror.DetachedReadOnlyShadowCandidateConnector;
import com.leanowtech.bloge.gateway.integration.mirror.DetachedReadOnlyShadowSourceResolutionVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.HttpOnlineReadOnlyShadowBaselineAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.HttpOnlineReadOnlyShadowCandidateAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineConnector;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowCandidateAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowCandidateConnector;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowCandidateTransport;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowSourceResolutionVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineEvidenceAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineObservationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineTransport;
import com.leanowtech.bloge.gateway.integration.mirror.PayloadFreeEqualityReadOnlyShadowPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ComposedReadOnlyShadowAccessAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.GovernedReadOnlyShadowDataPlane;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowDomainFidelitySource;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobScheduler;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobService;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobWorker;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTrustProvider;
import com.leanowtech.bloge.gateway.integration.MirrorRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.DomainFidelityRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.OnlineReadOnlyShadowBaselineRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.OnlineReadOnlyShadowDataPlaneRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.ReadOnlyShadowRuntimeAvailability;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunService;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * Physically isolated composition root for stateless mirror planning and execution.
 *
 * <p>The root requires both an explicit runtime switch and a non-production test or staging
 * profile. The negative production profile is intentional: activating {@code production}
 * alongside {@code test} still excludes every mirror bean. Production applications therefore
 * cannot acquire mirror execution capability by setting one property or adding a permissive
 * profile.</p>
 *
 * <p>This configuration assembles the internal Stage 1 kernel and append-only payload-free plan
 * and evidence stores. The availability marker is emitted only after protected plan, execution,
 * evidence, durable request-fencing, and atomic commit services have all been assembled.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({
        ScenarioRehearsalBatchSchedulerProperties.class,
        ScenarioRehearsalBatchFinalizationSchedulerProperties.class,
        ScenarioRehearsalBatchFinalizationSloProperties.class,
        ReadOnlyShadowJobSchedulerProperties.class,
        OnlineReadOnlyShadowBaselineProperties.class,
        OnlineReadOnlyShadowCandidateProperties.class
})
public class MirrorRuntimeConfiguration {

    /**
     * Creates the pure compiler over the exact runtime operator inventory.
     *
     * @param operatorRegistry runtime operator inventory frozen by each compilation
     * @param objectMapper canonical protocol mapper
     * @return mirror plan compiler available only in the isolated composition
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorPlanCompiler mirrorPlanCompiler(OperatorRegistry operatorRegistry,
                                                 ObjectMapper objectMapper) {
        return new MirrorPlanCompiler(operatorRegistry, objectMapper);
    }

    /**
     * Creates the short-lived independent mirror executor with mandatory external-site controls.
     *
     * <p>The resource adapter reconstructs descriptor semantics over fixture-backed transport.
     * Compilation and runtime closure checks require every external invocation to be replaced, so
     * the independent engine never invokes production transport and receives no production
     * credentials, interceptors, or request context carriers.</p>
     *
     * @param operatorRegistry runtime operator inventory used by the independent engine
     * @param objectMapper canonical protocol mapper
     * @param resourceRegistry descriptor inventory used only for fixture protocol reconstruction
     * @param expressionEvaluator descriptor expression evaluator
     * @param evidenceIntegrity governed signer/verifier boundary; unavailable signers fail closed
     * @return isolated mirror runtime service
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorRunService mirrorRunService(OperatorRegistry operatorRegistry,
                                             ObjectMapper objectMapper,
                                             ResourceRegistry resourceRegistry,
                                             BlgeExpressionEvaluator expressionEvaluator,
                                             MirrorEvidenceIntegrityService evidenceIntegrity,
                                             MirrorDeploymentIsolationRunTrustAuthority
                                                     deploymentTrust) {
        ResourceFixtureRuntime resourceRuntime = new ResourceFixtureRuntime(
                resourceRegistry, expressionEvaluator, objectMapper);
        return new MirrorRunService(operatorRegistry, objectMapper, resourceRuntime,
                Clock.systemUTC(), evidenceIntegrity, deploymentTrust);
    }

    /**
     * Creates the runtime trust bridge when a deployment agent is assembled.
     *
     * @param agents optional deployment-owned trust agent
     * @return agent-backed authority or an explicit fail-closed placeholder
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorDeploymentIsolationRunTrustAuthority
    mirrorDeploymentIsolationRunTrustAuthority(
            ObjectProvider<MirrorDeploymentIsolationTrustAgent> agents) {
        MirrorDeploymentIsolationTrustAgent agent = agents.getIfAvailable();
        return agent == null ? MirrorDeploymentIsolationRunTrustAuthority.unavailable()
                : new AgentBackedMirrorDeploymentIsolationRunTrustAuthority(
                agent, Clock.systemUTC());
    }

    /**
     * Creates the one signing and verification boundary shared by execution and durable evidence.
     *
     * @param objectMapper canonical protocol mapper
     * @param evidenceSigner governed evidence signer and verification key ring
     * @return mirror evidence integrity service
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorEvidenceIntegrityService mirrorEvidenceIntegrityService(
            ObjectMapper objectMapper, VisualEvidenceSigner evidenceSigner) {
        return new MirrorEvidenceIntegrityService(
                objectMapper, evidenceSigner, Clock.systemUTC());
    }

    /**
     * Creates the append-only sealed mirror-plan store.
     *
     * @param jdbc application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @return scope-isolated durable plan repository
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorPlanRepository mirrorPlanRepository(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new DatabaseMirrorPlanRepository(jdbc, objectMapper);
    }

    /**
     * Creates the signed portable Session-checkpoint verifier shared by stateful runtime and
     * ScenarioPack registration.
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionCheckpointIntegrityService
    mirrorSessionCheckpointIntegrityService(
            ObjectMapper objectMapper, VisualEvidenceSigner evidenceSigner) {
        return new MirrorSessionCheckpointIntegrityService(
                objectMapper, evidenceSigner, Clock.systemUTC());
    }

    /** Creates the append-only full-scope ScenarioPack artifact registry. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioArtifactRepository scenarioArtifactRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            MirrorSessionCheckpointIntegrityService checkpointIntegrity) {
        return new DatabaseScenarioArtifactRepository(
                jdbc, objectMapper, checkpointIntegrity);
    }

    /** Creates the pure exact-closure ScenarioPack rehearsal compiler. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalCompiler scenarioRehearsalCompiler(
            ObjectMapper objectMapper,
            MirrorSessionCheckpointIntegrityService checkpointIntegrity) {
        return new ScenarioRehearsalCompiler(
                objectMapper, checkpointIntegrity);
    }

    /** Creates the deterministic evaluator over independently verified Mirror evidence. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioHandlingAssertionEvaluator
    scenarioHandlingAssertionEvaluator(ObjectMapper objectMapper) {
        return new ScenarioHandlingAssertionEvaluator(objectMapper);
    }

    /** Creates the domain-separated signed Scenario aggregate integrity boundary. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalEvidenceIntegrityService
    scenarioRehearsalEvidenceIntegrityService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner evidenceSigner) {
        return new ScenarioRehearsalEvidenceIntegrityService(
                objectMapper, evidenceSigner, Clock.systemUTC());
    }

    /** Creates the append-only independently verified Scenario evidence store. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalEvidenceRepository
    scenarioRehearsalEvidenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ScenarioRehearsalEvidenceIntegrityService integrity) {
        return new DatabaseScenarioRehearsalEvidenceRepository(
                jdbc, objectMapper, integrity);
    }

    /**
     * Creates the append-only payload-free Scenario lifecycle audit.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @return full-scope lifecycle audit repository
     */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalLifecycleAuditRepository
    scenarioRehearsalLifecycleAuditRepository(
            JdbcTemplate jdbc) {
        return new DatabaseScenarioRehearsalLifecycleAuditRepository(
                jdbc);
    }

    /**
     * Creates the signed multi-hold retention and deletion-proof control plane.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param evidenceSigner governed retention-event signer
     * @return full-scope Scenario aggregate retention repository
     */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalRetentionRepository
    scenarioRehearsalRetentionRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            VisualEvidenceSigner evidenceSigner) {
        return new DatabaseScenarioRehearsalRetentionRepository(
                jdbc, objectMapper, evidenceSigner);
    }

    /**
     * Creates the database-clock aggregate lease and case-progress coordinator.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param lifecycleAudit mandatory payload-free transition audit
     * @return full-scope durable Scenario rehearsal request repository
     */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalRunRepository
    scenarioRehearsalRunRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ScenarioRehearsalLifecycleAuditRepository lifecycleAudit) {
        return new DatabaseScenarioRehearsalRunRepository(
                jdbc, objectMapper, lifecycleAudit);
    }

    /** Installs the conservative server-owned durable Scenario batch policy. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchPolicy
    scenarioRehearsalBatchPolicy() {
        return ScenarioRehearsalBatchPolicy.defaults();
    }

    /** Installs the human-role, expiry, and clock policy for reviewed business remediation. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalRemediationPolicy
    scenarioRehearsalRemediationPolicy() {
        return ScenarioRehearsalRemediationPolicy.defaults();
    }

    /** Installs server-owned authorization, review-horizon, and projection policy. */
    @Bean
    @ConditionalOnMissingBean
    public DomainFidelityPolicy domainFidelityPolicy() {
        return DomainFidelityPolicy.defaults();
    }

    /** Creates the domain-separated managed Profile signing and verification boundary. */
    @Bean
    @ConditionalOnMissingBean
    public DomainFidelityProfileIntegrity
    domainFidelityProfileIntegrity(
            ObjectMapper objectMapper,
            VisualEvidenceSigner evidenceSigner) {
        return new DomainFidelityProfileIntegrity(
                objectMapper, evidenceSigner);
    }

    /** Creates the full-scope append-only inventory and signed-profile repository. */
    @Bean
    @ConditionalOnMissingBean
    public DomainFidelityRepository domainFidelityRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            DomainFidelityProfileIntegrity integrity) {
        return new DatabaseDomainFidelityRepository(
                jdbc, objectMapper, integrity);
    }

    /** Installs the bounded lease, retry, and quarantine policy for evidence finalization. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchFinalizationPolicy
    scenarioRehearsalBatchFinalizationPolicy() {
        return ScenarioRehearsalBatchFinalizationPolicy.defaults();
    }

    /** Installs bounded, deployment-configurable finalization health thresholds. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchFinalizationHealthPolicy
    scenarioRehearsalBatchFinalizationHealthPolicy(
            ScenarioRehearsalBatchFinalizationSloProperties
                    properties) {
        return properties.policy();
    }

    /** Creates the domain-separated signed Scenario batch integrity boundary. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchEvidenceIntegrityService
    scenarioRehearsalBatchEvidenceIntegrityService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner evidenceSigner) {
        return new ScenarioRehearsalBatchEvidenceIntegrityService(
                objectMapper, evidenceSigner, Clock.systemUTC());
    }

    /** Creates the append-only independently verified Scenario batch evidence store. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchEvidenceRepository
    scenarioRehearsalBatchEvidenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ScenarioRehearsalBatchEvidenceIntegrityService
                    integrity) {
        return new DatabaseScenarioRehearsalBatchEvidenceRepository(
                jdbc, objectMapper, integrity);
    }

    /**
     * Creates the signed multi-hold retention and logical-deletion control plane for batches.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param evidenceSigner governed retention-event signer
     * @param evidence independently verifying terminal batch evidence
     * @return full-scope Scenario batch retention repository
     */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchRetentionRepository
    scenarioRehearsalBatchRetentionRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            VisualEvidenceSigner evidenceSigner,
            ScenarioRehearsalBatchEvidenceRepository evidence) {
        return new DatabaseScenarioRehearsalBatchRetentionRepository(
                jdbc, objectMapper, evidenceSigner, evidence);
    }

    /** Creates the fail-closed atomic terminal batch evidence publisher. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchEvidencePublisher
    scenarioRehearsalBatchEvidencePublisher(
            ScenarioRehearsalEvidenceRepository childEvidence,
            ScenarioRehearsalBatchEvidenceIntegrityService
                    integrity,
            ScenarioRehearsalBatchEvidenceRepository batches,
            ScenarioRehearsalBatchRetentionRepository retention) {
        return new ScenarioRehearsalBatchEvidencePublisher(
                childEvidence, integrity, batches, retention);
    }

    /** Creates the append-only payload-free Scenario batch lifecycle audit. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchLifecycleAuditRepository
    scenarioRehearsalBatchLifecycleAuditRepository(
            JdbcTemplate jdbc) {
        return new DatabaseScenarioRehearsalBatchLifecycleAuditRepository(
                jdbc);
    }

    /** Creates the cross-replica database-authoritative Scenario batch queue. */
    @Bean
    @ConditionalOnMissingBean
    public DatabaseScenarioRehearsalBatchRepository
    scenarioRehearsalBatchRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            ScenarioRehearsalBatchEvidencePublisher
                    evidencePublisher,
            ScenarioRehearsalBatchLifecycleAuditRepository
                    lifecycleAudit,
            ScenarioRehearsalBatchFinalizationPolicy
                    finalizationPolicy) {
        return new DatabaseScenarioRehearsalBatchRepository(
                jdbc,
                objectMapper,
                transactionManager,
                evidencePublisher,
                lifecycleAudit,
                finalizationPolicy);
    }

    /**
     * Creates the append-only reviewed-remediation ledger and atomic successor coordinator.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(
            ScenarioRehearsalBatchTransactionalAdmission.class)
    public ScenarioRehearsalRemediationRepository
    scenarioRehearsalRemediationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            ScenarioRehearsalBatchTransactionalAdmission
                    batchAdmission) {
        return new DatabaseScenarioRehearsalRemediationRepository(
                jdbc,
                objectMapper,
                transactionManager,
                batchAdmission);
    }

    /** Creates the exact-plan resolver that freezes immutable batch manifests. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchCompiler
    scenarioRehearsalBatchCompiler(
            ScenarioRehearsalIntegrationService rehearsals,
            ObjectMapper objectMapper) {
        return new ScenarioRehearsalBatchCompiler(
                rehearsals, objectMapper);
    }

    /** Creates the protected batch submission, query, and cancellation boundary. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchService
    scenarioRehearsalBatchService(
            ScenarioRehearsalBatchCompiler compiler,
            ScenarioRehearsalBatchRepository repository,
            ScenarioRehearsalBatchPolicy policy,
            ObjectMapper objectMapper,
            ScenarioRehearsalBatchEvidenceRepository evidence,
            MirrorOperationObservability observations,
            ScenarioRehearsalBatchFinalizationHealthPolicy
                    finalizationHealthPolicy) {
        return new ScenarioRehearsalBatchService(
                compiler,
                repository,
                policy,
                objectMapper,
                evidence,
                observations,
                finalizationHealthPolicy);
    }

    /** Creates the signed-source ANEKE batch correctness-workbook projection boundary. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchWorkbookService
    scenarioRehearsalBatchWorkbookService(
            ScenarioRehearsalBatchEvidenceRepository evidence,
            ScenarioRehearsalBatchEvidenceIntegrityService integrity,
            ScenarioRehearsalBatchRetentionRepository retention,
            ScenarioRehearsalRuntimeService rehearsals,
            ObjectMapper objectMapper,
            MirrorOperationObservability observations,
            VisualEvidenceSigner evidenceSigner) {
        return new ScenarioRehearsalBatchWorkbookService(
                evidence,
                integrity,
                retention,
                rehearsals,
                objectMapper,
                observations,
                evidenceSigner);
    }

    /** Creates the protected preview, approval, read, and atomic successor service. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(
            ScenarioRehearsalRemediationRepository.class)
    public ScenarioRehearsalRemediationService
    scenarioRehearsalRemediationService(
            ScenarioRehearsalRemediationRepository repository,
            ScenarioRehearsalRemediationPolicy policy,
            ScenarioRehearsalBatchPolicy batchPolicy,
            ScenarioRehearsalBatchWorkbookService workbooks,
            ScenarioRehearsalBatchEvidenceRepository evidence,
            ScenarioRehearsalBatchEvidenceIntegrityService
                    evidenceIntegrity,
            ScenarioRehearsalBatchCompiler compiler,
            ObjectMapper objectMapper,
            MirrorOperationObservability observations) {
        return new ScenarioRehearsalRemediationService(
                repository,
                policy,
                batchPolicy,
                workbooks,
                evidence,
                evidenceIntegrity,
                compiler,
                objectMapper,
                observations);
    }

    /** Creates one evidence-verifying durable Scenario batch worker turn. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchWorker
    scenarioRehearsalBatchWorker(
            ScenarioRehearsalBatchRepository repository,
            ScenarioRehearsalRuntimeService runtime,
            ScenarioRehearsalEvidenceIntegrityService integrity,
            ScenarioRehearsalBatchPolicy policy,
            ObjectMapper objectMapper) {
        return new ScenarioRehearsalBatchWorker(
                repository,
                runtime,
                integrity,
                policy,
                objectMapper);
    }

    /**
     * Starts explicitly enabled bounded worker lanes for one regional non-production partition.
     *
     * @param worker evidence-verifying single-item worker
     * @param properties strict process-local scheduler policy
     * @return closeable autonomous scheduler
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = ScenarioRehearsalBatchSchedulerProperties.PREFIX,
            name = "enabled",
            havingValue = "true")
    public ScenarioRehearsalBatchScheduler
    scenarioRehearsalBatchScheduler(
            ScenarioRehearsalBatchWorker worker,
            ScenarioRehearsalBatchSchedulerProperties
                    properties) {
        return new ScenarioRehearsalBatchScheduler(
                worker,
                properties.region(),
                properties.environmentId(),
                properties.instanceId(),
                properties.maximumPollers(),
                properties.initialDelay(),
                properties.pollInterval(),
                properties.drainTimeout());
    }

    /** Creates one outbox-fenced batch evidence finalization turn. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchFinalizationWorker
    scenarioRehearsalBatchFinalizationWorker(
            ScenarioRehearsalBatchRepository repository,
            ScenarioRehearsalBatchEvidencePublisher publisher,
            ScenarioRehearsalBatchFinalizationPolicy policy) {
        return new ScenarioRehearsalBatchFinalizationWorker(
                repository, publisher, policy);
    }

    /** Registers fixed-cardinality finalization state, failure, age, and health gauges. */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRehearsalBatchFinalizationHealthTelemetry
    scenarioRehearsalBatchFinalizationHealthTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new ScenarioRehearsalBatchFinalizationHealthTelemetry(
                meterRegistry.getIfAvailable(
                        SimpleMeterRegistry::new));
    }

    /**
     * Monitors the exact partition owned by the enabled process-local finalization scheduler.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix =
                    ScenarioRehearsalBatchFinalizationSchedulerProperties
                            .PREFIX,
            name = "enabled",
            havingValue = "true")
    public ScenarioRehearsalBatchFinalizationSloMonitor
    scenarioRehearsalBatchFinalizationSloMonitor(
            ScenarioRehearsalBatchRepository repository,
            ScenarioRehearsalBatchFinalizationHealthTelemetry
                    telemetry,
            ScenarioRehearsalBatchFinalizationHealthPolicy policy,
            ScenarioRehearsalBatchFinalizationSchedulerProperties
                    properties) {
        return new ScenarioRehearsalBatchFinalizationSloMonitor(
                repository,
                telemetry,
                policy,
                properties.region(),
                properties.environmentId());
    }

    /**
     * Starts explicitly enabled KMS-isolated finalization lanes for one regional partition.
     *
     * @param worker one durable evidence-finalization turn
     * @param properties strict process-local KMS scheduler policy
     * @return closeable autonomous finalization scheduler
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix =
                    ScenarioRehearsalBatchFinalizationSchedulerProperties
                            .PREFIX,
            name = "enabled",
            havingValue = "true")
    public ScenarioRehearsalBatchFinalizationScheduler
    scenarioRehearsalBatchFinalizationScheduler(
            ScenarioRehearsalBatchFinalizationWorker worker,
            ScenarioRehearsalBatchFinalizationSchedulerProperties
                    properties) {
        return new ScenarioRehearsalBatchFinalizationScheduler(
                worker,
                properties.region(),
                properties.environmentId(),
                properties.instanceId(),
                properties.maximumPollers(),
                properties.initialDelay(),
                properties.pollInterval(),
                properties.drainTimeout());
    }

    /** Creates the append-only payload-free compiled rehearsal-plan registry. */
    @Bean
    @ConditionalOnMissingBean
    public CompiledScenarioRehearsalPlanRepository
    compiledScenarioRehearsalPlanRepository(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new DatabaseCompiledScenarioRehearsalPlanRepository(
                jdbc, objectMapper);
    }

    /**
     * Creates the canonical isolation-authority publication integrity boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @return content-addressing and threshold-signature verifier
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorDeploymentIsolationAuthorityKeySetIntegrity
    mirrorDeploymentIsolationAuthorityKeySetIntegrity(ObjectMapper objectMapper) {
        return new MirrorDeploymentIsolationAuthorityKeySetIntegrity(objectMapper);
    }

    /**
     * Installs a fail-closed placeholder until the deployment supplies governed local trust.
     *
     * @return unavailable provider that cannot accept caller-selected roots or policy
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorDeploymentIsolationAuthorityTrustPolicyProvider
    mirrorDeploymentIsolationAuthorityTrustPolicyProvider() {
        return MirrorDeploymentIsolationAuthorityTrustPolicyProvider.unavailable();
    }

    /**
     * Creates the append-only authority publication store and durable CAS floor.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param integrity publication content-addressing verifier
     * @param transactionManager transaction manager shared by Mirror persistence
     * @return full-scope trusted-distribution repository
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorDeploymentIsolationAuthorityPublicationRepository
    mirrorDeploymentIsolationAuthorityPublicationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity,
            PlatformTransactionManager transactionManager) {
        return new DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository(
                jdbc, objectMapper, integrity, transactionManager);
    }

    /**
     * Creates the external isolation-attestation signature and content-addressing verifier.
     *
     * @param objectMapper canonical protocol mapper
     * @return independent external attestation verifier
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorDeploymentIsolationAttestationIntegrity
    mirrorDeploymentIsolationAttestationIntegrity(ObjectMapper objectMapper) {
        return new MirrorDeploymentIsolationAttestationIntegrity(objectMapper);
    }

    /**
     * Creates the canonical local status and atomic-bundle integrity boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @param attestationIntegrity external attestation content-addressing verifier
     * @return local status and bundle content-addressing boundary
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorDeploymentIsolationAttestationBundleIntegrity
    mirrorDeploymentIsolationAttestationBundleIntegrity(
            ObjectMapper objectMapper,
            MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity) {
        return new MirrorDeploymentIsolationAttestationBundleIntegrity(
                objectMapper, attestationIntegrity);
    }

    /**
     * Installs a fail-closed placeholder until exact bootstrap revisions are governed locally.
     *
     * @return unavailable provider that cannot accept request-selected bootstrap floors
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorDeploymentIsolationAttestationAdmissionPolicyProvider
    mirrorDeploymentIsolationAttestationAdmissionPolicyProvider() {
        return MirrorDeploymentIsolationAttestationAdmissionPolicyProvider.unavailable();
    }

    /**
     * Creates the append-only attestation body/status store and durable current floor.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param attestationIntegrity external attestation content-addressing verifier
     * @param bundleIntegrity local status and bundle verifier
     * @param transactionManager transaction manager shared by Mirror persistence
     * @return full-scope attestation trust-control repository
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorDeploymentIsolationAttestationRepository
    mirrorDeploymentIsolationAttestationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity,
            MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity,
            PlatformTransactionManager transactionManager) {
        return new DatabaseMirrorDeploymentIsolationAttestationRepository(
                jdbc, objectMapper, attestationIntegrity, bundleIntegrity, transactionManager);
    }

    /**
     * Creates the signed capability-observation integrity boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @return producer signature and content-addressing verifier
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityObservationIntegrity capabilityObservationIntegrity(
            ObjectMapper objectMapper) {
        return new CapabilityObservationIntegrity(objectMapper);
    }

    /**
     * Creates the local admission-decision content-addressing boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @return immutable admission decision integrity boundary
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityObservationAdmissionIntegrity
            capabilityObservationAdmissionIntegrity(ObjectMapper objectMapper) {
        return new CapabilityObservationAdmissionIntegrity(objectMapper);
    }

    /**
     * Installs a fail-closed placeholder until governed corpus policy is supplied.
     *
     * @return unavailable operator-owned observation policy provider
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityObservationAdmissionPolicyProvider
            capabilityObservationAdmissionPolicyProvider() {
        return CapabilityObservationAdmissionPolicyProvider.unavailable();
    }

    /**
     * Installs a fail-closed placeholder until a sanitized payload vault is integrated.
     *
     * @return unavailable external payload-reference verifier
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityObservationPayloadReferenceVerifier
            capabilityObservationPayloadReferenceVerifier() {
        return CapabilityObservationPayloadReferenceVerifier.unavailable();
    }

    /**
     * Creates the append-only observation and terminal-decision store.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param observationIntegrity producer observation integrity boundary
     * @param admissionIntegrity local decision integrity boundary
     * @return full-scope payload-free observation repository
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityObservationRepository capabilityObservationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CapabilityObservationIntegrity observationIntegrity,
            CapabilityObservationAdmissionIntegrity admissionIntegrity) {
        return new DatabaseCapabilityObservationRepository(
                jdbc, objectMapper, observationIntegrity, admissionIntegrity);
    }

    /**
     * Creates the corpus command and artifact content-addressing boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @return corpus governance integrity boundary
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityCorpusIntegrity capabilityCorpusIntegrity(
            ObjectMapper objectMapper) {
        return new CapabilityCorpusIntegrity(objectMapper);
    }

    /**
     * Installs a fail-closed placeholder until operator-owned corpus policy is supplied.
     *
     * @return unavailable corpus governance policy provider
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityCorpusGovernancePolicyProvider
            capabilityCorpusGovernancePolicyProvider() {
        return CapabilityCorpusGovernancePolicyProvider.unavailable();
    }

    /**
     * Installs a fail-closed placeholder until operator-owned retry policy is supplied.
     *
     * @return unavailable retry policy authority
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityRetryPolicyProvider capabilityRetryPolicyProvider() {
        return CapabilityRetryPolicyProvider.unavailable();
    }

    /**
     * Installs a fail-closed placeholder until operator-owned cluster policy is supplied.
     *
     * @return unavailable cluster policy authority
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityCorpusClusterPolicyProvider
            capabilityCorpusClusterPolicyProvider() {
        return CapabilityCorpusClusterPolicyProvider.unavailable();
    }

    /**
     * Installs a fail-closed placeholder until a data-plane cluster validator is supplied.
     *
     * @return unavailable cluster validation authority
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityCorpusClusterValidationAuthority
            capabilityCorpusClusterValidationAuthority() {
        return CapabilityCorpusClusterValidationAuthority.unavailable();
    }

    /**
     * Installs a fail-closed placeholder until external source lifecycle checks are supplied.
     *
     * @return unavailable metadata-only corpus source verifier
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityCorpusSourceVerifier capabilityCorpusSourceVerifier() {
        return CapabilityCorpusSourceVerifier.unavailable();
    }

    /**
     * Installs a fail-closed placeholder until a regional sanitized-payload vault is supplied.
     *
     * @return unavailable short-lived corpus payload authority
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityCorpusPayloadAuthority capabilityCorpusPayloadAuthority() {
        return CapabilityCorpusPayloadAuthority.unavailable();
    }

    /**
     * Installs a fail-closed placeholder until a shared current-generation authority is supplied.
     *
     * @return unavailable authority that cannot mint or refresh a serving floor
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorServingGenerationAuthority mirrorServingGenerationAuthority() {
        return MirrorServingGenerationAuthority.unavailable();
    }

    /**
     * Installs a fail-closed placeholder until operator-owned authority keys are supplied.
     *
     * @return unavailable local trust policy
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorServingGenerationTrustProvider
            mirrorServingGenerationTrustProvider() {
        return MirrorServingGenerationTrustProvider.unavailable();
    }

    /**
     * Creates the canonical content-addressing and independent Ed25519 verification boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @return serving-generation integrity kernel
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorServingGenerationIntegrity mirrorServingGenerationIntegrity(
            ObjectMapper objectMapper) {
        return new MirrorServingGenerationIntegrity(objectMapper);
    }

    /**
     * Creates the corpus-generation admission service shared by materialization and runtime.
     *
     * @param authority shared current-floor authority
     * @param trust operator-owned pinned authority key policy
     * @param integrity independent token verifier
     * @param objectMapper canonical protocol mapper
     * @return fail-closed serving-generation binder
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorServingGenerationService mirrorServingGenerationService(
            MirrorServingGenerationAuthority authority,
            MirrorServingGenerationTrustProvider trust,
            MirrorServingGenerationIntegrity integrity,
            ObjectMapper objectMapper,
            MirrorServingGenerationTelemetry telemetry) {
        return new MirrorServingGenerationService(
                authority, trust, integrity, objectMapper,
                Clock.systemUTC(), telemetry);
    }

    /**
     * Registers bounded serving-generation admission and floor-check counters.
     *
     * @param registries optional deployment meter registry
     * @return fixed-cardinality telemetry adapter
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorServingGenerationTelemetry mirrorServingGenerationTelemetry(
            ObjectProvider<MeterRegistry> registries) {
        return new MirrorServingGenerationTelemetry(
                registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Creates the terminal quarantine-review store.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param integrity corpus governance integrity boundary
     * @return full-scope append-only observation review repository
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityObservationReviewRepository
            capabilityObservationReviewRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CapabilityCorpusIntegrity integrity) {
        return new DatabaseCapabilityObservationReviewRepository(
                jdbc, objectMapper, integrity);
    }

    /**
     * Creates independent corpus revision and serving-publication lineages.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param integrity corpus governance integrity boundary
     * @return full-scope append-only corpus repository
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityCorpusRepository capabilityCorpusRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CapabilityCorpusIntegrity integrity) {
        return new DatabaseCapabilityCorpusRepository(
                jdbc, objectMapper, integrity);
    }

    /**
     * Creates the independent owner-reviewed trajectory publication lineage.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param integrity corpus and trajectory integrity boundary
     * @return full-scope append-only trajectory repository
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityCorpusTrajectoryRepository
            capabilityCorpusTrajectoryRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CapabilityCorpusIntegrity integrity) {
        return new DatabaseCapabilityCorpusTrajectoryRepository(
                jdbc, objectMapper, integrity);
    }

    /**
     * Creates the independent owner-reviewed cluster publication lineage.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param integrity corpus and cluster integrity boundary
     * @return full-scope append-only cluster repository
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityCorpusClusterRepository
            capabilityCorpusClusterRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CapabilityCorpusIntegrity integrity) {
        return new DatabaseCapabilityCorpusClusterRepository(
                jdbc, objectMapper, integrity);
    }

    /**
     * Creates the append-only full-enterprise-scope fixture authorization index.
     *
     * @param jdbc application JDBC boundary
     * @return payload-free mirror fixture scope repository
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorFixtureScopeRepository mirrorFixtureScopeRepository(JdbcTemplate jdbc) {
        return new DatabaseMirrorFixtureScopeRepository(jdbc);
    }

    /**
     * Creates the append-only independently verified evidence store.
     *
     * @param jdbc application JDBC boundary
     * @param objectMapper canonical protocol mapper
     * @param evidenceIntegrity shared detached-signature integrity boundary
     * @return scope-isolated durable evidence repository
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorEvidenceRepository mirrorEvidenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            MirrorEvidenceIntegrityService evidenceIntegrity) {
        return new DatabaseMirrorEvidenceRepository(jdbc, objectMapper, evidenceIntegrity);
    }

    /**
     * Creates the payload-free durable idempotency and fencing coordinator.
     *
     * @param jdbc application JDBC boundary
     * @return full-scope mirror execution request repository
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorRunRequestRepository mirrorRunRequestRepository(JdbcTemplate jdbc) {
        return new DatabaseMirrorRunRequestRepository(jdbc);
    }

    /**
     * Creates the append-only payload-free terminal operation audit.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @return exact-scope Mirror operation audit repository
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorOperationAuditRepository mirrorOperationAuditRepository(JdbcTemplate jdbc) {
        return new DatabaseMirrorOperationAuditRepository(jdbc);
    }

    /**
     * Creates the independent transaction boundary that preserves failure audits across rollback.
     *
     * @param audit durable payload-free operation audit
     * @param transactionManager transaction manager shared by Mirror persistence
     * @return isolated failure audit writer
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorOperationFailureAuditService mirrorOperationFailureAuditService(
            MirrorOperationAuditRepository audit,
            PlatformTransactionManager transactionManager) {
        return new MirrorOperationFailureAuditService(audit, transactionManager);
    }

    /**
     * Registers fixed-cardinality operation counters and latency timers.
     *
     * @param meterRegistry deployment meter registry when Actuator is installed
     * @return metric adapter that never labels tenant or resource identities
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorOperationTelemetry mirrorOperationTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new MirrorOperationTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Creates the mandatory audit-before-publish operation observer.
     *
     * @param audit durable payload-free operation audit
     * @param failureAudit independent failure-audit transaction boundary
     * @param telemetry fixed-cardinality metric adapter
     * @return observer injected into protected plan and run services
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorOperationObservability mirrorOperationObservability(
            MirrorOperationAuditRepository audit,
            MirrorOperationFailureAuditService failureAudit,
            MirrorOperationTelemetry telemetry) {
        return new MirrorOperationObservability(audit, failureAudit, telemetry);
    }

    /**
     * Creates the governed inventory and internal verified-source projection application boundary.
     */
    @Bean
    @ConditionalOnMissingBean
    public DomainFidelityService domainFidelityService(
            DomainFidelityRepository repository,
            DomainFidelityPolicy policy,
            DomainFidelityProfileIntegrity integrity,
            ObjectMapper objectMapper,
            MirrorOperationObservability observability) {
        return new DomainFidelityService(
                repository,
                policy,
                integrity,
                objectMapper,
                observability);
    }

    /** Creates the independently verified Scenario-workbook Fidelity source adapter. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ScenarioRehearsalRuntimeService.class)
    public ScenarioRehearsalDomainFidelitySource
    scenarioRehearsalDomainFidelitySource(
            ScenarioRehearsalRuntimeService rehearsals,
            ScenarioRehearsalEvidenceIntegrityService evidenceIntegrity,
            VisualEvidenceSigner signer,
            DomainFidelityPolicy policy,
            ObjectMapper objectMapper) {
        return new ScenarioRehearsalDomainFidelitySource(
                rehearsals,
                evidenceIntegrity,
                signer,
                policy,
                objectMapper);
    }

    /** Creates the signed read-only Shadow comparison integrity boundary. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowComparisonIntegrity
    readOnlyShadowComparisonIntegrity(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        return new ReadOnlyShadowComparisonIntegrity(
                objectMapper,
                signer);
    }

    /** Creates the detached source-binding content-addressing and signing boundary. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowSourceBindingIntegrity
    readOnlyShadowSourceBindingIntegrity(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        return new ReadOnlyShadowSourceBindingIntegrity(
                objectMapper,
                signer);
    }

    /** Creates the append-only exact-revision detached source-binding store. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowSourceBindingRepository
    readOnlyShadowSourceBindingRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ReadOnlyShadowSourceBindingIntegrity integrity) {
        return new DatabaseReadOnlyShadowSourceBindingRepository(
                jdbc,
                objectMapper,
                integrity);
    }

    /** Creates the candidate-closing detached source-binding admission service. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowSourceBindingService
    readOnlyShadowSourceBindingService(
            ReadOnlyShadowSourceBindingRepository bindings,
            MirrorEvidenceRepository evidence,
            ReadOnlyShadowSourceBindingIntegrity integrity) {
        return new ReadOnlyShadowSourceBindingService(
                bindings,
                evidence,
                integrity,
                Clock.systemUTC());
    }

    /** Creates the source-resolution content-addressing and signing boundary. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowSourceResolutionAttestationIntegrity
    readOnlyShadowSourceResolutionAttestationIntegrity(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        return new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                objectMapper,
                signer,
                Clock.systemUTC());
    }

    /** Creates the append-only exact-revision source-resolution attestation store. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowSourceResolutionAttestationRepository
    readOnlyShadowSourceResolutionAttestationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ReadOnlyShadowSourceResolutionAttestationIntegrity
                    integrity) {
        return new DatabaseReadOnlyShadowSourceResolutionAttestationRepository(
                jdbc,
                objectMapper,
                integrity);
    }

    /** Creates the exact source-resolution governance evidence read boundary. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowSourceResolutionAttestationService
    readOnlyShadowSourceResolutionAttestationService(
            ReadOnlyShadowSourceResolutionAttestationRepository
                    attestations) {
        return new ReadOnlyShadowSourceResolutionAttestationService(
                attestations);
    }

    /** Creates the database-authoritative Shadow queue and signed comparison store. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowJobRepository
    readOnlyShadowJobRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ReadOnlyShadowComparisonIntegrity integrity,
            PlatformTransactionManager transactionManager) {
        return new DatabaseReadOnlyShadowJobRepository(
                jdbc,
                objectMapper,
                integrity,
                transactionManager);
    }

    /** Freezes conservative server-owned retry, lease, and deadline bounds. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowJobPolicy
    readOnlyShadowJobPolicy() {
        return ReadOnlyShadowJobPolicy.DEFAULT;
    }

    /** Creates the audited exact-scope Shadow submission and read application boundary. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowJobService
    readOnlyShadowJobService(
            ReadOnlyShadowJobRepository repository,
            ReadOnlyShadowJobPolicy policy,
            MirrorOperationObservability observability) {
        return new ReadOnlyShadowJobService(
                repository,
                policy,
                observability);
    }

    /** Creates canonical verification for signed sampling, switch, and guard-policy publications. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowAuthorityIntegrity
    readOnlyShadowAuthorityIntegrity(
            ObjectMapper objectMapper) {
        return new ReadOnlyShadowAuthorityIntegrity(
                objectMapper);
    }

    /** Creates canonical root-signature verification for managed Shadow authority key sets. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowAuthorityKeySetIntegrity
    readOnlyShadowAuthorityKeySetIntegrity(
            ObjectMapper objectMapper) {
        return new ReadOnlyShadowAuthorityKeySetIntegrity(
                objectMapper);
    }

    /** Provides fail-closed bootstrap trust until a security control plane is connected. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowAuthorityKeySetTrustPolicyProvider
    readOnlyShadowAuthorityKeySetTrustPolicyProvider() {
        return ReadOnlyShadowAuthorityKeySetTrustPolicyProvider
                .unavailable();
    }

    /** Creates the append-only managed authority key-set log and durable revocation cursor. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowAuthorityKeySetRepository
    readOnlyShadowAuthorityKeySetRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ReadOnlyShadowAuthorityKeySetIntegrity integrity,
            PlatformTransactionManager transactionManager) {
        return new DatabaseReadOnlyShadowAuthorityKeySetRepository(
                jdbc,
                objectMapper,
                integrity,
                transactionManager);
    }

    /** Creates the root-trust admission boundary for authority key-set successors. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowAuthorityKeySetService
    readOnlyShadowAuthorityKeySetService(
            ReadOnlyShadowAuthorityKeySetRepository repository,
            ReadOnlyShadowAuthorityKeySetTrustPolicyProvider trustPolicies,
            ReadOnlyShadowAuthorityKeySetIntegrity integrity) {
        return new ReadOnlyShadowAuthorityKeySetService(
                repository,
                trustPolicies,
                integrity,
                Clock.systemUTC());
    }

    /** Resolves every authority signature from the root-verified database-current key-set head. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowAuthorityTrustStore
    readOnlyShadowAuthorityTrustStore(
            ReadOnlyShadowAuthorityKeySetRepository repository,
            ReadOnlyShadowAuthorityKeySetTrustPolicyProvider trustPolicies,
            ReadOnlyShadowAuthorityKeySetIntegrity integrity) {
        return new ManagedReadOnlyShadowAuthorityTrustStore(
                repository,
                trustPolicies,
                integrity,
                Clock.systemUTC());
    }

    /** Creates the database-authoritative signed Shadow publication log and current-head source. */
    @Bean
    @ConditionalOnMissingBean(
            ReadOnlyShadowAuthorityPublicationSource.class)
    public DatabaseReadOnlyShadowAuthorityPublicationRepository
    readOnlyShadowAuthorityPublicationSource(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ReadOnlyShadowAuthorityIntegrity integrity,
            ReadOnlyShadowAuthorityTrustStore trustStore,
            PlatformTransactionManager transactionManager) {
        return new DatabaseReadOnlyShadowAuthorityPublicationRepository(
                jdbc,
                objectMapper,
                integrity,
                trustStore,
                Clock.systemUTC(),
                transactionManager);
    }

    /** Verifies the current signed grant and guard-policy heads on every authority observation. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowSamplingGrantAuthority
    readOnlyShadowSamplingGrantAuthority(
            ReadOnlyShadowAuthorityPublicationSource source,
            ReadOnlyShadowAuthorityTrustStore trustStore,
            ReadOnlyShadowAuthorityIntegrity integrity) {
        return new SignedReadOnlyShadowSamplingGrantAuthority(
                source,
                trustStore,
                integrity,
                Clock.systemUTC());
    }

    /** Verifies the current signed kill-switch head on every authority observation. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowKillSwitchAuthority
    readOnlyShadowKillSwitchAuthority(
            ReadOnlyShadowAuthorityPublicationSource source,
            ReadOnlyShadowAuthorityTrustStore trustStore,
            ReadOnlyShadowAuthorityIntegrity integrity) {
        return new SignedReadOnlyShadowKillSwitchAuthority(
                source,
                trustStore,
                integrity,
                Clock.systemUTC());
    }

    /** Joins sampling, kill-switch, and deployment egress decisions around each paired run. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowAccessAuthority
    readOnlyShadowAccessAuthority(
            ReadOnlyShadowSamplingGrantAuthority sampling,
            ReadOnlyShadowKillSwitchAuthority killSwitch,
            MirrorDeploymentIsolationRunTrustAuthority egress) {
        return new ComposedReadOnlyShadowAccessAuthority(
                sampling,
                killSwitch,
                egress,
                Clock.systemUTC());
    }

    /** Provides a database-authoritative cross-replica budget, lease, and circuit guard. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowExecutionGuard
    readOnlyShadowExecutionGuard(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        return new DatabaseReadOnlyShadowExecutionGuard(
                jdbc,
                mapper,
                transactionManager);
    }

    /** Creates the immutable built-in payload-free equality policy. */
    @Bean
    @ConditionalOnMissingBean
    public PayloadFreeEqualityReadOnlyShadowPolicy
    payloadFreeEqualityReadOnlyShadowPolicy(
            ObjectMapper objectMapper) {
        return new PayloadFreeEqualityReadOnlyShadowPolicy(
                objectMapper);
    }

    @Bean
    ReadOnlyShadowDataPlaneModeSelection
    readOnlyShadowDataPlaneModeSelection(
            OnlineReadOnlyShadowBaselineProperties online,
            OnlineReadOnlyShadowCandidateProperties candidate,
            Environment environment) {
        boolean detached = environment.getProperty(
                "gateway.testing.mirror.read-only-shadow.detached-data-plane.enabled",
                Boolean.class,
                false);
        if (candidate.enabled()
                && !online.enabled()) {
            throw new IllegalArgumentException(
                    "online candidate requires online baseline mode");
        }
        return new ReadOnlyShadowDataPlaneModeSelection(
                online.enabled(),
                detached);
    }

    /**
     * Creates the strict regional sidecar HTTP authority only when every trust role is supplied.
     *
     * @param objectMapper strict protocol mapper
     * @param transport dedicated private-PKI sidecar transport
     * @param properties validated endpoint and resource policy
     * @param requestHeaders fresh per-request workload authorization
     * @return payload-free online baseline authority
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            OnlineReadOnlyShadowBaselineTransport.class,
            HttpOnlineReadOnlyShadowBaselineAuthority
                    .RequestHeadersProvider.class
    })
    @ConditionalOnProperty(
            prefix = OnlineReadOnlyShadowBaselineProperties
                    .PREFIX,
            name = "enabled",
            havingValue = "true")
    public OnlineReadOnlyShadowBaselineAuthority
    onlineReadOnlyShadowBaselineAuthority(
            ObjectMapper objectMapper,
            OnlineReadOnlyShadowBaselineTransport transport,
            OnlineReadOnlyShadowBaselineProperties properties,
            HttpOnlineReadOnlyShadowBaselineAuthority
                    .RequestHeadersProvider requestHeaders) {
        return new HttpOnlineReadOnlyShadowBaselineAuthority(
                objectMapper,
                Clock.systemUTC(),
                transport,
                properties.settings(),
                requestHeaders);
    }

    /**
     * Creates the strict isolated-candidate HTTP authority when its trust role is supplied.
     *
     * @param objectMapper strict protocol mapper
     * @param transport dedicated private-PKI candidate transport
     * @param properties validated endpoint and resource policy
     * @param requestHeaders fresh per-request workload authorization
     * @return payload-free online candidate authority
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            OnlineReadOnlyShadowCandidateTransport.class,
            HttpOnlineReadOnlyShadowCandidateAuthority
                    .RequestHeadersProvider.class
    })
    @ConditionalOnProperty(
            prefix = OnlineReadOnlyShadowCandidateProperties
                    .PREFIX,
            name = "enabled",
            havingValue = "true")
    public OnlineReadOnlyShadowCandidateAuthority
    onlineReadOnlyShadowCandidateAuthority(
            ObjectMapper objectMapper,
            OnlineReadOnlyShadowCandidateTransport transport,
            OnlineReadOnlyShadowCandidateProperties properties,
            HttpOnlineReadOnlyShadowCandidateAuthority
                    .RequestHeadersProvider requestHeaders) {
        return new HttpOnlineReadOnlyShadowCandidateAuthority(
                objectMapper,
                Clock.systemUTC(),
                transport,
                properties.settings(),
                requestHeaders);
    }

    /**
     * Creates the role-separated content-address and signature verification boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @param authority independently governed regional observation authority
     * @return online baseline observation verifier
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(
            OnlineReadOnlyShadowBaselineEvidenceAuthority
                    .class)
    @ConditionalOnProperty(
            prefix = OnlineReadOnlyShadowBaselineProperties
                    .PREFIX,
            name = "enabled",
            havingValue = "true")
    public OnlineReadOnlyShadowBaselineObservationIntegrity
    onlineReadOnlyShadowBaselineObservationIntegrity(
            ObjectMapper objectMapper,
            OnlineReadOnlyShadowBaselineEvidenceAuthority
                    authority) {
        return new OnlineReadOnlyShadowBaselineObservationIntegrity(
                objectMapper,
                authority,
                Clock.systemUTC());
    }

    /**
     * Installs online production-read baseline acquisition behind its explicit switch.
     *
     * @param authority strict regional command and observation authority
     * @param integrity independently governed evidence verifier
     * @param objectMapper canonical protocol mapper
     * @return online payload-isolated baseline connector
     */
    @Bean
    @ConditionalOnBean({
            OnlineReadOnlyShadowBaselineAuthority.class,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    .class
    })
    @ConditionalOnProperty(
            prefix = OnlineReadOnlyShadowBaselineProperties
                    .PREFIX,
            name = "enabled",
            havingValue = "true")
    public OnlineReadOnlyShadowBaselineConnector
    onlineReadOnlyShadowBaselineConnector(
            OnlineReadOnlyShadowBaselineAuthority authority,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    integrity,
            ObjectMapper objectMapper) {
        return new OnlineReadOnlyShadowBaselineConnector(
                authority,
                integrity,
                objectMapper,
                Clock.systemUTC());
    }

    /**
     * Publishes baseline-only readiness without implying paired online data-plane readiness.
     *
     * @param connector physically assembled online baseline connector
     * @param authority regional sidecar safety-capability authority
     * @param integrity independent observation verification boundary
     * @return dynamically probed public capability marker
     */
    @Bean
    @ConditionalOnBean(
            OnlineReadOnlyShadowBaselineConnector.class)
    public OnlineReadOnlyShadowBaselineRuntimeAvailability
    onlineReadOnlyShadowBaselineRuntimeAvailability(
            OnlineReadOnlyShadowBaselineConnector connector,
            OnlineReadOnlyShadowBaselineAuthority authority,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    integrity) {
        return new OnlineReadOnlyShadowBaselineRuntimeAvailability(
                connector != null,
                authority::ready,
                integrity::available);
    }

    /**
     * Installs same-input sealed candidate execution when its isolated authority is supplied.
     *
     * @param baselineAuthority exact online baseline artifact resolver
     * @param baselineIntegrity independently governed baseline evidence verifier
     * @param candidateAuthority isolated candidate runtime and evidence authority
     * @param evidenceIntegrity independently governed Mirror evidence verifier
     * @param policy exact payload-free normalization policy
     * @param objectMapper canonical protocol mapper
     * @return online candidate connector bound to the verified baseline vault receipt
     */
    @Bean
    @ConditionalOnBean({
            OnlineReadOnlyShadowBaselineAuthority.class,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    .class,
            OnlineReadOnlyShadowCandidateAuthority.class,
            MirrorEvidenceIntegrityService.class
    })
    @ConditionalOnProperty(
            prefix = OnlineReadOnlyShadowBaselineProperties
                    .PREFIX,
            name = "enabled",
            havingValue = "true")
    public OnlineReadOnlyShadowCandidateConnector
    onlineReadOnlyShadowCandidateConnector(
            OnlineReadOnlyShadowBaselineAuthority
                    baselineAuthority,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    baselineIntegrity,
            OnlineReadOnlyShadowCandidateAuthority
                    candidateAuthority,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            PayloadFreeEqualityReadOnlyShadowPolicy policy,
            ObjectMapper objectMapper) {
        return new OnlineReadOnlyShadowCandidateConnector(
                baselineAuthority,
                baselineIntegrity,
                candidateAuthority,
                evidenceIntegrity,
                policy,
                objectMapper,
                Clock.systemUTC());
    }

    /**
     * Installs independent online pair re-resolution when both exact-read authorities exist.
     *
     * @param baselineAuthority exact regional baseline artifact resolver
     * @param baselineIntegrity independent baseline observation verifier
     * @param candidateAuthority exact candidate evidence resolver
     * @param evidenceIntegrity independent Mirror evidence verifier
     * @param policy exact payload-free normalization policy
     * @param attestations append-only source-resolution repository
     * @param attestationIntegrity source-resolution signing authority
     * @param objectMapper canonical protocol mapper
     * @return online paired-source verifier and v2 attestation producer
     */
    @Bean
    @ConditionalOnBean({
            OnlineReadOnlyShadowBaselineConnector.class,
            OnlineReadOnlyShadowCandidateConnector.class,
            OnlineReadOnlyShadowCandidateAuthority.class
    })
    @ConditionalOnProperty(
            prefix = OnlineReadOnlyShadowBaselineProperties
                    .PREFIX,
            name = "enabled",
            havingValue = "true")
    public OnlineReadOnlyShadowSourceResolutionVerifier
    onlineReadOnlyShadowSourceResolutionVerifier(
            OnlineReadOnlyShadowBaselineAuthority
                    baselineAuthority,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    baselineIntegrity,
            OnlineReadOnlyShadowCandidateAuthority
                    candidateAuthority,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            PayloadFreeEqualityReadOnlyShadowPolicy policy,
            ReadOnlyShadowSourceResolutionAttestationRepository
                    attestations,
            ReadOnlyShadowSourceResolutionAttestationIntegrity
                    attestationIntegrity,
            ObjectMapper objectMapper) {
        return new OnlineReadOnlyShadowSourceResolutionVerifier(
                baselineAuthority,
                baselineIntegrity,
                candidateAuthority,
                evidenceIntegrity,
                policy,
                attestations,
                attestationIntegrity,
                objectMapper,
                Clock.systemUTC());
    }

    /**
     * Publishes layered online candidate, resolver, and aggregate data-plane readiness.
     *
     * @param candidate physically assembled same-input candidate connector
     * @param candidateAuthority isolated candidate runtime authority
     * @param evidenceIntegrity independent Mirror evidence verification authority
     * @param resolver physically assembled paired-source resolver
     * @param dataPlane governed aggregate data plane
     * @return dynamically sampled public online data-plane marker
     */
    @Bean
    @ConditionalOnBean(
            OnlineReadOnlyShadowSourceResolutionVerifier
                    .class)
    public OnlineReadOnlyShadowDataPlaneRuntimeAvailability
    onlineReadOnlyShadowDataPlaneRuntimeAvailability(
            OnlineReadOnlyShadowCandidateConnector candidate,
            OnlineReadOnlyShadowCandidateAuthority
                    candidateAuthority,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            OnlineReadOnlyShadowSourceResolutionVerifier resolver,
            ReadOnlyShadowDataPlane dataPlane) {
        return new OnlineReadOnlyShadowDataPlaneRuntimeAvailability(
                candidate != null,
                resolver != null,
                candidateAuthority::ready,
                evidenceIntegrity::available,
                resolver::ready,
                dataPlane::ready);
    }

    /** Installs the exact signed-binding baseline connector only behind its explicit switch. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.mirror.read-only-shadow.detached-data-plane",
            name = "enabled",
            havingValue = "true")
    public ReadOnlyShadowBaselineConnector
    detachedReadOnlyShadowBaselineConnector(
            ReadOnlyShadowSourceBindingService bindings,
            PayloadFreeEqualityReadOnlyShadowPolicy policy) {
        return new DetachedReadOnlyShadowBaselineConnector(
                bindings,
                policy,
                Clock.systemUTC());
    }

    /** Installs the independently verified detached candidate connector behind its switch. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.mirror.read-only-shadow.detached-data-plane",
            name = "enabled",
            havingValue = "true")
    public ReadOnlyShadowCandidateConnector
    detachedReadOnlyShadowCandidateConnector(
            ReadOnlyShadowSourceBindingService bindings,
            MirrorEvidenceRepository evidence,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            PayloadFreeEqualityReadOnlyShadowPolicy policy) {
        return new DetachedReadOnlyShadowCandidateConnector(
                bindings,
                evidence,
                evidenceIntegrity,
                policy,
                Clock.systemUTC());
    }

    /** Installs independent paired-source re-resolution and attestation behind its switch. */
    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.mirror.read-only-shadow.detached-data-plane",
            name = "enabled",
            havingValue = "true")
    public ReadOnlyShadowSourceResolutionVerifier
    detachedReadOnlyShadowSourceResolutionVerifier(
            ReadOnlyShadowSourceBindingService bindings,
            MirrorEvidenceRepository evidence,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            PayloadFreeEqualityReadOnlyShadowPolicy policy,
            ReadOnlyShadowSourceResolutionAttestationRepository
                    attestations,
            ReadOnlyShadowSourceResolutionAttestationIntegrity
                    attestationIntegrity,
            ObjectMapper objectMapper) {
        return new DetachedReadOnlyShadowSourceResolutionVerifier(
                bindings,
                evidence,
                evidenceIntegrity,
                policy,
                attestations,
                attestationIntegrity,
                objectMapper,
                Clock.systemUTC());
    }

    /** Provides a fail-closed baseline connector until an isolated read adapter is installed. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowBaselineConnector
    readOnlyShadowBaselineConnector() {
        return ReadOnlyShadowBaselineConnector.unavailable();
    }

    /** Provides a fail-closed candidate connector until a sealed runtime adapter is installed. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowCandidateConnector
    readOnlyShadowCandidateConnector() {
        return ReadOnlyShadowCandidateConnector.unavailable();
    }

    /** Provides fail-closed source resolution until both artifact trust adapters are installed. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowSourceResolutionVerifier
    readOnlyShadowSourceResolutionVerifier() {
        return ReadOnlyShadowSourceResolutionVerifier
                .unavailable();
    }

    /** Provides fail-closed normalized comparison until an exact policy runtime is installed. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowComparisonEngine
    readOnlyShadowComparisonEngine() {
        return ReadOnlyShadowComparisonEngine.unavailable();
    }

    /**
     * Assembles the governed data plane while every missing deep dependency remains fail-closed.
     */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowDataPlane
    readOnlyShadowDataPlane(
            ReadOnlyShadowAccessAuthority authority,
            ReadOnlyShadowExecutionGuard guard,
            ReadOnlyShadowBaselineConnector baseline,
            ReadOnlyShadowCandidateConnector candidate,
            ReadOnlyShadowSourceResolutionVerifier
                    sourceVerifier,
            ReadOnlyShadowComparisonEngine comparisonEngine) {
        return new GovernedReadOnlyShadowDataPlane(
                authority,
                guard,
                baseline,
                candidate,
                sourceVerifier,
                comparisonEngine,
                Clock.systemUTC());
    }

    /** Creates the owner/epoch fenced one-step durable Shadow worker. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowJobWorker
    readOnlyShadowJobWorker(
            ReadOnlyShadowJobRepository repository,
            ReadOnlyShadowDataPlane dataPlane,
            ReadOnlyShadowComparisonIntegrity integrity,
            ReadOnlyShadowJobPolicy policy) {
        return new ReadOnlyShadowJobWorker(
                repository,
                dataPlane,
                integrity,
                policy);
    }

    /** Starts explicitly enabled bounded Shadow worker lanes for one regional partition. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = ReadOnlyShadowJobSchedulerProperties.PREFIX,
            name = "enabled",
            havingValue = "true")
    public ReadOnlyShadowJobScheduler
    readOnlyShadowJobScheduler(
            ReadOnlyShadowJobWorker worker,
            ReadOnlyShadowJobSchedulerProperties properties) {
        return new ReadOnlyShadowJobScheduler(
                worker,
                properties.region(),
                properties.environmentId(),
                properties.instanceId(),
                properties.maximumPollers(),
                properties.initialDelay(),
                properties.pollInterval(),
                properties.drainTimeout());
    }

    /** Publishes independent Shadow API, audit, worker, scheduler, and serving readiness. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowRuntimeAvailability
    readOnlyShadowRuntimeAvailability(
            ReadOnlyShadowJobService service,
            ReadOnlyShadowJobWorker worker,
            ReadOnlyShadowAuthorityKeySetTrustPolicyProvider
                    authorityTrustPolicies,
            ReadOnlyShadowSourceBindingService sourceBindings,
            ReadOnlyShadowDataPlane dataPlane,
            ObjectProvider<ReadOnlyShadowJobScheduler>
                    scheduler) {
        return new ReadOnlyShadowRuntimeAvailability(
                service != null,
                true,
                worker::ready,
                () -> {
                    ReadOnlyShadowJobScheduler current =
                            scheduler.getIfAvailable();
                    return current != null
                            && current.ready();
                },
                true,
                authorityTrustPolicies::available,
                true,
                sourceBindings::ready,
                true,
                dataPlane::ready);
    }

    /** Creates the independently verified read-only Shadow Fidelity source adapter. */
    @Bean
    @ConditionalOnMissingBean
    public ReadOnlyShadowDomainFidelitySource
    readOnlyShadowDomainFidelitySource(
            ReadOnlyShadowComparisonIntegrity integrity,
            DomainFidelityPolicy policy) {
        return new ReadOnlyShadowDomainFidelitySource(
                integrity,
                policy);
    }

    /**
     * Creates the outcome observation integrity boundary only when the host supplies an independent
     * business-authority verifier.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AuthoritativeOutcomeAuthorityVerifier.class)
    public AuthoritativeOutcomeObservationIntegrity
    authoritativeOutcomeObservationIntegrity(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            AuthoritativeOutcomeAuthorityVerifier
                    authorityVerifier) {
        return new AuthoritativeOutcomeObservationIntegrity(
                objectMapper,
                signer,
                authorityVerifier);
    }

    /** Creates the authoritative outcome Fidelity adapter only after its trust boundary exists. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AuthoritativeOutcomeObservationIntegrity.class)
    public AuthoritativeOutcomeDomainFidelitySource
    authoritativeOutcomeDomainFidelitySource(
            AuthoritativeOutcomeObservationIntegrity integrity,
            DomainFidelityPolicy policy) {
        return new AuthoritativeOutcomeDomainFidelitySource(
                integrity,
                policy);
    }

    /** Composes typed, independently probed source-adapter readiness. */
    @Bean
    @ConditionalOnMissingBean
    public DomainFidelitySourceAvailability
    domainFidelitySourceAvailability(
            java.util.List<DomainFidelityMeasurementSource>
                    sources) {
        return new DomainFidelitySourceAvailability(sources);
    }

    /** Publishes separate route, signer, and typed source-adapter readiness. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DomainFidelityService.class)
    public DomainFidelityRuntimeAvailability
    domainFidelityRuntimeAvailability(
            DomainFidelityProfileIntegrity integrity,
            DomainFidelitySourceAvailability sources) {
        return new DomainFidelityRuntimeAvailability(
                true,
                true,
                integrity::available,
                sources);
    }

    /**
     * Publishes honest protected-API readiness to the integration capability probe.
     *
     * @param planService fully assembled authoritative plan application boundary
     * @param runService fully assembled durable execution and evidence application boundary
     * @param evidenceSigner governed signing authority required for terminal evidence
     * @param authorityService assembled isolation-authority publication boundary
     * @param trustPolicies dynamic authority trust-policy source
     * @param attestationService assembled isolation-attestation boundary
     * @param admissionPolicies dynamic attestation admission policy source
     * @param deploymentTrust deployment-agent authority for certification-required runs
     * @param observationService assembled observation-admission application boundary
     * @param observationPolicies dynamic observation admission policy source
     * @param payloadReferences dynamic sanitized payload-reference authority
     * @param corpusService assembled quarantine-review and corpus-publication boundary
     * @param corpusPolicies dynamic operator-owned corpus policy source
     * @param retryPolicies dynamic operator-owned retry policy source
     * @param corpusSources dynamic external source lifecycle authority
     * @param corpusServing online exact-publication materialization boundary
     * @return profile-owned mirror capability marker
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({MirrorPlanIntegrationService.class, MirrorRunIntegrationService.class,
            MirrorDeploymentIsolationAuthorityPublicationService.class,
            MirrorDeploymentIsolationAttestationService.class,
            CapabilityObservationAdmissionService.class,
            CapabilityCorpusGovernanceService.class,
            CapabilityCorpusTrajectoryGovernanceService.class,
            CapabilityCorpusClusterGovernanceService.class})
    public MirrorRuntimeAvailability mirrorRuntimeAvailability(
            MirrorPlanIntegrationService planService,
            MirrorRunIntegrationService runService,
            VisualEvidenceSigner evidenceSigner,
            MirrorDeploymentIsolationAuthorityPublicationService authorityService,
            MirrorDeploymentIsolationAuthorityTrustPolicyProvider trustPolicies,
            MirrorDeploymentIsolationAttestationService attestationService,
            MirrorDeploymentIsolationAttestationAdmissionPolicyProvider admissionPolicies,
            MirrorDeploymentIsolationRunTrustAuthority deploymentTrust,
            CapabilityObservationAdmissionService observationService,
            CapabilityObservationAdmissionPolicyProvider observationPolicies,
            CapabilityObservationPayloadReferenceVerifier payloadReferences,
            CapabilityCorpusGovernanceService corpusService,
            CapabilityCorpusGovernancePolicyProvider corpusPolicies,
            CapabilityRetryPolicyProvider retryPolicies,
            CapabilityCorpusClusterPolicyProvider clusterPolicies,
            CapabilityCorpusClusterValidationAuthority clusterValidations,
            CapabilityCorpusSourceVerifier corpusSources,
            CapabilityCorpusServingService corpusServing) {
        return new MirrorRuntimeAvailability(true, true, evidenceSigner::available,
                true, trustPolicies::available, true,
                () -> trustPolicies.available() && admissionPolicies.available(),
                deploymentTrust::available, true,
                () -> observationPolicies.available() && payloadReferences.available(),
                true,
                () -> corpusPolicies.available() && corpusSources.available(),
                true,
                () -> corpusPolicies.available()
                        && retryPolicies.available()
                        && corpusSources.available(),
                corpusServing::ready,
                corpusServing::trajectoryReady,
                true,
                () -> corpusPolicies.available()
                        && clusterPolicies.available()
                        && clusterValidations.available()
                        && corpusSources.available(),
                corpusServing::clusterReady);
    }

    /** Compatibility factory retained for focused readiness tests outside Spring composition. */
    public MirrorRuntimeAvailability mirrorRuntimeAvailability(
            MirrorPlanIntegrationService planService,
            MirrorRunIntegrationService runService,
            VisualEvidenceSigner evidenceSigner) {
        return new MirrorRuntimeAvailability(true, true, evidenceSigner::available);
    }
}

final class ReadOnlyShadowDataPlaneModeSelection {
    ReadOnlyShadowDataPlaneModeSelection(
            boolean onlineBaseline,
            boolean detachedDataPlane) {
        if (onlineBaseline && detachedDataPlane) {
            throw new IllegalArgumentException(
                    "online baseline and detached Shadow data planes are mutually exclusive");
        }
    }
}
