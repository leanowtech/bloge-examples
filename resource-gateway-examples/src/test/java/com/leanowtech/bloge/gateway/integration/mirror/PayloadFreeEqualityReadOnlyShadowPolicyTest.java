package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadFreeEqualityReadOnlyShadowPolicyTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy =
            new PayloadFreeEqualityReadOnlyShadowPolicy(mapper);

    @Test
    void contentAddressesRulesAndProjectsOnlySignedPayloadFreeFacts() {
        MirrorRunEvidence evidence =
                evidence("candidate-a", 'a').evidence();

        Map<DomainFidelityProfile.Dimension, String> facts =
                policy.normalize(evidence);

        assertThat(policy.reference().kind())
                .isEqualTo("SHADOW_COMPARISON_POLICY");
        assertThat(policy.reference().id())
                .isEqualTo(
                        PayloadFreeEqualityReadOnlyShadowPolicy
                                .POLICY_ID);
        assertThat(policy.reference().fingerprint())
                .isEqualTo(
                        PayloadFreeEqualityReadOnlyShadowPolicy
                                .POLICY_FINGERPRINT);
        assertThat(facts)
                .containsEntry(
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        evidence.semanticResultFingerprint())
                .containsEntry(
                        DomainFidelityProfile.Dimension.CONTRACT,
                        evidence.capabilityClosureFingerprint())
                .containsKey(
                        DomainFidelityProfile.Dimension.EFFECT)
                .doesNotContainKey(
                        DomainFidelityProfile.Dimension
                                .STATE_TRANSITION);
        assertThat(facts.values())
                .allMatch(value ->
                        value.matches("sha256:[a-f0-9]{64}"));
    }

    @Test
    void derivesMatchMismatchAndEvidenceGapWithoutCallerOutcomes() {
        String same = fingerprint('a');
        String baselineOnly = fingerprint('b');
        String candidateOnly = fingerprint('c');
        ReadOnlyShadowConnectorObservation baseline =
                observation(
                        ReadOnlyShadowComparison.SourceRole.BASELINE,
                        Map.of(
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                same,
                                DomainFidelityProfile.Dimension.CONTRACT,
                                baselineOnly));
        ReadOnlyShadowConnectorObservation candidate =
                observation(
                        ReadOnlyShadowComparison.SourceRole.CANDIDATE,
                        Map.of(
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                same,
                                DomainFidelityProfile.Dimension.CONTRACT,
                                candidateOnly,
                                DomainFidelityProfile.Dimension.EFFECT,
                                fingerprint('d')));

        List<ReadOnlyShadowComparison.DimensionComparison> result =
                policy.compare(
                        policy.reference(),
                        baseline,
                        candidate);

        assertThat(result)
                .extracting(
                        ReadOnlyShadowComparison
                                .DimensionComparison::dimension)
                .containsExactly(
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        DomainFidelityProfile.Dimension.CONTRACT,
                        DomainFidelityProfile.Dimension.EFFECT);
        assertThat(result)
                .extracting(
                        ReadOnlyShadowComparison
                                .DimensionComparison::outcome)
                .containsExactly(
                        ReadOnlyShadowComparison.DiffOutcome.MATCH,
                        ReadOnlyShadowComparison.DiffOutcome.MISMATCH,
                        ReadOnlyShadowComparison
                                .DiffOutcome.INDETERMINATE);
        assertThat(result.get(1).diffTypes())
                .containsExactly(
                        ReadOnlyShadowComparison
                                .DiffType.OUTPUT_SCHEMA);
        assertThat(result.get(2).diffTypes())
                .containsExactly(
                        ReadOnlyShadowComparison
                                .DiffType.EVIDENCE_GAP);
    }

    @Test
    void rejectsUnknownPolicyAndObservationPolicyDrift() {
        ReadOnlyShadowConnectorObservation baseline =
                observation(
                        ReadOnlyShadowComparison.SourceRole.BASELINE,
                        Map.of(
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                fingerprint('a')));
        ReadOnlyShadowConnectorObservation candidate =
                observation(
                        ReadOnlyShadowComparison.SourceRole.CANDIDATE,
                        Map.of(
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                fingerprint('a')));
        MirrorArtifactRef unknown =
                new MirrorArtifactRef(
                        "SHADOW_COMPARISON_POLICY",
                        "unknown",
                        1,
                        fingerprint('f'));

        assertThatThrownBy(() ->
                policy.compare(unknown, baseline, candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        ReadOnlyShadowConnectorObservation drifted =
                new ReadOnlyShadowConnectorObservation(
                        candidate.source(),
                        unknown,
                        candidate.normalizedFactFingerprints(),
                        false,
                        0);
        assertThatThrownBy(() ->
                policy.compare(
                        policy.reference(),
                        baseline,
                        drifted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different comparison policy");
    }

    private ReadOnlyShadowConnectorObservation observation(
            ReadOnlyShadowComparison.SourceRole role,
            Map<DomainFidelityProfile.Dimension, String> facts) {
        String artifactKind =
                role == ReadOnlyShadowComparison.SourceRole.BASELINE
                        ? "SHADOW_BASELINE_OBSERVATION"
                        : "MIRROR_EVIDENCE_BUNDLE";
        return new ReadOnlyShadowConnectorObservation(
                new ReadOnlyShadowComparison.SourceObservation(
                        role,
                        new MirrorArtifactRef(
                                artifactKind,
                                role.name().toLowerCase(),
                                1,
                                fingerprint('1')),
                        ReadOnlyShadowJobTestFixtures
                                .scope("support"),
                        ReadOnlyShadowJobTestFixtures.ref(
                                "CAPABILITY",
                                "refund",
                                '2'),
                        fingerprint('3'),
                        fingerprint('4'),
                        ReadOnlyShadowJobTestFixtures.NOW,
                        MirrorRunEvidence
                                .EvidenceClass.EXPLORATORY,
                        true),
                policy.reference(),
                new LinkedHashMap<>(facts),
                false,
                0);
    }

    private MirrorEvidenceBundle evidence(
            String runId,
            char semantic) {
        var signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        Clock.fixed(
                                ReadOnlyShadowJobTestFixtures.NOW,
                                ZoneOffset.UTC));
        MirrorPlan plan =
                MirrorPersistenceTestFixtures.plan(
                        mapper,
                        MirrorPersistenceTestFixtures
                                .scope("support"),
                        "detached-plan",
                        '4');
        return MirrorPersistenceTestFixtures.evidence(
                mapper,
                signer,
                plan,
                runId,
                semantic);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
