package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;

/** Planning-only descriptor binding used when the real httpResource operator is unavailable. */
final class EphemeralHttpResourceOperator implements Operator<Object, Object> {

    @Override
    public Object execute(Object input, OperatorContext context) {
        throw new UnsupportedOperationException(
                "Ephemeral httpResource binding is planning-only and cannot execute directly.");
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.UNKNOWN;
    }

    @Override
    public SideEffectType sideEffectType() {
        return SideEffectType.EXTERNAL_CALL;
    }
}
