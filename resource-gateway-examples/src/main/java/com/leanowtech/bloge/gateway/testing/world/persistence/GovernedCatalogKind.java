package com.leanowtech.bloge.gateway.testing.world.persistence;

import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.Scenario;

/** The three Stage 1 assets stored by the generic governed catalog. */
public enum GovernedCatalogKind {
    LOGICAL_RESOURCE_CONTRACT(LogicalResourceContract.class),
    RESOURCE_WORLD_MODEL(ResourceWorldModel.class),
    SCENARIO(Scenario.class);

    private final Class<?> valueType;

    GovernedCatalogKind(Class<?> valueType) {
        this.valueType = valueType;
    }

    public Class<?> valueType() {
        return valueType;
    }

    public boolean accepts(Object value) {
        return value != null && valueType.isInstance(value);
    }
}
