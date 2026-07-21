package com.leanowtech.bloge.gateway.visual;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedBrowserSessionCloserTest {

    private final List<AtomicBoolean> releases = new ArrayList<>();

    @AfterEach
    void releaseNonCooperativeHooks() {
        releases.forEach(release -> release.set(true));
    }

    @Test
    void reportsGracefulCloseWithoutInvokingForceClose() {
        AtomicBoolean forceCloseInvoked = new AtomicBoolean();

        var disposition = BoundedBrowserSessionCloser.close(
                Duration.ofSeconds(1), () -> { }, () -> forceCloseInvoked.set(true));

        assertThat(disposition).isEqualTo(BoundedBrowserSessionCloser.Disposition.GRACEFUL);
        assertThat(forceCloseInvoked).isFalse();
    }

    @Test
    void reportsForcedCloseWhenGracefulCloseMissesItsDeadline() throws Exception {
        AtomicBoolean release = release();
        CountDownLatch gracefulCloseEntered = new CountDownLatch(1);
        AtomicBoolean forceCloseInvoked = new AtomicBoolean();

        long startedAt = System.nanoTime();
        var disposition = BoundedBrowserSessionCloser.close(Duration.ofMillis(100), () -> {
            gracefulCloseEntered.countDown();
            while (!release.get()) {
                Thread.interrupted();
                Thread.onSpinWait();
            }
        }, () -> forceCloseInvoked.set(true));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(gracefulCloseEntered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(disposition).isEqualTo(BoundedBrowserSessionCloser.Disposition.FORCED);
        assertThat(forceCloseInvoked).isTrue();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void failsWhenNeitherGracefulNorForceCloseCanFinish() {
        AtomicBoolean releaseGraceful = release();
        AtomicBoolean releaseForce = release();

        assertThatThrownBy(() -> BoundedBrowserSessionCloser.close(
                Duration.ofMillis(100),
                () -> spinUntil(releaseGraceful),
                () -> spinUntil(releaseForce)))
                .isInstanceOfSatisfying(
                        BoundedBrowserSessionCloser.CloseException.class,
                        failure -> assertThat(failure.disposition()).isEqualTo(
                                BoundedBrowserSessionCloser.FailureDisposition.FORCE_TIMED_OUT));
    }

    @Test
    void forceCloseRecoversFromGracefulCloseFailureWithoutLeakingDiagnostics() {
        AtomicBoolean forceCloseInvoked = new AtomicBoolean();

        var disposition = BoundedBrowserSessionCloser.close(Duration.ofSeconds(1), () -> {
            throw new IllegalStateException("secret-browser-command-and-local-path");
        }, () -> forceCloseInvoked.set(true));

        assertThat(disposition).isEqualTo(BoundedBrowserSessionCloser.Disposition.FORCED);
        assertThat(forceCloseInvoked).isTrue();
    }

    @Test
    void classifiesForceCloseFailureWithoutLeakingDiagnostics() {
        var failure = closeFailure(() -> BoundedBrowserSessionCloser.close(
                Duration.ofSeconds(1),
                () -> { throw new IllegalStateException("secret-graceful-command"); },
                () -> { throw new IllegalStateException("secret-force-command-and-local-path"); }));

        assertThat(failure.disposition()).isEqualTo(
                BoundedBrowserSessionCloser.FailureDisposition.FORCE_FAILED);
        assertThat(failure).hasMessageNotContaining("secret-force-command");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void callerInterruptionPreservesInterruptStatusAndStartsForceClose() throws Exception {
        AtomicBoolean releaseGraceful = release();
        CountDownLatch gracefulCloseEntered = new CountDownLatch(1);
        CountDownLatch forceCloseEntered = new CountDownLatch(1);
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        AtomicReference<BoundedBrowserSessionCloser.CloseException> observed =
                new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                BoundedBrowserSessionCloser.close(Duration.ofSeconds(5), () -> {
                    gracefulCloseEntered.countDown();
                    spinUntil(releaseGraceful);
                }, forceCloseEntered::countDown);
            } catch (BoundedBrowserSessionCloser.CloseException failure) {
                observed.set(failure);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });

        assertThat(gracefulCloseEntered.await(1, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(1_000L);

        assertThat(caller.isAlive()).isFalse();
        assertThat(observed.get()).isNotNull().satisfies(failure ->
                assertThat(failure.disposition()).isEqualTo(
                        BoundedBrowserSessionCloser.FailureDisposition.CALLER_INTERRUPTED));
        assertThat(interruptPreserved).isTrue();
        assertThat(forceCloseEntered.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rejectsInvalidTimeout() {
        assertThatThrownBy(() -> BoundedBrowserSessionCloser.close(
                Duration.ofMillis(99), () -> { }, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedBrowserSessionCloser.close(
                Duration.ofNanos(100_000_001L), () -> { }, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedBrowserSessionCloser.close(
                Duration.ofSeconds(61), () -> { }, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AtomicBoolean release() {
        AtomicBoolean release = new AtomicBoolean();
        releases.add(release);
        return release;
    }

    private static void spinUntil(AtomicBoolean release) {
        while (!release.get()) {
            Thread.interrupted();
            Thread.onSpinWait();
        }
    }

    private static BoundedBrowserSessionCloser.CloseException closeFailure(Runnable invocation) {
        try {
            invocation.run();
            throw new AssertionError("Expected browser session close to fail");
        } catch (BoundedBrowserSessionCloser.CloseException failure) {
            return failure;
        }
    }
}
