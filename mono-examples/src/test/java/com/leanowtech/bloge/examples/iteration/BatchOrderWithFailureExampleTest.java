package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.ReservedKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchOrderWithFailureExampleTest {

    @Test
    void fluentApi_continuesAfterItemFailure() {
        GraphResult result = BatchOrderWithFailureExample.execute();

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        BatchOrderWithFailureExample.BatchSummary summary = result.getOutput(
                BatchOrderWithFailureExample.NODE_SUMMARIZE,
                BatchOrderWithFailureExample.BatchSummary.class);
        assertEquals(3, summary.totalProcessed());
        assertEquals(2, summary.successCount());
        assertEquals(1, summary.failureCount());
        assertTrue(BatchOrderWithFailureExample.itemResults(result).stream()
                .anyMatch(item -> item.containsKey(ReservedKeys.ERROR)));
    }

    @Test
    void dsl_continuesAfterItemFailure() {
        GraphResult result = BatchOrderWithFailureDslExample.execute();

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        var summary = BatchOrderWithFailureDslExample.summary(result);
        assertEquals(3, ((Number) summary.get("totalProcessed")).intValue());
        assertEquals(2, ((Number) summary.get("successCount")).intValue());
        assertEquals(1, ((Number) summary.get("failureCount")).intValue());
        assertTrue(BatchOrderWithFailureExample.itemResults(result).stream()
                .anyMatch(BatchOrderWithFailureDslExample::isErrorItem));
    }
}