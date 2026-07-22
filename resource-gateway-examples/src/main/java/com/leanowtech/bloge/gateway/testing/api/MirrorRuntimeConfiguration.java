package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunService;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
 * <p>This configuration assembles only the internal Stage 1 kernel. Protected HTTP adapters and
 * durable plan/evidence stores are added separately so the capability probe can remain closed
 * until those end-to-end surfaces are complete.</p>
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
                                             VisualEvidenceSigner evidenceSigner) {
        ResourceFixtureRuntime resourceRuntime = new ResourceFixtureRuntime(
                resourceRegistry, expressionEvaluator, objectMapper);
        return new MirrorRunService(operatorRegistry, objectMapper, resourceRuntime,
                Clock.systemUTC(), evidenceSigner);
    }
}
