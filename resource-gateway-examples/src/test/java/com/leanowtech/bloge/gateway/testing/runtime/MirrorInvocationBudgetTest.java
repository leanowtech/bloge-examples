package com.leanowtech.bloge.gateway.testing.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorInvocationBudgetTest {

    @Test
    void admitsExactlyTheSealedLimitAndReturnsAStablePayloadFreeFailure() {
        MirrorInvocationBudget budget = new MirrorInvocationBudget(2);

        budget.admit();
        budget.admit();

        assertThatThrownBy(budget::admit)
                .isInstanceOfSatisfying(TestControlException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(MirrorInvocationBudget.EXHAUSTED_CODE);
                    assertThat(failure.errorType()).isEqualTo("MIRROR_INVOCATION_BUDGET");
                    assertThat(failure.getMessage())
                            .isEqualTo("Mirror invocation occurrence budget exhausted.");
                });
        assertThat(budget.snapshot()).isEqualTo(
                new MirrorInvocationBudget.Snapshot(2, 2, 1));
        assertThat(budget.snapshot().exhausted()).isTrue();
    }

    @Test
    void parallelAdmissionsNeverOversubscribeTheBudget() throws Exception {
        int callers = 64;
        int maximum = 7;
        MirrorInvocationBudget budget = new MirrorInvocationBudget(maximum);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ArrayList<Future<?>> tasks = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < callers; index++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        budget.admit();
                        admitted.incrementAndGet();
                    } catch (TestControlException expected) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get();
            }
        }

        assertThat(admitted).hasValue(maximum);
        assertThat(rejected).hasValue(callers - maximum);
        assertThat(budget.snapshot()).isEqualTo(
                new MirrorInvocationBudget.Snapshot(maximum, maximum, callers - maximum));
    }

    @Test
    void rejectsImpossibleLimitsAndSnapshots() {
        assertThatThrownBy(() -> new MirrorInvocationBudget(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MirrorInvocationBudget.Snapshot(2, 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MirrorInvocationBudget.Snapshot(2, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
