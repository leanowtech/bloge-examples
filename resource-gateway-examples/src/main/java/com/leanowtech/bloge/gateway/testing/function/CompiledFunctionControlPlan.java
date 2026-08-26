package com.leanowtech.bloge.gateway.testing.function;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable static function-control artifact; its public form is payload-free. */
public final class CompiledFunctionControlPlan {

    private final List<ResolvedFunctionControl> controls;
    private final String planFingerprint;
    private final FunctionEvidenceCeiling evidenceCeiling;

    CompiledFunctionControlPlan(List<ResolvedFunctionControl> controls) {
        if (controls == null || controls.stream().anyMatch(java.util.Objects::isNull)) {
            throw new FunctionControlException(FunctionControlException.Code.PLAN_INVALID);
        }
        this.controls = controls.stream()
                .sorted(java.util.Comparator.comparing(control -> control.site().structuralKey()))
                .toList();
        Map<String, ResolvedFunctionControl> unique = new LinkedHashMap<>();
        for (ResolvedFunctionControl control : this.controls) {
            if (unique.putIfAbsent(control.site().structuralKey(), control) != null) {
                throw new FunctionControlException(FunctionControlException.Code.SITE_COLLISION);
            }
        }
        this.planFingerprint = FunctionValueSupport.fingerprint(
                this.controls.stream().map(ResolvedFunctionControl::semanticMaterial).toList());
        this.evidenceCeiling = this.controls.stream()
                .map(ResolvedFunctionControl::evidenceCeiling)
                .max(java.util.Comparator.comparingInt(Enum::ordinal))
                .orElse(FunctionEvidenceCeiling.CERTIFIABLE);
    }

    public List<ResolvedFunctionControl> controls() { return controls; }
    public String planFingerprint() { return planFingerprint; }
    public FunctionEvidenceCeiling evidenceCeiling() { return evidenceCeiling; }

    /** No raw return values, expected arguments, error messages, or schemas are exposed. */
    public List<Map<String, Object>> payloadFreeProjection() {
        return controls.stream().map(control -> {
            Map<String, Object> projection = new LinkedHashMap<>();
            projection.put("site", control.site().structuralKey());
            projection.put("functionName", control.site().functionName());
            projection.put("mode", control.mode().name());
            projection.put("ruleId", control.ruleId());
            projection.put("ruleIds", control.ruleIds());
            projection.put("expectedArgumentsFingerprints", control.expectedArgumentsFingerprints());
            projection.put("candidates", control.candidateProjections());
            projection.put("behavior", control.behavior() == null ? "DIRECT" : control.behavior().name());
            projection.put("returnValueFingerprint", control.returnValueFingerprint());
            projection.put("errorFingerprint", control.errorFingerprint());
            projection.put("durationMillis", control.duration().toMillis());
            projection.put("minimumConsumption", control.consumption().minimum());
            projection.put("maximumConsumption", control.consumption().maximum());
            projection.put("evidenceCeiling", control.evidenceCeiling().name());
            return Map.copyOf(projection);
        }).toList();
    }

    @Override
    public String toString() {
        return "CompiledFunctionControlPlan[size=" + controls.size()
                + ", fingerprint=" + planFingerprint + ", evidence=" + evidenceCeiling + "]";
    }
}
