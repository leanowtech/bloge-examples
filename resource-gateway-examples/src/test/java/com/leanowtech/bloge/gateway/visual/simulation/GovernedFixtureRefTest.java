package com.leanowtech.bloge.gateway.visual.simulation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the bounded, payload-free identity carried by a simulation request. */
class GovernedFixtureRefTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void preservesLegacyFixtureConstructorsAndCarriesExactReferenceSeparately() {
        NodeFixture legacy = new NodeFixture("sample", "expected");
        NodeFixture governed = new NodeFixture(null, null,
                new GovernedFixtureRef("fixture-1", 2, FINGERPRINT));

        assertThat(legacy.governedRef()).isNull();
        assertThat(governed.governedRef().fixtureAssetId()).isEqualTo("fixture-1");
        assertThat(governed.output()).isNull();
    }

    @Test
    void rejectsIncompleteOrNonExactReference() {
        assertThatThrownBy(() -> new GovernedFixtureRef("fixture-1", 0, FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GovernedFixtureRef("fixture-1", 1, "opaque"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
