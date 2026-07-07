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
  capabilities?: { streaming?: boolean };
  lowering?: { mode?: string };
  runtimeReadiness?: {
    state?: string;
    level?: string;
    executable?: boolean;
    title?: string;
    summary?: string;
  };
}

/** A BLOGE expression function exposed to authoring editors. */
export interface BuiltInFunctionDefinition {
  name: string;
  namespace?: string;
  displayName?: string;
  description?: string;
  category?: string;
  signatures?: BuiltInFunctionSignature[];
  examples?: string[];
}

/** One callable overload for a BLOGE expression function. */
export interface BuiltInFunctionSignature {
  label: string;
  description?: string;
  parameters?: BuiltInFunctionParameter[];
  returns?: BuiltInFunctionReturn;
}

/** One BLOGE expression function parameter. */
export interface BuiltInFunctionParameter {
  name: string;
  type?: string;
  schema?: SchemaEnvelope;
  optional?: boolean;
  variadic?: boolean;
  description?: string;
}

/** BLOGE expression function return contract. */
export interface BuiltInFunctionReturn {
  type?: string;
  schema?: SchemaEnvelope;
  description?: string;
}

/** A user-provided operator library (bloge.visualOperatorLibrary.v1). */
export interface OperatorLibrary {
  schemaVersion?: string;
  libraryId: string;
  displayName?: string;
  version?: string;
  owner?: string;
  status?: string;
  builtInFunctions?: BuiltInFunctionDefinition[];
  operators: OperatorDefinition[];
}

/** The response of GET /api/visual/operators. */
export interface OperatorCatalogResponse {
  operators: OperatorDefinition[];
  builtInFunctions?: BuiltInFunctionDefinition[];
}

/** Server-derived import readiness for one submitted operator library. */
export interface OperatorLibraryImportReadiness {
  state?: string;
  level?: string;
  operatorCount?: number;
  diagnosticCount?: number;
  message?: string;
  recommendedAction?: string;
}

/** The response of POST /admin/visual-operator-libraries/validate-text. */
export interface OperatorLibraryValidationResult {
  valid: boolean;
  diagnostics: VisualDiagnostic[];
  profile?: { libraryId?: string; operatorCount?: number };
  importReadiness?: OperatorLibraryImportReadiness;
}

/** Projection review for adapting bloge.capabilityCatalog.v1 into a visual operator library. */
export interface CapabilityCatalogProjectionReview {
  schemaVersion?: string;
  catalogId?: string;
  sourceSchemaVersion?: string;
  sourceOperatorCount?: number;
  sourceFunctionCount?: number;
  projectedOperatorCount?: number;
  projectedFunctionCount?: number;
  opaqueSchemaCount?: number;
  sourceDiagnosticCount?: number;
  coverageStatus?: string;
  sourceKinds?: string[];
}

/** The response of POST /admin/visual-operator-libraries/from-capability-catalog-text. */
export interface CapabilityCatalogVisualAdapterResult {
  schemaVersion?: string;
  library?: OperatorLibrary;
  validation: OperatorLibraryValidationResult;
  projectionReview?: CapabilityCatalogProjectionReview;
}

/** A draft node input binding. */
export interface DraftNodeBinding {
  kind: string;
  value?: unknown;
  path?: string;
  nodeId?: string;
  sourcePort?: string;
  targetPort?: string;
  targetPath?: string;
  expr?: string;
  fields?: Record<string, DraftNodeBinding>;
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

/** A visual graph draft (bloge.visualGraphDraft.v1) — the subset the authoring app sends or exports. */
export interface GraphDraft {
  schemaVersion?: string;
  draftId?: string;
  revision?: number;
  graphName: string;
  inputSchema?: SchemaEnvelope;
  nodes: DraftNode[];
  edges: DraftEdge[];
  visualLayout?: Record<string, unknown>;
  nodeFixtures?: Record<string, NodeFixture>;
  output: { nodeId: string; path?: string };
  operatorFingerprints?: Record<string, string>;
  operatorSnapshots?: Record<string, OperatorDefinition>;
}

/** A per-node author-supplied simulation fixture. */
export interface NodeFixture {
  output: unknown;
  expectedInput?: unknown;
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

/** The body of POST /api/visual/connections/candidates. */
export interface ConnectionCandidatesRequest {
  draft: GraphDraft;
  source: DraftEndpoint;
  kind?: string;
  includeRejected?: boolean;
  limit?: number;
  offset?: number;
  targetNodeId?: string;
  targetSurface?: string;
  targetPort?: string;
  targetPath?: string;
  query?: string;
  targetStatus?: string;
  facetFilters?: Record<string, string[]>;
}

/** A visual diagnostic. */
export interface VisualDiagnostic {
  level?: string;
  code?: string;
  message?: string;
  target?: string;
}

/** Source span in an imported BLOGE DSL document. */
export interface DslSourceSpan {
  sourceId?: string;
  startLine?: number;
  startColumn?: number;
  endLine?: number;
  endColumn?: number;
  dslKind?: string;
}

/** Source-map links from visual draft elements back to DSL source locations. */
export interface DslSourceMap {
  nodes?: Record<string, DslSourceSpan>;
  edges?: Record<string, DslSourceSpan>;
  bindings?: Record<string, DslSourceSpan>;
}

/** Import projection coverage summary. */
export interface DslImportCoverage {
  memberCount?: number;
  projectedNodeCount?: number;
  edgeCount?: number;
  unsupportedSyntaxCount?: number;
  missingOperatorCount?: number;
  missingFunctionCount?: number;
}

/** Conservative round-trip readiness summary for an imported DSL preview. */
export interface DslRoundTripSummary {
  supported?: boolean;
  status?: string;
  message?: string;
}

/** Request body for schema-neutral DSL preview import. */
export interface DslImportPreviewRequest {
  sourceId?: string;
  dsl: string;
  operatorLibraryIds?: string[];
  inlineLibraries?: OperatorLibrary[];
  mode?: string;
  layout?: Record<string, unknown>;
}

/** Response of POST /api/visual/dsl-imports/preview. */
export interface DslVisualProjection {
  schemaVersion?: string;
  sourceId: string;
  draft: GraphDraft;
  sourceMap?: DslSourceMap;
  coverage?: DslImportCoverage;
  roundTrip?: DslRoundTripSummary;
  diagnostics?: VisualDiagnostic[];
}

/** Result of committing a DSL import projection into the stored draft repository. */
export interface GraphDraftImportResult {
  schemaVersion?: string;
  imported: boolean;
  draft?: GraphDraft;
  diagnostics?: VisualDiagnostic[];
  validation?: VisualValidationResult;
  dependencyReport?: Record<string, unknown>;
  targetDependencyReport?: Record<string, unknown>;
}

/** Server-derived graph-level readiness returned by transient draft validation. */
export interface VisualGraphReadiness {
  state?: string;
  level?: string;
  executable?: boolean;
  artifactKinds?: string[];
  title?: string;
  summary?: string;
  nodeCount?: number;
  runtimeBindingRequirementCount?: number;
}

/** Server-derived action gates returned by transient draft validation. */
export interface VisualGraphActionReadiness {
  state?: string;
  compileNow?: boolean;
  runNow?: boolean;
  publishExecutableNow?: boolean;
  publishDesignNow?: boolean;
}

/** The response of POST /api/visual/drafts/validate. */
export interface VisualValidationResult {
  valid: boolean;
  diagnostics: VisualDiagnostic[];
  readiness?: VisualGraphReadiness;
  actionReadiness?: VisualGraphActionReadiness;
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

/** One server-enumerated target candidate for a connection source. */
export interface ConnectionCandidate {
  targetNodeId: string;
  targetNodeLabel?: string;
  targetOperatorRef?: string;
  targetSurface?: string;
  target: DraftEndpoint;
  accepted: boolean;
  targetStatus?: string;
  bindingKey?: string;
  summary?: ConnectionCheckSummary;
  diagnostics?: VisualDiagnostic[];
}

/** The response of POST /api/visual/connections/candidates. */
export interface ConnectionCandidatesResponse {
  schemaVersion?: string;
  source: DraftEndpoint;
  kind?: string;
  offset?: number;
  totalCandidateCount?: number;
  acceptedCount?: number;
  rejectedCount?: number;
  displayedCount?: number;
  truncated?: boolean;
  candidates: ConnectionCandidate[];
  diagnostics?: VisualDiagnostic[];
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

/** A browser-selectable sample input for one resource-gateway showcase scenario. */
export interface GatewayExamplePreset {
  label?: string;
  description?: string;
  values?: Record<string, unknown>;
  expected?: Record<string, unknown>;
}

/** The public gateway endpoint recipe used by one resource-gateway showcase scenario. */
export interface GatewayExampleRun {
  mode?: string;
  method?: string;
  pathTemplate?: string;
  bodyTemplate?: Record<string, unknown>;
  headers?: Record<string, string>;
}

/** Resolved browser request for executing one resource-gateway showcase scenario. */
export interface GatewayExampleRunRequest {
  mode: string;
  url: string;
  init: RequestInit;
}

/** Result of executing one non-streaming resource-gateway showcase scenario. */
export interface GatewayExampleRunResult {
  status: number;
  url: string;
  payload: unknown;
}

/** One input or output column in a matrix-oriented decision table. */
export interface GatewayDecisionColumn {
  key: string;
  label?: string;
}

/** One rule row in a matrix-oriented decision table. */
export interface GatewayDecisionRow {
  id: string;
  conditions?: Record<string, unknown>;
  output?: Record<string, unknown>;
  explanation?: string;
}

/** Optional decision-table metadata exposed for matrix-oriented showcase scenarios. */
export interface GatewayDecisionTable {
  title?: string;
  hitPolicy?: string;
  inputs?: GatewayDecisionColumn[];
  outputs?: GatewayDecisionColumn[];
  rows?: GatewayDecisionRow[];
}

/** Public scenario metadata returned by GET /api/gateway/examples/scenarios. */
export interface GatewayExampleScenario {
  graphName: string;
  title: string;
  graphFile?: string;
  pattern?: string;
  description?: string;
  concepts?: string[];
  sampleInput?: Record<string, unknown>;
  samplePresets?: GatewayExamplePreset[];
  run?: GatewayExampleRun;
  decisionTable?: GatewayDecisionTable | null;
  diagramPath?: string;
  inputSchema?: SchemaEnvelope;
  outputSchema?: SchemaEnvelope;
}

/** Canvas coordinate used by resource-gateway showcase diagrams. */
export interface GatewayDiagramPosition {
  x?: number;
  y?: number;
}

/** Stable node dimensions used by resource-gateway showcase diagrams. */
export interface GatewayDiagramSize {
  width?: number;
  height?: number;
}

/** One visual node returned by the resource-gateway diagram endpoint. */
export interface GatewayDiagramNode {
  id: string;
  kind?: string;
  operatorRef?: string | null;
  label?: string;
  position?: GatewayDiagramPosition;
  size?: GatewayDiagramSize;
  group?: string | null;
  annotations?: Record<string, unknown>;
}

/** One visual edge returned by the resource-gateway diagram endpoint. */
export interface GatewayDiagramEdge {
  id?: string;
  source: string;
  target: string;
  label?: string;
}

/** Optional grouping hint returned by the resource-gateway diagram endpoint. */
export interface GatewayDiagramGroup {
  id: string;
  label?: string;
  kind?: string;
}

/** Presentation-only layout returned by GET /api/gateway/examples/scenarios/{graph}/diagram. */
export interface GatewayExampleDiagram {
  schemaVersion?: string;
  rootId: string;
  executionMode?: string;
  nodes: GatewayDiagramNode[];
  edges: GatewayDiagramEdge[];
  groups?: GatewayDiagramGroup[];
  viewport?: {
    x?: number;
    y?: number;
    zoom?: number;
  };
}
