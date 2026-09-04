package com.leanowtech.bloge.gateway.solution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for Solution runtime trust material; evaluator and dispatch adapters stay optional. */
@Configuration
public class SolutionRuntimeConfiguration {
    /**
     * Builds the rotation-aware Feature token key ring.
     *
     * <p>Local demos with no configured values receive one process-local key. Replicated or
     * restart-stable deployments must inject the same active and verify-only generations from a
     * secret manager.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    FeatureTokenKeyProvider featureTokenKeyProvider(
            @Value("${gateway.agent-tdd.feature-token.active-key-id:}") String activeKeyId,
            @Value("${gateway.agent-tdd.feature-token.key-ring:}") String keyRing) {
        return InMemoryFeatureTokenKeyProvider.fromConfiguration(activeKeyId, keyRing);
    }
}
