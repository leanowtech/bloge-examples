package com.leanowtech.bloge.examples.durable;

import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.identity.ExecutionType;
import com.leanowtech.bloge.durable.DurableStoreFactory;

import javax.sql.DataSource;
import java.time.Instant;

final class DurableRuntimeExampleSupport {
    private DurableRuntimeExampleSupport() {
    }

    static DurableStoreFactory.RuntimeStores runtimeStores(DataSource dataSource, boolean migrateSchema) {
        DurableStoreFactory.Builder builder = DurableStoreFactory.builder(dataSource);
        if (migrateSchema) {
            builder.migrateSchema();
        }
        return builder.runtimeStores();
    }

    static ExecutionIdentity identity(String executionId, String graphName) {
        return new ExecutionIdentity(
                "tenant-demo",
                "examples",
                graphName + ':' + executionId,
                executionId,
                ExecutionType.GRAPH,
                graphName,
                "1.0.0",
                graphName + "-hash",
                null,
                "examples-shard",
                null,
                "req-" + executionId
        );
    }

    static ExecutionInstance runningExecution(String executionId, String graphName) {
        Instant now = Instant.now();
        return new ExecutionInstance(
                identity(executionId, graphName),
                ExecutionStatus.RUNNING,
                null,
                null,
                null,
                0,
                null,
                now,
                now,
                null
        );
    }
}
