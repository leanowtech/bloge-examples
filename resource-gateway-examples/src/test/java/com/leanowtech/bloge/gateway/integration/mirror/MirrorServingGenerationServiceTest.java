package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorServingGenerationServiceTest {
    private static final Instant NOW =
            Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "staging", "sg");
    private static final String PURPOSE = "MIRROR_REHEARSAL";

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final VisualEvidenceSigner signer =
            new InMemoryVisualEvidenceSigner();
    private final MirrorServingGenerationIntegrity integrity =
            new MirrorServingGenerationIntegrity(mapper);
    private final MutableAuthority authority = new MutableAuthority();
    private final ResolvedCorpusPayloads payloads = payloads();

    @AfterEach
    void closePayloads() {
        payloads.close();
    }

    @Test
    void bindsTheExactPayloadFreeDependencyClosureToOneSignedToken() {
        MirrorServingGenerationService service = service();

        ResolvedCorpusPayloads bound = service.bind(
                payloads, SCOPE, PURPOSE, NOW.plus(Duration.ofMinutes(30)));

        assertThat(bound.servingGenerationToken()).isPresent().get()
                .satisfies(token -> {
                    assertThat(token.material().dependencyClosureFingerprint())
                            .isEqualTo(authority.lastRequest
                                    .dependencyClosureFingerprint());
                    assertThat(token.material().scope()).isEqualTo(SCOPE);
                    assertThat(token.material().authorizedPurpose())
                            .isEqualTo(PURPOSE);
                });
        assertThat(bound.generationDependencies()).singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.artifactRefs()).contains(
                            ref("SANITIZED_PAYLOAD", "response", '4'));
                    assertThat(dependency.ruleRefs())
                            .containsExactly("observation-a");
                });
        bound.admitRun();
    }

    @Test
    void rejectsAuthorityOutageRejectionAndWrongTokenCoordinates() {
        MirrorServingGenerationService service = service();
        authority.outcome = MirrorServingGenerationAuthority.Outcome.UNAVAILABLE;
        assertCode(
                () -> service.bind(
                        payloads, SCOPE, PURPOSE,
                        NOW.plus(Duration.ofMinutes(30))),
                "SERVING_GENERATION_AUTHORITY_UNAVAILABLE");

        authority.outcome = MirrorServingGenerationAuthority.Outcome.REJECTED;
        assertCode(
                () -> service.bind(
                        payloads, SCOPE, PURPOSE,
                        NOW.plus(Duration.ofMinutes(30))),
                "SERVING_GENERATION_REJECTED");

        authority.outcome = MirrorServingGenerationAuthority.Outcome.CURRENT;
        authority.wrongScope = true;
        assertCode(
                () -> service.bind(
                        payloads, SCOPE, PURPOSE,
                        NOW.plus(Duration.ofMinutes(30))),
                "SERVING_GENERATION_TOKEN_INVALID");
        assertThat(payloads.lifecycle().state())
                .isEqualTo(ResolvedCorpusPayloads.GenerationState.OPEN);
    }

    @Test
    void reportsReadinessOnlyWhenAuthorityAndPinnedTrustAreAvailable() {
        MirrorServingGenerationService service = service();
        assertThat(service.ready()).isTrue();

        authority.available = false;
        assertThat(service.ready()).isFalse();
        assertThat(new MirrorServingGenerationService(
                authority, MirrorServingGenerationTrustProvider.unavailable(),
                integrity, mapper, Clock.fixed(NOW, ZoneOffset.UTC)).ready())
                .isFalse();
    }

    @Test
    void reportsTrustDistributionOutageAsAuthorityUnavailable() {
        MirrorServingGenerationService service =
                new MirrorServingGenerationService(
                        authority,
                        MirrorServingGenerationTrustProvider.unavailable(),
                        integrity, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

        assertCode(
                () -> service.bind(
                        payloads, SCOPE, PURPOSE,
                        NOW.plus(Duration.ofMinutes(30))),
                "SERVING_GENERATION_AUTHORITY_UNAVAILABLE");
    }

    private MirrorServingGenerationService service() {
        VisualEvidenceSigner.VerificationKey key = signer.key(
                signer.descriptor().activeKeyId()).orElseThrow();
        MirrorServingGenerationTrustProvider trust =
                MirrorServingGenerationTrustProvider.fixed(
                        new MirrorServingGenerationTrustProvider.AuthorityKey(
                                "corpus-authority-a", key.keyId(),
                                key.algorithm(), key.encodedPublicKey(),
                                NOW.minus(Duration.ofHours(1)),
                                NOW.plus(Duration.ofHours(2)),
                                MirrorServingGenerationTrustProvider.KeyState.ACTIVE));
        return new MirrorServingGenerationService(
                authority, trust, integrity, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ResolvedCorpusPayloads payloads() {
        ResolvedCorpusPayloads.Sample sample =
                ResolvedCorpusPayloads.Sample.response(
                        fingerprint('1'),
                        "{\"customerId\":\"C-1\"}".getBytes(
                                StandardCharsets.UTF_8),
                        List.of(
                                ref("CAPABILITY_CORPUS_PUBLICATION",
                                        "customer-publication", '2'),
                                ref("CAPABILITY_CORPUS_REVISION",
                                        "customer-corpus", '3'),
                                ref("SANITIZED_PAYLOAD", "response", '4')),
                        List.of("observation-a"),
                        1,
                        List.of());
        return ResolvedCorpusPayloads.of(List.of(
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        ref("CAPABILITY", "customer.lookup", '1'),
                        ref("CAPABILITY_CORPUS_PUBLICATION",
                                "customer-publication", '2'),
                        ref("CAPABILITY_CORPUS_REVISION",
                                "customer-corpus", '3'),
                        NOW,
                        NOW.plus(Duration.ofHours(1)),
                        List.of(sample))));
    }

    private static void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(
                        MirrorServingGenerationService.AdmissionException.class)
                .extracting(failure ->
                        ((MirrorServingGenerationService.AdmissionException)
                                failure).code())
                .isEqualTo(code);
    }

    private static MirrorArtifactRef ref(
            String kind, String id, char fingerprint) {
        return new MirrorArtifactRef(
                kind, id, 1, fingerprint(fingerprint));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private final class MutableAuthority
            implements MirrorServingGenerationAuthority {
        private boolean available = true;
        private Outcome outcome = Outcome.CURRENT;
        private boolean wrongScope;
        private AdmissionRequest lastRequest;
        private MirrorServingGenerationToken current;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Resolution admit(AdmissionRequest request) {
            lastRequest = request;
            if (outcome == Outcome.UNAVAILABLE) {
                return Resolution.unavailable("AUTHORITY_UNAVAILABLE");
            }
            if (outcome == Outcome.REJECTED) {
                return Resolution.rejected("DEPENDENCY_NOT_CURRENT");
            }
            CapabilitySnapshot.Scope tokenScope = wrongScope
                    ? new CapabilitySnapshot.Scope(
                    "tenant-b", "org-a", "support", "staging", "sg")
                    : request.scope();
            current = integrity.seal(
                    new MirrorServingGenerationToken.Material(
                            "support-corpus", 1, "", tokenScope,
                            request.authorizedPurpose(),
                            request.dependencyClosureFingerprint(),
                            9, NOW, NOW.plus(Duration.ofHours(1)),
                            Duration.ofSeconds(5)),
                    "corpus-authority-a", signer);
            return Resolution.current(current);
        }

        @Override
        public Resolution currentFloor(FloorRequest request) {
            return available && current != null
                    ? Resolution.current(current)
                    : Resolution.unavailable("AUTHORITY_UNAVAILABLE");
        }
    }
}
