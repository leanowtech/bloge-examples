package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes the immutable identity of every implementation-bearing Instruction used by a Solution. */
final class SolutionImplementationIdentity {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private SolutionImplementationIdentity() { }

    /**
     * Fingerprints current bindings and WRITE governance in deterministic Instruction-ref order.
     *
     * <p>The business contract fingerprint deliberately excludes mutable bindings. This second
     * coordinate prevents an owner signoff or reconciliation result from authorizing a different
     * implementation that retained the same business meaning.</p>
     */
    static String fingerprint(
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            String scope,
            SolutionContract solution) {
        List<String> refs = new ArrayList<>(solution.instructions());
        refs.sort(String::compareTo);
        List<Map<String, Object>> instructions = new ArrayList<>();
        for (String ref : refs) {
            InstructionContract contract = registry.requireInstruction(scope, ref);
            LinkedHashMap<String, Object> identity = new LinkedHashMap<>();
            identity.put("instructionRef", contract.instructionRef());
            identity.put("bindingRef", contract.bindingRef());
            identity.put("effect", contract.effect().name());
            identity.put("writeGovernance", contract.writeGovernance());
            identity.put("inputs", contract.inputs());
            identity.put("output", contract.output());
            instructions.add(Map.copyOf(identity));
        }
        return VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "solutionRef", solution.solutionRef(),
                "instructions", List.copyOf(instructions)), MAX_BYTES);
    }
}
