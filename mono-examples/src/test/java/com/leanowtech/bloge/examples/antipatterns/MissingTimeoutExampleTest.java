package com.leanowtech.bloge.examples.antipatterns;

import com.leanowtech.bloge.core.engine.GraphResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingTimeoutExampleTest {

    @Test
    void guardedGraph_failsFastAndFallsBack() {
        GraphResult slow = MissingTimeoutExample.executeWithoutTimeout("REPORT-1");
        GraphResult guarded = MissingTimeoutExample.executeWithTimeout("REPORT-1");

        MissingTimeoutExample.ReportSummary slowSummary =
                slow.getOutput("publishReport", MissingTimeoutExample.ReportSummary.class);
        MissingTimeoutExample.ReportSummary guardedSummary =
                guarded.getOutput("publishReport", MissingTimeoutExample.ReportSummary.class);

        assertTrue(slow.isSuccess());
        assertTrue(guarded.isSuccess());
        assertFalse(slowSummary.timedOut());
        assertTrue(guardedSummary.timedOut());
        assertTrue(slow.elapsed().toMillis() >= 200, "Slow graph should reflect the unbounded wait");
        assertTrue(guarded.elapsed().toMillis() < slow.elapsed().toMillis(), "Timeout should shorten the wait path");
        assertTrue(MissingTimeoutExample.lintDslExample().stream()
                .anyMatch(diagnostic -> diagnostic.ruleId().equals("missing-timeout")));
    }
}
