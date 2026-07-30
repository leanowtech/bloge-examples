package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationOutcome;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal one-shot entry point used only by the supervised function test process.
 */
public final class AuthoringFunctionTestWorkerMain {

    public static final String COMMAND = "--bloge-authoring-function-test-worker";
    static final int EXIT_PROTOCOL_FAILURE = 64;
    static final int EXIT_TIMEOUT = 124;

    private AuthoringFunctionTestWorkerMain() {
    }

    public static boolean requested(String[] args) {
        return args != null && args.length == 1 && COMMAND.equals(args[0]);
    }

    public static void main(String[] args) {
        int exitCode = run(
                System.in,
                System.out,
                new ObjectMapper().findAndRegisterModules());
        if (exitCode != 0) {
            Runtime.getRuntime().halt(exitCode);
        }
    }

    static int run(InputStream input, OutputStream output, ObjectMapper objectMapper) {
        try {
            byte[] material = readBounded(
                    input, AuthoringFunctionWorkerProtocol.MAXIMUM_REQUEST_BYTES);
            InvocationRequest request = objectMapper.readValue(material, InvocationRequest.class);
            if (!InvocationRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                    || request.requestId().isBlank()
                    || request.functionName().isBlank()
                    || request.expectedRuntimeFingerprint().isBlank()) {
                return EXIT_PROTOCOL_FAILURE;
            }
            TrustedCoreFunctionRuntime.Resolution resolution =
                    TrustedCoreFunctionRuntime.resolve(request.functionName());
            if (resolution.state() != TrustedCoreFunctionRuntime.State.BOUND
                    || !resolution.runtimeFingerprint()
                    .equals(request.expectedRuntimeFingerprint())) {
                return EXIT_PROTOCOL_FAILURE;
            }
            InvocationResponse response = invoke(request, resolution);
            byte[] encoded = objectMapper.writeValueAsBytes(response);
            if (encoded.length > AuthoringFunctionWorkerProtocol.MAXIMUM_RESPONSE_BYTES) {
                return EXIT_PROTOCOL_FAILURE;
            }
            output.write(encoded);
            output.flush();
            return 0;
        } catch (IOException | RuntimeException exception) {
            return EXIT_PROTOCOL_FAILURE;
        }
    }

    private static InvocationResponse invoke(
            InvocationRequest request,
            TrustedCoreFunctionRuntime.Resolution resolution) {
        ExpressionFunction function = resolution.function();
        AtomicBoolean completed = new AtomicBoolean(false);
        Thread watchdog = Thread.ofPlatform()
                .daemon(true)
                .name("authoring-function-worker-watchdog")
                .start(() -> {
                    try {
                        Thread.sleep(AuthoringFunctionWorkerProtocol.INVOCATION_TIMEOUT_MILLIS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    if (!completed.get()) {
                        Runtime.getRuntime().halt(EXIT_TIMEOUT);
                    }
                });
        long started = System.nanoTime();
        try {
            Object actual = function.apply(request.args().toArray(Object[]::new));
            return response(
                    request,
                    resolution,
                    InvocationOutcome.SUCCESS,
                    actual,
                    "",
                    elapsedMicros(started));
        } catch (Throwable throwable) {
            return response(
                    request,
                    resolution,
                    InvocationOutcome.INVOCATION_FAILED,
                    null,
                    stableErrorCode(throwable),
                    elapsedMicros(started));
        } finally {
            completed.set(true);
            watchdog.interrupt();
        }
    }

    private static InvocationResponse response(
            InvocationRequest request,
            TrustedCoreFunctionRuntime.Resolution resolution,
            InvocationOutcome outcome,
            Object actual,
            String errorCode,
            long durationMicros) {
        return new InvocationResponse(
                InvocationResponse.SCHEMA_VERSION,
                request.requestId(),
                AuthoringFunctionWorkerProtocol.EXECUTION_PROFILE,
                resolution.runtimeFingerprint(),
                outcome,
                actual,
                errorCode,
                durationMicros);
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        byte[] material = input.readNBytes(maximumBytes + 1);
        if (material.length > maximumBytes) {
            throw new IOException("Worker request exceeds the protocol limit");
        }
        return material;
    }

    private static String stableErrorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_ARGUMENT";
        }
        if (throwable instanceof IndexOutOfBoundsException) {
            return "OUT_OF_RANGE";
        }
        if (throwable instanceof ArithmeticException) {
            return "ARITHMETIC_ERROR";
        }
        return "FUNCTION_INVOCATION_FAILED";
    }

    private static long elapsedMicros(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMicros(Math.max(0, System.nanoTime() - startedNanos));
    }
}
