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
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseCompiledScenarioRehearsalPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioArtifactRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseScenarioRehearsalLifecycleAuditRepository;
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
import com.leanowtech.bloge.gateway.integration.mirror.CompiledScenarioRehearsalPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioArtifactRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalCompiler;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioHandlingAssertionEvaluator;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalLifecycleAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRunRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTrustProvider;
import com.leanowtech.bloge.gateway.integration.MirrorRuntimeAvailability;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
