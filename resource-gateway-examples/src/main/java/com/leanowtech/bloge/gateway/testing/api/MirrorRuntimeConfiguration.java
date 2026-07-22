package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorFixtureScopeRepository;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorFixtureScopeRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.MirrorRuntimeAvailability;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunService;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * and evidence stores. The profile-gated mirror controller and plan application service add the
 * protected planning adapter; the availability marker is emitted only after that service has been
 * assembled. Execution serving remains closed until its protected adapter is complete.</p>
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
     * @param evidenceSigner governed evidence signer; unavailable signers fail execution closed
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
     * Publishes honest protected-API readiness to the integration capability probe.
     *
     * @param planService fully assembled authoritative plan application boundary
     * @return profile-owned mirror capability marker
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MirrorPlanIntegrationService.class)
    public MirrorRuntimeAvailability mirrorRuntimeAvailability(
            MirrorPlanIntegrationService planService) {
        return new MirrorRuntimeAvailability(true, false);
    }
}
