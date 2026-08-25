package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.gateway.testing.domain.ExecutionMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
        return builder().schemaStandin(invocationSiteId, ruleId).build();
    }

    /** Creates a Java-only builder for several precise schema stand-in sites. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the frozen mode for one exact invocation site and rule pair. */
    public Optional<ExecutionMode> modeFor(String invocationSiteId, String ruleId) {
        String site = normalized(invocationSiteId);
        String rule = normalized(ruleId);
        if (site.isEmpty() || rule.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(modesBySiteAndRule
                .getOrDefault(site, Map.of())
                .get(rule));
    }

    Map<String, Map<String, ExecutionMode>> entries() {
        return modesBySiteAndRule;
    }

    /** Builder deliberately exposes no arbitrary execution-mode input. */
    public static final class Builder {
        private final Map<String, Map<String, ExecutionMode>> entries = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder schemaStandin(String invocationSiteId, String ruleId) {
            String site = requireNonBlank(invocationSiteId, "invocationSiteId");
            String rule = requireNonBlank(ruleId, "ruleId");
            Map<String, ExecutionMode> siteEntries = entries.computeIfAbsent(
                    site, ignored -> new LinkedHashMap<>());
            if (siteEntries.containsKey(rule)) {
                throw new IllegalArgumentException(
                        "duplicate execution-mode hint: " + site + " / " + rule);
            }
            siteEntries.put(rule, ExecutionMode.SCHEMA_STANDIN);
            return this;
        }

        public ExecutionModeHints build() {
            return entries.isEmpty() ? NONE : new ExecutionModeHints(entries);
        }

        private static String requireNonBlank(String value, String name) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return normalized;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
