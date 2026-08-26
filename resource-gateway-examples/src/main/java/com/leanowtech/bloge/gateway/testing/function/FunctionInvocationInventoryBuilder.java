package com.leanowtech.bloge.gateway.testing.function;

import com.leanowtech.bloge.core.model.CompiledGraph;
import com.leanowtech.bloge.core.model.GraphFunctionCall;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Builds function sites from BLOGE compiler artifacts and frozen runtime graph paths. */
public final class FunctionInvocationInventoryBuilder {

    public FunctionInvocationInventory build(CompiledGraph compiledGraph,
                                              InvocationInventory invocationInventory) {
        if (compiledGraph == null || invocationInventory == null) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        IdentityHashMap<Object, List<InvocationInventory.Entry>> entriesByGraph = new IdentityHashMap<>();
        for (InvocationInventory.Entry entry : invocationInventory.entries()) {
            if (entry == null || entry.graph() == null || entry.node() == null || entry.site() == null) {
                throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID);
            }
            validatePath(entry.site().graphPath(), entry.node().id());
            entriesByGraph.computeIfAbsent(entry.graph(), ignored -> new ArrayList<>()).add(entry);
        }
        List<InvocationInventory.Entry> rootEntries = entriesByGraph.get(compiledGraph.graph());
        if (rootEntries == null || rootEntries.stream()
                .noneMatch(entry -> "/root".equals(entry.site().graphPath()))) {
            throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID);
        }

        List<FunctionInvocationSite> sites = new ArrayList<>();
        java.util.IdentityHashMap<Object, Boolean> processedArtifacts = new java.util.IdentityHashMap<>();
        for (CompiledGraph.ArtifactEntry artifactEntry : compiledGraph.walk()) {
            CompiledGraph artifact = artifactEntry.artifact();
            if (processedArtifacts.put(artifact.graph(), Boolean.TRUE) != null) {
                continue;
            }
            List<InvocationInventory.Entry> mapped = entriesByGraph.get(artifact.graph());
            if (mapped == null) {
                if (!artifact.functionCalls().isEmpty()) {
                    throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID);
                }
                continue;
            }
            for (GraphFunctionCall call : artifact.functionCalls()) {
                List<InvocationInventory.Entry> owners = owners(mapped, call.ownerNodeId());
                if (owners.isEmpty()) {
                    throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID);
                }
                for (InvocationInventory.Entry owner : owners) {
                    sites.add(new FunctionInvocationSite(
                            owner.site().graphPath(), call.ownerNodeId(), call.functionName(),
                            call.line(), call.column()));
                }
            }
        }
        return new FunctionInvocationInventory(sites);
    }

    private static void validatePath(String graphPath, String nodeId) {
        try {
            FunctionInvocationSite normalized = new FunctionInvocationSite(graphPath, nodeId, "function", 0, 0);
            if (!normalized.graphPath().equals(graphPath)) {
                throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID);
            }
        } catch (FunctionControlException failure) {
            throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID, failure);
        }
    }

    private static List<InvocationInventory.Entry> owners(
            List<InvocationInventory.Entry> entries, String ownerNodeId) {
        List<InvocationInventory.Entry> found = new ArrayList<>();
        for (InvocationInventory.Entry entry : entries) {
            if (!entry.node().id().equals(ownerNodeId)
                    || entry.site().invocationKind() == com.leanowtech.bloge.gateway.testing.domain.InvocationSite.InvocationKind.COMPENSATION) {
                continue;
            }
            found.add(entry);
        }
        return found;
    }
}
