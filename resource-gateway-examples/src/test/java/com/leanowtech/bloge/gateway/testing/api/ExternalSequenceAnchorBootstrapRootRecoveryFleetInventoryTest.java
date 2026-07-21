package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest {

    @Test
    void snapshotCanonicalizesLanesAndExposesOnlyStableDescriptors() {
        Lane second = lane("tenant-b", "roots-b", 'b');
        Lane first = lane("tenant-a", "roots-a", 'a');

        Snapshot snapshot = snapshot(7L, second, first);

        assertThat(snapshot.lanes()).extracting(Lane::key)
                .containsExactly(first.key(), second.key());
        assertThat(snapshot.descriptors()).containsExactly(
                first.descriptor(), second.descriptor());
        assertThatThrownBy(() -> snapshot.lanes().add(first))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void laneRejectsAdvertisedBindingThatDiffersFromItsService() {
        ExpectedBinding actual = binding("tenant-a", "roots-a");
        ExpectedBinding advertised = binding("tenant-a", "roots-b");
        var service = mock(ExternalSequenceAnchorBootstrapRootCeremonyService.class);
        when(service.expectedBinding()).thenReturn(actual);

        assertThatThrownBy(() -> new Lane(advertised, fingerprint('a'), service,
                mock(ExternalSequenceAnchorBootstrapRootAuthorityResolver.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bootstrap-root recovery lane binding is invalid");
    }

    @Test
    void laneAndStandaloneDescriptorRejectNonCanonicalRuntimeFingerprint() {
        ExpectedBinding expected = binding("tenant-a", "roots-a");
        var service = mock(ExternalSequenceAnchorBootstrapRootCeremonyService.class);
        when(service.expectedBinding()).thenReturn(expected);

        assertThatThrownBy(() -> new Lane(expected, "A".repeat(64), service,
                mock(ExternalSequenceAnchorBootstrapRootAuthorityResolver.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LaneDescriptor(expected, "sha256:" + "g".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotRejectsDuplicateScopeAndRootSetEvenWhenRuntimeFingerprintDiffers() {
        Lane first = lane("tenant-a", "roots-a", 'a');
        Lane duplicate = lane("tenant-a", "roots-a", 'b');

        assertThatThrownBy(() -> snapshot(1L, first, duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bootstrap-root recovery fleet inventory is invalid");
    }

    @Test
    void protocolGenerationAndStandaloneLaneKeysAreStrict() {
        assertThatThrownBy(() -> new Snapshot("legacy", 1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(Snapshot.SCHEMA_VERSION, 0L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LaneKey("tenant\nadmin", "roots-a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Snapshot snapshot(long generation, Lane... lanes) {
        return new Snapshot(Snapshot.SCHEMA_VERSION, generation, List.of(lanes));
    }

    static Lane lane(String scopeId, String rootSetId, char fingerprintCharacter) {
        ExpectedBinding binding = binding(scopeId, rootSetId);
        var service = mock(ExternalSequenceAnchorBootstrapRootCeremonyService.class);
        when(service.expectedBinding()).thenReturn(binding);
        return new Lane(binding, fingerprint(fingerprintCharacter), service,
                mock(ExternalSequenceAnchorBootstrapRootAuthorityResolver.class));
    }

    static ExpectedBinding binding(String scopeId, String rootSetId) {
        return new ExpectedBinding(scopeId, rootSetId, "bootstrap-trust", 1, 0,
                Duration.ofDays(30), Duration.ofSeconds(5), Duration.ofMinutes(1), 32);
    }

    static String fingerprint(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }
}
