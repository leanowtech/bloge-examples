package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;

/**
 * Stable impact classification for every Agent-facing RG tool.
 *
 * <p>The impact is both documentation and enforcement: the MCP transport derives the exact
 * purpose-authenticated {@link IntegrationOperation} from this value before dispatch.</p>
 */
public enum McpToolImpact {
    READ(IntegrationOperation.AGENT_TDD_READ, true, false, true, false),
    DRAFT_WRITE(IntegrationOperation.AGENT_TDD_DRAFT_WRITE, false, false, true, false),
    PROPOSE(IntegrationOperation.AGENT_TDD_PROPOSE, false, false, true, false),
    EXECUTE(IntegrationOperation.AGENT_TDD_EXECUTE, false, false, false, false),
    /** Published runtime execution may reach a governed external WRITE binding. */
    RUNTIME_EXECUTE(IntegrationOperation.AGENT_TDD_EXECUTE, false, true, true, true),
    GOVERNED_WRITE(IntegrationOperation.AGENT_TDD_GOVERNED_WRITE, false, true, true, false);

    private final IntegrationOperation operation;
    private final boolean readOnly;
    private final boolean destructive;
    private final boolean idempotent;
    private final boolean openWorld;

    McpToolImpact(IntegrationOperation operation,
                  boolean readOnly,
                  boolean destructive,
                  boolean idempotent,
                  boolean openWorld) {
        this.operation = operation;
        this.readOnly = readOnly;
        this.destructive = destructive;
        this.idempotent = idempotent;
        this.openWorld = openWorld;
    }

    /** @return purpose-authenticated operation required before the tool may execute */
    public IntegrationOperation operation() {
        return operation;
    }

    /** @return MCP annotations derived from the enforced impact, not caller input */
    public java.util.Map<String, Object> annotations() {
        return java.util.Map.of(
                "readOnlyHint", readOnly,
                "destructiveHint", destructive,
                "idempotentHint", idempotent,
                "openWorldHint", openWorld
        );
    }
}
