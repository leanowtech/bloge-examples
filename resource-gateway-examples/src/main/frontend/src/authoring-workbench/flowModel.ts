import type { ApiResourceSpec, JsonObject, SchemaEnvelope } from './model';

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

export interface ApiResourceRef {
  kind: 'API_RESOURCE';
  resourceId: string;
  revision: number;
  fingerprint: string;
}

export type FixtureSubjectRef = ApiResourceRef | FlowDraftRef | FlowVersionRef;

export interface ResolvedApiNode {
  nodeId: string;
  label: string;
  resource: ApiResourceSpec;
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
        use: { kind: 'API_RESOURCE'; resourceId: string; revision: number; fingerprint: string };
        inputs: Array<{
          to: string;
          from: { kind: 'FLOW_INPUT'; path: string }
            | { kind: 'NODE_OUTPUT'; nodeId: string; path: string };
        }>;
      }>;
      output: { nodeId: string; path: '$' };
    };
    layout: { nodes: Record<string, { x: number; y: number }> };
  };
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
    controls: Array<{
      target: { kind: 'SUBJECT' } | { kind: 'NODE'; nodeId: string };
      behavior: FixtureBehavior;
      fidelity?: 'OUTPUT_LEVEL' | 'PROTOCOL_DERIVED' | 'TRANSPORT_LEVEL';
    }>;
    expect?: { output: JsonObject };
  }>;
}

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
export function buildReusableFlowCommand(draft: FlowFormDraft, nodes: ResolvedApiNode[]): ReusableFlowCommand {
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
    const inputs = Object.entries(node.resource.contract.input.schema.properties).map(([name, schema]) => {
      const prior = findPriorOutput(nodes, index, name, schema.type);
      if (prior) {
        return { to: `$.${name}`, from: { kind: 'NODE_OUTPUT' as const, nodeId: prior.nodeId, path: `$.${name}` } };
      }
      const existing = flowProperties[name];
      if (existing && existing.type !== schema.type) throw new Error(`Input ${name} has incompatible types.`);
      flowProperties[name] = structuredClone(schema);
      if (node.resource.contract.input.schema.required.includes(name) && !flowRequired.includes(name)) {
        flowRequired.push(name);
      }
      return { to: `$.${name}`, from: { kind: 'FLOW_INPUT' as const, path: `$.${name}` } };
    });
    return {
      nodeId, label: node.label.trim() || node.resource.displayName,
      use: {
        kind: 'API_RESOURCE' as const, resourceId: node.resource.resourceId,
        revision: node.resource.revision, fingerprint: node.resource.fingerprint,
      },
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
        output: structuredClone(last.resource.contract.output),
      },
      graph: { nodes: graphNodes, output: { nodeId: graphNodes[graphNodes.length - 1].nodeId, path: '$' } },
      layout: {
        nodes: Object.fromEntries(graphNodes.map((node, index) => [node.nodeId, { x: 120 + index * 280, y: 160 }])),
      },
    },
  };
}

/** Builds one explicit whole-flow RETURN case for the Fixture task on the object page. */
export function buildFlowFixtureCommand(
  subject: FlowDraftRef, displayName: string, inputSource: string, outputSource: string,
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

function findPriorOutput(nodes: ResolvedApiNode[], before: number, name: string, type: string) {
  for (let index = before - 1; index >= 0; index -= 1) {
    const schema = nodes[index].resource.contract.output.schema.properties[name];
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
