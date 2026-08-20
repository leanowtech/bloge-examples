package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Fail-closed command-line verifier for formal Capability Studio Stage Acceptance Result v2.
 *
 * <p>The CLI accepts one result path and discovers exactly one deployment-owned
 * {@link CapabilityStudioStageAcceptanceAuthorityProvider}. It validates the local protocol before
 * loading that provider, so malformed and non-{@code PASS} results make no external authority
 * calls. The result document cannot carry or select its own trust provider.</p>
 */
public final class CapabilityStudioStageAcceptanceCli {
    /** Exit code for a formally accepted result. */
    public static final int EXIT_ACCEPTED = 0;
    /** Exit code for usage, read, protocol, or provider configuration failure. */
    public static final int EXIT_INVALID = 2;
    /** Exit code for a valid result that is blocked, rejected, or not declared PASS. */
    public static final int EXIT_NOT_ACCEPTED = 3;

    private static final String CODE_PREFIX = "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.";
    private static final ObjectMapper JSON = new ObjectMapper();

    private CapabilityStudioStageAcceptanceCli() {
    }

    /**
     * Revalidates one result with an independently deployed authority provider.
     *
     * @param args exactly one Stage Acceptance Result v2 path
     * @param out payload-free result stream
     * @param err reserved payload-free error stream
     * @return {@link #EXIT_ACCEPTED}, {@link #EXIT_INVALID}, or {@link #EXIT_NOT_ACCEPTED}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Instant.now(), CapabilityStudioStageAcceptanceCli::providers);
    }

    /**
     * Standard process entry point.
     *
     * @param args exactly one Stage Acceptance Result v2 path
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            Instant now,
            ProviderSource providerSource) {
        PrintStream safeOut = out == null ? System.out : out;
        Objects.requireNonNull(now, "now is required");
        if (args == null || args.length != 1 || blank(args[0])) {
            invalid(safeOut, "USAGE");
            return EXIT_INVALID;
        }

        byte[] wire = readBounded(args[0]);
        if (wire == null) {
            invalid(safeOut, "READ");
            return EXIT_INVALID;
        }

        CapabilityStudioStageAcceptanceResultV2Verifier semanticVerifier =
                new CapabilityStudioStageAcceptanceResultV2Verifier();
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult semantic;
        try {
            semantic = semanticVerifier.verify(wire, now);
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROTOCOL_INVALID");
            return EXIT_INVALID;
        }
        if (!semantic.verified()) {
            safeOut.println("INVALID outcome=PROTOCOL_INVALID reasonCode="
                    + safeCode(semantic.errorCode(), CODE_PREFIX + "PROTOCOL_INVALID"));
            return EXIT_INVALID;
        }

        JsonNode result;
        try {
            result = JSON.readTree(wire);
        } catch (IOException | RuntimeException failure) {
            invalid(safeOut, "PROTOCOL_INVALID");
            return EXIT_INVALID;
        }
        if (!"PASS".equals(result.path("status").textValue())) {
            safeOut.println("NOT_ACCEPTED outcome=NOT_ACCEPTED reasonCode="
                    + CapabilityStudioStageAcceptanceAuthorityVerifier.CODE_PREFIX
                    + "STATUS_NOT_PASS");
            return EXIT_NOT_ACCEPTED;
        }

        CapabilityStudioStageAcceptanceAuthorityProvider provider;
        try {
            provider = CapabilityStudioProviderOutputIsolation.call(
                    () -> loadProvider(providerSource));
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }
        if (provider == null) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }

        CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver;
        CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuerPolicy;
        CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority;
        try {
            resolver = CapabilityStudioProviderOutputIsolation.call(
                    () -> Objects.requireNonNull(
                            provider.evidenceResolver(), "evidenceResolver is required"));
            issuerPolicy = CapabilityStudioProviderOutputIsolation.call(
                    () -> Objects.requireNonNull(
                            provider.evidenceIssuerPolicy(), "evidenceIssuerPolicy is required"));
            ownerAuthority = CapabilityStudioProviderOutputIsolation.call(
                    () -> Objects.requireNonNull(
                            provider.ownerAuthority(), "ownerAuthority is required"));
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }

        CapabilityStudioStageAcceptanceAuthorityVerifier.VerificationResult verification;
        try {
            verification = CapabilityStudioProviderOutputIsolation.call(
                    () -> new CapabilityStudioStageAcceptanceAuthorityVerifier().verify(
                            result, now, resolver, issuerPolicy, ownerAuthority));
        } catch (RuntimeException failure) {
            safeOut.println("NOT_ACCEPTED outcome=BLOCKED reasonCode="
                    + CapabilityStudioStageAcceptanceAuthorityVerifier.CODE_PREFIX
                    + "AUTHORITY_UNAVAILABLE");
            return EXIT_NOT_ACCEPTED;
        }
        if (verification.accepted()) {
            safeOut.println("ACCEPTED outcome=ACCEPTED reasonCode="
                    + safeCode(verification.reasonCode(),
                    CapabilityStudioStageAcceptanceAuthorityVerifier.CODE_PREFIX + "ACCEPTED"));
            return EXIT_ACCEPTED;
        }
        safeOut.println("NOT_ACCEPTED outcome=" + verification.outcome()
                + " reasonCode=" + safeCode(verification.reasonCode(),
                CapabilityStudioStageAcceptanceAuthorityVerifier.CODE_PREFIX
                        + "AUTHORITY_REJECTED"));
        return verification.outcome()
                == CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.PROTOCOL_INVALID
                ? EXIT_INVALID : EXIT_NOT_ACCEPTED;
    }

    @FunctionalInterface
    interface ProviderSource {
        List<CapabilityStudioStageAcceptanceAuthorityProvider> load();
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider loadProvider(
            ProviderSource source) {
        if (source == null) {
            return null;
        }
        try {
            List<CapabilityStudioStageAcceptanceAuthorityProvider> providers = source.load();
            if (providers == null || providers.size() != 1 || providers.getFirst() == null) {
                return null;
            }
            return providers.getFirst();
        } catch (RuntimeException | ServiceConfigurationError failure) {
            return null;
        }
    }

    private static List<CapabilityStudioStageAcceptanceAuthorityProvider> providers() {
        List<ServiceLoader.Provider<CapabilityStudioStageAcceptanceAuthorityProvider>> discovered =
                ServiceLoader.load(CapabilityStudioStageAcceptanceAuthorityProvider.class)
                        .stream().limit(2).toList();
        if (discovered.size() != 1) {
            return List.of();
        }
        return List.of(discovered.getFirst().get());
    }

    private static byte[] readBounded(String value) {
        try {
            Path path = Path.of(value);
            if (Files.size(path)
                    > CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES) {
                return null;
            }
            byte[] wire = Files.readAllBytes(path);
            return wire.length
                    <= CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES
                    ? wire : null;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static void invalid(PrintStream out, String suffix) {
        out.println("INVALID errorCode=" + CODE_PREFIX + suffix);
    }

    private static String safeCode(String value, String fallback) {
        return value != null && value.matches("[A-Z][A-Z0-9_.-]{0,254}") ? value : fallback;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
