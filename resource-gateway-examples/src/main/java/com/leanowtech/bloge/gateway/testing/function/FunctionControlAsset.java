package com.leanowtech.bloge.gateway.testing.function;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Governed, immutable function-control asset. The asset is payload-bearing only inside the
 * server-owned governed catalog; execution and evidence projections never expose its payload.
 */
public final class FunctionControlAsset {
    public static final String SCHEMA_VERSION = "bloge.functionControlAsset.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    private final String targetFingerprint;
    private final List<FunctionLibraryDeclaration> declarations;
    private final List<FunctionControlRule> rules;
    private final String assetFingerprint;

    public FunctionControlAsset(String targetFingerprint,
                                List<FunctionLibraryDeclaration> declarations,
                                List<FunctionControlRule> rules) {
        if (targetFingerprint == null || !FINGERPRINT.matcher(targetFingerprint).matches()
                || declarations == null || rules == null
                || declarations.size() > FunctionValueSupport.MAX_LIST_ENTRIES
                || rules.size() > FunctionValueSupport.MAX_LIST_ENTRIES) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        this.targetFingerprint = targetFingerprint;
        this.declarations = declarations.stream().map(declaration -> {
            if (declaration == null) {
                throw new FunctionControlException(FunctionControlException.Code.DECLARATION_INVALID);
            }
            return declaration;
        }).sorted(Comparator.comparing(FunctionLibraryDeclaration::functionName)).toList();
        this.rules = rules.stream().map(rule -> {
            if (rule == null) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
            }
            return rule;
        }).sorted(Comparator.comparing(FunctionControlRule::ruleId)).toList();
        if (this.declarations.stream().map(FunctionLibraryDeclaration::functionName).distinct().count()
                != this.declarations.size()
                || this.rules.stream().map(FunctionControlRule::ruleId).distinct().count()
                != this.rules.size()) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        this.assetFingerprint = FunctionValueSupport.fingerprint(fingerprintMaterial(
                targetFingerprint, this.declarations, this.rules));
    }

    public FunctionControlAsset(String targetFingerprint,
                                List<FunctionLibraryDeclaration> declarations,
                                List<FunctionControlRule> rules,
                                String assetFingerprint) {
        this(targetFingerprint, declarations, rules);
        if (assetFingerprint == null || !assetFingerprint.equals(this.assetFingerprint)) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
    }

    public String targetFingerprint() { return targetFingerprint; }
    public List<FunctionLibraryDeclaration> declarations() { return declarations; }
    public List<FunctionControlRule> rules() { return rules; }
    public String assetFingerprint() { return assetFingerprint; }

    Map<String, Object> fingerprintMaterial() {
        return fingerprintMaterial(targetFingerprint, declarations, rules);
    }

    private static Map<String, Object> fingerprintMaterial(
            String targetFingerprint, List<FunctionLibraryDeclaration> declarations,
            List<FunctionControlRule> rules) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("targetFingerprint", targetFingerprint);
        material.put("declarations", declarations.stream().map(declaration -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("functionName", declaration.functionName());
            value.put("runtimeName", declaration.runtimeName());
            value.put("pure", declaration.pure());
            value.put("requiredExecutionServices", declaration.requiredExecutionServices().stream().sorted().toList());
            value.put("effect", declaration.effect().name());
            value.put("parameterSchema", declaration.parameterSchema());
            value.put("returnSchema", declaration.returnSchema());
            value.put("status", declaration.status().name());
            value.put("functionFingerprint", declaration.functionFingerprint());
            return value;
        }).toList());
        material.put("rules", rules.stream().map(FunctionControlRule::semanticFingerprint).toList());
        return material;
    }

    @Override
    public String toString() {
        return "FunctionControlAsset[target=" + targetFingerprint
                + ", declarations=" + declarations.size() + ", rules=" + rules.size() + "]";
    }
}
