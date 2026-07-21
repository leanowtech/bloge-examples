package com.leanowtech.bloge.gateway.visual;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/** Captures one exact OS process identity and can forcibly terminate its current descendant tree. */
final class ScopedProcessTree {

    private static final long EXIT_POLL_NANOS = 5_000_000L;
    private static final int CAPTURE_ATTEMPTS = 20;
    private static final long CAPTURE_POLL_NANOS = 5_000_000L;

    private final Target root;
    private final List<Target> capturedTargets;

    private ScopedProcessTree(Target root, List<Target> capturedTargets) {
        this.root = root;
        this.capturedTargets = List.copyOf(capturedTargets);
    }

    /**
     * Captures the unique live process matching an executable and exact command argument.
     *
     * @param executable expected executable path
     * @param exactArgument exact process argument, such as a frozen ChromeDriver port
     * @return a process tree pinned to PID, start instant, and command
     * @throws ScopeException when one exact, inspectable live process cannot be identified
     */
    static ScopedProcessTree captureUnique(Path executable, String exactArgument) {
        Path expectedExecutable = normalized(Objects.requireNonNull(executable, "executable"));
        String requiredArgument = Objects.requireNonNull(exactArgument, "exactArgument");
        for (int attempt = 0; attempt < CAPTURE_ATTEMPTS; attempt++) {
            List<ProcessHandle> matches;
            try (var processes = ProcessHandle.allProcesses()) {
                matches = processes.filter(ProcessHandle::isAlive)
                        .filter(process -> commandMatches(
                                process, expectedExecutable, requiredArgument))
                        .toList();
            }
            if (matches.size() == 1) {
                return capture(matches.getFirst());
            }
            if (matches.size() > 1) {
                throw new ScopeException(Disposition.CAPTURE_AMBIGUOUS);
            }
            awaitCaptureRetry();
        }
        throw new ScopeException(Disposition.CAPTURE_AMBIGUOUS);
    }

    /**
     * Captures one already-owned process root and its currently visible descendants.
     *
     * @param root live process root
     * @return identity-pinned process-tree snapshot
     */
    static ScopedProcessTree capture(ProcessHandle root) {
        ProcessHandle requiredRoot = Objects.requireNonNull(root, "root");
        Target rootTarget = Target.capture(requiredRoot);
        List<Target> captured = new ArrayList<>();
        try (var descendants = requiredRoot.descendants()) {
            descendants.forEach(process -> captureLive(process).ifPresent(captured::add));
        }
        rootTarget.requireIdentityOrExit();
        captured.add(rootTarget);
        return new ScopedProcessTree(rootTarget, captured);
    }

    /**
     * Forcibly terminates the captured root and every descendant visible before root termination.
     *
     * <p>The caller must execute this operation behind its own wall-clock deadline. The method
     * keeps waiting until the captured processes are dead so completion is evidence of OS-level
     * exit, rather than evidence that a kill request was merely submitted.</p>
     *
     * @throws ScopeException when identity drift or process refusal prevents termination
     */
    void terminate() {
        List<Target> targets = terminationTargets();
        for (Target target : targets) {
            if (target.isOriginalAlive()
                    && !target.process().destroyForcibly()
                    && target.process().isAlive()) {
                throw new ScopeException(Disposition.TERMINATION_REFUSED);
            }
        }
        while (targets.stream().map(Target::process).anyMatch(ProcessHandle::isAlive)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ScopeException(Disposition.TERMINATION_INTERRUPTED);
            }
            LockSupport.parkNanos(EXIT_POLL_NANOS);
        }
    }

    /**
     * Proves that the captured root and descendants have exited without submitting a termination
     * request.
     *
     * @throws ScopeException when the captured process is still alive
     */
    void verifyTerminated() {
        if (capturedTargets.stream().anyMatch(Target::isOriginalAlive)) {
            throw new ScopeException(Disposition.STILL_RUNNING);
        }
    }

    private List<Target> terminationTargets() {
        Map<Long, Target> targets = new LinkedHashMap<>();
        capturedTargets.forEach(target -> targets.put(target.process().pid(), target));
        if (root.isOriginalAlive()) {
            try (var descendants = root.process().descendants()) {
                descendants.forEach(process -> captureLive(process)
                        .ifPresent(target -> targets.putIfAbsent(process.pid(), target)));
            }
        }
        Target rootTarget = targets.remove(root.process().pid());
        List<Target> ordered = new ArrayList<>(targets.values());
        ordered.add(rootTarget);
        return ordered;
    }

    private static Optional<Target> captureLive(ProcessHandle process) {
        if (!process.isAlive()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Target.capture(process));
        } catch (ScopeException failure) {
            if (failure.disposition() == Disposition.NOT_RUNNING) {
                return Optional.empty();
            }
            throw failure;
        }
    }

    private static boolean commandMatches(
            ProcessHandle process,
            Path expectedExecutable,
            String exactArgument) {
        ProcessHandle.Info info = process.info();
        return info.command().map(Path::of).map(ScopedProcessTree::normalized)
                .filter(expectedExecutable::equals)
                .isPresent()
                && info.arguments().map(arguments -> List.of(arguments).contains(exactArgument))
                .orElse(false)
                && info.startInstant().isPresent();
    }

    private static Path normalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException unavailable) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** Closed process-scope outcome that is safe to expose in test summaries. */
    enum Disposition {
        /** More or fewer than one process matched the frozen executable and argument. */
        CAPTURE_AMBIGUOUS,

        /** The operating system did not expose a stable command or start instant. */
        IDENTITY_UNAVAILABLE,

        /** The candidate process exited before capture completed. */
        NOT_RUNNING,

        /** A completed protocol shutdown left the captured process alive. */
        STILL_RUNNING,

        /** PID, start instant, or command no longer matches the captured identity. */
        IDENTITY_MISMATCH,

        /** The operating system refused forcible termination. */
        TERMINATION_REFUSED,

        /** The bounded caller interrupted termination before exit was proven. */
        TERMINATION_INTERRUPTED,

        /** The caller interrupted bounded identity stabilization. */
        CAPTURE_INTERRUPTED
    }

    /** Process-scope failure that intentionally omits commands, arguments, paths, and PIDs. */
    static final class ScopeException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final Disposition disposition;

        private ScopeException(Disposition disposition) {
            super("Scoped process tree operation failed");
            this.disposition = Objects.requireNonNull(disposition, "disposition");
        }

        /**
         * Returns the closed failure outcome.
         *
         * @return capture, identity, or termination failure
         */
        Disposition disposition() {
            return disposition;
        }
    }

    private record Identity(long pid, Instant startedAt, Path command) {
        private Identity {
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(command, "command");
        }

        private boolean matches(ProcessHandle process) {
            ProcessHandle.Info info = process.info();
            return process.pid() == pid
                    && info.startInstant().filter(startedAt::equals).isPresent()
                    && info.command().map(Path::of).map(ScopedProcessTree::normalized)
                    .filter(command::equals).isPresent();
        }
    }

    private record Target(ProcessHandle process, Identity identity) {
        private Target {
            Objects.requireNonNull(process, "process");
            Objects.requireNonNull(identity, "identity");
        }

        private static Target capture(ProcessHandle process) {
            for (int attempt = 0; attempt < CAPTURE_ATTEMPTS; attempt++) {
                if (!process.isAlive()) {
                    throw new ScopeException(Disposition.NOT_RUNNING);
                }
                ProcessHandle.Info info = process.info();
                var startedAt = info.startInstant();
                var command = info.command();
                if (startedAt.isPresent() && command.isPresent()) {
                    Target target = new Target(process, new Identity(
                            process.pid(), startedAt.orElseThrow(),
                            normalized(Path.of(command.orElseThrow()))));
                    if (target.isOriginalAlive()) {
                        return target;
                    }
                }
                awaitCaptureRetry();
            }
            if (!process.isAlive()) {
                throw new ScopeException(Disposition.NOT_RUNNING);
            }
            throw new ScopeException(Disposition.IDENTITY_UNAVAILABLE);
        }

        private boolean isOriginalAlive() {
            if (!process.isAlive()) {
                return false;
            }
            if (!identity.matches(process)) {
                throw new ScopeException(Disposition.IDENTITY_MISMATCH);
            }
            return true;
        }

        private void requireIdentityOrExit() {
            if (!process.isAlive()) {
                throw new ScopeException(Disposition.NOT_RUNNING);
            }
            if (!identity.matches(process)) {
                throw new ScopeException(Disposition.IDENTITY_MISMATCH);
            }
        }
    }

    private static void awaitCaptureRetry() {
        LockSupport.parkNanos(CAPTURE_POLL_NANOS);
        if (Thread.currentThread().isInterrupted()) {
            throw new ScopeException(Disposition.CAPTURE_INTERRUPTED);
        }
    }
}
