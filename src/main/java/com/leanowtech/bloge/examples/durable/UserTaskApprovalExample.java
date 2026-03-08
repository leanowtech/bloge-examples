package com.leanowtech.bloge.examples.durable;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.task.TaskInbox;
import com.leanowtech.bloge.core.runtime.task.TaskInboxQuery;
import com.leanowtech.bloge.core.runtime.task.TaskInboxStatus;
import com.leanowtech.bloge.durable.DurableStoreFactory;
import com.leanowtech.bloge.durable.UserTaskOperator;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UserTaskApprovalExample {

    public static void main(String[] args) {
        DataSource dataSource = EmbeddedH2DataSource.inMemory("user_task_example");
        DurableStoreFactory.RuntimeStores stores = DurableRuntimeExampleSupport.runtimeStores(dataSource, true);
        ExecutionInstance execution = DurableRuntimeExampleSupport.runningExecution("exec-loan-1", "loanApprovalGraph");
        stores.executionStore().create(execution);

        UserTaskOperator operator = new UserTaskOperator(
                DurableStoreFactory.wrapTaskInboxStore(stores.taskInboxStore(), stores.executionStore()),
                "loan-approval",
                null,
                List.of("risk-team")
        );

        Map<String, Object> input = Map.of(
                "title", "Loan approval required",
                "description", "Approve or reject loan LN-100",
                "priority", 4,
                "dueDate", Instant.now().plusSeconds(3600).toString(),
                "formData", Map.of("loanId", "LN-100", "amount", 120000)
        );
        OperatorContext ctx = new OperatorContext(
                "manualReview", "loanApprovalGraph", new GraphContext(), 0, "exec-loan-1");

        OperatorResult<Map<String, Object>> result = operator.execute(input, ctx);
        if (result instanceof OperatorResult.Suspended<Map<String, Object>> suspended) {
            System.out.println("Execution suspended: " + suspended.suspendKey());
        }

        List<TaskInbox> openTasks = stores.taskInboxStore().query(new TaskInboxQuery(
                null,
                "GROUP",
                "risk-team",
                Set.of(TaskInboxStatus.OPEN),
                null,
                null,
                "loan-approval",
                execution.identity().executionId(),
                0,
                20
        ));
        if (openTasks.isEmpty()) {
            System.out.println("No open tasks found.");
            return;
        }

        TaskInbox task = openTasks.get(0);
        TaskInbox claimed = stores.taskInboxStore().claim(task.taskId(), "alice", task.version()).orElseThrow();
        stores.taskInboxStore().complete(claimed.taskId(), Map.of("decision", "approved"), "alice", claimed.version());

        TaskInbox completed = stores.taskInboxStore().get(task.taskId()).orElseThrow();
        System.out.println("Task status: " + completed.status() + ", assignee: " + completed.assignee());
    }
}
