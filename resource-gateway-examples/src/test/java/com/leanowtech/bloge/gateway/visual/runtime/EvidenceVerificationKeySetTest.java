package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceVerificationKeySetTest {

    @Test
    void publishesCanonicalSnapshotAndImmediatelyVerifiesItsAttestation() {
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        VisualEvidenceSigner.KeySetResolution resolution = signer.resolveKeySet();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        EvidenceVerificationKeySet keySet = EvidenceVerificationKeySet.publish(
                mapper, signer, resolution.keySet());

        assertThat(keySet.snapshotFingerprint())
                .isEqualTo(ProtocolFingerprint.of(mapper, keySet.material()));
        assertThat(keySet.attestation().materialFingerprint())
                .isEqualTo(keySet.snapshotFingerprint());
        assertThat(keySet.attestation().keyId()).isEqualTo(keySet.activeKeyId());
        assertThat(signer.verify(keySet.attestation(), keySet.snapshotFingerprint()).valid()).isTrue();
        assertThat(keySet.keys()).singleElement().satisfies(key ->
                assertThat(key.state()).isEqualTo(EvidenceVerificationKeySet.KeyState.ACTIVE));
    }

    @Test
    void rejectsDuplicateKeysUnorderedEventsAndMissingActivePolicy() throws Exception {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        EvidenceVerificationKeySet.KeyPolicy key = new EvidenceVerificationKeySet.KeyPolicy(
                "key-a", "Ed25519", publicKey, now, now, null,
                EvidenceVerificationKeySet.KeyState.ACTIVE, "version-a");
        EvidenceVerificationKeySet.LifecycleEvent second = new EvidenceVerificationKeySet.LifecycleEvent(
                2, "event-2", "key-a", EvidenceVerificationKeySet.EventType.ACTIVATED,
                now, now, null, null, "KEY_ACTIVATED");
        EvidenceVerificationKeySet.LifecycleEvent first = new EvidenceVerificationKeySet.LifecycleEvent(
                1, "event-1", "key-a", EvidenceVerificationKeySet.EventType.CREATED,
                now, now, null, null, "KEY_CREATED");

        assertThatThrownBy(() -> new EvidenceVerificationKeySet.Source("provider", now,
                now.plusSeconds(60), "key-a", EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                List.of(key, key), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThat(new EvidenceVerificationKeySet.Source("provider", now,
                now.plusSeconds(60), "key-a", EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                List.of(key), List.of(second, first)).events())
                .extracting(EvidenceVerificationKeySet.LifecycleEvent::sequence)
                .containsExactly(1L, 2L);
        EvidenceVerificationKeySet.KeyPolicy retired = new EvidenceVerificationKeySet.KeyPolicy(
                "key-a", "Ed25519", publicKey, now, now, null,
                EvidenceVerificationKeySet.KeyState.VERIFY_ONLY, "version-a");
        assertThatThrownBy(() -> new EvidenceVerificationKeySet.Source("provider", now,
                now.plusSeconds(60), "key-a", EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                List.of(retired), List.of(first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active");
        assertThatThrownBy(() -> new EvidenceVerificationKeySet.Source("provider", now,
                now.plusSeconds(60), "key-a", EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                List.of(key), List.of(first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }
}
