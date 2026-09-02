package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StableFunctionCallSiteCompilerV2Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExactFixtureSubjectRefV2.OperatorVersion OWNER =
            new ExactFixtureSubjectRefV2.OperatorVersion(
                    "risk", 1, "risk.score", "sha256:" + "1".repeat(64));
    private static final ExactFixtureSubjectRefV2.BuiltinFunctionVersion LOOKUP =
            new ExactFixtureSubjectRefV2.BuiltinFunctionVersion(
                    "bloge", 1, "lookup", "sha256:" + "2".repeat(64),
                    "sha256:" + "3".repeat(64));

    @Test
    void preservesIdentityAcrossDiagnosticFormattingButChangesOnSemanticBindingDrift() {
        AtomicInteger sequence = new AtomicInteger();
        StableFunctionCallSiteCompilerV2 compiler = new StableFunctionCallSiteCompilerV2(
                () -> "call:" + sequence.incrementAndGet());
        var first = compiler.compile(OWNER, List.of(call("left", "$.customerId", 10, 4)), List.of());
        var reformatted = compiler.compile(OWNER,
                List.of(call("left", "$.customerId", 200, 40)), first);
        var changed = compiler.compile(OWNER,
                List.of(call("left", "$.referrerId", 200, 40)), reformatted);

        assertThat(reformatted).isEqualTo(first);
        assertThat(changed.getFirst().site().callSiteId()).isNotEqualTo(
                first.getFirst().site().callSiteId());
        assertThat(changed.getFirst().site().semanticFingerprint()).isNotEqualTo(
                first.getFirst().site().semanticFingerprint());
    }

    @Test
    void givesTwoSameNameCallsDifferentStableTargetsAndRejectsCompilerCollisions() {
        StableFunctionCallSiteCompilerV2 compiler = new StableFunctionCallSiteCompilerV2(
                new java.util.Iterator<String>() {
                    private final java.util.Iterator<String> values =
                            List.of("call:left", "call:right").iterator();
                    @Override public boolean hasNext() { return values.hasNext(); }
                    @Override public String next() { return values.next(); }
                }::next);

        var compiled = compiler.compile(OWNER, List.of(
                call("left", "$.customerId", 1, 1),
                call("right", "$.referrerId", 1, 30)), List.of());

        assertThat(compiled).extracting(value -> value.site().callable().functionName())
                .containsExactly("lookup", "lookup");
        assertThat(compiled).extracting(value -> value.site().callSiteId())
                .containsExactly("call:left", "call:right");
        StableFunctionCallSiteCompilerV2 duplicateCompiler =
                new StableFunctionCallSiteCompilerV2(() -> "call:duplicate");
        assertThatThrownBy(() -> duplicateCompiler.compile(OWNER, List.of(
                call("left", "$.customerId", 1, 1),
                call("left", "$.referrerId", 1, 30)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StableFunctionCallSiteCompilerV2.SemanticCall call(
            String authoringId, String path, int line, int column) {
        return new StableFunctionCallSiteCompilerV2.SemanticCall(authoringId, LOOKUP,
                JSON.createObjectNode().put("value", path), SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(), Map.of("line", String.valueOf(line),
                "column", String.valueOf(column), "layout", "ignored"));
    }
}
