package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorFixtureScopeRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorOperationAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorRunRequestRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorFixtureScopeRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationFailureAuditService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationObservability;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunRequestRepository;
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
                                             MirrorEvidenceIntegrityService evidenceIntegrity) {
        ResourceFixtureRuntime resourceRuntime = new ResourceFixtureRuntime(
                resourceRegistry, expressionEvaluator, objectMapper);
        return new MirrorRunService(operatorRegistry, objectMapper, resourceRuntime,
                Clock.systemUTC(), evidenceIntegrity);
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
     * @return profile-owned mirror capability marker
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({MirrorPlanIntegrationService.class, MirrorRunIntegrationService.class})
    public MirrorRuntimeAvailability mirrorRuntimeAvailability(
            MirrorPlanIntegrationService planService,
            MirrorRunIntegrationService runService,
            VisualEvidenceSigner evidenceSigner) {
        return new MirrorRuntimeAvailability(true, true, evidenceSigner::available);
    }
}
