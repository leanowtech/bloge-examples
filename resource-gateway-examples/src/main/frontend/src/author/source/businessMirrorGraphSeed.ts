import type { LegacyGraphPackageProjection } from '../../business-mirror/domain';
import type { GatewayExampleDiagram, GatewayExampleScenario, GraphDraft } from '../../types';

export interface BusinessMirrorGraphSeedCoordinate {
  graphName: string;
  sourceId: string;
  sourceRevision: number;
  sourceFingerprint: string;
  returnCoordinate: {
    route: 'business-mirror';
    packageId: string;
    task: 'capabilities';
    anchor: string;
  };
}

/** Parses only the allowlisted Business Mirror source contract; malformed links are ignored. */
export function parseBusinessMirrorGraphSeed(search: string): BusinessMirrorGraphSeedCoordinate | null {
  const params = new URLSearchParams(search);
  if (params.get('sourceKind') !== 'BUSINESS_MIRROR_LEGACY_GRAPH') return null;
  const graphName = params.get('sourceGraphName')?.trim() ?? '';
  const sourceId = params.get('sourceId')?.trim() ?? '';
  const sourceRevision = Number(params.get('sourceRevision'));
  const sourceFingerprint = params.get('sourceFingerprint')?.trim() ?? '';
  const packageId = params.get('returnPackageId')?.trim() ?? '';
  const anchor = params.get('returnAnchor')?.trim() ?? '';
  if (!graphName || !sourceId || !packageId || !anchor
      || params.get('returnRoute') !== 'business-mirror'
      || params.get('returnTask') !== 'capabilities'
      || !Number.isSafeInteger(sourceRevision) || sourceRevision < 1
      || !/^sha256:[0-9a-f]{64}$/.test(sourceFingerprint)) {
    return null;
  }
  return {
    graphName,
    sourceId,
    sourceRevision,
    sourceFingerprint,
    returnCoordinate: { route: 'business-mirror', packageId, task: 'capabilities', anchor },
  };
}

/** Validates the exact Business Mirror source and projects its official layout into an Author draft. */
export function graphDraftFromBusinessMirrorSeed(
  coordinate: BusinessMirrorGraphSeedCoordinate,
  projection: LegacyGraphPackageProjection,
  scenario: GatewayExampleScenario,
  diagram: GatewayExampleDiagram,
): GraphDraft {
  const source = projection.sourceGraphRef;
  if (projection.graphName !== coordinate.graphName || scenario.graphName !== coordinate.graphName
      || diagram.rootId !== coordinate.graphName || source.id !== coordinate.sourceId
      || source.revision !== coordinate.sourceRevision
      || source.fingerprint !== coordinate.sourceFingerprint) {
    throw new Error('RG.AUTHORING.BUSINESS_MIRROR_SOURCE_DRIFT');
  }
  const nodeIds = new Set(diagram.nodes.map((node) => node.id));
  const outgoing = new Set(diagram.edges.map((edge) => edge.source));
  const outputNodeId = [...diagram.nodes].reverse()
    .find((node) => !outgoing.has(node.id))?.id
    ?? diagram.nodes[diagram.nodes.length - 1]?.id
    ?? '';
  if (!outputNodeId || diagram.edges.some((edge) => !nodeIds.has(edge.source) || !nodeIds.has(edge.target))) {
    throw new Error('RG.AUTHORING.BUSINESS_MIRROR_TOPOLOGY_INVALID');
  }
  return {
    schemaVersion: 'bloge.visualGraphDraft.v1',
    draftId: '',
    revision: 0,
    graphName: coordinate.graphName,
    tenantId: projection.scope.tenantId,
    namespace: projection.scope.projectId,
    environment: projection.scope.environmentId,
    status: 'SOURCE_PREVIEW',
    inputSchema: scenario.inputSchema,
    outputSchema: scenario.outputSchema,
    nodes: diagram.nodes.map((node) => ({
      id: node.id,
      operatorRef: node.operatorRef?.trim() || `bloge:inferred:${node.kind?.trim() || 'node'}`,
      label: node.label?.trim() || node.id,
      position: {
        x: finite(node.position?.x),
        y: finite(node.position?.y),
      },
      config: {
        sourceKind: node.kind?.trim() || 'node',
        sourceAnnotations: node.annotations ?? {},
      },
    })),
    edges: diagram.edges.map((edge, index) => ({
      id: edge.id?.trim() || `edge:${index + 1}`,
      kind: 'data',
      source: { nodeId: edge.source, port: 'output' },
      target: { nodeId: edge.target, port: 'inputs' },
      condition: edge.label?.trim() || undefined,
    })),
    output: { nodeId: outputNodeId },
    visualLayout: {
      source: {
        kind: 'BUSINESS_MIRROR_LEGACY_GRAPH',
        id: coordinate.sourceId,
        revision: coordinate.sourceRevision,
        fingerprint: coordinate.sourceFingerprint,
        readOnly: true,
      },
      returnCoordinate: coordinate.returnCoordinate,
      groups: diagram.groups ?? [],
      viewport: diagram.viewport ?? {},
    },
  };
}

function finite(value: number | undefined): number {
  return Number.isFinite(value) ? Number(value) : 0;
}
