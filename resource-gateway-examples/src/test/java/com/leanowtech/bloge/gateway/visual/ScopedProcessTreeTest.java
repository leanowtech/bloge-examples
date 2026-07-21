package com.leanowtech.bloge.gateway.visual;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedProcessTreeTest {

    private final List<Process> processes = new ArrayList<>();
    private final List<ProcessHandle> processHandles = new ArrayList<>();

    @AfterEach
    void terminateProcesses() {
        processHandles.forEach(ProcessHandle::destroyForcibly);
        processes.forEach(Process::destroyForcibly);
    }

    @Test
    void forciblyTerminatesCapturedRootAndDescendant() throws Exception {
        Process root = startShellWithChild();
        ProcessHandle child = awaitChild(root.toHandle());
        processHandles.add(child);
        ScopedProcessTree scope = ScopedProcessTree.capture(root.toHandle());

        scope.terminate();

        assertThat(root.waitFor(1, TimeUnit.SECONDS)).isTrue();
        awaitExit(child);
        assertThat(root.isAlive()).isFalse();
        assertThat(child.isAlive()).isFalse();
    }

    @Test
    void doesNotMistakeRootExitForCapturedDescendantExit() throws Exception {
        Process root = startShellWithChild();
        ProcessHandle child = awaitChild(root.toHandle());
        processHandles.add(child);
        ScopedProcessTree scope = ScopedProcessTree.capture(root.toHandle());

        try {
            assertThat(root.destroyForcibly()).isSameAs(root);
            assertThat(root.waitFor(1, TimeUnit.SECONDS)).isTrue();
            assertThat(child.isAlive()).isTrue();
            assertThatThrownBy(scope::verifyTerminated)
                    .isInstanceOfSatisfying(ScopedProcessTree.ScopeException.class, failure ->
                            assertThat(failure.disposition()).isEqualTo(
                                    ScopedProcessTree.Disposition.STILL_RUNNING));

            scope.terminate();
            scope.verifyTerminated();
            awaitExit(child);
            assertThat(child.isAlive()).isFalse();
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void rejectsDeadProcessCaptureWithoutLeakingIdentity() throws Exception {
        Process process = start("/usr/bin/true");
        assertThat(process.waitFor(1, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> ScopedProcessTree.capture(process.toHandle()))
                .isInstanceOfSatisfying(ScopedProcessTree.ScopeException.class, failure -> {
                    assertThat(failure.disposition()).isEqualTo(
                            ScopedProcessTree.Disposition.NOT_RUNNING);
                    assertThat(failure.getMessage()).doesNotContain(Long.toString(process.pid()));
                });
    }

    @Test
    void uniqueCaptureFailsClosedWhenNoProcessMatches() {
        assertThatThrownBy(() -> ScopedProcessTree.captureUnique(
                java.nio.file.Path.of("/definitely/not/a/live/process"),
                "--resource-gateway-impossible-argument"))
                .isInstanceOfSatisfying(ScopedProcessTree.ScopeException.class, failure ->
                        assertThat(failure.disposition()).isEqualTo(
                                ScopedProcessTree.Disposition.CAPTURE_AMBIGUOUS));
    }

    @Test
    void capturesUniqueExecutableAndArgumentThenVerifiesExit() throws Exception {
        Path executable = Path.of("/bin/sleep");
        String uniqueDuration = "123456789";
        Process process = start(executable.toString(), uniqueDuration);
        ScopedProcessTree scope = ScopedProcessTree.captureUnique(executable, uniqueDuration);

        assertThatThrownBy(scope::verifyTerminated)
                .isInstanceOfSatisfying(ScopedProcessTree.ScopeException.class, failure ->
                        assertThat(failure.disposition()).isEqualTo(
                                ScopedProcessTree.Disposition.STILL_RUNNING));

        scope.terminate();
        scope.verifyTerminated();
        assertThat(process.waitFor(1, TimeUnit.SECONDS)).isTrue();
    }

    private Process startShellWithChild() throws IOException {
        return start("/bin/sh", "-c", "sleep 30 & wait");
    }

    private Process start(String... command) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        processes.add(process);
        processHandles.add(process.toHandle());
        return process;
    }

    private static ProcessHandle awaitChild(ProcessHandle root) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            try (var children = root.children()) {
                var child = children.findFirst();
                if (child.isPresent()) {
                    return child.orElseThrow();
                }
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("Expected child process");
    }

    private static void awaitExit(ProcessHandle process) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
    }
}
