package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/** Internal read-only shape shared by lifecycle v1 and receipt-aware v2 verification. */
interface TestSuiteStabilityObservationLedgerLifecyclePageView {
    String lifecyclePageId();

    String pageFingerprint();

    TestSuiteStabilityObservationLedgerLifecycleRequest request();

    String scopeFingerprint();

    TestSuiteStabilityObservationLedgerLifecyclePage.Floor startingFloor();

    TestSuiteStabilityObservationLedgerLifecyclePage.Floor terminalFloor();

    TestSuiteStabilityObservationLedgerLifecyclePage.Floor currentFloor();

    TestSuiteStabilityObservationLedgerLifecyclePage.Head head();

    boolean hasMore();

    JsonNode rawResponse();

    String outerKeyId();
}
