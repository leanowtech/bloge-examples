package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Instant;
import java.util.Map;

/**
 * DSL version of the ticket-approval long-running example.
 *
 * <p>Loads the {@code ticket-approval-wait.bloge} graph definition and executes it
 * with Map-based operators. The {@code waitApproval} operator throws
 * a suspended result to demonstrate the runtime suspend/resume mechanism;
 * the example then simulates a manager signal and resumes execution.
 *
 * <p>Run {@link #main(String[])} to execute the full suspend-signal-resume lifecycle.
 */
@SuppressWarnings("preview")
public class TicketApprovalLongRunningDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> CREATE_TICKET = (input, ctx) -> {
        Thread.sleep(20);
        String ticketId = "TKT-" + input.get("customerId") + "-001";
        System.out.println("  [createTicket]  created " + ticketId);
        return Map.of("ticketId", ticketId, "customerId", input.get("customerId"), "status", "open");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CLASSIFY_TICKET = (input, ctx) -> {
        Thread.sleep(30);
        System.out.println("  [classifyTicket] → billing / urgent");
        return Map.of("category", "billing", "urgency", "urgent");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> NOTIFY_MANAGER = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [notifyManager] → manager notified, awaiting approval");
        return Map.of("notificationId", "NOTIF-001", "sentAt", Instant.now().toString());
    };

    /** Returns a suspended result to pause execution until signal or timer. */
    static final SuspendableOperator<Map<String, Object>, Map<String, Object>> WAIT_APPROVAL = (input, ctx) -> {
        String ticketId = ctx.graphContext().get("ticketId", String.class);
        System.out.println("  [waitApproval]  SUSPENDING — signal_key='" + ticketId + "'");
        return OperatorResult.suspend(ticketId, null, java.time.Duration.ofSeconds(1));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SEND_CONFIRMATION = (input, ctx) -> {
        Thread.sleep(20);
        System.out.printf("  [sendConfirmation] decision=%s via email%n", input.get("decision"));
        return Map.of("sentAt", Instant.now().toString(), "channel", "email");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> AUDIT_LOG = (input, ctx) -> {
        Thread.sleep(10);
        System.out.println("  [auditLog] logged decision=" + input.get("decision"));
        return Map.of("auditId", "AUDIT-" + System.currentTimeMillis(), "recordedAt", Instant.now().toString());
    };

    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        registry.register("CreateTicketOperator",     CREATE_TICKET);
        registry.register("ClassifyTicketOperator",   CLASSIFY_TICKET);
        registry.register("NotifyManagerOperator",    NOTIFY_MANAGER);
        // waitApproval is a `wait` DSL node — skipped by DslCompiler; wire a suspendable
        // operator manually to demonstrate the suspend mechanism with the DSL structure.
        registry.registerRaw("WaitApprovalOperator",     WAIT_APPROVAL);
        registry.register("SendConfirmationOperator", SEND_CONFIRMATION);
        registry.register("AuditLogOperator",         AUDIT_LOG);

        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();
        var loader = new GraphLoader(registry);

        // The DSL below omits `wait` syntax (compiler skips wait nodes);
        // instead a regular WaitApprovalOperator demonstrates the suspended-result pattern.
        String dsl = """
                graph ticketApprovalWait {

                  node createTicket : CreateTicketOperator {
                    input {
                      customerId = ctx.customerId
                      message    = ctx.message
                    }
                  }

                  node classifyTicket : ClassifyTicketOperator {
                    depends_on = [createTicket]
                    input {
                      ticketId = createTicket.output.ticketId
                      message  = ctx.message
                    }
                    timeout = 5s
                  }

                  node notifyManager : NotifyManagerOperator {
                    depends_on = [classifyTicket]
                    input {
                      ticketId = createTicket.output.ticketId
                      category = classifyTicket.output.category
                      urgency  = classifyTicket.output.urgency
                    }
                  }

                  /// Represents: wait waitApproval = 48h after notifyManager { signal_key = ctx.ticketId }
                  /// WaitApprovalOperator returns a suspended result with signal_key = ctx.ticketId
                  node waitApproval : WaitApprovalOperator {
                    depends_on = [notifyManager]
                    input {
                      ticketId = createTicket.output.ticketId
                    }
                  }

                  node sendConfirmation : SendConfirmationOperator {
                    depends_on = [waitApproval]
                    input {
                      ticketId   = createTicket.output.ticketId
                      customerId = ctx.customerId
                      decision   = waitApproval.output.decision
                      approver   = waitApproval.output.approver
                    }
                  }

                  node auditLog : AuditLogOperator {
                    depends_on = [sendConfirmation]
                    input {
                      ticketId  = createTicket.output.ticketId
                      decision  = waitApproval.output.decision
                      timestamp = sendConfirmation.output.sentAt
                    }
                  }
                }
                """;

        Graph graph = loader.load(dsl);

        var ctx = new GraphContext(Map.of(
                "customerId", "CUST-99",
                "message", "Duplicate charge on order #4501",
                "ticketId", "TKT-CUST-99-001"
        ));

        // ── Phase 1: execute until suspension ────────────────────────────────
        System.out.println("\n═══ Phase 1 (DSL): Execute until suspension ═══");
        GraphResult phase1 = engine.execute(graph, ctx);

        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());
        for (var e : phase1.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", e.getKey(), e.getValue());
        }

        String execId = phase1.executionId();

        // ── Phase 2: manager signals approval ────────────────────────────────
        System.out.println("\n═══ Phase 2 (DSL): Manager approves ticket ═══");
        Thread.sleep(100); // simulate async manager decision

        Map<String, Object> approvalPayload = Map.of(
                "decision", "approved",
                "approver", "manager@example.com",
                "reason",   "Verified duplicate charge; refund authorised");

        // Persist the signal as completed runtime node output so resume() skips this node
        runtime.saveNodeOutput(execId, "ticketApprovalWait", "waitApproval", approvalPayload);
        System.out.println("Signal saved as runtime node output for 'waitApproval'");

        // ── Phase 3: resume — downstream nodes run ────────────────────────────
        System.out.println("\n═══ Phase 3 (DSL): Resume execution ═══");
        GraphResult phase3 = engine.resume(graph, execId, ctx);

        System.out.println("\n═══ Final DSL Result ═══");
        System.out.println("Success: " + phase3.isSuccess());
        System.out.println("Elapsed: " + phase3.elapsed().toMillis() + "ms");
        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", e.getKey(), e.getValue());
        }
        if (phase3.getStatus("auditLog") == NodeStatus.COMPLETED) {
            System.out.println("Audit: " + phase3.results().getRaw("auditLog"));
        }
    }
}
