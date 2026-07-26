package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyShadowSourceBindingIntegrityTest {
    private static final Instant NOW =
            Instant.parse("2026-07-26T12:00:00Z");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final ReadOnlyShadowSourceBindingIntegrity integrity =
            new ReadOnlyShadowSourceBindingIntegrity(
                    mapper,
                    InMemoryVisualEvidenceSigner.usingClock(
                            Clock.fixed(NOW, ZoneOffset.UTC)),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void signsNestedBaselineBeforeOuterBindingAndCanonicalizesFacts() {
        LinkedHashMap<DomainFidelityProfile.Dimension, String> facts =
                new LinkedHashMap<>();
        facts.put(
                DomainFidelityProfile.Dimension.STATE_TRANSITION,
                fingerprint('b'));
        facts.put(
                DomainFidelityProfile.Dimension.BEHAVIOR,
                fingerprint('a'));

        ReadOnlyShadowSourceBinding signed =
                integrity.sign(unsigned(facts));

        assertThat(signed.baselineObservationFingerprint())
                .startsWith("sha256:");
        assertThat(signed.bindingFingerprint())
                .startsWith("sha256:")
                .isNotEqualTo(
                        signed.baselineObservationFingerprint());
        assertThat(signed.artifactRef().kind())
                .isEqualTo("SHADOW_SOURCE_BINDING");
        assertThat(signed.baselineArtifactRef().kind())
                .isEqualTo("SHADOW_BASELINE_OBSERVATION");
        assertThat(signed.baseline()
                .normalizedFactFingerprints().keySet())
                .containsExactly(
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        DomainFidelityProfile.Dimension.STATE_TRANSITION);
        assertThat(integrity.verify(signed)).isEqualTo(signed);
    }

    @Test
    void rejectsOuterCoordinateTamperAndInvalidCertifiableWriteClaims() {
        ReadOnlyShadowSourceBinding signed =
                integrity.sign(unsigned(Map.of(
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        fingerprint('a'))));
        ReadOnlyShadowSourceBinding tampered =
                new ReadOnlyShadowSourceBinding(
                        signed.schemaVersion(),
                        signed.bindingFingerprint(),
                        signed.bindingId(),
                        signed.revision(),
                        signed.scope(),
                        signed.scenarioCaseRef(),
                        signed.targetCapabilityRef(),
                        signed.candidatePlanRef(),
                        signed.baselineBindingRef(),
                        signed.comparisonPolicyRef(),
                        signed.requestContextFingerprint(),
                        signed.baselineObservationFingerprint(),
                        signed.baseline(),
                        ref(
                                "MIRROR_EVIDENCE_BUNDLE",
                                "different-run",
                                'f'),
                        signed.validFrom(),
                        signed.expiresAt(),
                        signed.issuedAt(),
                        signed.bindingSeal());

        assertThatThrownBy(() -> integrity.verify(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
        assertThatThrownBy(() ->
                new ReadOnlyShadowSourceBinding.BaselineObservation(
                        fingerprint('a'),
                        Map.of(
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                fingerprint('a')),
                        NOW.minusSeconds(20),
                        MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                        true,
                        true,
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence closure");
    }

    private ReadOnlyShadowSourceBinding unsigned(
            Map<DomainFidelityProfile.Dimension, String> facts) {
        return new ReadOnlyShadowSourceBinding(
                ReadOnlyShadowSourceBinding.SCHEMA_VERSION,
                "",
                "refund-source-pair",
                1,
                ReadOnlyShadowJobTestFixtures.scope("support"),
                ref("SCENARIO_CASE", "refund-golden", '1'),
                ref("CAPABILITY", "refund", '2'),
                ref("MIRROR_PLAN", "refund-plan", '3'),
                ref(
                        "SHADOW_BASELINE_BINDING",
                        "refund-read",
                        '4'),
                ref(
                        "SHADOW_COMPARISON_POLICY",
                        "semantic-v1",
                        '5'),
                fingerprint('6'),
                "",
                new ReadOnlyShadowSourceBinding.BaselineObservation(
                        fingerprint('7'),
                        facts,
                        NOW.minusSeconds(20),
                        MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                        true,
                        false,
                        0),
                ref(
                        "MIRROR_EVIDENCE_BUNDLE",
                        "candidate-run",
                        '8'),
                NOW,
                NOW.plusSeconds(3600),
                NOW.minusSeconds(10),
                VisualRunEvidenceSeal.unsigned());
    }

    private static MirrorArtifactRef ref(
            String kind,
            String id,
            char value) {
        return new MirrorArtifactRef(
                kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
