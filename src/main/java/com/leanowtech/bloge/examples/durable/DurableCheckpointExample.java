package com.leanowtech.bloge.examples.durable;

import com.leanowtech.bloge.core.runtime.checkpoint.CheckpointType;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpoint;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.durable.DurableStoreFactory;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class DurableCheckpointExample {

    public static void main(String[] args) {
        DataSource dataSource = EmbeddedH2DataSource.inMemory("durable_checkpoint_example");
        DurableStoreFactory.RuntimeStores stores = DurableRuntimeExampleSupport.runtimeStores(dataSource, true);
        String executionId = "exec-" + UUID.randomUUID();
        ExecutionInstance execution = DurableRuntimeExampleSupport.runningExecution(executionId, "durableCheckpointGraph");
        Instant now = Instant.now();
        stores.executionStore().create(execution);
        stores.executionCheckpointStore().save(new ExecutionCheckpoint(
                execution.identity(),
                CheckpointType.NODE_OUTPUT,
                "fetchOrder",
                null,
                "{\"orderId\":\"ORD-1\"}",
                null,
                "1",
                0,
                now,
                now
        ));
        stores.executionCheckpointStore().save(new ExecutionCheckpoint(
                execution.identity(),
                CheckpointType.NODE_OUTPUT,
                "riskCheck",
                null,
                "{\"risk\":\"LOW\"}",
                null,
                "1",
                0,
                now,
                now
        ));

        DurableStoreFactory.RuntimeStores recoveredStores = DurableRuntimeExampleSupport.runtimeStores(dataSource, false);
        List<ExecutionCheckpoint> recovered = recoveredStores.executionCheckpointStore().loadAll(executionId);

        System.out.println("Recovered checkpoints: " + recovered.size());
        recovered.forEach(cp -> System.out.println(" - " + cp.nodeId() + " => " + cp.payload()));
    }
}
