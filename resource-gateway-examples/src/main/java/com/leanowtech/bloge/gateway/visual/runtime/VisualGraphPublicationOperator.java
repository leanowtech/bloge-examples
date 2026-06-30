package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime operator that invokes an immutable visual graph publication as a subgraph.
 */
@BlogeOperator(
        value = VisualGraphPublicationOperator.NAME,
        description = "Invokes an immutable published visual graph as a reusable subgraph operator",
        owner = "bloge-platform",
        tags = {"visual", "publication", "subgraph"}
)
public class VisualGraphPublicationOperator implements Operator<Object, Object> {

    public static final String NAME = "visualPublication";

    private static final String STACK_KEY = "_blogeVisualPublicationStack";
    private static final int MAX_NESTING_DEPTH = 8;

    private final VisualGraphPublicationRepository repository;
    private final ObjectProvider<VisualGraphRunService> runnerProvider;

    /**
     * @param repository publication repository
     * @param runnerProvider lazy visual graph runner provider
     */
    public VisualGraphPublicationOperator(VisualGraphPublicationRepository repository,
                                          ObjectProvider<VisualGraphRunService> runnerProvider) {
        this.repository = repository;
        this.runnerProvider = runnerProvider;
    }

    @Override
    public Object execute(Object input, OperatorContext ctx) {
        Map<String, Object> rawInput = objectMap(input);
        Map<String, Object> config = objectMap(rawInput.remove("config"));
        String publicationId = firstText(config.get("publicationId"), rawInput.remove("publicationId"));
        if (publicationId.isBlank()) {
            throw new IllegalArgumentException("visualPublication input config.publicationId is required.");
        }

        VisualGraphPublication publication = repository.find(publicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Visual graph publication '%s' was not found.".formatted(publicationId)));

        List<String> stack = currentStack(ctx);
        if (stack.contains(publicationId)) {
            throw new IllegalStateException("Recursive visual publication call detected: "
                    + String.join(" -> ", withStackEntry(stack, publicationId)));
        }
        if (stack.size() >= MAX_NESTING_DEPTH) {
            throw new IllegalStateException("Visual publication nesting depth exceeds %d."
                    .formatted(MAX_NESTING_DEPTH));
        }

        Map<String, Object> context = new LinkedHashMap<>();
        Object nestedContext = rawInput.remove("context");
        if (nestedContext instanceof Map<?, ?> contextMap) {
            context.putAll(objectMap(contextMap));
        }
        context.putAll(rawInput);
        context.put(STACK_KEY, withStackEntry(stack, publicationId));

        String outputNode = firstText(config.get("outputNode"));
        VisualGraphRunResponse response = runnerProvider.getObject().run(publication, context, outputNode);
        if (!response.success()) {
            throw new IllegalStateException(response.errors().isEmpty()
                    ? "Visual publication '%s' execution failed.".formatted(publicationId)
                    : String.join("; ", response.errors()));
        }
        return response.output();
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.UNKNOWN;
    }

    @Override
    public SideEffectType sideEffectType() {
        return SideEffectType.MIXED;
    }

    private static List<String> currentStack(OperatorContext ctx) {
        Object raw = ctx.graphContext().get(STACK_KEY);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
    }

    private static List<String> withStackEntry(List<String> stack, String publicationId) {
        List<String> next = new ArrayList<>(stack);
        next.add(publicationId);
        return List.copyOf(next);
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }
}
