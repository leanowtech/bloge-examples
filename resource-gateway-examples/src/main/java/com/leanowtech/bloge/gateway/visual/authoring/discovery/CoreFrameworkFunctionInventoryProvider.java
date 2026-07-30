package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.leanowtech.bloge.core.spi.BuiltInFunctions;
import com.leanowtech.bloge.core.spi.ExpressionFunction;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime inventory for the exact BLOGE core functions available on this process classpath.
 */
@Component
@Order(0)
public final class CoreFrameworkFunctionInventoryProvider
        implements FrameworkFunctionInventoryProvider {

    @Override
    public String providerId() {
        return "bloge-core";
    }

    @Override
    public String runtimeProfile() {
        return "bloge-core/default";
    }

    @Override
    public List<FunctionBinding> functions() {
        Map<String, ExpressionFunction> functions = new LinkedHashMap<>();
        BuiltInFunctions.registerAll(functions);
        return functions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new FunctionBinding(entry.getKey(), entry.getValue(), null))
                .toList();
    }
}
