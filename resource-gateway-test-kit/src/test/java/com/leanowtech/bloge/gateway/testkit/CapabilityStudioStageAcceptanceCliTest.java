package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioStageAcceptanceCliTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:12:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyAfterTheDeploymentProviderVerifiesEveryAuthority() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("pass.json", result.toString());
        Output output = new Output();

        int exit = run(artifact, List.of(acceptingProvider(result)), output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(output.text()).contains(
                "ACCEPTED outcome=ACCEPTED",
                CapabilityStudioStageAcceptanceAuthorityVerifier.CODE_PREFIX + "ACCEPTED");
        assertThat(output.text()).doesNotContain(
                "attestation:environment:1", "actor:correctness", "signature:correctness");
    }

    @Test
    void invalidProtocolAndHonestNonPassNeverLoadExternalProvider() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Output invalidOutput = new Output();
        int invalid = run(write("invalid.json", "{}"), () -> {
            loads.incrementAndGet();
            return List.of();
        }, invalidOutput);
        Output blockedOutput = new Output();
        int blocked = run(write("blocked.json", blockedResult().toString()), () -> {
            loads.incrementAndGet();
            return List.of();
        }, blockedOutput);

        assertThat(invalid).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(invalidOutput.text()).contains("outcome=PROTOCOL_INVALID");
        assertThat(blocked).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(blockedOutput.text()).contains("outcome=NOT_ACCEPTED", "STATUS_NOT_PASS");
        assertThat(loads).hasValue(0);
    }

    @Test
    void rejectsMissingOrAmbiguousProviderConfiguration() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("pass.json", result.toString());
        Output missingOutput = new Output();
        Output ambiguousOutput = new Output();

        int missing = run(artifact, List.of(), missingOutput);
        int ambiguous = run(artifact,
                List.of(acceptingProvider(result), acceptingProvider(result)), ambiguousOutput);

        assertThat(missing).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(ambiguous).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(missingOutput.text()).contains("PROVIDER_CONFIGURATION");
        assertThat(ambiguousOutput.text()).contains("PROVIDER_CONFIGURATION");
    }

    @Test
    void rejectsIncompleteOrFailingProviderWithoutLeakingException() throws Exception {
        String secret = "provider-secret-must-not-escape";
        ObjectNode result = passResult();
        Path artifact = write("pass.json", result.toString());
        Output incompleteOutput = new Output();
        Output failingOutput = new Output();
        CapabilityStudioStageAcceptanceAuthorityProvider incomplete = new Provider(
                null, (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());
        CapabilityStudioStageAcceptanceAuthorityProvider failing = new Provider(
                request -> {
                    throw new IllegalStateException(secret);
                }, (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());

        int incompleteExit = run(artifact, List.of(incomplete), incompleteOutput);
        int failingExit = run(artifact, List.of(failing), failingOutput);

        assertThat(incompleteExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(incompleteOutput.text()).contains("PROVIDER_CONFIGURATION");
        assertThat(failingExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(failingOutput.text()).contains("outcome=BLOCKED").doesNotContain(secret);
    }

    @Test
    void mapsDeterministicAuthorityRejectionToValidNotAccepted() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("pass.json", result.toString());
        Output output = new Output();
        Provider accepting = acceptingProvider(result);
        Provider rejecting = new Provider(
                accepting.resolver(),
                (reference, evidence, context) ->
                        CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                                .rejected("TEST_ISSUER_REJECTED"),
                accepting.owner());

        int exit = run(artifact, List.of(rejecting), output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(output.text()).contains("outcome=REJECTED", "TEST_ISSUER_REJECTED");
    }

    @Test
    void usageReadAndOversizeFailuresNeverEchoPaths() throws Exception {
        Output usage = new Output();
        int usageExit = CapabilityStudioStageAcceptanceCli.run(
                new String[0], usage.out(), usage.err(), NOW, List::of);
        String missingPath = temporaryDirectory.resolve("customer-secret.json").toString();
        Output missing = new Output();
        int missingExit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{missingPath}, missing.out(), missing.err(), NOW, List::of);
        Path oversized = temporaryDirectory.resolve("oversized-secret.json");
        Files.writeString(oversized, "x".repeat(
                CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES + 1));
        Output tooLarge = new Output();
        int tooLargeExit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{oversized.toString()}, tooLarge.out(), tooLarge.err(), NOW, List::of);

        assertThat(List.of(usageExit, missingExit, tooLargeExit))
                .containsOnly(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(usage.text()).contains("USAGE");
        assertThat(missing.text()).contains("READ").doesNotContain(missingPath);
        assertThat(tooLarge.text()).contains("READ").doesNotContain(oversized.toString());
    }

    @Test
    void providerLoaderFailureIsAStableConfigurationError() throws Exception {
        String secret = "loader-internal-secret";
        Output output = new Output();

        int exit = run(write("pass.json", passResult().toString()), () -> {
            throw new IllegalStateException(secret);
        }, output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(output.text()).contains("PROVIDER_CONFIGURATION").doesNotContain(secret);
    }

    @Test
    void isolatesProviderDiscoveryAccessorsAndAuthorityCallbacks() throws Exception {
        String secret = "capability-studio-provider-secret";
        ObjectNode result = passResult();
        Path artifact = write("noisy-provider.json", result.toString());
        Output output = new Output();
        ByteArrayOutputStream capturedOutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErrBytes = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream capturedOut = new PrintStream(capturedOutBytes, true, StandardCharsets.UTF_8);
        PrintStream capturedErr = new PrintStream(capturedErrBytes, true, StandardCharsets.UTF_8);

        try {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            int exit = run(artifact, () -> {
                System.out.println(secret + " discovery-out");
                System.err.println(secret + " discovery-err");
                return List.of(new NoisyProvider(result, secret));
            }, output);

            assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
            assertThat(output.text()).doesNotContain(secret);
            assertThat(capturedOutBytes.toString(StandardCharsets.UTF_8)).doesNotContain(secret);
            assertThat(capturedErrBytes.toString(StandardCharsets.UTF_8)).doesNotContain(secret);
            assertThat(System.out).isSameAs(capturedOut);
            assertThat(System.err).isSameAs(capturedErr);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertThat(System.out).isSameAs(originalOut);
        assertThat(System.err).isSameAs(originalErr);
    }

    @Test
    void restoresGlobalStreamsWithoutConvertingProviderErrors() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        AssertionError providerError = new AssertionError("provider-fatal-error");

        assertThatThrownBy(() -> run(write("provider-error.json", passResult().toString()), () -> {
            System.out.println("must-be-discarded");
            System.err.println("must-also-be-discarded");
            throw providerError;
        }, new Output())).isSameAs(providerError);

        assertThat(System.out).isSameAs(originalOut);
        assertThat(System.err).isSameAs(originalErr);
    }

    private int run(Path artifact,
                    List<CapabilityStudioStageAcceptanceAuthorityProvider> providers,
                    Output output) {
        return run(artifact, () -> providers, output);
    }

    private int run(Path artifact,
                    CapabilityStudioStageAcceptanceCli.ProviderSource providers,
                    Output output) {
        return CapabilityStudioStageAcceptanceCli.run(
                new String[]{artifact.toString()}, output.out(), output.err(), NOW, providers);
    }

    private Path write(String name, String value) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        Files.writeString(path, value);
        return path;
    }

    private static ObjectNode passResult() {
        return CapabilityStudioStageAcceptanceAuthorityVerifierTest.validStagePass();
    }

    private static ObjectNode blockedResult() {
        return new CapabilityStudioStageAcceptanceResultV2Builder(
                "SAR-cli-blocked", 1, "contract:cli", "1",
                new CapabilityStudioStageAcceptanceResultV2Builder.CandidateBuild(
                        "build:cli", "1", "abcdef1",
                        CapabilityStudioStageAcceptanceResultV2Builder.SourceTreeStatus.CLEAN,
                        fingerprint('5')),
                new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                        "baseline:cli", fingerprint('1')),
                new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                        "demo:cli", fingerprint('2')),
                fingerprint('4'), fingerprint('3'),
                CapabilityStudioStageAcceptanceResultV2Builder.ExecutionWindow.notStarted(
                        "2026-01-01T00:11:00Z"))
                .build();
    }

    private static Provider acceptingProvider(ObjectNode result) {
        String closure = result.path("evidenceClosureFingerprint").textValue();
        return new Provider(request -> EvidenceResolution.available(facts(request, closure)),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());
    }

    private static void printSecret(String secret, String phase) {
        System.out.println(secret + " " + phase + " out");
        System.err.println(secret + " " + phase + " err");
    }

    private static ResolvedEvidence facts(ResolutionRequest request, String closure) {
        if (request.kind() == ReferenceKind.SIGNATURE) {
            return new ResolvedEvidence(request.coordinate(), EvidenceKind.OWNER_SIGNATURE,
                    "issuer:owner-authority", "tenant:demo/environment:acceptance",
                    fingerprint('5'), fingerprint('4'), fingerprint('3'), null, null, closure,
                    "key:owner:1", "Ed25519", fingerprint('6'),
                    instant("00:07:00"), instant("00:30:00"), "c2lnbmF0dXJl");
        }
        if (request.key().equals("environment")) {
            return new ResolvedEvidence(request.coordinate(), EvidenceKind.ENVIRONMENT_ATTESTATION,
                    "issuer:deployment-control-plane", "tenant:demo/environment:acceptance",
                    fingerprint('5'), null, fingerprint('3'),
                    instant("00:00:00"), instant("00:30:00"), null,
                    "key:environment:1", "Ed25519", fingerprint('6'),
                    instant("00:00:00"), instant("00:30:00"), "c2lnbmF0dXJl");
        }
        if (request.key().equals("egress")) {
            return new ResolvedEvidence(request.coordinate(),
                    EvidenceKind.DEPLOYMENT_EGRESS_OBSERVATION,
                    "issuer:network-observer", "tenant:demo/environment:acceptance",
                    null, fingerprint('4'), null,
                    instant("00:00:00"), instant("00:05:00"), null,
                    "key:egress:1", "Ed25519", fingerprint('6'),
                    instant("00:05:00"), instant("00:30:00"), "c2lnbmF0dXJl");
        }
        return new ResolvedEvidence(request.coordinate(), EvidenceKind.ACCEPTANCE_EVIDENCE,
                "issuer:acceptance-evidence", "tenant:demo/environment:acceptance",
                fingerprint('5'), fingerprint('4'), fingerprint('3'),
                instant("00:00:00"), instant("00:05:00"), closure,
                "key:acceptance:1", "Ed25519", fingerprint('6'),
                instant("00:05:00"), instant("00:30:00"), "c2lnbmF0dXJl");
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision verified() {
        return CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.verified();
    }

    private static Instant instant(String time) {
        return Instant.parse("2026-01-01T" + time + "Z");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private record Provider(
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuer,
            CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority owner)
            implements CapabilityStudioStageAcceptanceAuthorityProvider {
        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver() {
            return resolver;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                evidenceIssuerPolicy() {
            return issuer;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
            return owner;
        }
    }

    private static final class NoisyProvider
            implements CapabilityStudioStageAcceptanceAuthorityProvider {
        private final ObjectNode result;
        private final String secret;

        private NoisyProvider(ObjectNode result, String secret) {
            this.result = result;
            this.secret = secret;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver() {
            printSecret(secret, "resolver-accessor");
            String closure = result.path("evidenceClosureFingerprint").textValue();
            return request -> {
                printSecret(secret, "resolver-callback");
                return EvidenceResolution.available(facts(request, closure));
            };
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                evidenceIssuerPolicy() {
            printSecret(secret, "issuer-accessor");
            return (reference, evidence, context) -> {
                printSecret(secret, "issuer-callback");
                return verified();
            };
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
            printSecret(secret, "owner-accessor");
            return (signoff, signature, context) -> {
                printSecret(secret, "owner-callback");
                return verified();
            };
        }
    }

    private static final class Output {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        PrintStream out() {
            return stream;
        }

        PrintStream err() {
            return stream;
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
