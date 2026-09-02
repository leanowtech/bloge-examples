import type { FixtureSetSummary, JsonObject, SchemaEnvelope } from './model';

export type FlowKind = 'TOOL' | 'SOLUTION';

export interface FlowFormDraft {
  flowId: string;
  displayName: string;
  kind: FlowKind;
  description: string;
}

export interface FlowDraftRef {
  kind: 'FLOW_DRAFT';
  draftId: string;
  revision: number;
  fingerprint: string;
}

export interface FlowVersionRef {
  kind: 'FLOW_VERSION';
  publicationId: string;
  revision: number;
  fingerprint: string;
}

export interface ReusableFlowVersion {
  schemaVersion: 'bloge.reusableFlowVersion.v1';
  publicationId: string;
  revision: number;
  fingerprint: string;
  source: { draftId: string; revision: number; fingerprint: string };
  flowId: string;
}

export interface ApiResourceRef {
  kind: 'API_RESOURCE';
  resourceId: string;
  revision: number;
  fingerprint: string;
}

export interface OperatorVersionRef {
  kind: 'OPERATOR_VERSION';
  libraryId: string;
  libraryRevision: number;
  operatorRef: string;
  contractFingerprint: string;
}

export interface BuiltinFunctionVersionRef {
  kind: 'BUILTIN_FUNCTION_VERSION';
  catalogId: string;
  catalogRevision: number;
  functionName: string;
  signatureFingerprint: string;
  runtimeFingerprint: string;
}

export type FixtureSubjectRef = ApiResourceRef | FlowDraftRef | FlowVersionRef
  | OperatorVersionRef | BuiltinFunctionVersionRef;

export interface ComposableCatalogItem {
  schemaVersion: 'bloge.composableCatalogItem.v1';
  displayName: string;
  reference: ApiResourceRef | FlowVersionRef;
  contract: { input: SchemaEnvelope; output: SchemaEnvelope };
}

export interface ResolvedFlowNode {
  nodeId: string;
  label: string;
  item: ComposableCatalogItem;
}

export interface ReusableFlowCommand {
  schemaVersion: 'bloge.reusableFlowSaveCommand.v1';
  flow: {
    displayName: string;
    kind: FlowKind;
    description: string;
    contract: { input: SchemaEnvelope; output: SchemaEnvelope };
    graph: {
      nodes: Array<{
        nodeId: string;
        label: string;
        use: ApiResourceRef | FlowVersionRef;
        inputs: Array<{
          to: string;
          from: { kind: 'FLOW_INPUT'; path: string }
            | { kind: 'NODE_OUTPUT'; nodeId: string; path: string }
            | { kind: 'CONSTANT'; value: unknown };
        }>;
      }>;
      output: { nodeId: string; path: string };
    };
    layout: { nodes: Record<string, { x: number; y: number }> };
  };
}

export interface LegacyReusableFlowReauthorPreview {
  schemaVersion: 'bloge.legacyReusableFlowReauthorPreview.v1';
  source: {
    kind: 'REUSABLE_FLOW_DRAFT' | 'REUSABLE_FLOW_VERSION';
    sourceId: string;
    sourceRevision: number;
  };
  suggestedFlowId: string;
  suggestedFlow: ReusableFlowCommand;
  fixtureReferences: number;
  diagnostics: Array<{ code: string; message: string }>;
}

export interface LegacyFixtureReauthorPreview {
  schemaVersion: 'bloge.legacyFixtureReauthorPreview.v1';
  source: { draftId: string; revision: number };
  targetFlowId: string;
  suggestedFixtureSetId: string;
  target: FlowDraftRef;
  references: Array<{
    nodeId: string;
    materialKind: 'INLINE' | 'GOVERNED';
    fidelity: 'OUTPUT_LEVEL' | 'PROTOCOL_DERIVED' | 'TRANSPORT_LEVEL';
    expectedInputPresent: boolean;
  }>;
  diagnostics: Array<{ code: string; message: string }>;
}

export type ReusableFlowDraft = ReusableFlowCommand['flow'] & {
  schemaVersion: 'bloge.reusableFlowDraft.v1';
  flowId: string;
  draftId: string;
  revision: number;
  fingerprint: string;
  status: 'DRAFT';
};

export interface FixtureSetView {
  schemaVersion: 'bloge.fixtureSet.v1';
  fixtureSetId: string;
  revision: number;
  fingerprint: string;
  statusRevision: number;
  displayName: string;
  subject: FixtureSubjectRef;
  cases: FixtureSetCommand['cases'];
  status: FixtureSetStatus;
}

export type FixtureSetStatus = 'PRIVATE_DRAFT' | 'SHARING_PENDING' | 'TEAM_AVAILABLE' | 'STALE' | 'REVOKED';

export interface ReusableFlowSaveReceipt {
  schemaVersion: 'bloge.reusableFlowSaveReceipt.v1';
  flowId: string;
  draft: FlowDraftRef;
  validation: 'VALID';
}

export interface ReusableFlowPublishReceipt {
  schemaVersion: 'bloge.reusableFlowPublishReceipt.v1';
  source: FlowDraftRef;
  version: FlowVersionRef;
  catalog: 'AVAILABLE';
}

export interface FixtureSetCommand {
  schemaVersion: 'bloge.fixtureSetCommand.v1';
  displayName: string;
  subject: FixtureSubjectRef;
  cases: Array<{
    caseId: string;
    name: string;
    input: JsonObject;
    when?: FixtureCondition;
    controls: Array<{
      target: { kind: 'SUBJECT' } | { kind: 'NODE'; nodeId: string };
      behavior: FixtureBehavior;
      fidelity?: 'OUTPUT_LEVEL' | 'PROTOCOL_DERIVED' | 'TRANSPORT_LEVEL';
    }>;
    expect?: { output: JsonObject };
  }>;
}

export interface FixtureCondition {
  conditionId: string;
  all: FixturePredicate[];
}

export type FixturePredicate =
  | { operator: 'EQ'; path: string; value: unknown }
  | { operator: 'IN'; path: string; values: unknown[] }
  | { operator: 'PRESENT' | 'ABSENT'; path: string }
  | { operator: 'NUMBER_RANGE'; path: string; minimum?: number; maximum?: number };

export type FixtureMaterial =
  | { kind: 'INLINE'; value: JsonObject }
  | { kind: 'FIXTURE_ASSET'; fixtureAssetId: string; revision: number; schemaFingerprint: string };

export type FixtureBehavior =
  | { kind: 'REAL' }
  | { kind: 'RETURN'; material: FixtureMaterial }
  | { kind: 'APPLY_CASE'; fixtureSetId: string; revision: number; caseId: string }
  | { kind: 'ERROR'; code: string; message: string }
  | { kind: 'TIMEOUT'; afterMs: number }
  | { kind: 'REPLAY'; replayId: string; fingerprint: string };

export interface FixtureSetSaveReceipt {
  schemaVersion: 'bloge.fixtureSetSaveReceipt.v1';
  fixtureSetId: string;
  revision: number;
  fingerprint: string;
  subject: FixtureSubjectRef;
  caseIds: string[];
  status: FixtureSetStatus;
  statusRevision: number;
}

export interface FixtureShareCommand {
  schemaVersion: 'bloge.fixtureShareCommand.v1';
  source: {
    fixtureSetId: string;
    revision: number;
    fingerprint: string;
    statusRevision: number;
  };
  policy: {
    classification: 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';
    retentionDays: number;
    redaction: { profileVersion: string; paths: string[] };
  };
}

export interface FixtureShareReceipt {
  schemaVersion: 'bloge.fixtureShareReceipt.v1';
  fixtureSetId: string;
  derivedFromRevision: number;
  revision: number;
  fingerprint: string;
  status: 'SHARING_PENDING';
  statusRevision: number;
  reviewRequestId: string;
}

export interface FixtureReviewCommand {
  schemaVersion: 'bloge.fixtureReviewCommand.v1';
  source: {
    reviewRequestId: string;
    fixtureSetId: string;
    revision: number;
    fingerprint: string;
    statusRevision: number;
  };
  attestations: {
    redactionReviewed: true;
    schemaValid: true;
    redactionVerified: true;
    comment: string;
  };
}

export interface FixtureReviewReceipt {
  schemaVersion: 'bloge.fixtureReviewReceipt.v1';
  reviewRequestId: string;
  fixtureSetId: string;
  derivedFromRevision: number;
  revision: number;
  fingerprint: string;
  status: 'TEAM_AVAILABLE';
  statusRevision: number;
  activatedAssetCount: number;
}

/** Builds an exact API-only reusable DAG and derives edges solely from input mappings. */
export function buildReusableFlowCommand(draft: FlowFormDraft, nodes: ResolvedFlowNode[]): ReusableFlowCommand {
  identifier(draft.flowId, 'Flow ID');
  const displayName = draft.displayName.trim();
  if (!displayName || displayName.length > 200) throw new Error('Flow name is required.');
  if (!['TOOL', 'SOLUTION'].includes(draft.kind)) throw new Error('Flow kind is invalid.');
  if (draft.description.length > 2000) throw new Error('Description is too long.');
  if (nodes.length === 0) throw new Error('Add at least one API Resource.');
  const seen = new Set<string>();
  const flowProperties: Record<string, { type: string; [key: string]: unknown }> = {};
  const flowRequired: string[] = [];
  const graphNodes = nodes.map((node, index) => {
    const nodeId = identifier(node.nodeId, 'Node ID');
    if (seen.has(nodeId)) throw new Error('Node IDs must be unique.');
    seen.add(nodeId);
    const inputs = Object.entries(node.item.contract.input.schema.properties).map(([name, schema]) => {
      const prior = findPriorOutput(nodes, index, name, schema.type);
      if (prior) {
        return { to: `$.${name}`, from: { kind: 'NODE_OUTPUT' as const, nodeId: prior.nodeId, path: `$.${name}` } };
      }
      const existing = flowProperties[name];
      if (existing && existing.type !== schema.type) throw new Error(`Input ${name} has incompatible types.`);
      flowProperties[name] = structuredClone(schema);
      if (node.item.contract.input.schema.required.includes(name) && !flowRequired.includes(name)) {
        flowRequired.push(name);
      }
      return { to: `$.${name}`, from: { kind: 'FLOW_INPUT' as const, path: `$.${name}` } };
    });
    return {
      nodeId, label: node.label.trim() || node.item.displayName,
      use: structuredClone(node.item.reference),
      inputs,
    };
  });
  const last = nodes[nodes.length - 1];
  return {
    schemaVersion: 'bloge.reusableFlowSaveCommand.v1',
    flow: {
      displayName, kind: draft.kind, description: draft.description,
      contract: {
        input: envelope(flowProperties, flowRequired),
        output: structuredClone(last.item.contract.output),
      },
      graph: { nodes: graphNodes, output: { nodeId: graphNodes[graphNodes.length - 1].nodeId, path: '$' } },
      layout: {
        nodes: Object.fromEntries(graphNodes.map((node, index) => [node.nodeId, { x: 120 + index * 280, y: 160 }])),
      },
    },
  };
}

/** Builds one parent-Flow Case whose every node explicitly reuses an exact leaf Fixture Case. */
export function buildParentFlowFixtureCommand(
  subject: FlowVersionRef, displayName: string, inputSource: string, outputSource: string,
  nodes: ResolvedFlowNode[], selections: Record<string, FixtureSetSummary>,
): FixtureSetCommand {
  const input = objectJson(inputSource, 'Fixture input');
  const output = objectJson(outputSource, 'Fixture output');
  const controls = nodes.map((node) => {
    const selected = selections[node.nodeId];
    const fixtureCase = selected?.cases[0];
    if (!selected || !fixtureCase) throw new Error(`Select one Fixture Case for ${node.label}.`);
    return {
      target: { kind: 'NODE' as const, nodeId: node.nodeId },
      behavior: {
        kind: 'APPLY_CASE' as const, fixtureSetId: selected.fixtureSetId,
        revision: selected.revision, caseId: fixtureCase.caseId,
      },
    };
  });
  return {
    schemaVersion: 'bloge.fixtureSetCommand.v1', displayName: requiredText(displayName, 'Fixture name'), subject,
    cases: [{ caseId: 'default', name: 'Default', input, controls, expect: { output } }],
  };
}

/** Builds one explicit whole-flow RETURN case for the Fixture task on the object page. */
export function buildFlowFixtureCommand(
  subject: FlowDraftRef | FlowVersionRef, displayName: string, inputSource: string, outputSource: string,
): FixtureSetCommand {
  const input = objectJson(inputSource, 'Fixture input');
  const output = objectJson(outputSource, 'Fixture output');
  return {
    schemaVersion: 'bloge.fixtureSetCommand.v1', displayName: requiredText(displayName, 'Fixture name'), subject,
    cases: [{
      caseId: 'default', name: 'Default', input,
      controls: [{
        target: { kind: 'SUBJECT' },
        behavior: { kind: 'RETURN', material: { kind: 'INLINE', value: output } },
      }],
      expect: { output },
    }],
  };
}

function findPriorOutput(nodes: ResolvedFlowNode[], before: number, name: string, type: string) {
  for (let index = before - 1; index >= 0; index -= 1) {
    const schema = nodes[index].item.contract.output.schema.properties[name];
    if (schema?.type === type) return nodes[index];
  }
  return null;
}

function envelope(properties: Record<string, { type: string; [key: string]: unknown }>, required: string[]): SchemaEnvelope {
  return {
    format: 'json-schema', version: '2020-12',
    schema: { type: 'object', properties, required, additionalProperties: false },
  };
}

function objectJson(source: string, label: string): JsonObject {
  try {
    const parsed: unknown = JSON.parse(source);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error();
    return parsed as JsonObject;
  } catch {
    throw new Error(`${label} must be a JSON object.`);
  }
}

function identifier(value: string, label: string): string {
  const normalized = value.trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(normalized)) {
    throw new Error(`${label} must be a simple identifier.`);
  }
  return normalized;
}

function requiredText(value: string, label: string): string {
  const normalized = value.trim();
  if (!normalized || normalized.length > 200) throw new Error(`${label} is required.`);
  return normalized;
}
