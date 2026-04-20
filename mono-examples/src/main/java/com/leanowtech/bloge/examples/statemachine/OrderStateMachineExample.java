package com.leanowtech.bloge.examples.statemachine;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.state.builder.StateMachineBuilder;
import com.leanowtech.bloge.state.checkpoint.StateMachineCheckpoint;
import com.leanowtech.bloge.state.engine.StateMachineExecutor;
import com.leanowtech.bloge.state.engine.StateMachineResult;
import com.leanowtech.bloge.state.model.StateMachineDef;
import com.leanowtech.bloge.state.spi.StateMachineListener;
import com.leanowtech.bloge.state.spi.event.StateMachineEvent.CheckpointRestoredEvent;
import com.leanowtech.bloge.state.spi.event.StateMachineEvent.CheckpointSavedEvent;
import com.leanowtech.bloge.state.spi.event.StateMachineEvent.SignalReceivedEvent;
import com.leanowtech.bloge.state.spi.event.StateMachineEvent.StateEnterEvent;
import com.leanowtech.bloge.state.spi.event.StateMachineEvent.StateMachineCompleteEvent;
import com.leanowtech.bloge.state.spi.event.StateMachineEvent.StateMachineStartEvent;
import com.leanowtech.bloge.state.spi.event.StateMachineEvent.TransitionEvent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates the complete state machine API using an order fulfillment workflow.
 *
 * <p>This example shows:
 * <ul>
 *   <li>Building a state machine with the fluent {@link StateMachineBuilder} API</li>
 *   <li>Global transitions (CANCEL event) that apply to all non-terminal states</li>
 *   <li>Per-state timeouts and global machine timeout</li>
 *   <li>Checkpoint creation and resume across a simulated restart</li>
 *   <li>Attaching a {@link StateMachineListener} for audit logging</li>
 * </ul>
 *
 * <p>Order lifecycle: PENDING → CONFIRMED → SHIPPED → DELIVERED
 * Global transitions: CANCEL → CANCELLED (from any non-terminal state)
 *
 * @see StateMachineBuilder
 * @see StateMachineExecutor
 * @see StateMachineListener
 */
public final class OrderStateMachineExample {

    private OrderStateMachineExample() {
    }

    /**
     * Builds the order lifecycle state machine definition.
     *
     * <p>The machine defines five non-terminal states and three terminal states:
     * <ul>
     *   <li>{@code pending} — initial state, waits for CONFIRM</li>
     *   <li>{@code confirmed} — waits for SHIP</li>
     *   <li>{@code shipped} — waits for DELIVER or RETURN_REQUESTED</li>
     *   <li>{@code returning} — waits for RETURN_COMPLETED</li>
     *   <li>{@code delivered}, {@code cancelled}, {@code expired}, {@code returned} — terminal</li>
     * </ul>
     *
     * @return immutable state machine definition
     */
    public static StateMachineDef buildStateMachine() {
        return StateMachineBuilder.create("orderLifecycle")
                .maxTransitions(50)
                .maxStateVisits(5)
                .globalTimeout(Duration.ofDays(7))
                .globalTransition("CANCEL", "cancelled")
                .state("pending").initial()
                    .on("CONFIRM").goTo("confirmed")
                    .timeout(Duration.ofHours(24)).onTimeout("expired")
                    .done()
                .state("confirmed")
                    .on("SHIP").goTo("shipped")
                    .timeout(Duration.ofHours(48)).onTimeout("expired")
                    .done()
                .state("shipped")
                    .on("DELIVER").goTo("delivered")
                    .on("RETURN_REQUESTED").goTo("returning")
                    .timeout(Duration.ofDays(14)).onTimeout("expired")
                    .done()
                .state("returning")
                    .on("RETURN_COMPLETED").goTo("returned")
                    .done()
                .state("delivered").terminal().done()
                .state("cancelled").terminal().done()
                .state("expired").terminal().done()
                .state("returned").terminal().done()
                .build();
    }

    /**
     * Executes the order lifecycle with signals, listener logging, and checkpoint/resume.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        GraphEngine engine = GraphEngine.builder().registry(registry).build();

        // 1. Create a listener for audit logging
        AuditListener audit = new AuditListener();
        StateMachineExecutor executor = StateMachineExecutor.builder(engine)
                .listeners(List.of(audit))
                .build();
        StateMachineDef def = buildStateMachine();

        // 2. Start the machine
        StateMachineResult result = executor.execute(def, Map.of(
                "orderId", "ORD-5001",
                "customerId", "CUST-77"
        ));
        System.out.println("After execute: " + describe(result));

        // 3. Signal CONFIRM
        result = executor.signal(result.instance(), def, "CONFIRM", Map.of("confirmedBy", "system"));
        System.out.println("After CONFIRM: " + describe(result));

        // 4. Create a checkpoint (simulating persistence)
        StateMachineCheckpoint checkpoint = executor.createCheckpoint(result.instance());
        System.out.println("Checkpoint saved at state: " + checkpoint.currentStateId());

        // 4b. Derive a checkpoint variant with an extended deadline (toBuilder)
        StateMachineCheckpoint extended = checkpoint.toBuilder()
            .stateTimeoutDeadline(checkpoint.checkpointedAt().plus(Duration.ofHours(1)))
                .globalTimeoutDeadline(checkpoint.checkpointedAt().plus(Duration.ofHours(2)))
                .build();
        System.out.println("Extended checkpoint deadline: " + extended.globalTimeoutDeadline());

        // 5. Simulate a restart by creating a new executor and resuming
        StateMachineExecutor executor2 = StateMachineExecutor.builder(engine)
                .listeners(List.of(audit))
                .build();
        result = executor2.resumeFromCheckpoint(def, extended);
        System.out.println("After resume: " + describe(result));

        // 6. Signal SHIP
        result = executor2.signal(result.instance(), def, "SHIP", Map.of("carrier", "FedEx"));
        System.out.println("After SHIP: " + describe(result));

        // 7. Signal DELIVER → terminal
        result = executor2.signal(result.instance(), def, "DELIVER", Map.of("deliveredAt", "2025-01-15"));
        System.out.println("After DELIVER: " + describe(result));

        // 8. Show final context
        System.out.println("Final context: " + new LinkedHashMap<>(result.instance().sharedContext()));

        // --- Demonstrate global CANCEL transition ---
        System.out.println("\n--- Cancel scenario ---");
        StateMachineExecutor executor3 = StateMachineExecutor.builder(engine)
                .listeners(List.of(audit))
                .build();
        result = executor3.execute(def, Map.of("orderId", "ORD-5002", "customerId", "CUST-88"));
        System.out.println("Started: " + describe(result));

        result = executor3.signal(result.instance(), def, "CANCEL", Map.of("reason", "customer request"));
        System.out.println("After CANCEL: " + describe(result));
    }

    /**
     * Formats a result for display.
     */
    private static String describe(StateMachineResult result) {
        return result.status() + " state=" + result.instance().currentStateId()
                + " awaited=" + result.awaitedEvents();
    }

    /**
     * Example {@link StateMachineListener} that prints lifecycle events to stdout.
     *
     * <p>This listener demonstrates the audit logging pattern — capturing every
     * state entry, transition, and signal for compliance or debugging purposes.
     */
    static final class AuditListener implements StateMachineListener {

        @Override
        public void onStateMachineStart(StateMachineStartEvent event) {
            System.out.println("  [AUDIT] START " + event.machineName() + " context=" + event.initialContext());
        }

        @Override
        public void onStateMachineComplete(StateMachineCompleteEvent event) {
            System.out.println("  [AUDIT] COMPLETE " + event.machineName() + " status=" + event.finalStatus());
        }

        @Override
        public void onStateEnter(StateEnterEvent event) {
            System.out.println("  [AUDIT] ENTER state=" + event.stateId() + " type=" + event.stateType());
        }

        @Override
        public void onTransition(TransitionEvent event) {
            System.out.println("  [AUDIT] TRANSITION " + event.fromState() + " --[" + event.event() + "]--> " + event.toState());
        }

        @Override
        public void onSignalReceived(SignalReceivedEvent event) {
            System.out.println("  [AUDIT] SIGNAL event=" + event.event() + " in state=" + event.stateId() + " payload=" + event.payload());
        }

        @Override
        public void onCheckpointSaved(CheckpointSavedEvent event) {
            System.out.println("  [AUDIT] CHECKPOINT SAVED at state=" + event.currentStateId());
        }

        @Override
        public void onCheckpointRestored(CheckpointRestoredEvent event) {
            System.out.println("  [AUDIT] CHECKPOINT RESTORED at state=" + event.restoredStateId());
        }
    }
}
