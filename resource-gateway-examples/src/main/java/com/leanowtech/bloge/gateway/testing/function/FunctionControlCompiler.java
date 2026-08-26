package com.leanowtech.bloge.gateway.testing.function;

import com.leanowtech.bloge.core.spi.ExpressionFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles frozen function inventory, registry facts, declarations, and rules into a plan. */
public final class FunctionControlCompiler {

    public CompiledFunctionControlPlan compile(
            FunctionInvocationInventory inventory,
            Map<String, ? extends ExpressionFunction> runtimeFunctions,
            Collection<FunctionLibraryDeclaration> declarations,
            Collection<FunctionControlRule> rules
    ) {
        if (inventory == null || runtimeFunctions == null || declarations == null || rules == null) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        Map<String, FunctionLibraryDeclaration> declarationByName = declarations(declarations);
        Map<String, FunctionControlRule> ruleById = rules(rules);
        Map<String, FunctionRuntimeFact> runtimeByName = runtime(runtimeFunctions);
        Map<String, List<FunctionControlRule>> matching = matchRules(inventory, ruleById.values());

        List<ResolvedFunctionControl> resolved = new ArrayList<>();
        for (FunctionInvocationSite site : inventory.sites()) {
            FunctionLibraryDeclaration declaration = declarationByName.get(site.functionName());
            FunctionRuntimeFact runtimeFact = resolveRuntime(site.functionName(), declaration, runtimeByName);
            if (runtimeFact == null) {
                throw new FunctionControlException(declaration == null
                        ? FunctionControlException.Code.RUNTIME_MISSING
                        : FunctionControlException.Code.DECLARATION_DRIFT);
            }
            if (declaration != null) {
                verifyRuntime(runtimeFact, declaration);
            }
            List<FunctionControlRule> candidates = matching.getOrDefault(site.structuralKey(), List.of());
            for (FunctionControlRule candidate : candidates) {
                validateRule(candidate, declaration);
            }
            FunctionEvidenceCeiling evidence = evidence(declaration, candidates);
            resolved.add(new ResolvedFunctionControl(site, runtimeFact,
                    declaration == null ? "" : declaration.functionFingerprint(), candidates, evidence));
        }
        return new CompiledFunctionControlPlan(resolved);
    }

    private static Map<String, FunctionLibraryDeclaration> declarations(
            Collection<FunctionLibraryDeclaration> declarations) {
        Map<String, FunctionLibraryDeclaration> result = new LinkedHashMap<>();
        for (FunctionLibraryDeclaration declaration : declarations) {
            if (declaration == null || result.putIfAbsent(declaration.functionName(), declaration) != null) {
                throw new FunctionControlException(FunctionControlException.Code.DECLARATION_INVALID);
            }
        }
        return result;
    }

    private static Map<String, FunctionControlRule> rules(Collection<FunctionControlRule> rules) {
        Map<String, FunctionControlRule> result = new LinkedHashMap<>();
        for (FunctionControlRule rule : rules) {
            if (rule == null || result.putIfAbsent(rule.ruleId(), rule) != null) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
            }
        }
        return result;
    }

    private static Map<String, FunctionRuntimeFact> runtime(
            Map<String, ? extends ExpressionFunction> runtimeFunctions) {
        Map<String, FunctionRuntimeFact> result = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends ExpressionFunction> entry : runtimeFunctions.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_INVALID);
            }
            FunctionRuntimeFact fact = FunctionRuntimeFact.from(entry.getKey(), entry.getValue());
            if (result.putIfAbsent(fact.registryName(), fact) != null) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_INVALID);
            }
        }
        return result;
    }

    private static Map<String, List<FunctionControlRule>> matchRules(
            FunctionInvocationInventory inventory, Collection<FunctionControlRule> rules) {
        Map<String, List<FunctionControlRule>> result = new LinkedHashMap<>();
        for (FunctionControlRule rule : rules) {
            List<FunctionInvocationSite> matched = inventory.sites().stream()
                    .filter(rule.selector()::matches)
                    .toList();
            if (matched.isEmpty()) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_ZERO_MATCH);
            }
            if (rule.selector().functionNameOnly() && matched.size() > 1) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_AMBIGUOUS);
            }
            for (FunctionInvocationSite site : matched) {
                result.computeIfAbsent(site.structuralKey(), ignored -> new ArrayList<>()).add(rule);
            }
        }
        for (List<FunctionControlRule> candidates : result.values()) {
            java.util.Set<String> exactFingerprints = new java.util.HashSet<>();
            int wildcardCount = 0;
            for (FunctionControlRule candidate : candidates) {
                if (candidate.expectedArguments() == null) {
                    wildcardCount++;
                } else if (!exactFingerprints.add(FunctionValueSupport.fingerprint(candidate.expectedArguments()))) {
                    throw new FunctionControlException(FunctionControlException.Code.RULE_OVERLAP);
                }
            }
            if (wildcardCount > 1) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_OVERLAP);
            }
            candidates.sort(Comparator.comparing((FunctionControlRule rule) ->
                            rule.expectedArguments() == null ? 1 : 0)
                    .thenComparing(Comparator.comparingInt(FunctionControlRule::priority).reversed())
                    .thenComparing(FunctionControlRule::ruleId));
            if (candidates.size() > 1
                    && candidates.get(0).expectedArguments() == null
                    && candidates.get(1).expectedArguments() == null
                    && candidates.get(0).priority() == candidates.get(1).priority()) {
                throw new FunctionControlException(FunctionControlException.Code.RULE_OVERLAP);
            }
        }
        return result;
    }

    private static void verifyRuntime(FunctionRuntimeFact runtime,
                                      FunctionLibraryDeclaration declaration) {
        if (!runtime.runtimeName().equals(declaration.runtimeName())
                || runtime.pure() != declaration.pure()
                || !runtime.requiredExecutionServices().equals(
                declaration.requiredExecutionServices().stream().sorted().toList())) {
            throw new FunctionControlException(FunctionControlException.Code.DECLARATION_DRIFT);
        }
    }

    private static FunctionEvidenceCeiling evidence(FunctionLibraryDeclaration declaration,
                                                    List<FunctionControlRule> rules) {
        if (declaration == null || !declaration.certifiable()
                || declaration.status() == FunctionDeclarationStatus.UNKNOWN
                || declaration.status() == FunctionDeclarationStatus.LEGACY) {
            if (!rules.isEmpty()) {
                throw new FunctionControlException(FunctionControlException.Code.UNKNOWN_NOT_CERTIFIABLE);
            }
            return FunctionEvidenceCeiling.PREVIEW;
        }
        if (rules.isEmpty()) {
            return FunctionEvidenceCeiling.CERTIFIABLE;
        }
        if (declaration.pure()) {
            if (rules.stream().anyMatch(rule -> !rule.forcePureOverride())) {
                throw new FunctionControlException(FunctionControlException.Code.PURE_NOT_OVERRIDDEN);
            }
            return FunctionEvidenceCeiling.EXPLORATORY;
        }
        return FunctionEvidenceCeiling.CERTIFIABLE;
    }

    private static FunctionRuntimeFact resolveRuntime(String functionName,
                                                      FunctionLibraryDeclaration declaration,
                                                      Map<String, FunctionRuntimeFact> runtimeByName) {
        FunctionRuntimeFact direct = runtimeByName.get(functionName);
        if (direct != null || declaration == null) {
            return direct;
        }
        return runtimeByName.values().stream()
                .filter(runtime -> runtime.runtimeName().equals(declaration.runtimeName()))
                .reduce((left, right) -> {
                    throw new FunctionControlException(FunctionControlException.Code.RUNTIME_INVALID);
                }).orElseGet(() -> {
                    if (!runtimeByName.isEmpty()) {
                        throw new FunctionControlException(FunctionControlException.Code.DECLARATION_DRIFT);
                    }
                    return null;
                });
    }

    private static void validateRule(FunctionControlRule rule,
                                     FunctionLibraryDeclaration declaration) {
        if (declaration == null) {
            return;
        }
        if (rule.expectedArguments() != null
                && !FunctionValueSupport.accepts(declaration.parameterSchema(), rule.expectedArguments())) {
            throw new FunctionControlException(FunctionControlException.Code.SCHEMA_INVALID);
        }
        if ((rule.behavior() == FunctionControlRule.Behavior.RETURN
                || rule.behavior() == FunctionControlRule.Behavior.DELAY)
                && !FunctionValueSupport.accepts(declaration.returnSchema(), rule.executableReturnValue())) {
            throw new FunctionControlException(FunctionControlException.Code.SCHEMA_INVALID);
        }
    }
}
