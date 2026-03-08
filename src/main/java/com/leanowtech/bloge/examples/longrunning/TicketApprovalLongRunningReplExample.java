package com.leanowtech.bloge.examples.longrunning;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.Map;
import java.util.Scanner;

public class TicketApprovalLongRunningReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("CreateTicketOperator", TicketApprovalLongRunningDslExample.CREATE_TICKET);
        registry.register("ClassifyTicketOperator", TicketApprovalLongRunningDslExample.CLASSIFY_TICKET);
        registry.register("NotifyManagerOperator", TicketApprovalLongRunningDslExample.NOTIFY_MANAGER);
        registry.registerRaw("WaitApprovalOperator", TicketApprovalLongRunningDslExample.WAIT_APPROVAL);
        registry.register("SendConfirmationOperator", TicketApprovalLongRunningDslExample.SEND_CONFIRMATION);
        registry.register("AuditLogOperator", TicketApprovalLongRunningDslExample.AUDIT_LOG);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String customerId = ReplHelper.promptString(scanner, "customerId", "CUST-99");
        String message = ReplHelper.promptString(scanner, "message", "Duplicate charge on order #4501");
        String ticketId = ReplHelper.promptString(scanner, "ticketId", "TKT-CUST-99-001");
        return Map.of(
                "customerId", customerId,
                "message", message,
                "ticketId", ticketId
        );
    }

    public static void main(String[] args) throws Exception {
        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("Ticket Approval Long Running REPL");
                Map<String, Object> values = promptContext(scanner);

                var registry = new DefaultOperatorRegistry();
                Graph graph = buildGraph(registry);
                var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
                var engine = runtime.engine();

                GraphResult phase1 = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(phase1);

                if (phase1.isSuspended() || phase1.getStatus("waitApproval") == NodeStatus.SUSPENDED) {
                    System.out.print("Timer will fire in 2s... (press Enter to advance)");
                    scanner.nextLine();
                    Thread.sleep(2100);

                    runtime.saveNodeOutput(phase1.executionId(), "ticketApprovalWait", "waitApproval",
                            LongRunningRuntimeExampleSupport.payload(
                                    "decision", "auto-approved",
                                    "approver", "system",
                                    "reason", "No manager response within SLA"
                            ));

                    GraphResult phase2 = engine.resume(graph, phase1.executionId(), new GraphContext(values));
                    ReplHelper.printResult(phase2);
                }

                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
