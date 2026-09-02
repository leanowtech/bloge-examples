package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Compiles semantic function calls into persistent, source-position-independent Call Site authority.
 *
 * <p>An authoring id is a server-owned identity persisted with the Flow or Operator source. The
 * public {@code callSiteId} also closes the callable coordinate, normalized argument bindings and
 * input/output contracts. Formatting, canvas layout, source lines and AST indexes are deliberately
 * absent. Recompiling unchanged semantics preserves the id; changing callable or bindings creates a
 * new id so an old Fixture binding becomes stale rather than controlling a different expression.</p>
 */
public final class StableFunctionCallSiteCompilerV2 {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final Supplier<String> identifiers;

    public StableFunctionCallSiteCompilerV2() {
        this(() -> "call:" + UUID.randomUUID().toString().replace("-", ""));
    }

    StableFunctionCallSiteCompilerV2(Supplier<String> identifiers) {
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    /** Compiles and collision-checks one complete call-site inventory. */
    public List<CompiledCallSite> compile(
            ExactFixtureSubjectRefV2 owner, List<SemanticCall> calls,
            List<CompiledCallSite> previous) {
        if (!(owner instanceof ExactFixtureSubjectRefV2.FlowDraft
                || owner instanceof ExactFixtureSubjectRefV2.FlowVersion
                || owner instanceof ExactFixtureSubjectRefV2.OperatorVersion)) {
            throw new IllegalArgumentException("call-site owner is unsupported");
        }
        List<SemanticCall> source = calls == null ? List.of() : List.copyOf(calls);
        Map<String, CompiledCallSite> prior = new LinkedHashMap<>();
        for (CompiledCallSite value : previous == null ? List.<CompiledCallSite>of() : previous) {
            if (prior.putIfAbsent(value.authoringId(), value) != null) {
                throw new IllegalArgumentException("previous call-site authority is ambiguous");
            }
        }
        HashSet<String> authoringIds = new HashSet<>();
        HashSet<String> publicIds = new HashSet<>();
        List<CompiledCallSite> result = new ArrayList<>();
        for (SemanticCall call : source) {
            if (!authoringIds.add(call.authoringId())) {
                throw new IllegalArgumentException("semantic call authority is ambiguous");
            }
            String semanticFingerprint = semanticFingerprint(owner, call);
            CompiledCallSite old = prior.get(call.authoringId());
            String callSiteId = old != null
                    && old.site().semanticFingerprint().equals(semanticFingerprint)
                    ? old.site().callSiteId() : nextIdentifier();
            if (!publicIds.add(callSiteId)) {
                throw new IllegalArgumentException("compiled call-site identity collided");
            }
            result.add(new CompiledCallSite(call.authoringId(),
                    new ComponentSimulationAuthorityV2.CallSite(callSiteId, call.callable(),
                            call.input(), call.output(), semanticFingerprint)));
        }
        return List.copyOf(result);
    }

    private String nextIdentifier() {
        String value = identifiers.get();
        if (value == null || !ExactFixtureSubjectRefV2.IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("generated call-site identity is invalid");
        }
        return value;
    }

    private static String semanticFingerprint(ExactFixtureSubjectRefV2 owner, SemanticCall call) {
        ObjectNode material = JSON.createObjectNode();
        material.set("owner", JSON.valueToTree(owner));
        material.put("authoringId", call.authoringId());
        material.set("callable", JSON.valueToTree(call.callable()));
        material.set("argumentBindings", call.argumentBindings());
        material.set("input", JSON.valueToTree(call.input()));
        material.set("output", JSON.valueToTree(call.output()));
        return AuthoringFingerprints.of(material);
    }

    /** Compiler input containing semantic material only; diagnostics never affect identity. */
    public record SemanticCall(
            String authoringId, ExactFixtureSubjectRefV2.BuiltinFunctionVersion callable,
            JsonNode argumentBindings, SchemaEnvelope input, SchemaEnvelope output,
            Map<String, String> diagnosticMetadata) {
        public SemanticCall {
            if (authoringId == null || !ExactFixtureSubjectRefV2.IDENTIFIER.matcher(authoringId).matches()
                    || callable == null || argumentBindings == null || input == null || output == null) {
                throw new IllegalArgumentException("semantic call is incomplete");
            }
            argumentBindings = argumentBindings.deepCopy();
            diagnosticMetadata = diagnosticMetadata == null ? Map.of() : Map.copyOf(diagnosticMetadata);
        }
        @Override public JsonNode argumentBindings() { return argumentBindings.deepCopy(); }
        @Override public Map<String, String> diagnosticMetadata() { return Map.copyOf(diagnosticMetadata); }
    }

    /** Persistable compiler output; only {@link #site()} is exposed as runtime target authority. */
    public record CompiledCallSite(String authoringId, ComponentSimulationAuthorityV2.CallSite site) {
        public CompiledCallSite {
            if (authoringId == null || !ExactFixtureSubjectRefV2.IDENTIFIER.matcher(authoringId).matches()
                    || site == null) {
                throw new IllegalArgumentException("compiled call site is incomplete");
            }
        }
    }
}
