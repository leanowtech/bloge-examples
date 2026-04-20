package com.leanowtech.bloge.examples.antipatterns;

import com.leanowtech.bloge.examples.common.ExampleDslResources;
import com.leanowtech.bloge.lint.LintDiagnostic;
import com.leanowtech.bloge.lint.LintRunner;

import java.util.List;

/**
 * Demonstrates compile-time cycle detection for a graph with mutually dependent nodes.
 */
public final class CircularDependencyExample {

    private CircularDependencyExample() {
    }

    /**
     * Runs the BLOGE linter over the intentionally cyclic DSL example.
     */
    public static List<LintDiagnostic> diagnostics() {
        return new LintRunner().lintSource(ExampleDslResources.readResource("/bloge/antipatterns/circular-dependency.bloge"));
    }

    public static void main(String[] args) {
        diagnostics().forEach(diagnostic ->
                System.out.println(diagnostic.ruleId() + ": " + diagnostic.message()));
    }
}
