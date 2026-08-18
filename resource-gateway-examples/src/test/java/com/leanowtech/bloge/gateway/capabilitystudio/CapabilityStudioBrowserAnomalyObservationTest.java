package com.leanowtech.bloge.gateway.capabilitystudio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioBrowserAnomalyObservationTest {
    @Test
    void correlatesRealPutAnd409WhenRequestCallbackArrivesFirst() {
        CapabilityStudioBrowserAnomalyMatrixProducerIT.ConflictObservationTracker tracker =
                new CapabilityStudioBrowserAnomalyMatrixProducerIT.ConflictObservationTracker();

        tracker.requestSent("request-1");
        tracker.responseReceived("request-1", 409);

        assertThat(tracker.realPut409Observed()).isTrue();
        assertThat(tracker.observedStatus()).isEqualTo(409);
        assertThat(tracker.phase()).isEqualTo("REAL_PUT_409_CORRELATED");
    }

    @Test
    void correlatesRealPutAnd409WhenResponseCallbackArrivesFirst() {
        CapabilityStudioBrowserAnomalyMatrixProducerIT.ConflictObservationTracker tracker =
                new CapabilityStudioBrowserAnomalyMatrixProducerIT.ConflictObservationTracker();

        tracker.responseReceived("request-1", 409);
        assertThat(tracker.realPut409Observed()).isFalse();

        tracker.requestSent("request-1");

        assertThat(tracker.realPut409Observed()).isTrue();
        assertThat(tracker.observedStatus()).isEqualTo(409);
        assertThat(tracker.phase()).isEqualTo("REAL_PUT_409_CORRELATED");
    }

    @Test
    void doesNotTreatAnUnrelated409AsTheConflictTrigger() {
        CapabilityStudioBrowserAnomalyMatrixProducerIT.ConflictObservationTracker tracker =
                new CapabilityStudioBrowserAnomalyMatrixProducerIT.ConflictObservationTracker();

        tracker.requestSent("request-1");
        tracker.responseReceived("request-2", 409);

        assertThat(tracker.realPut409Observed()).isFalse();
        assertThat(tracker.observedStatus()).isZero();
    }

    @Test
    void acceptsErrorSurfaceCopyOnlyWhenItMatchesTheRequestedLocale() {
        String english = "What happened\nThe tutorial branch changed in another session.\n"
                + "Current impact\nYour unsaved values are still present.\n"
                + "Recovery action\nReload the latest revision before saving again.";
        String chinese = "发生了什么\n教程分支在另一个会话中发生了变化。\n"
                + "当前影响\n未保存的值仍然保留。\n"
                + "恢复动作\n重新加载最新版本后再保存。";

        assertThat(CapabilityStudioBrowserAnomalyMatrixProducerIT.safeBusinessError(english, "en-US"))
                .isTrue();
        assertThat(CapabilityStudioBrowserAnomalyMatrixProducerIT.safeBusinessError(chinese, "zh-CN"))
                .isTrue();
        assertThat(CapabilityStudioBrowserAnomalyMatrixProducerIT.safeBusinessError(english, "zh-CN"))
                .isFalse();
        assertThat(CapabilityStudioBrowserAnomalyMatrixProducerIT.safeBusinessError(chinese, "en-US"))
                .isFalse();
    }
}
