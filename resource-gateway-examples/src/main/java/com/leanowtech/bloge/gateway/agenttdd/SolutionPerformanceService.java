package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Derives payload-free Solution operating signals from the latest durable test evidence. */
public final class SolutionPerformanceService {
    private final AgentTddStateRepository states;

    /** Creates a read model over the shared evidence repository. */
    public SolutionPerformanceService(AgentTddStateRepository states) {
        this.states = Objects.requireNonNull(states, "states");
    }

    /**
     * Returns rule and disposition counts, escalation rate and currently red GOLDEN identifiers.
     *
     * <p>No input, expected value, runtime result or diagnostic prose enters this projection.</p>
     */
    public Map<String, Object> performance(String solutionRef, IntegrationRequestContext identity) {
        if (identity == null || !IntegrationOperation.AGENT_TDD_READ.accepts(identity.purpose())) {
            throw new AgentTddToolException("FORBIDDEN_PURPOSE", "Read purpose is required.");
        }
        identity.requireComplete();
        String scope = AgentTddMutationService.scopeKey(identity);
        AgentTddStoredAsset evidence = states.find(
                        scope, SolutionTestingService.SOLUTION_EVIDENCE, solutionRef)
                .orElse(null);
        if (evidence == null) return Map.of(
                "solutionRef", solutionRef, "evidenceFingerprint", "", "totalCases", 0,
                "hitDistribution", List.of(), "dispositionDistribution", List.of(),
                "escalationRate", 0.0d, "redGolden", List.of());
        LinkedHashMap<String, Integer> rules = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> dispositions = new LinkedHashMap<>();
        List<String> red = new ArrayList<>();
        int escalated = 0;
        int total = 0;
        for (JsonNode row : evidence.data().path("cases")) {
            total++;
            String instructionRef = row.path("instructionRef").asText("TERMINAL");
            dispositions.merge(instructionRef, 1, Integer::sum);
            JsonNode path = row.path("rulePath");
            String rule = path.isArray() && !path.isEmpty()
                    ? path.path(path.size() - 1).asText("OTHERWISE") : "OTHERWISE";
            rules.merge(rule, 1, Integer::sum);
            if (instructionRef.toLowerCase(java.util.Locale.ROOT).contains("escalat")) escalated++;
            if (row.path("verdict").asText().endsWith("_FAIL")) {
                red.add(row.path("caseId").asText());
            }
        }
        return Map.of("solutionRef", solutionRef,
                "evidenceFingerprint", evidence.fingerprint(),
                "totalCases", total,
                "hitDistribution", distribution(rules, "ruleId", total),
                "dispositionDistribution", distribution(dispositions, "instructionRef", total),
                "escalationRate", total == 0 ? 0.0d : (double) escalated / total,
                "redGolden", List.copyOf(red));
    }

    private static List<Map<String, Object>> distribution(
            Map<String, Integer> counts, String keyName, int total) {
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> Map.of(
                keyName, (Object) entry.getKey(), "count", entry.getValue(),
                "share", total == 0 ? 0.0d : (double) entry.getValue() / total)).toList();
    }
}
