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
}
