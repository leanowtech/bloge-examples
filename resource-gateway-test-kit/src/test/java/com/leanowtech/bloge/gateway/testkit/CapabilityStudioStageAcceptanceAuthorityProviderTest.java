package com.leanowtech.bloge.gateway.testkit;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioStageAcceptanceAuthorityProviderTest {
    private static final EvidenceResolver RESOLVER = request -> EvidenceResolution.unavailable();
    private static final EvidenceIssuerPolicy ISSUER =
            (reference, evidence, context) ->
                    CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.unavailable();
    private static final OwnerAuthority OWNER =
            (signoff, signature, context) ->
                    CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.unavailable();

    @Test
    void preservesThePublicLegacyFourArgumentBindingContract() {
        String material = fingerprint('a');

        CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding binding =
                new CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding(
                        material, RESOLVER, ISSUER, OWNER);
        CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding duplicate =
                new CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding(
                        material, RESOLVER, ISSUER, OWNER);

        assertThat(binding.fingerprint()).isEqualTo(material);
        assertThat(binding).isEqualTo(duplicate);
        assertThat(Arrays.stream(CapabilityStudioStageAcceptanceAuthorityProvider
                .AuthorityBinding.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("fingerprint", "resolver", "issuerPolicy", "ownerAuthority");
    }

    @Test
    void snapshotsTargetAdmissionBytesAndEnforcesEachBound() {
        byte[] target = "target-binding-secret".getBytes(StandardCharsets.UTF_8);
        byte[] candidate = new byte[]{2, 3};
        byte[] environment = new byte[]{4, 5};
        CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding admission =
                targetAdmission(target, candidate, environment);

        target[0] = 'X';
        candidate[0] = 9;
        environment[0] = 8;
        byte[] targetCopy = admission.targetBindingBytes();
        byte[] candidateCopy = admission.candidateAttestationBytes();
        byte[] environmentCopy = admission.environmentAttestationBytes();
        targetCopy[0] = 'Y';
        candidateCopy[0] = 7;
        environmentCopy[0] = 6;

        assertThat(admission.targetBindingBytes()).containsExactly(
                "target-binding-secret".getBytes(StandardCharsets.UTF_8));
        assertThat(admission.candidateAttestationBytes()).containsExactly(2, 3);
        assertThat(admission.environmentAttestationBytes()).containsExactly(4, 5);
        assertThat(admission.toString()).doesNotContain("target-binding-secret");

        assertThatThrownBy(() -> targetAdmission(
                new byte[CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .MAXIMUM_TARGET_BINDING_BYTES + 1], candidate, environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetBindingBytes");
    }

    @Test
    void targetBoundFingerprintIsDeterministicAndDistinctFromAuthorityMaterial() {
        String material = fingerprint('a');
        CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding firstAdmission =
                targetAdmission(new byte[]{1}, new byte[]{2}, new byte[]{3});
        CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding secondAdmission =
                targetAdmission(new byte[]{1}, new byte[]{2}, new byte[]{3});

        CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding authorityBinding =
                new CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding(
                        material, RESOLVER, ISSUER, OWNER);
        CapabilityStudioStageAcceptanceAuthorityProvider.TargetBoundAuthorityBinding first =
                new CapabilityStudioStageAcceptanceAuthorityProvider.TargetBoundAuthorityBinding(
                        authorityBinding, firstAdmission);
        CapabilityStudioStageAcceptanceAuthorityProvider.TargetBoundAuthorityBinding second =
                new CapabilityStudioStageAcceptanceAuthorityProvider.TargetBoundAuthorityBinding(
                        authorityBinding, secondAdmission);
        String expected = CapabilityStudioStageAcceptanceAuthorityProvider
                .TargetBoundAuthorityBinding
                .aggregateFingerprint(
                        CapabilityStudioStageAcceptanceAuthorityProvider
                                .TargetBoundAuthorityBinding.MESSAGE_VERSION,
                        material, firstAdmission.targetBindingFingerprint());

        assertThat(first.fingerprint()).isEqualTo(expected);
        assertThat(second.fingerprint()).isEqualTo(expected);
        assertThat(first.fingerprint()).isNotEqualTo(material);
        assertThat(CapabilityStudioStageAcceptanceAuthorityProvider.aggregateFingerprint(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .TargetBoundAuthorityBinding.MESSAGE_VERSION,
                material, firstAdmission.targetBindingFingerprint())).isEqualTo(expected);
        assertThat(CapabilityStudioStageAcceptanceAuthorityProvider.TargetBoundAuthorityBinding
                .aggregateCanonicalMessage(
                        CapabilityStudioStageAcceptanceAuthorityProvider
                                .TargetBoundAuthorityBinding.MESSAGE_VERSION,
                        material, firstAdmission.targetBindingFingerprint()))
                .isEqualTo("{\"messageVersion\":\""
                        + CapabilityStudioStageAcceptanceAuthorityProvider
                        .TargetBoundAuthorityBinding.MESSAGE_VERSION
                        + "\",\"authorityMaterialFingerprint\":\"" + material
                        + "\",\"targetBindingFingerprint\":\""
                        + firstAdmission.targetBindingFingerprint() + "\"}");
        assertThat(first.toString()).doesNotContain(material,
                firstAdmission.targetBindingFingerprint());
    }

    @Test
    void rejectsAnExplicitOuterFingerprintThatDoesNotMatchTheAggregate() {
        var authorityBinding =
                new CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding(
                        fingerprint('a'), RESOLVER, ISSUER, OWNER);
        var admission = targetAdmission(new byte[]{1}, new byte[]{2}, new byte[]{3});

        assertThatThrownBy(() ->
                new CapabilityStudioStageAcceptanceAuthorityProvider.TargetBoundAuthorityBinding(
                        fingerprint('c'), authorityBinding, admission))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("target-bound authority binding fingerprint is invalid");
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding
    targetAdmission(byte[] target, byte[] candidate, byte[] environment) {
        var context = new CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext(
                "lease:test", Set.of("runtime:test"), fingerprint('b'));
        return new CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding(
                target, candidate, environment, context,
                facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                        .verified(),
                facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                        .verified());
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
