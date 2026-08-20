package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioStageAcceptanceProviderConformanceCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:12:00Z");

    @TempDir
    Path temp;

    @Test
    void conformantWritesReportAndInvalidOrNonPassDoNotLoadProvider() throws Exception {
        ObjectNode pass = CapabilityStudioStageAcceptanceAuthorityVerifierTest.validStagePass();
        Path input = write("pass.json", pass.toString());
        AtomicInteger loads = new AtomicInteger();
        Path output = temp.resolve("report.json");
        Output success = new Output();
        int accepted = run(input, output, () -> {
            loads.incrementAndGet();
            return List.of(provider(pass));
        }, success);

        assertThat(accepted).isZero();
        assertThat(loads).hasValue(1);
        assertThat(success.text()).startsWith("CONFORMANT verdict=CONFORMANT checkCount=6 challengeCount=");
        assertThat(new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier()
                .verify(Files.readAllBytes(output)).verified()).isTrue();

        AtomicInteger skipped = new AtomicInteger();
        Output invalidOut = new Output();
        assertThat(run(write("invalid.json", "{}"), temp.resolve("invalid-report.json"), () -> {
            skipped.incrementAndGet();
            return List.of(provider(pass));
        }, invalidOut)).isEqualTo(2);
        ObjectNode nonPass = pass.deepCopy();
        nonPass.put("status", "FAIL");
        ((ObjectNode) nonPass.path("acceptanceChecks").get(0)).put("status", "FAIL")
                .putArray("evidenceIds");
        nonPass.putArray("diagnostics").addObject().put("code", "ACCEPTANCE_CHECK_FAILED");
        nonPass.put("evidenceClosureFingerprint",
                CapabilityStudioStageAcceptanceResultV2Verifier.closureFingerprint(nonPass));
        for (JsonNode signoff : nonPass.path("signoffs")) {
            ((ObjectNode) signoff).put("evidenceClosureFingerprint",
                    nonPass.path("evidenceClosureFingerprint").asText());
        }
        assertThat(run(write("non-pass.json", nonPass.toString()),
                temp.resolve("non-pass-report.json"), () -> {
                    skipped.incrementAndGet();
                    return List.of(provider(pass));
                }, new Output())).isEqualTo(3);
        assertThat(skipped).hasValue(0);
    }

    @Test
    void missingDuplicateLoaderFailureAndProviderFailureAreBlockedAndOutputIsCreateNew()
            throws Exception {
        ObjectNode pass = CapabilityStudioStageAcceptanceAuthorityVerifierTest.validStagePass();
        Path input = write("pass.json", pass.toString());
        List<List<CapabilityStudioStageAcceptanceAuthorityProvider>> providerSets = List.of(
                List.<CapabilityStudioStageAcceptanceAuthorityProvider>of(),
                List.of(provider(pass), provider(pass)));
        for (List<CapabilityStudioStageAcceptanceAuthorityProvider> providers : providerSets) {
            Path output = temp.resolve("blocked-" + providers.size() + ".json");
            assertThat(run(input, output, () -> providers, new Output())).isEqualTo(3);
            assertThat(new ObjectMapper().readTree(Files.readAllBytes(output)).path("verdict").asText())
                    .isEqualTo("BLOCKED");
        }
        Path existing = temp.resolve("existing.json");
        Files.writeString(existing, "keep", StandardCharsets.UTF_8);
        assertThat(run(input, existing, () -> List.of(provider(pass)), new Output())).isEqualTo(2);
        assertThat(Files.readString(existing)).isEqualTo("keep");
        assertThat(run(input, temp.resolve("loader.json"), () -> {
            throw new IllegalStateException("secret-loader");
        }, new Output())).isEqualTo(3);
        assertThat(run(input, temp.resolve("provider-error.json"), () -> List.of(new Provider(
                request -> { throw new IllegalStateException("secret-provider"); },
                (reference, evidence, context) -> CapabilityStudioStageAcceptanceAuthorityVerifier
                        .AuthorityDecision.verified(),
                (signoff, signature, context) -> CapabilityStudioStageAcceptanceAuthorityVerifier
                        .AuthorityDecision.verified())), new Output())).isEqualTo(3);
    }

    @Test
    void rejectsUsageOversizeAndWriteFailuresWithoutLeakingInputOrProviderMessages()
            throws Exception {
        Output usage = new Output();
        assertThat(CapabilityStudioStageAcceptanceProviderConformanceCli.run(
                new String[0], usage.stream(), usage.stream(), NOW, List::of)).isEqualTo(2);

        Path oversized = temp.resolve("oversized.json");
        Files.write(oversized, new byte[
                CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES + 1]);
        Output size = new Output();
        assertThat(run(oversized, temp.resolve("oversized-report.json"), List::of, size))
                .isEqualTo(2);

        ObjectNode pass = CapabilityStudioStageAcceptanceAuthorityVerifierTest.validStagePass();
        Path input = write("secret-input.json", pass.toString());
        Output providerError = new Output();
        assertThat(run(input, temp.resolve("provider-error-redacted.json"), () -> {
            throw new IllegalStateException("secret-loader-message");
        }, providerError)).isEqualTo(3);
        assertThat(providerError.text()).doesNotContain(
                "secret-loader-message", "attestation:environment:1", "actor:correctness");

        assertThat(usage.text()).contains("PROVIDER_CONFORMANCE_CLI.USAGE");
        assertThat(size.text()).contains("PROVIDER_CONFORMANCE_CLI.READ");
    }

    private int run(Path input, Path output,
                    CapabilityStudioStageAcceptanceProviderConformanceCli.ProviderSource source,
                    Output out) {
        return CapabilityStudioStageAcceptanceProviderConformanceCli.run(
                new String[]{"--result", input.toString(), "--output", output.toString()},
                out.stream(), out.stream(), NOW, source);
    }

    private Path write(String name, String value) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private static Provider provider(ObjectNode input) {
        Set<CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate> coordinates =
                new HashSet<>();
        for (JsonNode value : input.path("evidenceRefs")) {
            coordinates.add(new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                    value.path("exactRef").asText(), value.path("fingerprint").asText()));
        }
        for (JsonNode value : input.path("signoffs")) {
            coordinates.add(new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                    value.path("signatureRef").path("exactRef").asText(),
                    value.path("signatureRef").path("fingerprint").asText()));
        }
        String closure = input.path("evidenceClosureFingerprint").asText();
        return new Provider(request -> coordinates.contains(request.coordinate())
                ? CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution.available(
                        facts(request, closure))
                : CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution.notFound(),
                (reference, evidence, context) -> evidence.materialFingerprint().equals(fp('6'))
                        ? CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.verified()
                        : CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.rejected(),
                (signoff, signature, context) -> signoff.evidenceClosureFingerprint()
                        .equals(context.evidenceClosureFingerprint())
                        && signature.evidenceClosureFingerprint()
                        .equals(context.evidenceClosureFingerprint())
                        ? CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.verified()
                        : CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.rejected());
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence facts(
            CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest request,
            String closure) {
        if (request.kind() == CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind.SIGNATURE) {
            return new CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence(
                    request.coordinate(), CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind.OWNER_SIGNATURE,
                    "issuer:owner-authority", "tenant:demo/environment:acceptance", fp('5'), fp('4'), fp('3'),
                    null, null, closure, "key:owner:1", "Ed25519", fp('6'), instant("00:07:00"),
                    instant("00:30:00"), "c2lnbmF0dXJl");
        }
        if (request.key().equals("environment")) {
            return new CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence(
                    request.coordinate(), CapabilityStudioStageAcceptanceAuthorityVerifier
                    .EvidenceKind.ENVIRONMENT_ATTESTATION,
                    "issuer:deployment-control-plane", "tenant:demo/environment:acceptance",
                    fp('5'), null, fp('3'), instant("00:00:00"), instant("00:30:00"), null,
                    "key:environment:1", "Ed25519", fp('6'), instant("00:00:00"),
                    instant("00:30:00"), "c2lnbmF0dXJl");
        }
        if (request.key().equals("egress")) {
            return new CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence(
                    request.coordinate(), CapabilityStudioStageAcceptanceAuthorityVerifier
                    .EvidenceKind.DEPLOYMENT_EGRESS_OBSERVATION,
                    "issuer:network-observer", "tenant:demo/environment:acceptance", null,
                    fp('4'), null, instant("00:00:00"), instant("00:05:00"), null,
                    "key:egress:1", "Ed25519", fp('6'), instant("00:05:00"),
                    instant("00:30:00"), "c2lnbmF0dXJl");
        }
        return new CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence(
                request.coordinate(), CapabilityStudioStageAcceptanceAuthorityVerifier
                .EvidenceKind.ACCEPTANCE_EVIDENCE,
                "issuer:acceptance-evidence", "tenant:demo/environment:acceptance", fp('5'), fp('4'), fp('3'),
                instant("00:00:00"), instant("00:05:00"), null, "key:acceptance:1", "Ed25519", fp('6'),
                instant("00:05:00"), instant("00:30:00"), "c2lnbmF0dXJl");
    }

    private static Instant instant(String time) { return Instant.parse("2026-01-01T" + time + "Z"); }
    private static String fp(char value) { return "sha256:" + String.valueOf(value).repeat(64); }

    private record Provider(
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuer,
            CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority owner)
            implements CapabilityStudioStageAcceptanceAuthorityProvider {
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver() {
            return resolver;
        }
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy evidenceIssuerPolicy() {
            return issuer;
        }
        public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
            return owner;
        }
    }

    private static final class Output {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private PrintStream stream() { return new PrintStream(bytes, true, StandardCharsets.UTF_8); }
        private String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }
}
