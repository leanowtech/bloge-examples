package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorServingGenerationIntegrityTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "support", "staging", "sg");
    private static final String PURPOSE = "MIRROR_REHEARSAL";
    private static final String DEPENDENCY = fingerprint('a');

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final VisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
    private final MirrorServingGenerationIntegrity integrity =
            new MirrorServingGenerationIntegrity(mapper);

    @Test
    void sealsAndIndependentlyVerifiesOneScopeBoundGeneration() {
        MirrorServingGenerationToken token = token(1, "", 7, DEPENDENCY);
        MirrorServingGenerationTrustProvider trust = trust(token);

        MirrorServingGenerationIntegrity.VerifiedGeneration verified = integrity.verify(
                token,
                trust,
                new MirrorServingGenerationIntegrity.Expectation(
                        SCOPE, PURPOSE, DEPENDENCY, NOW.plus(Duration.ofMinutes(30))),
                NOW);

        assertThat(verified.tokenFingerprint()).isEqualTo(token.tokenFingerprint());
        assertThat(verified.generation()).isEqualTo(1);
        assertThat(verified.revocationCursor()).isEqualTo(7);
        assertThat(verified.maximumStaleness()).isEqualTo(Duration.ofSeconds(5));
        assertThat(token.toString()).doesNotContain(token.seal().signature());
    }

    @Test
    void rejectsTamperingWrongScopePurposeDependencyAndShortHorizon() {
        MirrorServingGenerationToken token = token(1, "", 7, DEPENDENCY);
        MirrorServingGenerationTrustProvider trust = trust(token);
        MirrorServingGenerationToken tampered = new MirrorServingGenerationToken(
                token.schemaVersion(), token.tokenFingerprint(), token.materialFingerprint(),
                new MirrorServingGenerationToken.Material(
                        token.material().streamId(), token.material().generation(),
                        token.material().previousTokenFingerprint(), token.material().scope(),
                        token.material().authorizedPurpose(), fingerprint('b'),
                        token.material().revocationCursor(), token.material().issuedAt(),
                        token.material().expiresAt(), token.material().maximumStaleness()),
                token.seal());

        assertThatThrownBy(() -> integrity.verify(
                tampered, trust, expectation(SCOPE, PURPOSE, fingerprint('b')), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> integrity.verify(
                token, trust,
                expectation(new CapabilitySnapshot.Scope(
                        "tenant-b", "org-a", "support", "staging", "sg"),
                        PURPOSE, DEPENDENCY),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> integrity.verify(
                token, trust, expectation(SCOPE, "OTHER_PURPOSE", DEPENDENCY), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purpose");
        assertThatThrownBy(() -> integrity.verify(
                token, trust, expectation(SCOPE, PURPOSE, fingerprint('c')), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dependency");
        assertThatThrownBy(() -> integrity.verify(
                token, trust,
                new MirrorServingGenerationIntegrity.Expectation(
                        SCOPE, PURPOSE, DEPENDENCY, NOW.plus(Duration.ofHours(2))),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horizon");
    }

    @Test
    void rejectsUnknownRevokedAndCryptographicallyInvalidAuthorityKeys() {
        MirrorServingGenerationToken token = token(1, "", 7, DEPENDENCY);

        assertThatThrownBy(() -> integrity.verify(
                token, MirrorServingGenerationTrustProvider.unavailable(),
                expectation(SCOPE, PURPOSE, DEPENDENCY), NOW))
                .isInstanceOf(
                        MirrorServingGenerationIntegrity
                                .TrustUnavailableException.class)
                .hasMessageContaining("unavailable");

        MirrorServingGenerationTrustProvider.AuthorityKey key =
                authorityKey(token, signer);
        MirrorServingGenerationTrustProvider revoked =
                MirrorServingGenerationTrustProvider.fixed(
                        new MirrorServingGenerationTrustProvider.AuthorityKey(
                                key.authorityId(), key.keyId(), key.algorithm(),
                                key.encodedPublicKey(), key.notBefore(), key.notAfter(),
                                MirrorServingGenerationTrustProvider.KeyState.REVOKED));
        assertThatThrownBy(() -> integrity.verify(
                token, revoked, expectation(SCOPE, PURPOSE, DEPENDENCY), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revoked");

        VisualEvidenceSigner otherSigner = new InMemoryVisualEvidenceSigner();
        VisualEvidenceSigner.VerificationKey otherKey = otherSigner.key(
                otherSigner.descriptor().activeKeyId()).orElseThrow();
        MirrorServingGenerationTrustProvider wrong =
                MirrorServingGenerationTrustProvider.fixed(
                        new MirrorServingGenerationTrustProvider.AuthorityKey(
                                token.seal().authorityId(), token.seal().keyId(),
                                otherKey.algorithm(), otherKey.encodedPublicKey(),
                                NOW.minus(Duration.ofHours(1)),
                                NOW.plus(Duration.ofHours(2)),
                                MirrorServingGenerationTrustProvider.KeyState.ACTIVE));
        assertThatThrownBy(() -> integrity.verify(
                token, wrong, expectation(SCOPE, PURPOSE, DEPENDENCY), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void enforcesMonotonicPredecessorAndBoundedTokenWindows() {
        MirrorServingGenerationToken first = token(1, "", 7, DEPENDENCY);

        assertThatThrownBy(() -> material(2, "", 8, DEPENDENCY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("predecessor");
        assertThatThrownBy(() -> new MirrorServingGenerationToken.Material(
                "support-corpus", 2, first.tokenFingerprint(), SCOPE, PURPOSE,
                DEPENDENCY, 8, NOW, NOW.plus(Duration.ofHours(25)),
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new MirrorServingGenerationToken.Material(
                "support-corpus", 2, first.tokenFingerprint(), SCOPE, PURPOSE,
                DEPENDENCY, 8, NOW, NOW.plus(Duration.ofHours(1)),
                Duration.ofMinutes(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("staleness");
    }

    private MirrorServingGenerationToken token(
            long generation,
            String previous,
            long revocationCursor,
            String dependency) {
        return integrity.seal(
                material(generation, previous, revocationCursor, dependency),
                "corpus-authority-a",
                signer);
    }

    private static MirrorServingGenerationToken.Material material(
            long generation,
            String previous,
            long revocationCursor,
            String dependency) {
        return new MirrorServingGenerationToken.Material(
                "support-corpus", generation, previous, SCOPE, PURPOSE,
                dependency, revocationCursor, NOW, NOW.plus(Duration.ofHours(1)),
                Duration.ofSeconds(5));
    }

    private MirrorServingGenerationTrustProvider trust(
            MirrorServingGenerationToken token) {
        return MirrorServingGenerationTrustProvider.fixed(
                authorityKey(token, signer));
    }

    private static MirrorServingGenerationTrustProvider.AuthorityKey authorityKey(
            MirrorServingGenerationToken token,
            VisualEvidenceSigner source) {
        VisualEvidenceSigner.VerificationKey key = source.key(token.seal().keyId())
                .orElseThrow();
        return new MirrorServingGenerationTrustProvider.AuthorityKey(
                token.seal().authorityId(), key.keyId(), key.algorithm(),
                key.encodedPublicKey(), NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofHours(2)),
                MirrorServingGenerationTrustProvider.KeyState.ACTIVE);
    }

    private static MirrorServingGenerationIntegrity.Expectation expectation(
            CapabilitySnapshot.Scope scope,
            String purpose,
            String dependency) {
        return new MirrorServingGenerationIntegrity.Expectation(
                scope, purpose, dependency, NOW.plus(Duration.ofMinutes(30)));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
