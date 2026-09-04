package com.leanowtech.bloge.gateway.solution;

import com.leanowtech.bloge.core.operator.OperatorContext;

import java.util.Map;

/**
 * Governed execution seam used by {@link InstructionCallOperator} after effect checks.
 *
 * <p>P2 supplies the pure dispatch boundary. P5 installs controlled write execution and
 * reconciliation behind this interface; absent infrastructure fails closed.</p>
 */
@FunctionalInterface
public interface InstructionDispatchChannel {
    /** Executes one bound instruction and returns mandatory {@code result} plus {@code reasoning}. */
    Map<String, Object> execute(
            InstructionContract instruction, Map<String, Object> values, OperatorContext context)
            throws Exception;
}
