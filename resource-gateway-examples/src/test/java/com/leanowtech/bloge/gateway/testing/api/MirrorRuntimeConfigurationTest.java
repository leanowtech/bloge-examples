package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorFixtureScopeRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationFailureAuditService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationObservability;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunRequestRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CompiledScenarioRehearsalPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ComposedReadOnlyShadowAccessAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeAuthorityVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeDomainFidelitySource;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeObservationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfileIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityService;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseReadOnlyShadowExecutionGuard;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseReadOnlyShadowAuthorityPublicationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseReadOnlyShadowSourceResolutionAttestationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DetachedReadOnlyShadowBaselineConnector;
import com.leanowtech.bloge.gateway.integration.mirror.DetachedReadOnlyShadowCandidateConnector;
import com.leanowtech.bloge.gateway.integration.mirror.DetachedReadOnlyShadowSourceResolutionVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.HttpOnlineReadOnlyShadowBaselineAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.HttpOnlineReadOnlyShadowCandidateAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineConnector;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineEvidenceAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineTransport;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowCandidateAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowCandidateConnector;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowCandidateTransport;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowSourceResolutionVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.GovernedReadOnlyShadowDataPlane;
import com.leanowtech.bloge.gateway.integration.mirror.PayloadFreeEqualityReadOnlyShadowPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAccessAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityPublicationSource;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityTrustStore;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowBaselineConnector;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowCandidateConnector;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowComparisonIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowComparisonEngine;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowDataPlane;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowDomainFidelitySource;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowExecutionGuard;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobScheduler;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobService;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobWorker;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowKillSwitchAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSamplingGrantAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.SignedReadOnlyShadowKillSwitchAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.SignedReadOnlyShadowSamplingGrantAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionAttestationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionAttestationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionAttestationService;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioArtifactRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchCompiler;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationHealthPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationHealthTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationScheduler;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationSloMonitor;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationWorker;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRetentionRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchScheduler;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchTransactionalAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchWorker;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalCompiler;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityKeySetIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityPublicationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityTrustPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationAdmissionPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationBundleIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRuntimeService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationPayloadReferenceVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusGovernancePolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusTrajectoryRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusSourceVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityRetryPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationReviewRepository;
import com.leanowtech.bloge.gateway.integration.MirrorRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.DomainFidelityRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.OnlineReadOnlyShadowBaselineRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.OnlineReadOnlyShadowDataPlaneRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.ReadOnlyShadowRuntimeAvailability;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MirrorRuntimeConfigurationTest {

    @Test
    void disabledSwitchInstallsNoMirrorKernelInTestProfile() {
        try (var context = context(false, "test")) {
            assertMirrorKernelAbsent(context);
        }
    }

    @Test
    void explicitSwitchAssemblesMirrorKernelInTestAndStagingOnly() {
        try (var test = context(true, "test");
             var staging = context(true, "staging")) {
            assertMirrorKernelPresent(test);
            assertMirrorKernelPresent(staging);
        }
    }

    @Test
    void outcomeAdapterRequiresAnExplicitHealthyBusinessAuthority() {
        try (var absent = context(true, "test");
             var configured = context(
                     Map.of(
                             "gateway.testing.mirror.enabled",
                             true,
                             "test.outcome-authority",
                             true,
                             "test.available-signer",
                             true),
                     "test")) {
            assertThat(absent.getBeansOfType(
                    AuthoritativeOutcomeObservationIntegrity.class))
                    .isEmpty();
            assertThat(absent.getBeansOfType(
                    AuthoritativeOutcomeDomainFidelitySource.class))
                    .isEmpty();
            assertThat(absent.getBean(
                    DomainFidelityRuntimeAvailability.class)
                    .outcomeAdapterReady()).isFalse();

            assertThat(configured.getBeansOfType(
                    AuthoritativeOutcomeObservationIntegrity.class))
                    .hasSize(1);
            assertThat(configured.getBeansOfType(
                    AuthoritativeOutcomeDomainFidelitySource.class))
                    .hasSize(1);
            assertThat(configured.getBean(
                    DomainFidelityRuntimeAvailability.class)
                    .outcomeAdapterReady()).isTrue();
        }
    }

    @Test
    void detachedDataPlaneSwitchInstallsExactArtifactConnectorsButRemainsFailClosed() {
        try (var context = context(
                Map.of(
                        "gateway.testing.mirror.enabled", true,
                        "gateway.testing.mirror.read-only-shadow.detached-data-plane.enabled",
                        true),
                "test")) {
            assertThat(context.getBean(ReadOnlyShadowBaselineConnector.class))
                    .isInstanceOf(DetachedReadOnlyShadowBaselineConnector.class)
                    .satisfies(connector -> assertThat(connector.ready()).isFalse());
            assertThat(context.getBean(ReadOnlyShadowCandidateConnector.class))
                    .isInstanceOf(DetachedReadOnlyShadowCandidateConnector.class)
                    .satisfies(connector -> assertThat(connector.ready()).isFalse());
            assertThat(context.getBean(ReadOnlyShadowSourceResolutionVerifier.class))
                    .isInstanceOf(DetachedReadOnlyShadowSourceResolutionVerifier.class)
                    .satisfies(verifier -> assertThat(verifier.ready()).isFalse());
            assertThat(context.getBean(ReadOnlyShadowComparisonEngine.class))
                    .isInstanceOf(PayloadFreeEqualityReadOnlyShadowPolicy.class)
                    .satisfies(engine -> assertThat(engine.ready()).isTrue());
            assertThat(context.getBean(ReadOnlyShadowRuntimeAvailability.class))
                    .satisfies(availability -> {
                        assertThat(availability.sourceResolutionApi()).isTrue();
                        assertThat(availability.detachedDataPlaneReady()).isFalse();
                        assertThat(availability.servingReady()).isFalse();
                    });
        }
    }

    @Test
    void onlineBaselineSwitchInstallsOnlyTheRoleSeparatedBaselineConnector() {
        try (var context = context(
                Map.of(
                        "gateway.testing.mirror.enabled", true,
                        OnlineReadOnlyShadowBaselineProperties.PREFIX
                                + ".enabled", true,
                        OnlineReadOnlyShadowBaselineProperties.PREFIX
                                + ".base-uri",
                        "https://baseline.ap.example.test"),
                "staging")) {
            assertThat(context.getBean(
                    ReadOnlyShadowBaselineConnector.class))
                    .isInstanceOf(
                            OnlineReadOnlyShadowBaselineConnector
                                    .class)
                    .satisfies(connector ->
                            assertThat(connector.ready())
                                    .isFalse());
            assertThat(context.getBean(
                    OnlineReadOnlyShadowBaselineRuntimeAvailability
                            .class).snapshot())
                    .satisfies(snapshot -> {
                        assertThat(snapshot.connectorInstalled())
                                .isTrue();
                        assertThat(snapshot.authorityReady())
                                .isFalse();
                        assertThat(snapshot.evidenceVerificationReady())
                                .isTrue();
                        assertThat(snapshot.baselineReady())
                                .isFalse();
                    });
            assertThat(context.getBean(
                    ReadOnlyShadowCandidateConnector.class)
                    .ready()).isFalse();
            assertThat(context.getBean(
                    ReadOnlyShadowDataPlane.class)
                    .ready()).isFalse();
        }
    }

    @Test
    void onlineCandidateRequiresItsExplicitIsolatedRuntimeAuthority() {
        try (var context = context(
                Map.of(
                        "gateway.testing.mirror.enabled", true,
                        OnlineReadOnlyShadowBaselineProperties.PREFIX
                                + ".enabled", true,
                        OnlineReadOnlyShadowBaselineProperties.PREFIX
                                + ".base-uri",
                        "https://baseline.ap.example.test",
                        "test.online-candidate-authority",
                        true),
                "staging")) {
            assertThat(context.getBean(
                    ReadOnlyShadowCandidateConnector.class))
                    .isInstanceOf(
                            OnlineReadOnlyShadowCandidateConnector
                                    .class);
            assertThat(context.getBean(
                    ReadOnlyShadowSourceResolutionVerifier.class)
                    .getClass())
                    .isEqualTo(
                            OnlineReadOnlyShadowSourceResolutionVerifier
                                    .class);
            assertThat(context.getBean(
                    ReadOnlyShadowSourceResolutionVerifier.class)
                    .ready()).isFalse();
            assertThat(context.getBean(
                    OnlineReadOnlyShadowDataPlaneRuntimeAvailability
                            .class).snapshot())
                    .satisfies(snapshot -> {
                        assertThat(snapshot
                                .candidateConnectorInstalled())
                                .isTrue();
                        assertThat(snapshot
                                .pairedResolverInstalled())
                                .isTrue();
                        assertThat(snapshot
                                .candidateAuthorityReady())
                                .isFalse();
                        assertThat(snapshot.dataPlaneReady())
                                .isFalse();
                    });
            assertThat(context.getBean(
                    ReadOnlyShadowDataPlane.class)
                    .ready()).isFalse();
        }
    }

    @Test
    void onlineCandidateSwitchInstallsTheRoleSeparatedHttpAuthority() {
        try (var context = context(
                Map.of(
                        "gateway.testing.mirror.enabled", true,
                        OnlineReadOnlyShadowBaselineProperties.PREFIX
                                + ".enabled", true,
                        OnlineReadOnlyShadowBaselineProperties.PREFIX
                                + ".base-uri",
                        "https://baseline.ap.example.test",
                        OnlineReadOnlyShadowCandidateProperties.PREFIX
                                + ".enabled", true,
                        OnlineReadOnlyShadowCandidateProperties.PREFIX
                                + ".base-uri",
                        "https://candidate.ap.example.test"),
                "staging")) {
            assertThat(context.getBean(
                    OnlineReadOnlyShadowCandidateAuthority
                            .class))
                    .isInstanceOf(
                            HttpOnlineReadOnlyShadowCandidateAuthority
                                    .class);
            assertThat(context.getBean(
                    ReadOnlyShadowCandidateConnector.class))
                    .isInstanceOf(
                            OnlineReadOnlyShadowCandidateConnector
                                    .class);
            assertThat(context.getBean(
                    ReadOnlyShadowSourceResolutionVerifier.class))
                    .isInstanceOf(
                            OnlineReadOnlyShadowSourceResolutionVerifier
                                    .class);
            assertThat(context.getBean(
                    OnlineReadOnlyShadowDataPlaneRuntimeAvailability
                            .class)
                    .snapshot().candidateAuthorityReady())
                    .isFalse();
        }
    }

    @Test
    void onlineCandidateCannotStartWithoutTheBaselineMode() {
        assertThatThrownBy(() ->
                context(
                        Map.of(
                                "gateway.testing.mirror.enabled",
                                true,
                                OnlineReadOnlyShadowCandidateProperties
                                        .PREFIX
                                        + ".enabled",
                                true,
                                OnlineReadOnlyShadowCandidateProperties
                                        .PREFIX
                                        + ".base-uri",
                                "https://candidate.ap.example.test"),
                        "staging"))
                .hasRootCauseInstanceOf(
                        IllegalArgumentException.class)
                .hasRootCauseMessage(
                        "online candidate requires online baseline mode");
    }

    @Test
    void productionPresencePhysicallyExcludesMirrorEvenWhenTestIsAlsoActive() {
        try (var production = context(true, "production");
             var mixed = context(true, "production", "test")) {
            assertMirrorKernelAbsent(production);
            assertMirrorKernelAbsent(mixed);
        }
    }

    @Test
    void batchSchedulerRequiresExplicitBoundedNonProductionConfiguration() {
        Map<String, Object> properties = Map.of(
                "gateway.testing.mirror.enabled", true,
                "gateway.testing.mirror.scenario-batch.scheduler.enabled", true,
                "gateway.testing.mirror.scenario-batch.scheduler.instance-id", "replica-a",
                "gateway.testing.mirror.scenario-batch.scheduler.region", "sg",
                "gateway.testing.mirror.scenario-batch.scheduler.environment-id", "test",
                "gateway.testing.mirror.scenario-batch.scheduler.maximum-pollers", 2,
                "gateway.testing.mirror.scenario-batch.scheduler.initial-delay-millis", 300_000,
                "gateway.testing.mirror.scenario-batch.scheduler.poll-interval-millis", 1_000,
                "gateway.testing.mirror.scenario-batch.scheduler.drain-timeout-millis", 1_000);

        try (var test = context(properties, "test");
             var production = context(
                     properties, "production", "test")) {
            assertThat(test.getBeansOfType(
                    ScenarioRehearsalBatchScheduler.class).values())
                    .singleElement()
                    .satisfies(scheduler -> {
                        assertThat(scheduler.ready()).isTrue();
                        assertThat(scheduler.region())
                                .isEqualTo("sg");
                        assertThat(scheduler.environmentId())
                                .isEqualTo("test");
                    });
            assertThat(production.getBeansOfType(
                    ScenarioRehearsalBatchScheduler.class))
                    .isEmpty();
        }
    }

    @Test
    void finalizationSchedulerRequiresItsOwnBoundedKmsBudget() {
        Map<String, Object> properties = Map.of(
                "gateway.testing.mirror.enabled", true,
                "gateway.testing.mirror.scenario-batch.finalization-scheduler.enabled", true,
                "gateway.testing.mirror.scenario-batch.finalization-scheduler.instance-id", "replica-a",
                "gateway.testing.mirror.scenario-batch.finalization-scheduler.region", "sg",
                "gateway.testing.mirror.scenario-batch.finalization-scheduler.environment-id", "test",
                "gateway.testing.mirror.scenario-batch.finalization-scheduler.maximum-pollers", 1,
                "gateway.testing.mirror.scenario-batch.finalization-scheduler.initial-delay-millis", 300_000,
                "gateway.testing.mirror.scenario-batch.finalization-scheduler.poll-interval-millis", 1_000,
                "gateway.testing.mirror.scenario-batch.finalization-scheduler.drain-timeout-millis", 1_000);

        try (var test = context(properties, "test");
             var production = context(
                     properties, "production", "test")) {
            assertThat(test.getBeansOfType(
                    ScenarioRehearsalBatchFinalizationScheduler
                            .class).values())
                    .singleElement()
                    .satisfies(scheduler -> {
                        assertThat(scheduler.ready()).isTrue();
                        assertThat(scheduler.region())
                                .isEqualTo("sg");
                        assertThat(scheduler.environmentId())
                                .isEqualTo("test");
                    });
            assertThat(test.getBeansOfType(
                    ScenarioRehearsalBatchFinalizationHealthPolicy
                            .class)).hasSize(1);
            assertThat(test.getBeansOfType(
                    ScenarioRehearsalBatchFinalizationHealthTelemetry
                            .class)).hasSize(1);
            assertThat(test.getBeansOfType(
                    ScenarioRehearsalBatchFinalizationSloMonitor
                            .class)).hasSize(1);
            assertThat(production.getBeansOfType(
                    ScenarioRehearsalBatchFinalizationScheduler
                            .class)).isEmpty();
            assertThat(production.getBeansOfType(
                    ScenarioRehearsalBatchFinalizationSloMonitor
                            .class)).isEmpty();
        }
    }

    @Test
    void shadowSchedulerRequiresExplicitBoundedNonProductionConfiguration() {
        Map<String, Object> properties = Map.of(
                "gateway.testing.mirror.enabled", true,
                "gateway.testing.mirror.shadow-job.scheduler.enabled", true,
                "gateway.testing.mirror.shadow-job.scheduler.instance-id", "replica-a",
                "gateway.testing.mirror.shadow-job.scheduler.region", "sg",
                "gateway.testing.mirror.shadow-job.scheduler.environment-id", "shadow-staging",
                "gateway.testing.mirror.shadow-job.scheduler.maximum-pollers", 2,
                "gateway.testing.mirror.shadow-job.scheduler.initial-delay-millis", 300_000,
                "gateway.testing.mirror.shadow-job.scheduler.poll-interval-millis", 1_000,
                "gateway.testing.mirror.shadow-job.scheduler.drain-timeout-millis", 1_000);

        try (var staging = context(properties, "staging");
             var production = context(
                     properties, "production", "staging")) {
            assertThat(staging.getBeansOfType(
                    ReadOnlyShadowJobScheduler.class).values())
                    .singleElement()
                    .satisfies(scheduler -> {
                        assertThat(scheduler.ready()).isTrue();
                        assertThat(scheduler.region()).isEqualTo("sg");
                        assertThat(scheduler.environmentId())
                                .isEqualTo("shadow-staging");
                    });
            assertThat(staging.getBean(
                    ReadOnlyShadowRuntimeAvailability.class))
                    .satisfies(availability -> {
                        assertThat(availability.jobApi()).isTrue();
                        assertThat(availability.lifecycleAudit()).isTrue();
                        assertThat(availability.workerReady()).isFalse();
                        assertThat(availability.schedulerReady()).isTrue();
                        assertThat(availability.servingReady()).isFalse();
                    });
            assertThat(production.getBeansOfType(
                    ReadOnlyShadowJobScheduler.class)).isEmpty();
        }
    }

    @Test
    void servingAvailabilityRequiresAnActuallyAvailableEvidenceSigner() {
        MirrorRuntimeConfiguration configuration = new MirrorRuntimeConfiguration();
        MirrorPlanIntegrationService plans = mock(MirrorPlanIntegrationService.class);
        MirrorRunIntegrationService runs = mock(MirrorRunIntegrationService.class);

        MirrorRuntimeAvailability unavailable = configuration.mirrorRuntimeAvailability(
                plans, runs, VisualEvidenceSigner.unavailable());
        MirrorRuntimeAvailability available = configuration.mirrorRuntimeAvailability(
                plans, runs, new InMemoryVisualEvidenceSigner());

        assertThat(unavailable.planCompilationApi()).isTrue();
        assertThat(unavailable.executionApi()).isTrue();
        assertThat(unavailable.executionReady()).isFalse();
        assertThat(available.executionReady()).isTrue();
    }

    @Test
    void servingAvailabilityTracksSignerChangesAndFailsClosed() {
        MirrorRuntimeConfiguration configuration = new MirrorRuntimeConfiguration();
        VisualEvidenceSigner signer = mock(VisualEvidenceSigner.class);
        AtomicBoolean ready = new AtomicBoolean(false);
        when(signer.available()).thenAnswer(invocation -> ready.get());
        MirrorRuntimeAvailability availability = configuration.mirrorRuntimeAvailability(
                mock(MirrorPlanIntegrationService.class),
                mock(MirrorRunIntegrationService.class), signer);

        assertThat(availability.executionReady()).isFalse();
        ready.set(true);
        assertThat(availability.executionReady()).isTrue();
        when(signer.available()).thenThrow(new IllegalStateException("provider unavailable"));
        assertThat(availability.executionReady()).isFalse();
    }

    private static AnnotationConfigApplicationContext context(boolean enabled,
                                                              String... profiles) {
        return context(
                Map.of(
                        "gateway.testing.mirror.enabled",
                        enabled),
                profiles);
    }

    private static AnnotationConfigApplicationContext context(
            Map<String, Object> properties,
            String... profiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "mirror-runtime-test", properties));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(OperatorRegistry.class, DefaultOperatorRegistry::new);
        context.registerBean(ResourceRegistry.class, EmptyResourceRegistry::new);
        context.registerBean(BlgeExpressionEvaluator.class,
                () -> new BlgeExpressionEvaluator());
        context.registerBean(
                VisualEvidenceSigner.class,
                () -> Boolean.TRUE.equals(
                        properties.get("test.available-signer"))
                        ? new InMemoryVisualEvidenceSigner()
                        : VisualEvidenceSigner.unavailable());
        if (Boolean.TRUE.equals(
                properties.get("test.outcome-authority"))) {
            context.registerBean(
                    AuthoritativeOutcomeAuthorityVerifier.class,
                    () -> new AuthoritativeOutcomeAuthorityVerifier() {
                        @Override
                        public boolean available() {
                            return true;
                        }

                        @Override
                        public void verify(
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .AuthoritativeOutcomeObservation
                                        observation) {
                        }
                    });
        }
        if (Boolean.TRUE.equals(
                properties.get(
                        OnlineReadOnlyShadowBaselineProperties
                                .PREFIX
                                + ".enabled"))) {
            context.registerBean(
                    OnlineReadOnlyShadowBaselineTransport
                            .class,
                    MirrorRuntimeConfigurationTest
                            ::onlineBaselineTransport);
            context.registerBean(
                    HttpOnlineReadOnlyShadowBaselineAuthority
                            .RequestHeadersProvider.class,
                    () -> (operation, uri) -> Map.of());
            context.registerBean(
                    OnlineReadOnlyShadowBaselineEvidenceAuthority
                            .class,
                    () -> OnlineReadOnlyShadowBaselineEvidenceAuthority
                            .from(
                                    new InMemoryVisualEvidenceSigner()));
        }
        if (Boolean.TRUE.equals(
                properties.get(
                        "test.online-candidate-authority"))) {
            context.registerBean(
                    OnlineReadOnlyShadowCandidateAuthority
                            .class,
                    () -> mock(
                            OnlineReadOnlyShadowCandidateAuthority
                                    .class));
        }
        if (Boolean.TRUE.equals(
                properties.get(
                        OnlineReadOnlyShadowCandidateProperties
                                .PREFIX
                                + ".enabled"))) {
            context.registerBean(
                    OnlineReadOnlyShadowCandidateTransport
                            .class,
                    MirrorRuntimeConfigurationTest
                            ::onlineCandidateTransport);
            context.registerBean(
                    HttpOnlineReadOnlyShadowCandidateAuthority
                            .RequestHeadersProvider.class,
                    () -> (operation, uri) -> Map.of());
        }
        context.registerBean(
                ScenarioRehearsalIntegrationService.class,
                () -> mock(ScenarioRehearsalIntegrationService.class));
        context.registerBean(
                ScenarioRehearsalRuntimeService.class,
                () -> mock(ScenarioRehearsalRuntimeService.class));
        context.registerBean(EmbeddedDatabase.class,
                () -> new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true).build(),
                definition -> definition.setDestroyMethodName("shutdown"));
        context.registerBean(JdbcTemplate.class,
                () -> new JdbcTemplate(context.getBean(EmbeddedDatabase.class)));
        context.register(TransactionConfiguration.class);
        context.register(MirrorRuntimeConfiguration.class);
        context.refresh();
        return context;
    }

    private static OnlineReadOnlyShadowBaselineTransport
    onlineBaselineTransport() {
        return new OnlineReadOnlyShadowBaselineTransport() {
            @Override
            public HttpClient client(
                    Duration connectTimeout) {
                return HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(
                                HttpClient.Redirect.NEVER)
                        .build();
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        Descriptor.SCHEMA_VERSION,
                        false,
                        true,
                        true,
                        true,
                        true);
            }
        };
    }

    private static OnlineReadOnlyShadowCandidateTransport
    onlineCandidateTransport() {
        return OnlineReadOnlyShadowCandidateTransport
                .from(onlineBaselineTransport());
    }

    private static void assertMirrorKernelPresent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(MirrorRuntimeConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorPlanCompiler.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorEvidenceIntegrityService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorPlanRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorSessionCheckpointIntegrityService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioArtifactRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalCompiler.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchPolicy.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchFinalizationPolicy.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchTransactionalAdmission.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalRemediationPolicy.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalRemediationRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalRemediationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchRetentionRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchCompiler.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchWorker.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchFinalizationWorker.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchFinalizationScheduler.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                CompiledScenarioRehearsalPlanRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorFixtureScopeRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorEvidenceRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunRequestRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorOperationAuditRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorOperationFailureAuditService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorOperationObservability.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorOperationTelemetry.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityPublicationRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityTrustPolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationBundleIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationAdmissionPolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityObservationIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationAdmissionIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationAdmissionPolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityObservationPayloadReferenceVerifier.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityCorpusIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationReviewRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusTrajectoryRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                DomainFidelityProfileIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                DomainFidelityRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                DomainFidelityService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowComparisonIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowDomainFidelitySource.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobPolicy.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceBindingIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceBindingRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceBindingService.class).values())
                .singleElement()
                .satisfies(service ->
                        assertThat(service.ready()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceResolutionAttestationIntegrity.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceResolutionAttestationRepository.class)
                .values())
                .singleElement()
                .isInstanceOf(
                        DatabaseReadOnlyShadowSourceResolutionAttestationRepository
                                .class);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceResolutionAttestationService.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowAuthorityIntegrity.class))
                .hasSize(1);
        assertThat(context.getBeansOfType(
                ReadOnlyShadowAuthorityPublicationSource.class)
                .values())
                .singleElement()
                .isInstanceOf(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .class)
                .satisfies(source ->
                        assertThat(source.available()).isTrue());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowAuthorityTrustStore.class)
                .values())
                .singleElement()
                .satisfies(trust ->
                        assertThat(trust.available()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSamplingGrantAuthority.class).values())
                .singleElement()
                .isInstanceOf(
                        SignedReadOnlyShadowSamplingGrantAuthority
                                .class)
                .satisfies(authority ->
                        assertThat(authority.available()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowKillSwitchAuthority.class).values())
                .singleElement()
                .isInstanceOf(
                        SignedReadOnlyShadowKillSwitchAuthority
                                .class)
                .satisfies(authority ->
                        assertThat(authority.available()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowAccessAuthority.class).values())
                .singleElement()
                .isInstanceOf(
                        ComposedReadOnlyShadowAccessAuthority.class)
                .satisfies(authority ->
                        assertThat(authority.ready()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowExecutionGuard.class).values())
                .singleElement()
                .isInstanceOf(
                        DatabaseReadOnlyShadowExecutionGuard.class)
                .satisfies(guard ->
                        assertThat(guard.ready()).isTrue());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowBaselineConnector.class).values())
                .singleElement()
                .satisfies(connector ->
                        assertThat(connector.ready()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowCandidateConnector.class).values())
                .singleElement()
                .satisfies(connector ->
                        assertThat(connector.ready()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceResolutionVerifier.class).values())
                .singleElement()
                .satisfies(verifier ->
                        assertThat(verifier.ready()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowComparisonEngine.class).values())
                .singleElement()
                .isInstanceOf(
                        PayloadFreeEqualityReadOnlyShadowPolicy.class)
                .satisfies(engine ->
                        assertThat(engine.ready()).isTrue());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowDataPlane.class).values())
                .singleElement()
                .isInstanceOf(
                        GovernedReadOnlyShadowDataPlane.class)
                .satisfies(dataPlane ->
                        assertThat(dataPlane.ready()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobWorker.class).values())
                .singleElement()
                .satisfies(worker ->
                        assertThat(worker.ready()).isFalse());
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowRuntimeAvailability.class).values())
                .singleElement()
                .satisfies(availability -> {
                    assertThat(availability.jobApi()).isTrue();
                    assertThat(availability.lifecycleAudit()).isTrue();
                    assertThat(availability.workerReady()).isFalse();
                    assertThat(availability.schedulerReady()).isFalse();
                    assertThat(availability.sourceBindingApi()).isTrue();
                    assertThat(availability.sourceBindingReady()).isFalse();
                    assertThat(availability.sourceResolutionApi()).isTrue();
                    assertThat(availability.detachedDataPlaneReady()).isFalse();
                    assertThat(availability.servingReady()).isFalse();
                });
        assertThat(context.getBeansOfType(
                DomainFidelityRuntimeAvailability.class).values())
                .singleElement()
                .satisfies(availability -> {
                    assertThat(availability.inventoryApi()).isTrue();
                    assertThat(availability.profileReadApi()).isTrue();
                    assertThat(availability.signingReady()).isFalse();
                    assertThat(availability.projectionReady()).isFalse();
                });
        assertThat(context.getBeansOfType(
                CapabilityCorpusGovernancePolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityRetryPolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityCorpusSourceVerifier.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorPlanRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(ScenarioArtifactRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(
                CompiledScenarioRehearsalPlanRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorFixtureScopeRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorEvidenceRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorRunRequestRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(CapabilityObservationRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(CapabilityObservationReviewRepository.class)))
                .isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(CapabilityCorpusRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(
                CapabilityCorpusTrajectoryRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(DomainFidelityRepository.class)))
                .isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(DomainFidelityService.class)))
                .isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(ReadOnlyShadowJobService.class)))
                .isTrue();
        assertThat(context.getBean(MirrorRunService.class).engineConfiguration())
                .satisfies(configuration -> {
                    assertThat(configuration.interceptorTypes()).isEmpty();
                    assertThat(configuration.listenerTypes())
                            .containsExactly("com.leanowtech.bloge.gateway.testing.runtime.InvocationRecorder");
                    assertThat(configuration.durableStores()).isFalse();
                    assertThat(configuration.productionContextCarriers()).isFalse();
                    assertThat(configuration.productionExtensionListeners()).isFalse();
                });
    }

    private static void assertMirrorKernelAbsent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(MirrorRuntimeConfiguration.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorPlanCompiler.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorRunService.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorEvidenceIntegrityService.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorPlanRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorSessionCheckpointIntegrityService.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioArtifactRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalCompiler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchPolicy.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchTransactionalAdmission.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalRemediationPolicy.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalRemediationRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalRemediationService.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchRetentionRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchCompiler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchService.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalBatchScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CompiledScenarioRehearsalPlanRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorFixtureScopeRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorEvidenceRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorRunRequestRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorOperationAuditRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorOperationFailureAuditService.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorOperationObservability.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorOperationTelemetry.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityPublicationRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityTrustPolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationBundleIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationAdmissionPolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationAdmissionIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationAdmissionPolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationPayloadReferenceVerifier.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationReviewRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusTrajectoryRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                DomainFidelityProfileIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                DomainFidelityRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                DomainFidelityService.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowComparisonIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowDomainFidelitySource.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobPolicy.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobService.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceResolutionAttestationIntegrity.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceResolutionAttestationRepository.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowSourceResolutionAttestationService.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowDataPlane.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowJobScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ReadOnlyShadowRuntimeAvailability.class)).isEmpty();
        assertThat(context.getBeansOfType(
                DomainFidelityRuntimeAvailability.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusGovernancePolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityRetryPolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusSourceVerifier.class)).isEmpty();
    }

    private static final class EmptyResourceRegistry implements ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            throw new ResourceNotFoundException(resourceId);
        }

        @Override
        public boolean contains(String resourceId) {
            return false;
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.of();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
        @Bean
        PlatformTransactionManager transactionManager(EmbeddedDatabase database) {
            return new DataSourceTransactionManager(database);
        }
    }
}
