package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationOutcome;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/**
 * Launches one bounded JVM per invocation and treats every protocol ambiguity as a failed run.
 */
@Component
public final class ProcessAuthoringFunctionTestWorker implements AuthoringFunctionTestWorker {

    private static final Semaphore WORKER_PERMITS = new Semaphore(
            AuthoringFunctionWorkerProtocol.MAXIMUM_CONCURRENT_WORKERS, true);

    private final ObjectMapper objectMapper;
    private final WorkerCommandFactory commandFactory;

    @Autowired
    public ProcessAuthoringFunctionTestWorker(ObjectMapper objectMapper) {
        this(objectMapper, ProcessAuthoringFunctionTestWorker::defaultCommand);
    }

    ProcessAuthoringFunctionTestWorker(ObjectMapper objectMapper,
                                       WorkerCommandFactory commandFactory) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory");
    }

    @Override
    public String executionProfile() {
        return AuthoringFunctionWorkerProtocol.EXECUTION_PROFILE;
    }

    @Override
    public InvocationResponse invoke(String functionName,
                                     String expectedRuntimeFingerprint,
                                     List<Object> args) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        boolean acquired = WORKER_PERMITS.tryAcquire();
        if (!acquired) {
            return failure(
                    requestId,
                    expectedRuntimeFingerprint,
                    InvocationOutcome.WORKER_UNAVAILABLE,
                    "WORKER_SATURATED",
                    started);
        }
        try {
            return invokeAcquired(
                    requestId, functionName, expectedRuntimeFingerprint, args, started);
        } finally {
            WORKER_PERMITS.release();
        }
    }

    private InvocationResponse invokeAcquired(String requestId,
                                              String functionName,
                                              String expectedRuntimeFingerprint,
                                              List<Object> args,
                                              long started) {
        Path workDirectory = null;
        Process process = null;
        try {
            InvocationRequest request = new InvocationRequest(
                    InvocationRequest.SCHEMA_VERSION,
                    requestId,
                    functionName,
                    expectedRuntimeFingerprint,
                    args);
            byte[] encodedRequest = objectMapper.writeValueAsBytes(request);
            if (encodedRequest.length > AuthoringFunctionWorkerProtocol.MAXIMUM_REQUEST_BYTES) {
                return failure(
                        requestId,
                        expectedRuntimeFingerprint,
                        InvocationOutcome.WORKER_FAILED,
                        "WORKER_REQUEST_TOO_LARGE",
                        started);
            }

            workDirectory = Files.createTempDirectory("bloge-function-worker-");
            ProcessBuilder builder = new ProcessBuilder(commandFactory.command(workDirectory));
            builder.directory(workDirectory.toFile());
            builder.redirectErrorStream(false);
            builder.environment().clear();
            builder.environment().put("HOME", workDirectory.toString());
            builder.environment().put("TMPDIR", workDirectory.toString());
            builder.environment().put("LANG", "C");
            builder.environment().put("LC_ALL", "C");
            builder.environment().put("TZ", "UTC");
            process = builder.start();

            FutureTask<CapturedStream> stdout = capture(
                    process.getInputStream(),
                    AuthoringFunctionWorkerProtocol.MAXIMUM_RESPONSE_BYTES);
            FutureTask<CapturedStream> stderr = capture(
                    process.getErrorStream(),
                    AuthoringFunctionWorkerProtocol.MAXIMUM_STDERR_BYTES);
            process.getOutputStream().write(encodedRequest);
            process.getOutputStream().close();

            boolean finished = process.waitFor(
                    AuthoringFunctionWorkerProtocol.SUPERVISOR_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                awaitCapture(stdout);
                awaitCapture(stderr);
                return failure(
                        requestId,
                        expectedRuntimeFingerprint,
                        InvocationOutcome.TIMEOUT,
                        "TIMEOUT",
                        started);
            }

            CapturedStream output = awaitCapture(stdout);
            awaitCapture(stderr);
            int exitCode = process.exitValue();
            if (exitCode == AuthoringFunctionTestWorkerMain.EXIT_TIMEOUT) {
                return failure(
                        requestId,
                        expectedRuntimeFingerprint,
                        InvocationOutcome.TIMEOUT,
                        "TIMEOUT",
                        started);
            }
            if (exitCode == 3 || exitCode == 134 || exitCode == 137) {
                return failure(
                        requestId,
                        expectedRuntimeFingerprint,
                        InvocationOutcome.RESOURCE_EXHAUSTED,
                        "WORKER_RESOURCE_EXHAUSTED",
                        started);
            }
            if (exitCode != 0 || output.truncated()) {
                return failure(
                        requestId,
                        expectedRuntimeFingerprint,
                        InvocationOutcome.WORKER_FAILED,
                        "WORKER_PROTOCOL_FAILURE",
                        started);
            }
            InvocationResponse response =
                    objectMapper.readValue(output.material(), InvocationResponse.class);
            if (!InvocationResponse.SCHEMA_VERSION.equals(response.schemaVersion())
                    || !requestId.equals(response.requestId())
                    || !executionProfile().equals(response.executionProfile())
                    || !expectedRuntimeFingerprint.equals(response.runtimeFingerprint())) {
                return failure(
                        requestId,
                        expectedRuntimeFingerprint,
                        InvocationOutcome.WORKER_FAILED,
                        "WORKER_ATTESTATION_MISMATCH",
                        started);
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminate(process);
            return failure(
                    requestId,
                    expectedRuntimeFingerprint,
                    InvocationOutcome.WORKER_UNAVAILABLE,
                    "WORKER_INTERRUPTED",
                    started);
        } catch (IOException | RuntimeException exception) {
            terminate(process);
            return failure(
                    requestId,
                    expectedRuntimeFingerprint,
                    InvocationOutcome.WORKER_FAILED,
                    "WORKER_LAUNCH_FAILED",
                    started);
        } finally {
            terminate(process);
            eraseDirectory(workDirectory);
        }
    }

    private InvocationResponse failure(String requestId,
                                       String runtimeFingerprint,
                                       InvocationOutcome outcome,
                                       String errorCode,
                                       long started) {
        return new InvocationResponse(
                InvocationResponse.SCHEMA_VERSION,
                requestId,
                executionProfile(),
                runtimeFingerprint,
                outcome,
                null,
                errorCode,
                elapsedMicros(started));
    }

    private static FutureTask<CapturedStream> capture(InputStream input, int maximumBytes) {
        FutureTask<CapturedStream> capture = new FutureTask<>(
                () -> readStream(input, maximumBytes));
        Thread.ofVirtual().name("authoring-function-worker-stream").start(capture);
        return capture;
    }

    private static CapturedStream awaitCapture(FutureTask<CapturedStream> capture) {
        try {
            return capture.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CapturedStream(new byte[0], true);
        } catch (Exception exception) {
            return new CapturedStream(new byte[0], true);
        }
    }

    private static CapturedStream readStream(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream(
                Math.min(maximumBytes, 8 * 1024));
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int writable = Math.max(0, Math.min(read, maximumBytes - retained.size()));
            if (writable > 0) {
                retained.write(buffer, 0, writable);
            }
            total += read;
        }
        return new CapturedStream(retained.toByteArray(), total > maximumBytes);
    }

    private static void terminate(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try (var descendants = process.descendants()) {
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
        process.destroyForcibly();
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> defaultCommand(Path workDirectory) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("--enable-preview");
        command.add("-Xms16m");
        command.add("-Xmx" + AuthoringFunctionWorkerProtocol.WORKER_HEAP_MIB + "m");
        command.add("-XX:MaxMetaspaceSize="
                + AuthoringFunctionWorkerProtocol.WORKER_METASPACE_MIB + "m");
        command.add("-XX:MaxDirectMemorySize=16m");
        command.add("-Xss512k");
        command.add("-XX:ActiveProcessorCount=1");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Djava.io.tmpdir=" + workDirectory);

        Optional<String> packagedJar = currentPackagedApplicationJar();
        if (packagedJar.isPresent()) {
            command.add("-jar");
            command.add(packagedJar.orElseThrow());
            command.add(AuthoringFunctionTestWorkerMain.COMMAND);
            return List.copyOf(command);
        }

        String classpath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path", ""));
        command.add("-cp");
        command.add(classpath);
        command.add(AuthoringFunctionTestWorkerMain.class.getName());
        command.add(AuthoringFunctionTestWorkerMain.COMMAND);
        return List.copyOf(command);
    }

    private static Optional<String> currentPackagedApplicationJar() {
        String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        for (int index = 0; index + 1 < arguments.length; index++) {
            if ("-jar".equals(arguments[index])) {
                Path candidate = Path.of(arguments[index + 1]).toAbsolutePath();
                if (isResourceGatewayBootJar(candidate)) {
                    return Optional.of(candidate.toString());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isResourceGatewayBootJar(Path candidate) {
        if (!Files.isRegularFile(candidate)) {
            return false;
        }
        try (ZipFile archive = new ZipFile(candidate.toFile())) {
            return archive.getEntry(
                    "BOOT-INF/classes/com/leanowtech/bloge/gateway/"
                            + "ResourceGatewayApplication.class") != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable)
                .toAbsolutePath()
                .toString();
    }

    private static void eraseDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The worker directory contains no durable evidence and is best-effort erased.
                }
            });
        } catch (IOException ignored) {
            // Creation or cleanup failure is not exposed with local path material.
        }
    }

    private static long elapsedMicros(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMicros(Math.max(0, System.nanoTime() - startedNanos));
    }

    @FunctionalInterface
    interface WorkerCommandFactory {
        List<String> command(Path workDirectory);
    }

    private record CapturedStream(byte[] material, boolean truncated) {
        private CapturedStream {
            material = material == null ? new byte[0] : Arrays.copyOf(material, material.length);
        }
    }
}
