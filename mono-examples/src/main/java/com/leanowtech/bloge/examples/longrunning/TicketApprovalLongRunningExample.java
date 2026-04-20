package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Demonstrates the long-running <em>wait-for-approval</em> pattern using the
 * Java Fluent API.
 *
 * <p>A customer-service ticket is created, classified, and then <em>suspended</em>
 * while waiting for a manager to approve or reject it. If no decision arrives
 * within the configured timeout the helper-managed in-memory timer fires and
 * auto-approves the ticket.
 *
 * <h2>Graph layout</h2>
 * <pre>
 * createTicket → classifyTicket → notifyManager
 *                                     ↓
 *                              [SUSPEND waitApproval]
 *                              (signal OR 2-second timer)
 *                                     ↓
 *                              sendConfirmation → auditLog
 * </pre>
 *
 * <h2>Long-running lifecycle (Fluent API)</h2>
 * <ol>
 *   <li>{@code engine.executeWithOperators(graph, ctx, ops)} — graph runs to
 *       {@code waitApproval}, operator returns a suspended result, execution
 *       returns {@link GraphResult#isSuspended()} == {@code true}.</li>
 *   <li>External event: manager approves the ticket (or timer fires automatically).</li>
 *   <li>Persist the signal data as runtime node output with
 *       {@code runtime.saveNodeOutput(execId, "ticketApprovalWait", "waitApproval", decision)}
 *       so the suspended node is treated as completed.</li>
 *   <li>{@code engine.resume(graph, execId, ctx)} — engine skips already-saved runtime node outputs
 *       and runs {@code sendConfirmation} and {@code auditLog}.</li>
 * </ol>
 */
@SuppressWarnings("preview")
public class TicketApprovalLongRunningExample {

    // ── Records ──────────────────────────────────────────────────────────────

    public record TicketInput(String customerId, String message) {}
    public record Ticket(String ticketId, String customerId, String status) {}

    public record ClassifyInput(String ticketId, String message) {}
    public record Classification(String category, String urgency) {}

    public record NotifyInput(String ticketId, String category, String urgency) {}
    public record NotifyResult(String notificationId, String sentAt) {}

    /** Approval decision — either produced by manager signal or by timer auto-close. */
    public record ApprovalDecision(String decision, String approver, String reason) {}

    public record ConfirmInput(String ticketId, String customerId, ApprovalDecision decision) {}
    public record ConfirmResult(String sentAt, String channel) {}

    public record AuditInput(String ticketId, ApprovalDecision decision, String timestamp) {}
    public record AuditResult(String auditId, String recordedAt) {}

    // ── Operators ─────────────────────────────────────────────────────────────

    static final Operator<TicketInput, Ticket> CREATE_TICKET = (input, ctx) -> {
        Thread.sleep(20);
        String ticketId = "TKT-" + input.customerId() + "-" + System.currentTimeMillis();
        System.out.println("  [createTicket]  created " + ticketId);
        return new Ticket(ticketId, input.customerId(), "open");
    };

    static final Operator<ClassifyInput, Classification> CLASSIFY_TICKET = (input, ctx) -> {
        Thread.sleep(30);
        System.out.println("  [classifyTicket] → billing / urgent");
        return new Classification("billing", "urgent");
    };

    static final Operator<NotifyInput, NotifyResult> NOTIFY_MANAGER = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [notifyManager] → manager notified, waiting for approval");
        return new NotifyResult("NOTIF-001", Instant.now().toString());
    };

    /**
     * Approval gate: returns a suspended result with a 2-second timeout
     * (shortened from 48 h for the example).  When the timer fires, the engine
     * calls {@code signal(executionId, "waitApproval", timerPayload)} automatically.
     */
    static final SuspendableOperator<NotifyResult, ApprovalDecision> WAIT_APPROVAL = (input, ctx) -> {
        String ticketId = ctx.graphContext().get("ticketId", String.class);
        System.out.println("  [waitApproval]  SUSPENDING — key='" + ticketId + "', timeout=2s");
        // partialOutput = null; timer will supply the output on fire
        return OperatorResult.suspend(ticketId, null, Duration.ofSeconds(2));
    };

    static final Operator<ConfirmInput, ConfirmResult> SEND_CONFIRMATION = (input, ctx) -> {
        Thread.sleep(20);
        System.out.printf("  [sendConfirmation] customer=%s decision=%s%n",
                input.customerId(), input.decision().decision());
        return new ConfirmResult(Instant.now().toString(), "email");
    };

    static final Operator<AuditInput, AuditResult> AUDIT_LOG = (input, ctx) -> {
        Thread.sleep(10);
        System.out.println("  [auditLog] recorded decision=" + input.decision().decision());
        return new AuditResult("AUDIT-" + System.currentTimeMillis(), Instant.now().toString());
    };

    // ── Main ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {

        var registry = new DefaultOperatorRegistry();

        // The runtime helper wires an in-memory timer service so that when the suspend timeout fires
        // it automatically calls signal(executionId, nodeId, firePayload).
        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();

        // ── Build graph ───────────────────────────────────────────────────────
        Graph graph = Graph.builder("ticketApprovalWait")
                .node("createTicket", CREATE_TICKET)
                    .input((results, ctx) -> new TicketInput(
                            ctx.get("customerId", String.class),
                            ctx.get("message", String.class)))
                .node("classifyTicket", CLASSIFY_TICKET)
                    .dependsOn("createTicket")
                    .input((results, ctx) -> new ClassifyInput(
                            results.get("createTicket", Ticket.class).ticketId(),
                            ctx.get("message", String.class)))
                .node("notifyManager", NOTIFY_MANAGER)
                    .dependsOn("classifyTicket")
                    .input((results, ctx) -> new NotifyInput(
                            results.get("createTicket", Ticket.class).ticketId(),
                            results.get("classifyTicket", Classification.class).category(),
                            results.get("classifyTicket", Classification.class).urgency()))
                .suspendNode("waitApproval", WAIT_APPROVAL)
                    .dependsOn("notifyManager")
                    .input((results, ctx) -> results.get("notifyManager", NotifyResult.class))
                .node("sendConfirmation", SEND_CONFIRMATION)
                    .dependsOn("waitApproval")
                    .input((results, ctx) -> {
                        var ticket = results.get("createTicket", Ticket.class);
                        var decision = results.get("waitApproval", ApprovalDecision.class);
                        return new ConfirmInput(ticket.ticketId(), ticket.customerId(), decision);
                    })
                .node("auditLog", AUDIT_LOG)
                    .dependsOn("sendConfirmation")
                    .input((results, ctx) -> {
                        var ticket = results.get("createTicket", Ticket.class);
                        var decision = results.get("waitApproval", ApprovalDecision.class);
                        var confirm = results.get("sendConfirmation", ConfirmResult.class);
                        return new AuditInput(ticket.ticketId(), decision, confirm.sentAt());
                    })
                .build();

        // ── Phase 1: execute until suspension ────────────────────────────────
        var ctx = new GraphContext(Map.of(
                "customerId", "CUST-42",
                "message", "My invoice is wrong — charged twice for the same item",
                "ticketId", "TKT-CUST-42-001"
        ));

        System.out.println("\n═══ Phase 1: Execute until suspension ═══");
        GraphResult phase1 = engine.executeWithOperators(graph, ctx, Map.of(
                "createTicket",     CREATE_TICKET,
                "classifyTicket",   CLASSIFY_TICKET,
                "notifyManager",    NOTIFY_MANAGER,
                "waitApproval",     WAIT_APPROVAL,
                "sendConfirmation", SEND_CONFIRMATION,
                "auditLog",         AUDIT_LOG
        ));

        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());
        System.out.println("Suspended nodes: " + phase1.suspendedNodes());
        for (var e : phase1.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", e.getKey(), e.getValue());
        }

        String execId = phase1.executionId();
        // ── Phase 2a: timer fires automatically (2-second timeout) ─────────────
        // The helper-managed in-memory timer fires after the timeout duration, which calls
        // engine.signal(executionId, "waitApproval", timerPayload).
        // We then save the signal data as runtime node output and call resume().
        System.out.println("\n═══ Phase 2: Waiting for timer to fire (2 s)... ═══");
        Thread.sleep(2500); // wait slightly longer than the 2-second timeout

        // The auto-close payload (from on_timeout in the DSL / timer.firePayload() in Java)
        ApprovalDecision autoApproved = new ApprovalDecision("auto-approved", "system",
                "No manager response within SLA");
        System.out.println("Timer fired — saving signal as runtime node output");

        // Save the timer-fired output as completed runtime node output so resume() skips it
        runtime.saveNodeOutput(execId, "ticketApprovalWait", "waitApproval", autoApproved);

        // ── Phase 3: resume — runs sendConfirmation and auditLog ─────────────
        System.out.println("\n═══ Phase 3: Resume after timer signal ═══");
        // Ensure waitApproval output is available for downstream input assemblers
        ctx.put("waitApproval.decision", autoApproved);

        // For resume we must re-inject the operator map so the engine can resolve operators
        // by node ID (resume() uses the registry, so we register operators there too)
        registry.register("createTicket",     CREATE_TICKET);
        registry.register("classifyTicket",   CLASSIFY_TICKET);
        registry.register("notifyManager",    NOTIFY_MANAGER);
        registry.registerRaw("waitApproval",     WAIT_APPROVAL);
        registry.register("sendConfirmation", SEND_CONFIRMATION);
        registry.register("auditLog",         AUDIT_LOG);

        GraphResult phase3 = engine.resume(graph, execId, ctx);

        System.out.println("\n═══ Final Result ═══");
        System.out.println("Success: " + phase3.isSuccess());
        System.out.println("Elapsed: " + phase3.elapsed().toMillis() + "ms");

        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", e.getKey(), e.getValue());
        }

        if (phase3.getStatus("auditLog") == NodeStatus.COMPLETED) {
            AuditResult audit = phase3.getOutput("auditLog", AuditResult.class);
            System.out.println("\nAudit entry: " + audit);
        }
    }
}
