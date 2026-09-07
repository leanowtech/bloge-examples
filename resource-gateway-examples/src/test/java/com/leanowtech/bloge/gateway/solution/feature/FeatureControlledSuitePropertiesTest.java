package com.leanowtech.bloge.gateway.solution.feature;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Specifies the strict typed configuration boundary for Feature controlled suites. */
class FeatureControlledSuitePropertiesTest {

    @Test
    void exposesSafeReconciliationDefaultsAndAcceptsPositiveOverrides() {
        FeatureControlledSuiteProperties properties = new FeatureControlledSuiteProperties();

        assertThat(properties.getReconciliationInitialDelayMs()).isEqualTo(60_000);
        assertThat(properties.getReconciliationFixedDelayMs()).isEqualTo(21_600_000);

        properties.setReconciliationInitialDelayMs(1_000);
        properties.setReconciliationFixedDelayMs(2_000);

        assertThat(properties.getReconciliationInitialDelayMs()).isEqualTo(1_000);
        assertThat(properties.getReconciliationFixedDelayMs()).isEqualTo(2_000);
    }

    @Test
    void rejectsNonPositiveReconciliationIntervals() {
        FeatureControlledSuiteProperties properties = new FeatureControlledSuiteProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setReconciliationInitialDelayMs(0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setReconciliationFixedDelayMs(-1));
    }
}
