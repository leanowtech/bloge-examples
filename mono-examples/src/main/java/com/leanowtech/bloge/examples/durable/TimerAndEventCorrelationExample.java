package com.leanowtech.bloge.examples.durable;

import com.leanowtech.bloge.core.runtime.event.EventMatcher;
import com.leanowtech.bloge.core.runtime.event.EventMatcherQuery;
import com.leanowtech.bloge.core.runtime.event.EventMatcherStatus;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.wait.ExecutionWait;
import com.leanowtech.bloge.core.runtime.wait.WaitStatus;
import com.leanowtech.bloge.core.runtime.wait.WaitType;
import com.leanowtech.bloge.durable.RuntimeStores;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public class TimerAndEventCorrelationExample {

    public static void main(String[] args) {
        DataSource dataSource = EmbeddedH2DataSource.inMemory("timer_event_example");
        RuntimeStores stores = DurableRuntimeExampleSupport.runtimeStores(dataSource, true);

        String executionId = "exec-timer-event-1";
        ExecutionInstance execution = DurableRuntimeExampleSupport.runningExecution(executionId, "timerEventGraph");
        Instant now = Instant.now();
        stores.executionStore().create(execution);
        stores.waitStore().create(new ExecutionWait(
                "wait-1",
                execution.identity(),
                WaitType.WAIT_TIMER,
                "waitPayment",
                "timer:waitPayment",
                now.plusSeconds(300),
                "SIGNAL",
                "{\"source\":\"timeout\"}",
                null,
                WaitStatus.WAITING,
                0,
                now,
                now,
                null
        ));

        stores.eventMatcherStore().create(new EventMatcher(
                "matcher-1",
                execution.identity(),
                "waitPayment",
                "payment.confirmed",
                "orderId",
                "ORD-1",
                false,
                EventMatcherStatus.WAITING,
                0,
                Instant.now(),
                Instant.now(),
                null
        ));

        RuntimeStores recoveredStores = DurableRuntimeExampleSupport.runtimeStores(dataSource, false);
        List<ExecutionWait> activeTimers = recoveredStores.waitStore().findByExecution(executionId).stream()
                .filter(wait -> wait.waitType() == WaitType.WAIT_TIMER)
                .filter(wait -> wait.status() == WaitStatus.WAITING)
                .toList();
        List<EventMatcher> waitingMatchers = recoveredStores.eventMatcherStore().query(new EventMatcherQuery(
                "payment.confirmed",
                "orderId",
                "ORD-1",
                Set.of(EventMatcherStatus.WAITING),
                executionId,
                0,
                20
        ));

        System.out.println("Recovered active timers: " + activeTimers.size());
        System.out.println("Recovered waiting event matchers: " + waitingMatchers.size());
    }
}
