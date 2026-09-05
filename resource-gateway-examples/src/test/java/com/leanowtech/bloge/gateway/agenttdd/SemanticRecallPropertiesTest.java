package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the completed rollout defaults and honest effective-state projection. */
class SemanticRecallPropertiesTest {

    @Test
    void completedDefaultsKeepTheCertifiedWorkflowAndCompatibilityPathsAvailable() {
        SemanticRecallProperties properties = new SemanticRecallProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isRequireSurface()).isFalse();
        assertThat(properties.isEnforceJourneyActions()).isTrue();
        assertThat(properties.isControlledBusinessTestsEnabled()).isTrue();
        assertThat(properties.isAllowLegacyFeatureContract()).isTrue();
        assertThat(properties.isSemanticRankerEnabled()).isFalse();
    }

    @Test
    void requestedRankerNeverClaimsEffectiveUntilAnImplementationExists() {
        SemanticRecallProperties properties = new SemanticRecallProperties();
        properties.setSemanticRankerEnabled(true);

        assertThat(properties.readiness())
                .containsEntry("semanticRankerConfigured", true)
                .containsEntry("semanticRankerEffective", false)
                .containsEntry("semanticRankerState", "NOT_AVAILABLE");
    }
}
