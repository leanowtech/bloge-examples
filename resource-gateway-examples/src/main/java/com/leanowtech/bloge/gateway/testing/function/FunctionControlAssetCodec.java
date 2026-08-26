package com.leanowtech.bloge.gateway.testing.function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Catalog bridge for the payload-bearing, versioned function-control asset. */
public final class FunctionControlAssetCodec {
    private static final Set<String> ASSET_FIELDS = Set.of(
            "schemaVersion", "targetFingerprint", "declarations", "rules", "assetFingerprint");
    private static final Set<String> DECLARATION_FIELDS = Set.of(
            "functionName", "runtimeName", "pure", "requiredExecutionServices", "effect",
            "parameterSchema", "returnSchema", "status", "functionFingerprint");
    private static final Set<String> RULE_FIELDS = Set.of(
            "ruleId", "selector", "expectedArguments", "behavior", "returnValue", "errorMessage",
            "durationMillis", "minimumConsumption", "maximumConsumption", "forcePureOverride", "priority");
    private static final Set<String> SELECTOR_FIELDS = Set.of(
            "graphPath", "nodeId", "functionName", "line", "column");

    private FunctionControlAssetCodec() { }

    public static ObjectNode encode(ObjectMapper mapper, FunctionControlAsset asset) {
        if (mapper == null || asset == null) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("schemaVersion", FunctionControlAsset.SCHEMA_VERSION);
        node.put("targetFingerprint", asset.targetFingerprint());
        ArrayNode declarations = node.putArray("declarations");
        for (FunctionLibraryDeclaration declaration : asset.declarations()) {
            ObjectNode item = declarations.addObject();
            item.put("functionName", declaration.functionName());
            item.put("runtimeName", declaration.runtimeName());
            item.put("pure", declaration.pure());
            item.set("requiredExecutionServices", mapper.valueToTree(declaration.requiredExecutionServices()));
            item.put("effect", declaration.effect().name());
            item.set("parameterSchema", mapper.valueToTree(declaration.parameterSchema()));
            item.set("returnSchema", mapper.valueToTree(declaration.returnSchema()));
            item.put("status", declaration.status().name());
            item.put("functionFingerprint", declaration.functionFingerprint());
        }
        ArrayNode rules = node.putArray("rules");
        for (FunctionControlRule rule : asset.rules()) {
            ObjectNode item = rules.addObject();
            item.put("ruleId", rule.ruleId());
            FunctionControlRule.Selector selector = rule.selector();
            item.putObject("selector")
                    .put("graphPath", selector.graphPath())
                    .put("nodeId", selector.nodeId())
                    .put("functionName", selector.functionName())
                    .put("line", selector.line())
                    .put("column", selector.column());
            if (rule.expectedArguments() == null) {
                item.putNull("expectedArguments");
            } else {
                item.set("expectedArguments", mapper.valueToTree(rule.expectedArguments()));
            }
            item.put("behavior", rule.behavior().name());
            if (rule.returnValueProvided()) {
                item.set("returnValue", mapper.valueToTree(rule.executableReturnValue()));
            } else {
                item.putNull("returnValue");
            }
            item.put("errorMessage", rule.executableErrorMessage());
            item.put("durationMillis", rule.duration().toMillis());
            item.put("minimumConsumption", rule.consumption().minimum());
            item.put("maximumConsumption", rule.consumption().maximum());
            item.put("forcePureOverride", rule.forcePureOverride());
            item.put("priority", rule.priority());
        }
        node.put("assetFingerprint", asset.assetFingerprint());
        return node;
    }

    public static FunctionControlAsset decode(ObjectMapper mapper, JsonNode node) {
        try {
            if (mapper == null || node == null || !node.isObject()) {
                throw invalid();
            }
            exactFields(node, ASSET_FIELDS);
            if (!FunctionControlAsset.SCHEMA_VERSION.equals(text(node, "schemaVersion"))) {
                throw invalid();
            }
            List<FunctionLibraryDeclaration> declarations = new ArrayList<>();
            JsonNode declarationsNode = node.get("declarations");
            if (declarationsNode == null || !declarationsNode.isArray()
                    || declarationsNode.size() > FunctionValueSupport.MAX_LIST_ENTRIES) {
                throw invalid();
            }
            for (JsonNode item : declarationsNode) {
                exactFields(item, DECLARATION_FIELDS);
                declarations.add(new FunctionLibraryDeclaration(
                        text(item, "functionName"), text(item, "runtimeName"),
                        bool(item, "pure"), strings(item, "requiredExecutionServices"),
                        FunctionEffect.valueOf(text(item, "effect")),
                        object(mapper, item, "parameterSchema"), object(mapper, item, "returnSchema"),
                        FunctionDeclarationStatus.valueOf(text(item, "status")),
                        text(item, "functionFingerprint")));
            }
            List<FunctionControlRule> rules = new ArrayList<>();
            JsonNode rulesNode = node.get("rules");
            if (rulesNode == null || !rulesNode.isArray()
                    || rulesNode.size() > FunctionValueSupport.MAX_LIST_ENTRIES) {
                throw invalid();
            }
            for (JsonNode item : rulesNode) {
                exactFields(item, RULE_FIELDS);
                JsonNode selector = item.get("selector");
                exactFields(selector, SELECTOR_FIELDS);
                List<?> expected = item.get("expectedArguments") == null
                        || item.get("expectedArguments").isNull()
                        ? null : mapper.convertValue(item.get("expectedArguments"), List.class);
                rules.add(new FunctionControlRule(
                        text(item, "ruleId"),
                        new FunctionControlRule.Selector(text(selector, "graphPath"),
                                text(selector, "nodeId"), text(selector, "functionName"),
                                integer(selector, "line"), integer(selector, "column")),
                        expected, FunctionControlRule.Behavior.valueOf(text(item, "behavior")),
                        item.get("returnValue") == null || item.get("returnValue").isNull()
                                ? null : mapper.convertValue(item.get("returnValue"), Object.class),
                        text(item, "errorMessage"), Duration.ofMillis(longValue(item, "durationMillis")),
                        new FunctionControlRule.Consumption(longValue(item, "minimumConsumption"),
                                longValue(item, "maximumConsumption")),
                        bool(item, "forcePureOverride"), integer(item, "priority")));
            }
            return new FunctionControlAsset(text(node, "targetFingerprint"), declarations, rules,
                    text(node, "assetFingerprint"));
        } catch (FunctionControlException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
    }

    private static void exactFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw invalid();
        Iterator<String> names = node.fieldNames();
        Set<String> seen = new HashSet<>();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name) || !seen.add(name)) throw invalid();
        }
        if (seen.size() != allowed.size()) throw invalid();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt() || !value.isIntegralNumber()) throw invalid();
        return value.intValue();
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || !value.isIntegralNumber()) throw invalid();
        return value.longValue();
    }

    private static java.util.Set<String> strings(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw invalid();
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw invalid();
            result.add(item.textValue());
        }
        return result;
    }

    private static java.util.Map<String, Object> object(ObjectMapper mapper, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) throw invalid();
        return mapper.convertValue(value, java.util.Map.class);
    }

    private static FunctionControlException invalid() {
        return new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
    }
}
