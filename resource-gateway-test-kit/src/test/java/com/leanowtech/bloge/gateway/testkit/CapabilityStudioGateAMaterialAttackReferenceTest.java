package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent Java re-execution of the frozen Gate A real-material attack pack. */
class CapabilityStudioGateAMaterialAttackReferenceTest {
    @Test
    void replaysAllFrozenGuardsAndReviewerSupplementalAttacksFromMaterial() throws Exception {
        var results = CapabilityStudioGateAMaterialAttackReference.verifyFrozenMaterialAttacks();
        var primary = CapabilityStudioGateAMaterialAttackReference.guardCatalogOrder();

        assertThat(results).hasSize(38);
        assertThat(results.subList(0, primary.size()))
                .extracting(CapabilityStudioGateAMaterialAttackReference.Observed::guardId)
                .containsExactlyElementsOf(primary);
        assertThat(results).allMatch(result -> !result.status().equals("PASS"));
    }
}
