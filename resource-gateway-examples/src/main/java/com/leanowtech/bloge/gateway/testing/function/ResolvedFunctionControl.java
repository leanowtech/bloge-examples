package com.leanowtech.bloge.gateway.testing.function;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Payload-free public projection of one resolved function site. */
public final class ResolvedFunctionControl {

    private final FunctionInvocationSite site;
    private final FunctionRuntimeFact runtimeFact;
    private final String functionFingerprint;
    private final List<FunctionControlRule> executableRules;
    private final FunctionControlMode mode;
    private final FunctionControlRule.Behavior behavior;
    private final String returnValueFingerprint;
    private final String errorFingerprint;
    private final Duration duration;
    private final FunctionControlRule.Consumption consumption;
    private final boolean forcePureOverride;
    private final FunctionEvidenceCeiling evidenceCeiling;

    ResolvedFunctionControl(FunctionInvocationSite site,
                            FunctionRuntimeFact runtimeFact,
                            String functionFingerprint,
                            List<FunctionControlRule> rules,
                            FunctionEvidenceCeiling evidenceCeiling) {
        this.site = site;
        this.runtimeFact = runtimeFact;
        this.functionFingerprint = functionFingerprint == null ? "" : functionFingerprint;
        this.executableRules = rules == null ? List.of() : List.copyOf(rules);
        FunctionControlRule rule = this.executableRules.isEmpty() ? null : this.executableRules.getFirst();
        this.mode = rule == null ? FunctionControlMode.DIRECT : FunctionControlMode.CONTROLLED;
        this.behavior = rule == null ? null : rule.behavior();
        this.returnValueFingerprint = rule == null ? "" : rule.returnValueFingerprint();
        this.errorFingerprint = rule == null ? "" : rule.errorFingerprint();
        this.duration = rule == null ? Duration.ZERO : rule.duration();
        this.consumption = rule == null ? FunctionControlRule.Consumption.exactly(1) : rule.consumption();
        this.forcePureOverride = rule != null && rule.forcePureOverride();
        this.evidenceCeiling = evidenceCeiling;
    }

    public FunctionInvocationSite site() { return site; }
    public FunctionRuntimeFact runtimeFact() { return runtimeFact; }
    public String functionFingerprint() { return functionFingerprint; }
    public String ruleId() { return executableRules.isEmpty() ? "" : executableRules.getFirst().ruleId(); }
    public List<String> ruleIds() { return executableRules.stream().map(FunctionControlRule::ruleId).toList(); }
    public FunctionControlMode mode() { return mode; }
    public FunctionControlRule.Behavior behavior() { return behavior; }
    public String returnValueFingerprint() { return returnValueFingerprint; }
    public String errorFingerprint() { return errorFingerprint; }
    public Duration duration() { return duration; }
    public FunctionControlRule.Consumption consumption() { return consumption; }
    public boolean forcePureOverride() { return forcePureOverride; }
    public FunctionEvidenceCeiling evidenceCeiling() { return evidenceCeiling; }
    public List<String> expectedArgumentsFingerprints() {
        return executableRules.stream().map(rule -> rule.expectedArguments() == null ? ""
                : FunctionValueSupport.fingerprint(rule.expectedArguments())).toList();
    }

    /** Ordered, payload-free candidates retained for the future runtime resolver. */
    public List<Map<String, Object>> candidateProjections() {
        return executableRules.stream().map(rule -> {
            Map<String, Object> projection = new LinkedHashMap<>();
            projection.put("ruleId", rule.ruleId());
            projection.put("expectedArgumentsFingerprint", rule.expectedArguments() == null
                    ? null : FunctionValueSupport.fingerprint(rule.expectedArguments()));
            projection.put("behavior", rule.behavior().name());
            projection.put("returnValueFingerprint", rule.returnValueFingerprint());
            projection.put("errorFingerprint", rule.errorFingerprint());
            projection.put("durationMillis", rule.duration().toMillis());
            projection.put("minimumConsumption", rule.consumption().minimum());
            projection.put("maximumConsumption", rule.consumption().maximum());
            projection.put("forcePureOverride", rule.forcePureOverride());
            projection.put("priority", rule.priority());
            return java.util.Collections.unmodifiableMap(projection);
        }).toList();
    }

    /** Server-owned rule payload for a later resolver; never serialized by this projection. */
    List<FunctionControlRule> executableRules() { return executableRules; }

    Map<String, Object> semanticMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("site", site.structuralKey());
        material.put("runtimeFact", runtimeFact.fingerprintMaterial());
        material.put("functionFingerprint", functionFingerprint);
        material.put("ruleIds", ruleIds());
        material.put("mode", mode.name());
        material.put("behavior", behavior == null ? "DIRECT" : behavior.name());
        material.put("returnValueFingerprint", returnValueFingerprint);
        material.put("errorFingerprint", errorFingerprint);
        material.put("durationMillis", duration.toMillis());
        material.put("minimumConsumption", consumption.minimum());
        material.put("maximumConsumption", consumption.maximum());
        material.put("forcePureOverride", forcePureOverride);
        material.put("evidenceCeiling", evidenceCeiling.name());
        material.put("expectedArgumentsFingerprints", expectedArgumentsFingerprints());
        material.put("candidates", executableRules.stream().map(FunctionControlRule::semanticFingerprint).toList());
        return material;
    }

    @Override
    public String toString() {
        return "ResolvedFunctionControl[site=" + site.structuralKey() + ", mode=" + mode
                + ", ruleId=" + ruleId() + ", evidence=" + evidenceCeiling + "]";
    }
}
