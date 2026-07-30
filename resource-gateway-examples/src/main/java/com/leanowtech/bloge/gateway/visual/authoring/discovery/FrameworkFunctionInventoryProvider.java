package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;

import java.util.Collection;

/**
 * Extension point through which an embedding application exposes its effective function runtime.
 *
 * <p>A provider may omit {@code declaredContract} when its framework SPI only exposes a callable
 * implementation. Such a function is discoverable and testable, but cannot pass signature parity
 * until a provider supplies an authoritative contract.</p>
 */
public interface FrameworkFunctionInventoryProvider {

    /** Stable provider identity included in runtime fingerprints. */
    String providerId();

    /** Target runtime profile to which the functions are bound. */
    String runtimeProfile();

    /** Bounded in-memory snapshot; implementations must not perform remote I/O. */
    Collection<FunctionBinding> functions();

    record FunctionBinding(
            String callableName,
            ExpressionFunction function,
            OperatorLibrary.BuiltInFunction declaredContract
    ) {
        public FunctionBinding {
            callableName = callableName == null ? "" : callableName.trim();
        }
    }
}
