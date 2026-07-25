package com.leanowtech.bloge.gateway.visual;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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

    @Test
    void retriesTransientIdentityUnavailabilityBeforeTermination() {
        ControllableProcessHandle process = new ControllableProcessHandle();
        ScopedProcessTree scope = ScopedProcessTree.capture(process);
        process.hideIdentityOnce();

        scope.terminate();

        scope.verifyTerminated();
        assertThat(process.destroyForciblyInvoked()).isTrue();
    }

    @Test
    void refusesTerminationWhenIdentityRemainsUnavailable() {
        ControllableProcessHandle process = new ControllableProcessHandle();
        ScopedProcessTree scope = ScopedProcessTree.capture(process);
        process.hideIdentity();

        assertThatThrownBy(scope::terminate)
                .isInstanceOfSatisfying(ScopedProcessTree.ScopeException.class, failure ->
                        assertThat(failure.disposition()).isEqualTo(
                                ScopedProcessTree.Disposition.IDENTITY_UNAVAILABLE));
        assertThat(process.destroyForciblyInvoked()).isFalse();
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

    private static final class ControllableProcessHandle implements ProcessHandle {
        private static final long PID = Long.MAX_VALUE - 1L;
        private static final Instant STARTED_AT = Instant.parse("2026-01-01T00:00:00Z");
        private static final String COMMAND = "/bin/sleep";

        private final CompletableFuture<ProcessHandle> exit = new CompletableFuture<>();
        private boolean alive = true;
        private boolean destroyForciblyInvoked;
        private boolean identityHidden;
        private boolean hideIdentityOnce;

        private void hideIdentityOnce() {
            hideIdentityOnce = true;
        }

        private void hideIdentity() {
            identityHidden = true;
        }

        private boolean destroyForciblyInvoked() {
            return destroyForciblyInvoked;
        }

        @Override
        public long pid() {
            return PID;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            boolean hidden = identityHidden || hideIdentityOnce;
            hideIdentityOnce = false;
            return new Info() {
                @Override
                public Optional<String> command() {
                    return hidden ? Optional.empty() : Optional.of(COMMAND);
                }

                @Override
                public Optional<String> commandLine() {
                    return Optional.empty();
                }

                @Override
                public Optional<String[]> arguments() {
                    return Optional.empty();
                }

                @Override
                public Optional<Instant> startInstant() {
                    return hidden ? Optional.empty() : Optional.of(STARTED_AT);
                }

                @Override
                public Optional<Duration> totalCpuDuration() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> user() {
                    return Optional.empty();
                }
            };
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return exit;
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            return destroyForcibly();
        }

        @Override
        public boolean destroyForcibly() {
            destroyForciblyInvoked = true;
            alive = false;
            exit.complete(this);
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid(), other.pid());
        }
    }
}
