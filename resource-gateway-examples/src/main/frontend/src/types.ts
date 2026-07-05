// TypeScript mirrors of the relevant BLOGE visual wire contracts. Intentionally minimal — only the
// fields the authoring app reads or sends are modelled.

/** A JSON Schema envelope (bloge SchemaEnvelope). */
export interface SchemaEnvelope {
  format?: string;
  version?: string;
  schema: Record<string, unknown>;
}

/** An operator input/output port. */
export interface OperatorPort {
  name: string;
  schema: SchemaEnvelope;
  required?: boolean;
  description?: string;
}

/** A visual operator definition (bloge.visualOperator.v1). */
export interface OperatorDefinition {
  operatorRef: string;
  display?: { name?: string; description?: string; tags?: string[] };
  source?: { kind?: string; libraryId?: string };
  ports?: { inputs: OperatorPort[]; outputs: OperatorPort[] };
  lowering?: { mode?: string };
}

/** A draft node input binding. */
export interface DraftNodeBinding {
  kind: string;
  value?: unknown;
  path?: string;
}

/** A visual graph draft node. */
export interface DraftNode {
  id: string;
  operatorRef: string;
  label?: string;
  inputs?: Record<string, DraftNodeBinding>;
  config?: Record<string, unknown>;
  position?: { x: number; y: number };
}

/** A visual graph draft edge endpoint. */
export interface DraftEndpoint {
  nodeId: string;
  port?: string;
  path?: string;
}

/** A visual graph draft edge. */
export interface DraftEdge {
  id: string;
  kind: string;
  source: DraftEndpoint;
  target: DraftEndpoint;
  condition?: string;
}

/** A visual graph draft (bloge.visualGraphDraft.v1) — the subset the authoring app sends. */
export interface GraphDraft {
  graphName: string;
  nodes: DraftNode[];
  edges: DraftEdge[];
  output: { nodeId: string; path?: string };
}

/** A per-node author-supplied mock output fixture. */
export interface NodeFixture {
  output: unknown;
}

/** The body of POST /api/visual/graphs/simulate. */
export interface SimulationRequest {
  draft: GraphDraft;
  context: Record<string, unknown>;
  outputNode: string;
  fixtures?: Record<string, NodeFixture>;
}

/** The body of POST /api/visual/connections/check. */
export interface ConnectionCheckRequest {
  draft: GraphDraft;
  source: DraftEndpoint;
  target: DraftEndpoint;
  kind: string;
  condition?: string;
}

/** A visual diagnostic. */
export interface VisualDiagnostic {
  level?: string;
  code?: string;
  message?: string;
  target?: string;
}

/** Stable server-authored summary of a proposed connection decision. */
export interface ConnectionCheckSummary {
  accepted?: boolean;
  message?: string;
  graphStillInvalid?: boolean;
  readinessState?: string;
  readinessLevel?: string;
}

/** The response of POST /api/visual/connections/check. */
export interface ConnectionCheckResponse {
  accepted: boolean;
  edge?: DraftEdge;
  bindingKey?: string;
  diagnostics: VisualDiagnostic[];
  validation?: {
    valid?: boolean;
    diagnostics?: VisualDiagnostic[];
  };
  summary?: ConnectionCheckSummary;
}

/** The response of POST /api/visual/graphs/simulate. */
export interface SimulationResponse {
  validated: boolean;
  compiled: boolean;
  success: boolean;
  graphName: string;
  outputNode: string;
  output: unknown;
  results: Record<string, unknown>;
  statusMap: Record<string, string>;
  mockedNodeIds: string[];
  realNodeIds: string[];
  terminalOutputConforms: boolean;
  diagnostics: VisualDiagnostic[];
  errors: string[];
  generatedDsl: string;
}
