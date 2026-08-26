package com.leanowtech.bloge.gateway.testing.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable, bidirectional source/output coordinate map for one pure compilation. */
public final class WorldScenarioSourceMap {
    private final Map<String, List<String>> sourceToOutputs;
    private final Map<String, List<String>> outputToSources;

    private WorldScenarioSourceMap(Map<String, List<String>> sourceToOutputs,
                                   Map<String, List<String>> outputToSources) {
        this.sourceToOutputs = freeze(sourceToOutputs);
        this.outputToSources = freeze(outputToSources);
    }

    static WorldScenarioSourceMap of(List<Link> links) {
        Map<String, Set<String>> forward = new TreeMap<>();
        Map<String, Set<String>> reverse = new TreeMap<>();
        for (Link link : links == null ? List.<Link>of() : links) {
            if (link == null || link.source().isBlank() || link.output().isBlank()) {
                throw invalid();
            }
            forward.computeIfAbsent(link.source(), ignored -> new TreeSet<>()).add(link.output());
            reverse.computeIfAbsent(link.output(), ignored -> new TreeSet<>()).add(link.source());
        }
        return new WorldScenarioSourceMap(toLists(forward), toLists(reverse));
    }

    public Map<String, List<String>> sourceToOutputs() {
        return sourceToOutputs;
    }

    public Map<String, List<String>> outputToSources() {
        return outputToSources;
    }

    public List<String> sourceToOutputs(String source) {
        return sourceToOutputs.getOrDefault(source == null ? "" : source, List.of());
    }

    public List<String> outputToSources(String output) {
        return outputToSources.getOrDefault(output == null ? "" : output, List.of());
    }

    public static String coordinate(String kind, String value) {
        if (kind == null || kind.isBlank() || value == null || value.isBlank()) {
            throw invalid();
        }
        return kind.trim() + ":" + value.trim();
    }

    static Link link(String source, String output) {
        return new Link(source, output);
    }

    private static WorldScenarioCompilationException invalid() {
        return new WorldScenarioCompilationException(
                WorldScenarioCompilationException.Code.SOURCE_MAP_INVALID);
    }

    record Link(String source, String output) {
        Link {
            source = source == null ? "" : source.trim();
            output = output == null ? "" : output.trim();
        }
    }

    private static Map<String, List<String>> freeze(Map<String, List<String>> values) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            List<String> outputs = new ArrayList<>(entry.getValue());
            outputs.sort(Comparator.naturalOrder());
            copy.put(entry.getKey(), List.copyOf(outputs));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> toLists(Map<String, Set<String>> values) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return result;
    }
}
