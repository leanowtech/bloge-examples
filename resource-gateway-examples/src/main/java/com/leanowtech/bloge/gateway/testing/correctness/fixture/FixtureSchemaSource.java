package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;

/** Exact governed schema resolver used before Fixture review or activation. */
@FunctionalInterface
public interface FixtureSchemaSource {

    boolean schemaIsCurrent(EnterpriseScope scope, ExactSchemaRef schemaRef);

    static FixtureSchemaSource denyAll() {
        return (scope, ref) -> false;
    }
}
