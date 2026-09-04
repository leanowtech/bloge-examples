package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies independent rate buckets, window rollover and shared preview/gate concurrency. */
class McpRequestLimiterTest {

    @Test
    void limitsReferenceAndSharedAuthoringRatesPerIdentityAndResetsAfterAWindow() {
        AtomicLong time = new AtomicLong();
        McpRequestLimiter limiter = new McpRequestLimiter(20, 2, 2, 4, time::get);

        limiter.acquire(identity("agent-a"), "rg.dsl.reference.get").close();
        limiter.acquire(identity("agent-a"), "rg.dsl.reference.get").close();
        assertRateLimited(() -> limiter.acquire(identity("agent-a"), "rg.dsl.reference.get"));
        limiter.acquire(identity("agent-b"), "rg.dsl.reference.get").close();

        limiter.acquire(identity("agent-a"), "rg.dsl.preview").close();
        limiter.acquire(identity("agent-a"), "rg.gate.check").close();
        assertRateLimited(() -> limiter.acquire(identity("agent-a"), "rg.dsl.preview"));

        time.addAndGet(McpRequestLimiter.WINDOW.toNanos());
        limiter.acquire(identity("agent-a"), "rg.dsl.reference.get").close();
        limiter.acquire(identity("agent-a"), "rg.dsl.preview").close();
    }

    @Test
    void rejectsAFifthConcurrentAuthoringCallAndReleasesPermits() {
        McpRequestLimiter limiter = new McpRequestLimiter(20, 20, 20, 4, System::nanoTime);
        McpRequestLimiter.Permit first = limiter.acquire(identity("agent-a"), "rg.dsl.preview");
        McpRequestLimiter.Permit second = limiter.acquire(identity("agent-a"), "rg.gate.check");
        McpRequestLimiter.Permit third = limiter.acquire(identity("agent-a"), "rg.dsl.preview");
        McpRequestLimiter.Permit fourth = limiter.acquire(identity("agent-a"), "rg.gate.check");

        assertThatThrownBy(() -> limiter.acquire(identity("agent-a"), "rg.dsl.preview"))
                .isInstanceOfSatisfying(McpProtocolException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.code()).isEqualTo(-32030));
        first.close();
        limiter.acquire(identity("agent-a"), "rg.dsl.preview").close();
        second.close();
        third.close();
        fourth.close();
    }

    @Test
    void evictsIdleAuthoringIdentitySemaphoresInsteadOfPermanentlyExhaustingCapacity() {
        McpRequestLimiter limiter = new McpRequestLimiter(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, System::nanoTime);

        for (int index = 0; index <= 10_000; index++) {
            limiter.acquire(identity("agent-" + index), "rg.dsl.preview").close();
        }
    }

    @Test
    void evictsExpiredRateWindowsBeforeRejectingANewIdentity() {
        AtomicLong time = new AtomicLong();
        McpRequestLimiter limiter = new McpRequestLimiter(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, time::get);
        for (int index = 0; index < 15_000; index++) {
            limiter.acquire(identity("agent-" + index), "rg.dsl.reference.get").close();
        }

        time.addAndGet(McpRequestLimiter.WINDOW.toNanos());

        limiter.acquire(identity("fresh-agent"), "rg.dsl.reference.get").close();
    }

    private static void assertRateLimited(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(McpProtocolException.class,
                failure -> org.assertj.core.api.Assertions.assertThat(failure.code()).isEqualTo(-32029));
    }

    private static IntegrationRequestContext identity(String actor) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", actor, "", "AGENT_TDD_READ", "corr-1");
    }
}
