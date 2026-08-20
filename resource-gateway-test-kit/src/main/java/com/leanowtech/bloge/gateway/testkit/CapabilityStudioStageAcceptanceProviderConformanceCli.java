package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Command-line entry point for producing an atomic Provider Conformance report. */
public final class CapabilityStudioStageAcceptanceProviderConformanceCli {
    /** Exit code for a conformant provider. */
    public static final int EXIT_CONFORMANT = 0;
    /** Exit code for usage, input, read, or output failure. */
    public static final int EXIT_INVALID = 2;
    /** Exit code for a non-conformant or blocked provider. */
    public static final int EXIT_NOT_CONFORMANT = 3;

    private static final int MAXIMUM_INPUT_BYTES =
            CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLI_CODE_PREFIX =
            "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_PROVIDER_CONFORMANCE_CLI.";

    private CapabilityStudioStageAcceptanceProviderConformanceCli() {
    }

    /**
     * Runs the CLI using the current clock and Java ServiceLoader.
     *
     * @param args exactly {@code --result PATH --output PATH}
     * @param out payload-free result stream
     * @param err reserved payload-free error stream
     * @return one of the public exit codes
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Instant.now(),
                CapabilityStudioStageAcceptanceProviderConformanceCli::providers);
    }

    /**
     * Process entry point.
     *
     * @param args exactly {@code --result PATH --output PATH}
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the CLI with injectable clock and provider source for deterministic tests.
     *
     * @param args exactly {@code --result PATH --output PATH}
     * @param out payload-free output stream
     * @param err reserved error stream
     * @param now trusted verification clock
     * @param providerSource provider discovery source, invoked only after local PASS
     * @return one of the public exit codes
     */
    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            Instant now,
            ProviderSource providerSource) {
        PrintStream safeOut = out == null ? System.out : out;
        if (now == null || !validArgs(args)) {
            printFailure(safeOut, "USAGE");
            return EXIT_INVALID;
        }
        byte[] input = readBounded(args[1]);
        if (input == null) {
            printFailure(safeOut, "READ");
            return EXIT_INVALID;
        }

        CapabilityStudioStageAcceptanceProviderConformance tck =
                new CapabilityStudioStageAcceptanceProviderConformance();
        CapabilityStudioStageAcceptanceProviderConformance.Result local;
        try {
            local = tck.verify(input, now, null);
        } catch (RuntimeException | ServiceConfigurationError | LinkageError failure) {
            printFailure(safeOut, "INPUT_INVALID");
            return EXIT_INVALID;
        }
        if (local.verdict()
                == CapabilityStudioStageAcceptanceProviderConformance.Verdict.INPUT_INVALID) {
            return writeReport(local, input, now, args[3], safeOut);
        }
        if (local.verdict()
                == CapabilityStudioStageAcceptanceProviderConformance.Verdict.NON_CONFORMANT) {
            return writeReport(local, input, now, args[3], safeOut);
        }

        CapabilityStudioStageAcceptanceAuthorityProvider provider = loadProvider(providerSource);
        CapabilityStudioStageAcceptanceProviderConformance.Result result;
        if (provider == null) {
            result = local;
        } else {
            try {
                result = tck.verify(input, now, provider);
            } catch (RuntimeException | ServiceConfigurationError | LinkageError providerFailure) {
                result = local;
            }
        }
        return writeReport(result, input, now, args[3], safeOut);
    }

    @FunctionalInterface
    interface ProviderSource {
        List<CapabilityStudioStageAcceptanceAuthorityProvider> load();
    }

    private static int writeReport(
            CapabilityStudioStageAcceptanceProviderConformance.Result result,
            byte[] sourceResult,
            Instant verificationTime,
            String outputPath,
            PrintStream out) {
        try {
            var report = CapabilityStudioStageAcceptanceProviderConformanceResultBuilder.build(result);
            byte[] bytes = JSON.writeValueAsBytes(report);
            var verification = new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier()
                    .verifyBound(bytes, sourceResult, verificationTime);
            if (!verification.verified()) {
                printFailure(out, "REPORT_INVALID");
                return EXIT_INVALID;
            }
            if (bytes.length > CapabilityStudioStageAcceptanceProviderConformanceResultVerifier
                    .MAXIMUM_REPORT_BYTES) {
                printFailure(out, "REPORT_SIZE_LIMIT");
                return EXIT_INVALID;
            }
            atomicCreate(Path.of(outputPath), bytes);
            String verdict = result.verdict().name();
            if (result.verdict()
                    == CapabilityStudioStageAcceptanceProviderConformance.Verdict.CONFORMANT) {
                out.println("CONFORMANT verdict=CONFORMANT checkCount=6 challengeCount="
                        + result.challengeCount() + " reportFingerprint="
                        + report.path("reportFingerprint").textValue());
                return EXIT_CONFORMANT;
            }
            out.println(verdict + " verdict=" + verdict + " reasonCode=" + result.reasonCode());
            return result.verdict()
                    == CapabilityStudioStageAcceptanceProviderConformance.Verdict.INPUT_INVALID
                    ? EXIT_INVALID : EXIT_NOT_CONFORMANT;
        } catch (IOException | RuntimeException failure) {
            printFailure(out, "WRITE");
            return EXIT_INVALID;
        }
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider loadProvider(
            ProviderSource source) {
        if (source == null) {
            return null;
        }
        try {
            List<CapabilityStudioStageAcceptanceAuthorityProvider> loaded = source.load();
            if (loaded == null || loaded.size() != 1 || loaded.getFirst() == null) {
                return null;
            }
            return loaded.getFirst();
        } catch (RuntimeException | ServiceConfigurationError | LinkageError failure) {
            return null;
        }
    }

    private static void atomicCreate(Path output, byte[] bytes) throws IOException {
        Path absolute = output.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("output directory is unavailable");
        }
        Path temporary = Files.createTempFile(parent, ".provider-conformance-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.createLink(absolute, temporary);
        } finally {
            Files.deleteIfExists(temporary);
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
        try (InputStream input = Files.newInputStream(Path.of(value))) {
            byte[] bytes = input.readNBytes(MAXIMUM_INPUT_BYTES + 1);
            return bytes.length <= MAXIMUM_INPUT_BYTES ? bytes : null;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static boolean validArgs(String[] args) {
        return args != null && args.length == 4
                && "--result".equals(args[0]) && !blank(args[1])
                && "--output".equals(args[2]) && !blank(args[3]);
    }

    private static void printFailure(PrintStream out, String suffix) {
        out.println("INPUT_INVALID verdict=INPUT_INVALID reasonCode=" + CLI_CODE_PREFIX + suffix);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
