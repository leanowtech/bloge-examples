package com.leanowtech.bloge.gateway.visual;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedBrowserSessionLauncherTest {

    private final List<AtomicBoolean> releases = new ArrayList<>();

    @AfterEach
    void releaseNonCooperativeFactories() {
        releases.forEach(release -> release.set(true));
    }

    @Test
    void transfersExactlyOneSuccessfullyCreatedSession() {
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicInteger cleaned = new AtomicInteger();
        Object session = new Object();

        Object launched = BoundedBrowserSessionLauncher.launch(
                Duration.ofSeconds(1), () -> session, () -> aborted.set(true),
                ignored -> cleaned.incrementAndGet());

        assertThat(launched).isSameAs(session);
        assertThat(aborted).isFalse();
        assertThat(cleaned).hasValue(0);
    }

    @Test
    void returnsAtDeadlineEvenWhenFactoryIgnoresInterrupts() throws Exception {
        AtomicBoolean release = release();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean aborted = new AtomicBoolean();

        long startedAt = System.nanoTime();
        var failure = launchFailure(() -> BoundedBrowserSessionLauncher.launch(
                Duration.ofMillis(100), () -> {
                    entered.countDown();
                    while (!release.get()) {
                        Thread.interrupted();
                        Thread.onSpinWait();
                    }
                    return new Object();
                }, () -> aborted.set(true), ignored -> { }));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(failure.disposition()).isEqualTo(
                BoundedBrowserSessionLauncher.Disposition.TIMED_OUT);
        await(aborted::get);
    }

    @Test
    void cleansSessionThatArrivesAfterTimeoutWithoutTransferringOwnership() throws Exception {
        AtomicBoolean release = release();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger cleaned = new AtomicInteger();
        Object lateSession = new Object();

        var failure = launchFailure(() -> BoundedBrowserSessionLauncher.launch(
                Duration.ofMillis(100), () -> {
                    entered.countDown();
                    while (!release.get()) {
                        Thread.interrupted();
                        Thread.onSpinWait();
                    }
                    return lateSession;
                }, () -> { }, cleanedSession -> {
                    assertThat(cleanedSession).isSameAs(lateSession);
                    cleaned.incrementAndGet();
                }));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.disposition()).isEqualTo(
                BoundedBrowserSessionLauncher.Disposition.TIMED_OUT);

        release.set(true);
        await(() -> cleaned.get() == 1);
        assertThat(cleaned).hasValue(1);
    }

    @Test
    void callerInterruptionAbortsLaunchAndPreservesInterruptStatus() throws Exception {
        AtomicBoolean release = release();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        AtomicReference<BoundedBrowserSessionLauncher.LaunchException> observed =
                new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                BoundedBrowserSessionLauncher.launch(Duration.ofSeconds(5), () -> {
                    entered.countDown();
                    while (!release.get()) {
                        Thread.interrupted();
                        Thread.onSpinWait();
                    }
                    return new Object();
                }, () -> aborted.set(true), ignored -> { });
            } catch (BoundedBrowserSessionLauncher.LaunchException failure) {
                observed.set(failure);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(1_000L);

        assertThat(caller.isAlive()).isFalse();
        assertThat(observed.get()).isNotNull().satisfies(failure ->
                assertThat(failure.disposition()).isEqualTo(
                        BoundedBrowserSessionLauncher.Disposition.CALLER_INTERRUPTED));
        assertThat(interruptPreserved).isTrue();
        await(aborted::get);
    }

    @Test
    void factoryFailureIsClassifiedWithoutLeakingDiagnostics() {
        var failure = launchFailure(() -> BoundedBrowserSessionLauncher.launch(
                Duration.ofSeconds(1), () -> {
                    throw new IllegalStateException("secret-browser-command-and-local-path");
                }, () -> { }, ignored -> { }));

        assertThat(failure.disposition()).isEqualTo(
                BoundedBrowserSessionLauncher.Disposition.FACTORY_FAILED);
        assertThat(failure).hasMessageNotContaining("secret-browser-command");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void blockingAbortHookCannotExtendTheCallerDeadline() {
        AtomicBoolean releaseFactory = release();
        AtomicBoolean releaseAbort = release();
        CountDownLatch abortEntered = new CountDownLatch(1);

        long startedAt = System.nanoTime();
        var failure = launchFailure(() -> BoundedBrowserSessionLauncher.launch(
                Duration.ofMillis(100), () -> {
                    while (!releaseFactory.get()) {
                        Thread.interrupted();
                        Thread.onSpinWait();
                    }
                    return new Object();
                }, () -> {
                    abortEntered.countDown();
                    while (!releaseAbort.get()) {
                        Thread.interrupted();
                        Thread.onSpinWait();
                    }
                }, ignored -> { }));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(failure.disposition()).isEqualTo(
                BoundedBrowserSessionLauncher.Disposition.TIMED_OUT);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        try {
            assertThat(abortEntered.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    void rejectsSubBoundaryNonIntegralAndUnboundedTimeouts() {
        assertThatThrownBy(() -> BoundedBrowserSessionLauncher.launch(
                Duration.ofMillis(99), Object::new, () -> { }, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedBrowserSessionLauncher.launch(
                Duration.ofNanos(100_000_001L), Object::new, () -> { }, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedBrowserSessionLauncher.launch(
                Duration.ofSeconds(61), Object::new, () -> { }, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AtomicBoolean release() {
        AtomicBoolean release = new AtomicBoolean();
        releases.add(release);
        return release;
    }

    private static BoundedBrowserSessionLauncher.LaunchException launchFailure(
            Runnable invocation) {
        try {
            invocation.run();
            throw new AssertionError("Expected browser session launch to fail");
        } catch (BoundedBrowserSessionLauncher.LaunchException failure) {
            return failure;
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
