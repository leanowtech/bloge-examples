package com.leanowtech.bloge.gateway.testkit;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fail-closed command-line adapter for executing one exact governed suite in CI.
 *
 * <p>The bearer credential is accepted only from {@code RESOURCE_GATEWAY_TOKEN}; command-line
 * token arguments are intentionally unsupported to keep secrets out of process listings. A stable
 * client request id is mandatory so infrastructure retries cannot silently execute side effects
 * twice. Standard and pure-DSL mutation suites use distinct endpoints and scheduling strategies;
 * callers must select mutation mode explicitly.</p>
 */
public final class ResourceGatewaySuiteCli {

    private static final String TOKEN_ENV = "RESOURCE_GATEWAY_TOKEN";
    private static final int CONFIGURATION_ERROR = 2;

    private ResourceGatewaySuiteCli() {
    }

    /**
     * Runs the command and exits with zero only when the configured suite gate passes.
     *
     * @param args command-line options
     */
    public static void main(String[] args) {
        int exitCode = run(args, System.getenv(), System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Executes the CLI without terminating the current JVM, enabling build-tool and test reuse.
     *
     * @param args command-line options
     * @param environment process environment; the token is read only from this map
     * @param output payload-free normal output
     * @param error payload-free error output
     * @return zero for a passing gate, one for failed evidence, or two for adapter/config failure
     */
    public static int run(String[] args, Map<String, String> environment,
                          PrintStream output, PrintStream error) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        PrintStream safeOutput = output == null ? System.out : output;
        PrintStream safeError = error == null ? System.err : error;
        CliOptions options;
        try {
            options = CliOptions.parse(args, env);
        } catch (IllegalArgumentException failure) {
            safeError.println("Resource Gateway suite configuration error: " + safe(failure.getMessage(), 1024));
            return CONFIGURATION_ERROR;
        }

        try {
            ResourceGatewayTestClient client = ResourceGatewayTestClient.builder(options.baseUri())
                    .bearerToken(() -> options.token())
                    .requestTimeout(options.timeout())
                    .build();
            TestSuiteRun run = options.execute(client);
            if (run.status() == TestSuiteRun.Status.RUNNING) {
                writeInfrastructureFailure(options, "RG.TESTKIT.SUITE_NON_TERMINAL",
                        "The suite returned a non-terminal checkpoint; no gate verdict is available.",
                        safeError);
                return CONFIGURATION_ERROR;
            }
            JUnitXmlReportWriter.Report report = JUnitXmlReportWriter.writeSuite(
                    options.report(), run, options.requirePromotionEligible());
            safeOutput.println("suiteRunId=" + safe(run.suiteRunId(), 256)
                    + "; evaluationMode=" + run.evaluationMode()
                    + "; status=" + run.status()
                    + "; coverage=" + run.coverageStatus()
                    + "; admissionCoverage=" + run.admissionCoverage()
                    .map(value -> value.status().name()).orElse("NOT_APPLICABLE")
                    + "; promotion=" + run.promotionStatus()
                    + "; cases=" + run.caseResults().size()
                    + mutationSummary(run)
                    + "; report=" + options.report().toAbsolutePath());
            return report.exitCode();
        } catch (ResourceGatewayTestException failure) {
            writeInfrastructureFailure(options, failure.code(), failure.title(), safeError);
            return CONFIGURATION_ERROR;
        } catch (IOException failure) {
            writeInfrastructureFailure(options, "RG.TESTKIT.REPORT_WRITE_FAILED",
                    "The CI report could not be written.", safeError);
            return CONFIGURATION_ERROR;
        } catch (RuntimeException failure) {
            writeInfrastructureFailure(options, "RG.TESTKIT.SUITE_ADAPTER_FAILED",
                    "The governed suite adapter failed before a gate result was available.", safeError);
            return CONFIGURATION_ERROR;
        }
    }

    private static void writeInfrastructureFailure(CliOptions options, String code, String summary,
                                                   PrintStream error) {
        String safeCode = safe(code, 255);
        String safeSummary = safe(summary, 512);
        error.println(safeCode + ": " + safeSummary);
        try {
            JUnitXmlReportWriter.writeInfrastructureFailure(options.report(),
                    "resource-gateway suite " + options.suiteId(), safeCode, safeSummary);
        } catch (IOException ignored) {
            error.println("RG.TESTKIT.REPORT_WRITE_FAILED: The infrastructure failure report could not be written.");
        }
    }

    private static String safe(String value, int maximum) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static String mutationSummary(TestSuiteRun run) {
        return run.mutationScore().map(score -> "; mutationBaseline="
                + run.mutationBaselineStatus().map(Enum::name).orElse("UNAVAILABLE")
                + "; mutants=" + score.plannedMutants()
                + "; mutationScore=" + score.scoreBasisPoints()
                + "; mutationScoreStatus=" + score.status()).orElse("");
    }

    private enum RunMode {
        STANDARD,
        MUTATION
    }

    private record CliOptions(
            URI baseUri,
            String token,
            String suiteId,
            long revision,
            String fingerprint,
            String clientRequestId,
            RunMode mode,
            String strategy,
            Path report,
            Duration timeout,
            boolean requirePromotionEligible
    ) {
        private TestSuiteRun execute(ResourceGatewayTestClient client) {
            Map<String, String> metadata = Map.of("source", "resource-gateway-suite-cli");
            if (mode == RunMode.MUTATION) {
                ResourceGatewayTestClient.MutationStrategy mutationStrategy = enumValue(
                        ResourceGatewayTestClient.MutationStrategy.class, strategy, "strategy");
                return client.executeMutationSuite(suiteId, revision, fingerprint,
                        clientRequestId, mutationStrategy, metadata);
            }
            ResourceGatewayTestClient.SuiteStrategy suiteStrategy = enumValue(
                    ResourceGatewayTestClient.SuiteStrategy.class, strategy, "strategy");
            return client.executeSuite(suiteId, revision, fingerprint, clientRequestId,
                    suiteStrategy, metadata);
        }

        private static CliOptions parse(String[] args, Map<String, String> environment) {
            Map<String, String> options = parseArguments(args);
            List<String> missing = new ArrayList<>();
            String baseUri = value(options, "base-uri", environment, "RESOURCE_GATEWAY_BASE_URI");
            String token = normalized(environment.get(TOKEN_ENV));
            String suiteId = value(options, "suite-id", environment, "RESOURCE_GATEWAY_SUITE_ID");
            String revision = value(options, "revision", environment, "RESOURCE_GATEWAY_SUITE_REVISION");
            String fingerprint = value(options, "fingerprint", environment,
                    "RESOURCE_GATEWAY_SUITE_FINGERPRINT");
            String clientRequestId = value(options, "client-request-id", environment,
                    "RESOURCE_GATEWAY_CLIENT_REQUEST_ID");
            required(baseUri, "--base-uri or RESOURCE_GATEWAY_BASE_URI", missing);
            required(token, TOKEN_ENV, missing);
            required(suiteId, "--suite-id or RESOURCE_GATEWAY_SUITE_ID", missing);
            required(revision, "--revision or RESOURCE_GATEWAY_SUITE_REVISION", missing);
            required(fingerprint, "--fingerprint or RESOURCE_GATEWAY_SUITE_FINGERPRINT", missing);
            required(clientRequestId, "--client-request-id or RESOURCE_GATEWAY_CLIENT_REQUEST_ID", missing);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("Missing required settings: " + String.join(", ", missing));
            }
            long parsedRevision = positiveLong(revision, "revision");
            long timeoutSeconds = positiveLong(value(options, "timeout-seconds", environment,
                    "RESOURCE_GATEWAY_TIMEOUT_SECONDS", "60"), "timeout-seconds");
            RunMode mode = enumValue(RunMode.class,
                    value(options, "mode", environment, "RESOURCE_GATEWAY_SUITE_MODE", "STANDARD"),
                    "mode");
            String strategy = value(options, "strategy", environment,
                    "RESOURCE_GATEWAY_SUITE_STRATEGY", "COLLECT_ALL");
            if (mode == RunMode.MUTATION) {
                strategy = enumValue(ResourceGatewayTestClient.MutationStrategy.class,
                        strategy, "strategy").name();
            } else {
                strategy = enumValue(ResourceGatewayTestClient.SuiteStrategy.class,
                        strategy, "strategy").name();
            }
            String report = value(options, "report", environment, "RESOURCE_GATEWAY_JUNIT_XML",
                    "target/resource-gateway-suite.xml");
            boolean requirePromotion = !options.containsKey("allow-non-eligible")
                    && !"true".equalsIgnoreCase(normalized(environment.get(
                            "RESOURCE_GATEWAY_ALLOW_NON_ELIGIBLE")));
            return new CliOptions(URI.create(baseUri), token, suiteId, parsedRevision,
                    fingerprint, clientRequestId, mode, strategy, Path.of(report),
                    Duration.ofSeconds(timeoutSeconds), requirePromotion);
        }

        private static Map<String, String> parseArguments(String[] args) {
            Map<String, String> options = new LinkedHashMap<>();
            String[] values = args == null ? new String[0] : args;
            for (int index = 0; index < values.length; index++) {
                String argument = normalized(values[index]);
                if (!argument.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected positional argument");
                }
                String name = argument.substring(2);
                if ("allow-non-eligible".equals(name)) {
                    if (options.putIfAbsent(name, "true") != null) {
                        throw new IllegalArgumentException("Duplicate option: --" + name);
                    }
                    continue;
                }
                if (!List.of("base-uri", "suite-id", "revision", "fingerprint", "client-request-id",
                        "mode", "strategy", "report", "timeout-seconds").contains(name)) {
                    throw new IllegalArgumentException("Unknown option: --" + name);
                }
                if (++index >= values.length || normalized(values[index]).startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for --" + name);
                }
                if (options.putIfAbsent(name, normalized(values[index])) != null) {
                    throw new IllegalArgumentException("Duplicate option: --" + name);
                }
            }
            return options;
        }

        private static String value(Map<String, String> options, String option,
                                    Map<String, String> environment, String environmentName) {
            return value(options, option, environment, environmentName, "");
        }

        private static String value(Map<String, String> options, String option,
                                    Map<String, String> environment, String environmentName,
                                    String fallback) {
            String configured = normalized(options.get(option));
            if (!configured.isBlank()) {
                return configured;
            }
            String fromEnvironment = normalized(environment.get(environmentName));
            return fromEnvironment.isBlank() ? fallback : fromEnvironment;
        }

        private static void required(String value, String label, List<String> missing) {
            if (normalized(value).isBlank()) {
                missing.add(label);
            }
        }

        private static long positiveLong(String value, String field) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 1) {
                    throw new NumberFormatException("not positive");
                }
                return parsed;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(field + " must be a positive integer", failure);
            }
        }

        private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
            try {
                return Enum.valueOf(type, normalized(value).toUpperCase(java.util.Locale.ROOT));
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException(field + " has an unsupported value", failure);
            }
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
