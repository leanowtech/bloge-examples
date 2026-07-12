package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.change.VisualChangeEventPublisher;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeFact;

import java.util.List;

/** Append-only transactional source for integration change facts. */
public interface IntegrationChangeEventOutbox extends VisualChangeEventPublisher {

    @Override
    default void publish(VisualChangeFact fact) {
        append(IntegrationChangeEvent.from(fact));
    }

    IntegrationChangeEvent append(IntegrationChangeEvent event);

    List<IntegrationChangeEvent> read(long afterSequence,
                                      long throughSequence,
                                      String tenantId,
                                      String environmentId,
                                      int limit);

    boolean hasAfter(long afterSequence,
                     long throughSequence,
                     String tenantId,
                     String environmentId);

    long highWaterSequence();

    boolean available();

}
