package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealthTest {

    @Test
    void verifiedSignedInventoryIsUpWithAggregateOnlyDetails() {
        var health = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
                () -> observation(true, "VERIFIED", 17L, 8));

        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails())
                .containsEntry("inventoryStatus", "VERIFIED")
                .containsEntry("inventoryAvailable", true)
                .containsEntry("inventoryGeneration", 17L)
                .containsEntry("laneCount", 8)
                .containsEntry("validSignatureCount", 3)
                .containsEntry("requiredSignatureCount", 2)
                .containsEntry("runtimeExpiryFence", true)
                .containsEntry("fleetTopologyBound", true)
                .containsEntry("exactRuntimeBinding", true)
                .containsEntry("automaticRefresh", false)
                .containsEntry("signedRevocation", false)
                .containsEntry("durableGenerationFloor", false)
                .doesNotContainKeys("fleetId", "scopeId", "laneKeys", "expiresAt",
                        "materialFingerprint", "policyFingerprint", "authorityId",
                        "publicKey", "privateKey", "exception", "message");
    }

    @Test
    void expiredSignedInventoryIsDownButRetainsBoundedAggregateContext() {
        var health = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
                () -> observation(false, "EXPIRED", 19L, 0));

        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails())
                .containsEntry("inventoryStatus", "EXPIRED")
                .containsEntry("inventoryAvailable", false)
                .containsEntry("inventoryGeneration", 19L)
                .containsEntry("laneCount", 0);
    }

    @Test
    void observationFailureIsCollapsedWithoutExceptionOrIdentityDetails() {
        var health = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(() -> {
            throw new IllegalStateException("fleet tenant-a roots-a secret detail");
        });

        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails())
                .containsEntry("inventoryStatus", "UNAVAILABLE")
                .doesNotContainValue("fleet tenant-a roots-a secret detail");
        assertThat(result.getDetails()).hasSize(2);
    }

    @Test
    void authorityObservationRejectsUnboundedOrInternallyContradictoryShape() {
        assertThatThrownBy(() -> new Observation(Observation.SCHEMA_VERSION, true,
                "VERIFIED", "STATIC_SIGNED_ED25519_M_OF_N", 0L, 1,
                Instant.parse("2026-07-21T01:00:00Z"), 2, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Observation(Observation.SCHEMA_VERSION, true,
                "VERIFIED", "STATIC_SIGNED_ED25519_M_OF_N", 1L, 1,
                Instant.parse("2026-07-21T01:00:00Z"), 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Observation(Observation.SCHEMA_VERSION, true,
                "VERIFIED", "STATIC_SIGNED_ED25519_M_OF_N", 1L, 257,
                Instant.parse("2026-07-21T01:00:00Z"), 2, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Observation(Observation.SCHEMA_VERSION, true,
                "EXPIRED", "STATIC_SIGNED_ED25519_M_OF_N", 1L, 1,
                Instant.parse("2026-07-21T01:00:00Z"), 2, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Observation(Observation.SCHEMA_VERSION, false,
                "VERIFIED", "STATIC_SIGNED_ED25519_M_OF_N", 1L, 1,
                Instant.parse("2026-07-21T01:00:00Z"), 2, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Observation observation(
            boolean available, String status, long generation, int lanes) {
        return new Observation(Observation.SCHEMA_VERSION, available, status,
                "STATIC_SIGNED_ED25519_M_OF_N", generation, lanes,
                Instant.parse("2026-07-21T01:00:00Z"), 3, 2);
    }
}
