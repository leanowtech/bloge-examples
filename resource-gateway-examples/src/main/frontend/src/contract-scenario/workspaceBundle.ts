import { canonicalJson, sha256Fingerprint } from './fingerprint';
import type {
  ContractDraft,
  ScenarioDraftSet,
  ScenarioPublicationAssetRef,
  StoredScenarioPublication,
  VisualAuthoringWorkspaceBundle,
} from './domain';
import type { GraphDraft } from '../types';

const SCHEMA_VERSION = 'bloge.visualAuthoringWorkspaceBundle.v1';
const FINGERPRINT = /^sha256:[0-9a-f]{64}$/;
const SENSITIVE_FIELD = /(secret|password|passwd|token|api[_-]?key|credential|authorization|bearer)/i;
const REFERENCE_FIELD = /(secretRef|secretReference|credentialRef|tokenRef|apiKeyRef)/i;
const RAW_SECRET_VALUE = /^(Bearer\s+.+|Basic\s+.+|sk-[A-Za-z0-9_-]{12,}|AKIA[A-Z0-9]{12,}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/i;
const TOP_LEVEL_FIELDS = new Set([
  'schemaVersion',
  'classification',
  'graphDraft',
  'contractProjection',
  'scenarioDraftSet',
  'operatorSnapshotRefs',
  'publicationRefs',
]);
const CLASSIFICATIONS = new Set(['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED']);

/** Stable import failure that carries only machine diagnostics and value-free JSON Pointer paths. */
export class WorkspaceBundleError extends Error {
  readonly code: string;
  readonly paths: string[];

  constructor(code: string, message: string, paths: string[] = []) {
    super(message);
    this.name = 'WorkspaceBundleError';
    this.code = code;
    this.paths = [...paths];
  }
}

/** Creates the secret-safe portable asset shared by browser and VS Code authoring hosts. */
export function createWorkspaceBundle(
  graphDraft: GraphDraft,
  contract: ContractDraft,
  contractFingerprint: string,
  scenarioDraftSet: ScenarioDraftSet,
  publication: StoredScenarioPublication | null,
): VisualAuthoringWorkspaceBundle {
  const publicationRefs: ScenarioPublicationAssetRef[] = publication
    ? [
        ...publication.report.fixtures,
        ...(publication.report.suite ? [publication.report.suite] : []),
      ]
    : [];
  return {
    schemaVersion: SCHEMA_VERSION,
    classification: scenarioDraftSet.metadata.classification,
    graphDraft,
    contractProjection: {
      schemaVersion: 'bloge.scenarioContractProjection.v1',
      scope: { ...scenarioDraftSet.scope },
      contract,
      contractFingerprint,
    },
    scenarioDraftSet,
    operatorSnapshotRefs: graphDraft.nodes.map((node) => ({
      nodeId: node.id,
      operatorRef: node.operatorRef,
      ...(graphDraft.operatorFingerprints?.[node.id]
        ? { fingerprint: graphDraft.operatorFingerprints[node.id] }
        : {}),
    })),
    publicationRefs,
  };
}

/** Parses and independently re-verifies every coordinate required for offline authoring. */
export async function parseWorkspaceBundle(text: string): Promise<VisualAuthoringWorkspaceBundle> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new WorkspaceBundleError('WORKSPACE_JSON_INVALID', 'Workspace bundle is not valid JSON.');
  }
  if (!isRecord(parsed)) {
    throw new WorkspaceBundleError('WORKSPACE_SHAPE_INVALID', 'Workspace bundle must be an object.');
  }
  const unknownFields = Object.keys(parsed).filter((field) => !TOP_LEVEL_FIELDS.has(field));
  if (unknownFields.length > 0 || parsed.schemaVersion !== SCHEMA_VERSION) {
    throw new WorkspaceBundleError(
      'WORKSPACE_SCHEMA_UNSUPPORTED',
      'Workspace schema version or top-level fields are unsupported.',
      unknownFields.map((field) => `/${pointer(field)}`),
    );
  }
  const secretPaths = rawSecretPaths(parsed);
  if (secretPaths.length > 0) {
    throw new WorkspaceBundleError(
      'WORKSPACE_RAW_SECRET_FORBIDDEN',
      'Raw secret material is forbidden; use a secretRef.',
      secretPaths,
    );
  }
  if (!isWorkspaceShape(parsed)) {
    throw new WorkspaceBundleError(
      'WORKSPACE_SHAPE_INVALID',
      'Workspace bundle is missing a required authoring asset.',
    );
  }
  const bundle = parsed as unknown as VisualAuthoringWorkspaceBundle;
  if (!FINGERPRINT.test(bundle.contractProjection.contractFingerprint)) {
    throw new WorkspaceBundleError(
      'WORKSPACE_CONTRACT_FINGERPRINT_INVALID',
      'Contract fingerprint is malformed.',
    );
  }
  const actualContractFingerprint = await sha256Fingerprint(bundle.contractProjection.contract);
  if (actualContractFingerprint !== bundle.contractProjection.contractFingerprint) {
    throw new WorkspaceBundleError(
      'WORKSPACE_CONTRACT_FINGERPRINT_INVALID',
      'Contract fingerprint does not match the bundled Contract.',
    );
  }
  const actualTargetFingerprint = await sha256Fingerprint(bundle.graphDraft);
  const target = bundle.contractProjection.contract.target;
  const expectedTargetId = bundle.graphDraft.draftId || bundle.graphDraft.graphName;
  if (target.kind !== 'GRAPH'
      || target.id !== expectedTargetId
      || target.revision !== Math.max(0, bundle.graphDraft.revision ?? 0)
      || target.fingerprint !== actualTargetFingerprint) {
    throw new WorkspaceBundleError(
      'WORKSPACE_TARGET_FINGERPRINT_INVALID',
      'Graph target coordinate does not match the bundled GraphDraft.',
    );
  }
  if (canonicalJson(bundle.scenarioDraftSet.target) !== canonicalJson(target)
      || bundle.scenarioDraftSet.contractFingerprint
        !== bundle.contractProjection.contractFingerprint) {
    throw new WorkspaceBundleError(
      'WORKSPACE_TARGET_MISMATCH',
      'Scenario target or Contract coordinate does not match the bundle.',
    );
  }
  if (canonicalJson(bundle.scenarioDraftSet.scope)
      !== canonicalJson(bundle.contractProjection.scope)
      || (bundle.graphDraft.tenantId
        && bundle.graphDraft.tenantId !== bundle.scenarioDraftSet.scope.tenantId)
      || (bundle.graphDraft.environment
        && bundle.graphDraft.environment !== bundle.scenarioDraftSet.scope.environment)) {
    throw new WorkspaceBundleError(
      'WORKSPACE_SCOPE_MISMATCH',
      'Graph, Contract, and Scenario enterprise scopes do not match.',
    );
  }
  const expectedRefs = createWorkspaceBundle(
    bundle.graphDraft,
    bundle.contractProjection.contract,
    bundle.contractProjection.contractFingerprint,
    bundle.scenarioDraftSet,
    null,
  ).operatorSnapshotRefs;
  if (canonicalJson(expectedRefs) !== canonicalJson(bundle.operatorSnapshotRefs)) {
    throw new WorkspaceBundleError(
      'WORKSPACE_OPERATOR_INDEX_INVALID',
      'Operator snapshot index does not match the bundled GraphDraft.',
    );
  }
  if (bundle.classification !== bundle.scenarioDraftSet.metadata.classification) {
    throw new WorkspaceBundleError(
      'WORKSPACE_CLASSIFICATION_MISMATCH',
      'Workspace classification must match the Scenario asset.',
    );
  }
  return bundle;
}

function rawSecretPaths(value: unknown): string[] {
  const paths: string[] = [];
  scan(value, '', '', paths);
  return paths.sort();
}

function scan(value: unknown, path: string, fieldName: string, paths: string[]) {
  if (Array.isArray(value)) {
    value.forEach((entry, index) => scan(entry, `${path}/${index}`, fieldName, paths));
    return;
  }
  if (isRecord(value)) {
    Object.entries(value).forEach(([key, entry]) => (
      scan(entry, `${path}/${pointer(key)}`, key, paths)
    ));
    return;
  }
  if (typeof value !== 'string' || value.trim() === '') return;
  const candidate = value.trim();
  if (REFERENCE_FIELD.test(fieldName)
      || candidate.startsWith('${')
      || candidate.startsWith('{{')
      || candidate.startsWith('secretRef:')) {
    return;
  }
  if (SENSITIVE_FIELD.test(fieldName) || RAW_SECRET_VALUE.test(candidate)) {
    paths.push(path || '/');
  }
}

function pointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isWorkspaceShape(value: Record<string, unknown>): boolean {
  if (!CLASSIFICATIONS.has(String(value.classification))
      || !isRecord(value.graphDraft)
      || value.graphDraft.schemaVersion !== 'bloge.visualGraphDraft.v1'
      || typeof value.graphDraft.graphName !== 'string'
      || value.graphDraft.graphName.trim() === ''
      || !Array.isArray(value.graphDraft.nodes)
      || !Array.isArray(value.graphDraft.edges)
      || !value.graphDraft.nodes.every(isGraphNode)
      || !isRecord(value.contractProjection)
      || value.contractProjection.schemaVersion !== 'bloge.scenarioContractProjection.v1'
      || !isRecord(value.contractProjection.contract)
      || value.contractProjection.contract.schemaVersion !== 'bloge.contractDraft.v1'
      || !isExactTarget(value.contractProjection.contract.target)
      || !Array.isArray(value.contractProjection.contract.errorContract)
      || !isRecord(value.contractProjection.contract.executionSemantics)
      || !Array.isArray(value.contractProjection.contract.invariants)
      || !isRecord(value.contractProjection.contract.compatibilityPolicy)
      || !isRecord(value.contractProjection.contract.fieldMetadata)
      || !isScope(value.contractProjection.scope)
      || !isRecord(value.scenarioDraftSet)
      || value.scenarioDraftSet.schemaVersion !== 'bloge.scenarioDraftSet.v1'
      || !isExactTarget(value.scenarioDraftSet.target)
      || !isScope(value.scenarioDraftSet.scope)
      || !Array.isArray(value.scenarioDraftSet.scenarios)
      || !value.scenarioDraftSet.scenarios.every(isScenario)
      || !isRecord(value.scenarioDraftSet.metadata)
      || !CLASSIFICATIONS.has(String(value.scenarioDraftSet.metadata.classification))
      || !Array.isArray(value.operatorSnapshotRefs)
      || !value.operatorSnapshotRefs.every(isOperatorSnapshotRef)
      || !Array.isArray(value.publicationRefs)
      || !value.publicationRefs.every(isPublicationRef)) {
    return false;
  }
  return true;
}

function isGraphNode(value: unknown): boolean {
  return isRecord(value)
    && typeof value.id === 'string'
    && value.id.trim() !== ''
    && typeof value.operatorRef === 'string'
    && value.operatorRef.trim() !== '';
}

function isExactTarget(value: unknown): boolean {
  return isRecord(value)
    && (value.kind === 'GRAPH' || value.kind === 'OPERATOR')
    && typeof value.id === 'string'
    && value.id.trim() !== ''
    && Number.isInteger(value.revision)
    && typeof value.fingerprint === 'string'
    && FINGERPRINT.test(value.fingerprint);
}

function isScope(value: unknown): boolean {
  return isRecord(value)
    && ['tenantId', 'organizationId', 'projectId', 'environment', 'region']
      .every((field) => typeof value[field] === 'string' && String(value[field]).trim() !== '');
}

function isScenario(value: unknown): boolean {
  return isRecord(value)
    && typeof value.scenarioId === 'string'
    && value.scenarioId.trim() !== ''
    && isRecord(value.given)
    && Array.isArray(value.dependencies)
    && value.dependencies.every((dependency) => (
      isRecord(dependency)
      && isRecord(dependency.selector)
      && isRecord(dependency.behavior)
      && isRecord(dependency.consumption)
      && isRecord(dependency.schemaCheck)
    ))
    && isRecord(value.then)
    && Array.isArray(value.then.assertions)
    && value.then.assertions.every(isRecord);
}

function isOperatorSnapshotRef(value: unknown): boolean {
  return isRecord(value)
    && typeof value.nodeId === 'string'
    && value.nodeId.trim() !== ''
    && typeof value.operatorRef === 'string'
    && value.operatorRef.trim() !== ''
    && (value.fingerprint === undefined
      || (typeof value.fingerprint === 'string' && FINGERPRINT.test(value.fingerprint)));
}

function isPublicationRef(value: unknown): boolean {
  return isRecord(value)
    && (value.kind === 'FIXTURE_BUNDLE' || value.kind === 'TEST_SUITE')
    && typeof value.id === 'string'
    && value.id.trim() !== ''
    && Number.isInteger(value.revision)
    && Number(value.revision) > 0
    && typeof value.fingerprint === 'string'
    && FINGERPRINT.test(value.fingerprint);
}
