package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.gateway.testing.domain.ExecutionMode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Internal compiler input; never part of a fixture or public execution plan. */
public final class ExecutionModeHints {

    private static final ExecutionModeHints NONE = new ExecutionModeHints(Map.of());
    private final Map<String, Map<String, ExecutionMode>> modesBySiteAndRule;

    private ExecutionModeHints(Map<String, Map<String, ExecutionMode>> modesBySiteAndRule) {
        Map<String, Map<String, ExecutionMode>> frozen = new LinkedHashMap<>();
        if (modesBySiteAndRule != null) {
            modesBySiteAndRule.forEach((siteId, modes) -> frozen.put(siteId,
                    modes == null ? Map.of() : Map.copyOf(modes)));
        }
        this.modesBySiteAndRule = Map.copyOf(frozen);
    }

    public static ExecutionModeHints none() {
        return NONE;
    }

    public static ExecutionModeHints schemaStandin(String invocationSiteId, String ruleId) {
        return new ExecutionModeHints(Map.of(invocationSiteId,
                Map.of(ruleId, ExecutionMode.SCHEMA_STANDIN)));
    }

    Map<String, Map<String, ExecutionMode>> entries() {
        return modesBySiteAndRule;
    }
}
