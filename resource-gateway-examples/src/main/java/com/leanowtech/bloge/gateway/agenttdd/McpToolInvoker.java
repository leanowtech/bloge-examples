package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

/** Application boundary invoked only after the MCP transport authenticates the tool impact. */
@FunctionalInterface
public interface McpToolInvoker {
    /**
     * Invokes one cataloged tool.
     *
     * @param name exact MCP tool name
     * @param arguments validated JSON argument object
     * @param identity trusted tenant/environment/workload identity
     * @return structured RG success or error envelope
     */
    Object invoke(String name, JsonNode arguments, IntegrationRequestContext identity);
}
