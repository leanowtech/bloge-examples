package com.leanowtech.bloge.examples.durable;

import com.leanowtech.bloge.core.runtime.checkpoint.CheckpointType;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpoint;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.durable.RuntimeStores;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class DurableCheckpointExample {

    public static void main(String[] args) {
        DataSource dataSource = EmbeddedH2DataSource.inMemory("durable_checkpoint_example");
        RuntimeStores stores = DurableRuntimeExampleSupport.runtimeStores(dataSource, true);
        String executionId = "exec-" + UUID.randomUUID();
        ExecutionInstance execution = DurableRuntimeExampleSupport.runningExecution(executionId, "durableCheckpointGraph");
        Instant now = Instant.now();
        stores.executionStore().create(execution);
        stores.executionCheckpointStore().save(ExecutionCheckpoint.builder(execution.identity(), CheckpointType.NODE_OUTPUT, "fetchOrder")
                .payload("{\"orderId\":\"ORD-1\"}")
                .schemaVersion("1")
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build());
        stores.executionCheckpointStore().save(ExecutionCheckpoint.builder(execution.identity(), CheckpointType.NODE_OUTPUT, "riskCheck")
                .payload("{\"risk\":\"LOW\"}")
                .schemaVersion("1")
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build());

        RuntimeStores recoveredStores = DurableRuntimeExampleSupport.runtimeStores(dataSource, false);
        List<ExecutionCheckpoint> recovered = recoveredStores.executionCheckpointStore().loadAll(executionId);

        System.out.println("Recovered checkpoints: " + recovered.size());
        recovered.forEach(cp -> System.out.println(" - " + cp.nodeId() + " => " + cp.payload()));
    }
}
