package com.leanowtech.bloge.gateway.visualadapter.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;

/**
 * Payload-bearing boundary used while deriving a governed graph-node Fixture descriptor.
 */
@FunctionalInterface
public interface PromotedGraphNodeFixtureMaterialWriter {

    /**
     * Redacts, protects, persists, and returns the payload-free receipt for one material revision.
     *
     * @param request complete protected-material write request derived from the graph draft
     * @param identity trusted authenticated caller context
     * @return payload-free material receipt
     */
    Receipt write(WriteRequest request, IntegrationRequestContext identity);
}
