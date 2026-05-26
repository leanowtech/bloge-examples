package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.compiler.DslGraphDefinitionCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Factory methods for DSL graph-definition codecs used by the graph-engine service.
 *
 * <p>The upstream default JSON codec round-trips Java records, but BLOGE 0.8.3 adds
 * {@link AstNode.RetryDef#retryOnCategories()} as a {@link Set}. JSON arrays are deserialized
 * as lists, so retry-enabled AST nodes can otherwise reappear as raw maps during registry
 * evolution checks. These helpers keep the stored wire format unchanged and repair that
 * AST shape before handing it back to the DSL compiler.</p>
 */
public final class GraphEngineDslCodecs {

    private GraphEngineDslCodecs() {
    }

    /**
     * Creates a DSL graph-definition codec that preserves retry AST nodes across JSON round-trips.
     *
     * @param jsonCodec service JSON codec used for the registry payload
     * @return graph-definition codec safe for graph-engine runtime artifact publication
     */
    public static DslGraphDefinitionCodec graphDefinitionCodec(JsonCodec jsonCodec) {
        return new DslGraphDefinitionCodec(astRoundTripJsonCodec(jsonCodec));
    }

    /**
     * Creates a DSL graph-definition codec with a custom compiler factory and retry-safe AST repair.
     *
     * @param jsonCodec service JSON codec used for the registry payload
     * @param compilerFactory factory used by {@link DslGraphDefinitionCodec#decode}
     * @return graph-definition codec safe for graph-engine runtime artifact publication
     */
    public static DslGraphDefinitionCodec graphDefinitionCodec(JsonCodec jsonCodec,
                                                               Function<OperatorRegistry, DslCompiler> compilerFactory) {
        return new DslGraphDefinitionCodec(astRoundTripJsonCodec(jsonCodec), compilerFactory);
    }

    static JsonCodec astRoundTripJsonCodec(JsonCodec jsonCodec) {
        return new AstRoundTripJsonCodec(jsonCodec == null ? JsonCodec.DEFAULT : jsonCodec);
    }

    private static final class AstRoundTripJsonCodec implements JsonCodec {
        private final JsonCodec delegate;

        private AstRoundTripJsonCodec(JsonCodec delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String serialize(Object value) {
            return delegate.serialize(value);
        }

        @Override
        public Object deserialize(String json) {
            return repair(delegate.deserialize(json));
        }

        private Object repair(Object value) {
            if (value instanceof AstNode.GraphDef graphDef) {
                return new AstNode.GraphDef(
                        graphDef.name(),
                        repairAstNodes(graphDef.members()),
                        graphDef.inputSchema(),
                        graphDef.outputSchema(),
                        graphDef.streamingOutputNodeId(),
                        graphDef.streamingInputs(),
                        graphDef.description(),
                        graphDef.line(),
                        graphDef.column()
                );
            }
            if (value instanceof AstNode.ExtensionDef extensionDef) {
                return new AstNode.ExtensionDef(
                        extensionDef.kind(),
                        extensionDef.id(),
                        extensionDef.properties(),
                        repairAstNodes(extensionDef.children()),
                        extensionDef.description(),
                        extensionDef.line(),
                        extensionDef.column()
                );
            }
            if (value instanceof AstNode.ForEachDef forEachDef) {
                return new AstNode.ForEachDef(
                        forEachDef.id(),
                        forEachDef.itemsExpr(),
                        forEachDef.sequential(),
                        forEachDef.itemVar(),
                        forEachDef.indexVar(),
                        forEachDef.scope(),
                        forEachDef.streaming(),
                        forEachDef.bufferSize(),
                        forEachDef.maxConcurrency(),
                        forEachDef.batchSize(),
                        forEachDef.onItemFailure(),
                        repairAstNodes(forEachDef.body()),
                        forEachDef.description(),
                        forEachDef.line(),
                        forEachDef.column()
                );
            }
            if (value instanceof AstNode.LoopDef loopDef) {
                return new AstNode.LoopDef(
                        loopDef.id(),
                        loopDef.maxIterations(),
                        loopDef.delay(),
                        loopDef.dependsOn(),
                        loopDef.scope(),
                        loopDef.streaming(),
                        loopDef.bufferSize(),
                        repairAstNodes(loopDef.body()),
                        loopDef.carryDef(),
                        loopDef.untilCondition(),
                        loopDef.exitRoutes(),
                        loopDef.description(),
                        loopDef.line(),
                        loopDef.column()
                );
            }
            if (value instanceof Map<?, ?> map) {
                return repairTaggedRecord(map);
            }
            return value;
        }

        private Object repairTaggedRecord(Map<?, ?> rawMap) {
            Object recordClass = rawMap.get("__recordClass__");
            Object rawData = rawMap.get("__data__");
            if (!(recordClass instanceof String className) || !(rawData instanceof Map<?, ?> rawDataMap)) {
                return rawMap;
            }
            Map<String, Object> data = stringKeyMap(rawDataMap);
            if (AstNode.NodeDef.class.getName().equals(className)) {
                return new AstNode.NodeDef(
                        stringValue(data.get("id")),
                        stringValue(data.get("operatorRef")),
                        cast(repair(data.get("input"))),
                        stringList(data.get("dependsOn")),
                        cast(repair(data.get("timeout"))),
                        cast(repair(data.get("retry"))),
                        cast(repair(data.get("fallback"))),
                        cast(repair(data.get("compensation"))),
                        cast(repair(data.get("inputSchema"))),
                        cast(repair(data.get("outputSchema"))),
                        cast(repair(data.get("scope"))),
                        booleanValue(data.get("streaming")),
                        integerValue(data.get("bufferSize")),
                        stringValue(data.get("executionMode")),
                        stringValue(data.get("workerTopic")),
                        cast(repair(data.get("upstreamResolutionPolicy"))),
                        stringValue(data.get("description")),
                        intValue(data.get("line")),
                        intValue(data.get("column"))
                );
            }
            if (AstNode.RetryDef.class.getName().equals(className)) {
                return new AstNode.RetryDef(
                        intValue(data.get("attempts")),
                        cast(repair(data.get("backoff"))),
                        stringValue(data.get("strategy")),
                        stringSet(data.get("retryOnCategories"))
                );
            }
            return rawMap;
        }

        private List<AstNode> repairAstNodes(List<?> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<AstNode> repaired = new ArrayList<>(values.size());
            for (Object value : values) {
                repaired.add((AstNode) repair(value));
            }
            return List.copyOf(repaired);
        }

        private Map<String, Object> stringKeyMap(Map<?, ?> rawMap) {
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> converted.put(String.valueOf(key), value));
            return converted;
        }

        private List<String> stringList(Object value) {
            if (value == null) {
                return List.of();
            }
            if (value instanceof Collection<?> collection) {
                return collection.stream().map(String::valueOf).toList();
            }
            return List.of(String.valueOf(value));
        }

        private Set<String> stringSet(Object value) {
            if (value == null) {
                return Set.of();
            }
            if (value instanceof Collection<?> collection) {
                LinkedHashSet<String> converted = new LinkedHashSet<>();
                collection.forEach(item -> converted.add(String.valueOf(item)));
                return Set.copyOf(converted);
            }
            return Set.of(String.valueOf(value));
        }

        private String stringValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private boolean booleanValue(Object value) {
            return value instanceof Boolean bool && bool;
        }

        private Integer integerValue(Object value) {
            if (value == null) {
                return null;
            }
            return value instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(value));
        }

        private int intValue(Object value) {
            Integer integer = integerValue(value);
            return integer == null ? 0 : integer;
        }

        @SuppressWarnings("unchecked")
        private <T> T cast(Object value) {
            return (T) value;
        }
    }
}
