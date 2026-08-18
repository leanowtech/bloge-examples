export type LocalizedValue = string | {
  en?: string;
  'zh-CN'?: string;
  zh?: string;
};

export type CapabilityAssetKind = 'API' | 'FEATURE' | 'TOOL';

export type FeatureRehearsalPermission = 'STRUCTURE_ONLY' | 'PAYLOAD_VISIBLE';
export type FeatureRehearsalRunStatus = 'PASSED' | 'ASSERTION_FAILED' | 'EXECUTION_FAILED'
  | 'CONTROL_PLAN_REJECTED' | 'FIXTURE_UNMATCHED' | 'FIXTURE_UNUSED'
  | 'CONTROL_PLAN_UNAVAILABLE' | 'EVIDENCE_INCOMPLETE' | 'CANCELLED' | 'TIMED_OUT';
export type FeatureRehearsalNodeStatus = 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'SKIPPED'
  | 'PARTIAL' | 'MOCKED' | 'CANCELLED' | 'FALLBACK' | 'NOT_INVOKED';
export type FeatureRehearsalEdgeStatus = 'TRANSFERRED' | 'SKIPPED' | 'NOT_TRANSFERRED';

export interface FeatureRehearsalAttempt {
  attempt: number;
  status: FeatureRehearsalNodeStatus;
  fidelity: string;
  input: unknown | null;
  inputFingerprint: string;
  output: unknown | null;
  outputFingerprint: string;
  errorCode: string;
  durationMs: number;
}

export interface FeatureRehearsalNode {
  nodeId: string;
  operatorRef: string;
  status: FeatureRehearsalNodeStatus;
  fidelity: string;
  graphPath: string;
  invocationSite: string;
  correlation: string;
  occurrence: number;
  graphOccurrence: number;
  input: unknown | null;
  inputFingerprint: string;
  output: unknown | null;
  outputFingerprint: string;
  errorCode: string;
  durationMs: number;
  attempts: FeatureRehearsalAttempt[];
  retryCount: number;
  fallbackStatus: string | null;
}

export interface FeatureRehearsalEdge {
  edgeId: string;
  status: FeatureRehearsalEdgeStatus;
  graphPath: string;
  correlation: string;
  graphOccurrence: number;
  fromInvocationSite: string;
  toInvocationSite: string;
  value: unknown | null;
  valueFingerprint: string;
}

export interface FeatureRehearsalFirstDifference {
  source: string;
  locator: string;
  scope: string;
  path: string;
  expected: unknown | null;
  expectedFingerprint: string;
  actual: unknown | null;
  actualFingerprint: string;
}

export interface FeatureRehearsalTruncation {
  nodesTruncated: boolean;
  omittedNodes: number;
  edgesTruncated: boolean;
  omittedEdges: number;
  attemptsTruncated: boolean;
  omittedAttempts: number;
}

export interface FeatureRehearsalProjection {
  schemaVersion: 'resource-gateway.capability-studio.feature-rehearsal.v1';
  scenario: { id: string; name: LocalizedValue; expectedResult: LocalizedValue };
  graph: { id: string; fingerprint: string };
  run: {
    runId: string;
    status: FeatureRehearsalRunStatus;
    semanticFingerprint: string;
    realExternalCallCount: number;
    bindingMode: 'FIXTURE_CONTROLLED_NON_PRODUCTION';
  };
  dataLens: {
    schemaVersion: 'resource-gateway.capability-studio.data-lens.v1';
    runId: string;
    runStatus: FeatureRehearsalRunStatus;
    permissionMode: FeatureRehearsalPermission;
    nodes: FeatureRehearsalNode[];
    edges: FeatureRehearsalEdge[];
    firstDifference: FeatureRehearsalFirstDifference | null;
    truncation: FeatureRehearsalTruncation;
    fingerprint: string;
  };
}

export type GovernedBaselineStatus = 'PASSED' | 'FAILED_CLOSED';
export type GovernedBaselineRoundStatus =
  | 'PASSED'
  | 'FAILED_CLOSED'
  | 'COMPLETED_WITH_FAILURES'
  | 'PARTIAL'
  | 'EVIDENCE_INCOMPLETE';
export type GovernedBaselineCaseStatus =
  | 'PASSED'
  | 'FAILED'
  | 'FAILED_CLOSED'
  | 'ASSERTION_FAILED'
  | 'EXECUTION_FAILED'
  | 'CONTROL_PLAN_REJECTED'
  | 'FIXTURE_UNMATCHED'
  | 'FIXTURE_UNUSED'
  | 'CONTROL_PLAN_UNAVAILABLE'
  | 'EVIDENCE_INCOMPLETE'
  | 'CANCELLED'
  | 'TIMED_OUT'
  | 'NOT_SCHEDULED';

export interface GovernedBaselineSuiteRef {
  kind: 'TEST_SUITE';
  id: string;
  revision: number;
  fingerprint: string;
}

export interface GovernedBaselineRound {
  round: number;
  suiteRunId: string;
  evidenceFingerprint: string;
  status: GovernedBaselineRoundStatus;
  childRunCount: number;
}

export interface GovernedBaselineCaseRound {
  round: number;
  runId: string;
  status: GovernedBaselineCaseStatus;
  fixtureBundleId: string;
  fixtureRevision: number;
  fixtureFingerprint: string;
  evidenceFingerprint: string;
  semanticResultFingerprint: string;
  assertionsEvaluated: 1;
  assertionsPassed: 1;
  fixtureControlsEvaluated: number;
  fixtureControlsSatisfied: number;
}

export interface GovernedBaselineCase {
  caseId: string;
  oracleId: string;
  oracleStatus: 'PASS';
  semanticResultFingerprint: string;
  assertionsEvaluated: 3;
  assertionsPassed: 3;
  fixtureControlsEvaluated: number;
  fixtureControlsSatisfied: number;
  proofs: string[];
  rounds: GovernedBaselineCaseRound[];
}

interface GovernedBaselineProjectionBase {
  schemaVersion: 'resource-gateway.capability-studio.governed-baseline.v2';
  evidenceKind: 'DEVELOPMENT_TEST_OWNED';
  baselineId: string;
  verificationScope: 'GOVERNED_SUITE_ASSERTIONS_AND_BUSINESS_ORACLES';
  releaseGateStatus: 'NO_GO';
  caseCount: 9;
  roundCount: 3;
  limitations: string[];
  diagnostics: string[];
}

export interface GovernedBaselineSuccessProjection extends GovernedBaselineProjectionBase {
  status: 'PASSED';
  suiteRunCount: 3;
  childRunCount: 27;
  evidenceClass: 'EXPLORATORY';
  oraclePassCount: 9;
  businessCheckCount: 27;
  businessCheckPassCount: 27;
  realExternalCallCount: number;
  compilationFingerprint: string;
  sourceMapFingerprint: string;
  provenanceFingerprint: string;
  publication: {
    receiptFingerprint: string;
    suiteRef: GovernedBaselineSuiteRef;
    fixtureCount: 9;
  };
  rounds: [GovernedBaselineRound, GovernedBaselineRound, GovernedBaselineRound];
  cases: [
    GovernedBaselineCase,
    GovernedBaselineCase,
    GovernedBaselineCase,
    GovernedBaselineCase,
    GovernedBaselineCase,
    GovernedBaselineCase,
    GovernedBaselineCase,
    GovernedBaselineCase,
    GovernedBaselineCase
  ];
}

export interface GovernedBaselineFailureProjection extends GovernedBaselineProjectionBase {
  status: 'FAILED_CLOSED';
  suiteRunCount: 0;
  childRunCount: 0;
  evidenceClass: null;
  oraclePassCount: 0;
  businessCheckCount: 0;
  businessCheckPassCount: 0;
  realExternalCallCount: null;
  compilationFingerprint: null;
  sourceMapFingerprint: null;
  provenanceFingerprint: null;
  publication: null;
  rounds: [];
  cases: [];
}

export type GovernedBaselineProjection =
  | GovernedBaselineSuccessProjection
  | GovernedBaselineFailureProjection;

export interface CapabilityAssetSummary {
  kind: CapabilityAssetKind;
  name: LocalizedValue;
  summary: LocalizedValue;
  owner: LocalizedValue;
  readiness: LocalizedValue;
  technicalRef?: string;
  fingerprint?: string;
  contract?: ContractSummary;
}

export interface ContractSummary {
  inputs: Array<ContractField>;
  successResult: Array<ContractField>;
  errors: Array<ContractError>;
  sideEffects: LocalizedValue[];
  owner: LocalizedValue;
  sla: LocalizedValue;
  sensitivity: LocalizedValue;
}

export interface ContractField {
  name: LocalizedValue;
  type: LocalizedValue;
  required?: boolean;
  description?: LocalizedValue;
}

export interface ContractError {
  code: LocalizedValue;
  meaning: LocalizedValue;
  retryable?: boolean;
}

export interface ScenarioRow {
  name: LocalizedValue;
  category: LocalizedValue;
  source: LocalizedValue;
  owner: LocalizedValue;
  oracle: LocalizedValue;
  contractCount: number;
  expectedResult: LocalizedValue;
  quality: LocalizedValue;
  lifecycle: LocalizedValue;
  technicalRef?: string;
}

export type ScenarioDatasetLifecycle = 'DRAFT' | 'REVIEW_READY' | 'ACTIVE' | 'STALE' | 'RETIRED';
export type ScenarioDatasetClassification = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';
export type ScenarioCaseCategory = 'GOLDEN' | 'NEGATIVE' | 'BOUNDARY' | 'FAULT' | 'REGRESSION' | 'SECURITY';
export type ScenarioCaseLifecycle = 'DRAFT' | 'ACTIVE' | 'STALE' | 'RETIRED';
export type ScenarioCaseQualityState = 'DESIGNED_NOT_RUN' | 'READY' | 'STALE' | 'BLOCKED';
export type ScenarioBehavior = 'RETURN' | 'ERROR' | 'DELAY' | 'TIMEOUT' | 'REPLAY' | 'OBSERVE' | 'MUST_NOT_CALL';

export interface ScenarioScope {
  tenantId: string;
  organizationId: string;
  projectId: string;
  environmentId: string;
  region: string;
}

export type ScenarioRefKind = 'API' | 'FEATURE' | 'TOOL' | 'GRAPH_DRAFT' | 'CONTRACT' | 'DATASET' | 'DATA_CASE' | 'BEHAVIOR_PROFILE' | 'BINDING_PLAN' | 'CAPABILITY_SNAPSHOT' | 'SOURCE' | 'ORACLE' | 'FIXTURE_BUNDLE' | 'TEST_SUITE' | 'MIRROR_PLAN' | 'EVIDENCE';

export interface ScenarioExactRef {
  kind: ScenarioRefKind;
  id: string;
  revision: number;
  fingerprint: string;
  authority: string;
  scope: ScenarioScope;
}

export interface ScenarioOwner {
  id: string;
  name: string;
}

export interface ScenarioSource {
  displayName: string;
  type: string;
}

export interface ScenarioOracle {
  displayName: string;
  summary: string;
}

export interface ScenarioBehaviorProfile {
  behaviorRef: ScenarioExactRef;
  dependencyRef: ScenarioExactRef;
  purpose: 'RUNTIME_CONTROL' | 'BUSINESS_EXPECTATION';
  behavior: ScenarioBehavior;
  summary: string;
}

export interface ScenarioCase {
  caseRef: ScenarioExactRef;
  name: string;
  businessIntent: string;
  category: ScenarioCaseCategory;
  lifecycle: ScenarioCaseLifecycle;
  qualityState: ScenarioCaseQualityState;
  owner: ScenarioOwner | null;
  sourceRef: ScenarioExactRef | null;
  source: ScenarioSource | null;
  oracleRef: ScenarioExactRef | null;
  oracle: ScenarioOracle | null;
  applicableContractRefs: ScenarioExactRef[];
  behaviorProfiles: ScenarioBehaviorProfile[];
}

export interface ScenarioDatasetQuality {
  status: 'READY' | 'STALE' | 'BLOCKED';
  totalCaseCount: number;
  activeCaseCount: number;
  staleCaseCount: number;
  ownerCoveragePercent: number;
  sourceCoveragePercent: number;
  oracleCoveragePercent: number;
  contractCoveragePercent: number;
  behaviorClosurePercent: number;
}

export interface ScenarioDataset {
  schemaVersion: 'resource-gateway.capability-studio.scenario-dataset.v1';
  datasetRef: ScenarioExactRef;
  name: string;
  description: string;
  lifecycle: ScenarioDatasetLifecycle;
  classification: ScenarioDatasetClassification;
  owner: ScenarioOwner;
  targetRef: ScenarioExactRef;
  contractRefs: ScenarioExactRef[];
  cases: ScenarioCase[];
  quality: ScenarioDatasetQuality;
}

export interface BranchSummary {
  name: LocalizedValue;
  purpose: LocalizedValue;
  status: LocalizedValue;
  technicalRef?: string;
}

export interface CapabilityStudioModel {
  capability: {
    name: LocalizedValue;
    summary: LocalizedValue;
    owner: LocalizedValue;
    readiness: LocalizedValue;
    technicalRef?: string;
    fingerprint?: string;
  };
  assets: {
    apis: CapabilityAssetSummary[];
    features: CapabilityAssetSummary[];
    tools: CapabilityAssetSummary[];
  };
  scenarios: ScenarioRow[];
  baseline: BranchSummary;
  tutorialBranch: BranchSummary;
  acceptanceStatus: LocalizedValue;
  protocolVersion?: string;
}

export class CapabilityStudioProtocolError extends Error {
  readonly code: string;
  readonly impact: string;

  constructor(code: string, message: string, impact: string) {
    super(message);
    this.name = 'CapabilityStudioProtocolError';
    this.code = code;
    this.impact = impact;
  }
}

export function localized(value: LocalizedValue | undefined, locale: 'en' | 'zh-CN'): string {
  if (typeof value === 'string') return value;
  if (!value) return '';
  return value[locale] ?? value.en ?? value['zh-CN'] ?? value.zh ?? '';
}

export function isCapabilityStudioProtocolError(error: unknown): error is CapabilityStudioProtocolError {
  return error instanceof CapabilityStudioProtocolError;
}

type JsonObject = Record<string, unknown>;

function object(value: unknown, path: string): JsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw invalid(`Expected an object at ${path}.`);
  }
  return value as JsonObject;
}

function array(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) throw invalid(`Expected an array at ${path}.`);
  return value;
}

function text(value: unknown, path: string): LocalizedValue {
  if (typeof value === 'string' && value.trim()) return value;
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const candidate = value as JsonObject;
    if (['en', 'zh-CN', 'zh'].some((key) => typeof candidate[key] === 'string' && (candidate[key] as string).trim())) {
      return value as LocalizedValue;
    }
  }
  throw invalid(`Missing required text at ${path}.`);
}

function optionalText(value: unknown): LocalizedValue | undefined {
  if (typeof value === 'string' && value.trim()) return value;
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const candidate = value as JsonObject;
    if (['en', 'zh-CN', 'zh'].some((key) => typeof candidate[key] === 'string' && (candidate[key] as string).trim())) {
      return value as LocalizedValue;
    }
  }
  return undefined;
}

function displayText(value: unknown, path: string, ...keys: string[]): LocalizedValue {
  const direct = optionalText(value);
  if (direct) return direct;
  const source = object(value, path);
  return text(pick(source, ...keys), path);
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function exactRef(value: unknown): { display?: string; fingerprint?: string } {
  if (typeof value === 'string' && value.trim()) return { display: value };
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const ref = value as JsonObject;
  const kind = stringValue(ref.kind);
  const id = stringValue(ref.id);
  const revision = typeof ref.revision === 'number' ? ref.revision : undefined;
  return {
    display: kind && id && revision ? `${kind}:${id}@${revision}` : id,
    fingerprint: stringValue(ref.fingerprint),
  };
}

function pick(source: JsonObject, ...keys: string[]): unknown {
  return keys.map((key) => source[key]).find((value) => value !== undefined);
}

function invalid(message: string): CapabilityStudioProtocolError {
  return new CapabilityStudioProtocolError(
    'RG.CAPABILITY_STUDIO.INVALID_DEMO_PACK',
    `[RG.CAPABILITY_STUDIO.INVALID_DEMO_PACK] ${message}`,
    'The Capability Studio cannot establish the canonical asset, contract, or scenario inventory.',
  );
}

function invalidScenarioDataset(message: string): CapabilityStudioProtocolError {
  return new CapabilityStudioProtocolError(
    'RG.CAPABILITY_STUDIO.INVALID_SCENARIO_DATASET',
    `[RG.CAPABILITY_STUDIO.INVALID_SCENARIO_DATASET] ${message}`,
    'The scenario dataset cannot be trusted or displayed, so GP-03 remains unavailable.',
  );
}

function invalidFeatureRehearsal(message: string): CapabilityStudioProtocolError {
  return new CapabilityStudioProtocolError(
    'RG.CAPABILITY_STUDIO.INVALID_FEATURE_REHEARSAL',
    `[RG.CAPABILITY_STUDIO.INVALID_FEATURE_REHEARSAL] ${message}`,
    'The Feature rehearsal response cannot be trusted or displayed.',
  );
}

function invalidGovernedBaseline(message: string): CapabilityStudioProtocolError {
  return new CapabilityStudioProtocolError(
    'RG.CAPABILITY_STUDIO.INVALID_GOVERNED_BASELINE',
    `[RG.CAPABILITY_STUDIO.INVALID_GOVERNED_BASELINE] ${message}`,
    'The governed baseline cannot be trusted, development validation was not established, and existing assets remain unchanged.',
  );
}

function parseField(value: unknown, path: string): ContractField {
  const source = object(value, path);
  return {
    name: text(pick(source, 'name', 'label'), `${path}.name`),
    type: text(pick(source, 'type', 'schemaType'), `${path}.type`),
    required: source.required === undefined ? undefined : Boolean(source.required),
    description: optionalText(pick(source, 'description', 'meaning')),
  };
}

function parseOutputField(value: unknown, path: string): ContractField {
  if (typeof value === 'string' && value.trim()) {
    return { name: value, type: 'contract output' };
  }
  return parseField(value, path);
}

function parseContract(
  value: unknown,
  path: string,
  fallbackOwner: LocalizedValue,
  asset: JsonObject,
): ContractSummary {
  const source = object(value, path);
  const inputs = array(pick(source, 'inputs', 'inputSchema', 'request'), `${path}.inputs`)
    .map((entry, index) => parseField(entry, `${path}.inputs[${index}]`));
  const rawSideEffects = pick(source, 'sideEffects', 'effects', 'sideEffect', 'effect') ?? asset.sideEffect;
  const sideEffects = Array.isArray(rawSideEffects)
    ? rawSideEffects.map((entry, index) => text(entry, `${path}.sideEffects[${index}]`))
    : [text(rawSideEffects, `${path}.sideEffect`)];
  return {
    inputs,
    successResult: array(pick(source, 'successResult', 'successOutputs', 'outputs', 'outputSchema', 'success'), `${path}.successResult`)
      .map((entry, index) => parseOutputField(entry, `${path}.successResult[${index}]`)),
    errors: array(pick(source, 'errors', 'errorSchema'), `${path}.errors`).map((entry, index) => {
      const error = object(entry, `${path}.errors[${index}]`);
      return {
        code: text(pick(error, 'code', 'name'), `${path}.errors[${index}].code`),
        meaning: text(pick(error, 'meaning', 'description', 'message'), `${path}.errors[${index}].meaning`),
        retryable: error.retryable === undefined ? undefined : Boolean(error.retryable),
      };
    }),
    sideEffects,
    owner: optionalText(source.owner) ?? fallbackOwner,
    sla: text(pick(source, 'sla', 'serviceLevel') ?? asset.sla, `${path}.sla`),
    sensitivity: optionalText(pick(source, 'sensitivity', 'dataSensitivity'))
      ?? (inputs.some((_, index) => {
        const raw = array(pick(source, 'inputs', 'inputSchema', 'request'), `${path}.inputs`)[index];
        return Boolean(raw && typeof raw === 'object' && !Array.isArray(raw) && (raw as JsonObject).sensitive);
      }) ? 'SENSITIVE_FIELDS_DECLARED' : 'NO_SENSITIVE_FIELDS_DECLARED'),
  };
}

function parseAsset(value: unknown, kind: CapabilityAssetKind, path: string, fallbackOwner: LocalizedValue): CapabilityAssetSummary {
  const source = object(value, path);
  const owner = source.owner === undefined
    ? fallbackOwner
    : displayText(source.owner, `${path}.owner`, 'name', 'displayName');
  const contractValue = pick(source, 'contract', 'businessContract', 'contractSummary');
  const ref = exactRef(pick(source, 'technicalRef', 'ref', 'assetRef'));
  return {
    kind,
    name: text(pick(source, 'name', 'label', 'displayName'), `${path}.name`),
    summary: text(pick(source, 'summary', 'description', 'purpose'), `${path}.summary`),
    owner,
    readiness: text(pick(source, 'readiness', 'status', 'lifecycle'), `${path}.readiness`),
    technicalRef: ref.display ?? stringValue(source.id),
    fingerprint: ref.fingerprint ?? stringValue(source.fingerprint),
    contract: contractValue === undefined ? undefined : parseContract(contractValue, `${path}.contract`, owner, source),
  };
}

function parseBranch(value: unknown, path: string): BranchSummary {
  const source = object(value, path);
  const ref = exactRef(pick(source, 'technicalRef', 'ref'));
  return {
    name: text(pick(source, 'name', 'label'), `${path}.name`),
    purpose: text(pick(source, 'purpose', 'summary', 'description'), `${path}.purpose`),
    status: text(pick(source, 'status', 'lifecycle'), `${path}.status`),
    technicalRef: ref.display ?? stringValue(source.id),
  };
}

function parseScenario(value: unknown, path: string): ScenarioRow {
  const source = object(value, path);
  const contractCount = pick(source, 'contractCount', 'applicableContractCount', 'contracts');
  if (typeof contractCount !== 'number' || contractCount < 1) {
    throw invalid(`Expected a positive contract count at ${path}.contractCount.`);
  }
  return {
    name: text(pick(source, 'name', 'label', 'businessName'), `${path}.name`),
    category: text(pick(source, 'category', 'kind'), `${path}.category`),
    source: displayText(pick(source, 'source', 'origin'), `${path}.source`, 'displayName', 'name', 'type'),
    owner: displayText(source.owner, `${path}.owner`, 'name', 'displayName'),
    oracle: displayText(pick(source, 'oracle', 'oracleName'), `${path}.oracle`, 'displayName', 'name', 'summary'),
    contractCount,
    expectedResult: text(pick(source, 'expectedResult', 'expectation'), `${path}.expectedResult`),
    quality: text(pick(source, 'quality', 'qualityState'), `${path}.quality`),
    lifecycle: text(pick(source, 'lifecycle', 'status'), `${path}.lifecycle`),
    technicalRef: exactRef(pick(source, 'technicalRef', 'ref', 'caseRef')).display ?? stringValue(source.id),
  };
}

export function parseCapabilityStudioDemoPack(payload: unknown): CapabilityStudioModel {
  const root = object(payload, 'response');
  const source = object(pick(root, 'capability', 'capabilityAsset', 'asset') ?? root, 'capability');
  const capabilityOwner = displayText(
    pick(source, 'owner', 'ownerName', 'capabilityOwner'),
    'capability.owner',
    'name',
    'displayName',
  );
  const assetsSource = object(pick(source, 'assets', 'assetInventory') ?? source, 'capability.assets');
  const apis = array(pick(assetsSource, 'apis', 'api', 'apiCapabilities'), 'capability.assets.apis')
    .map((entry, index) => parseAsset(entry, 'API', `capability.assets.apis[${index}]`, capabilityOwner));
  const features = array(pick(assetsSource, 'features', 'feature', 'featureCapabilities'), 'capability.assets.features')
    .map((entry, index) => parseAsset(entry, 'FEATURE', `capability.assets.features[${index}]`, capabilityOwner));
  const tools = array(pick(assetsSource, 'tools', 'tool', 'toolCapabilities'), 'capability.assets.tools')
    .map((entry, index) => parseAsset(entry, 'TOOL', `capability.assets.tools[${index}]`, capabilityOwner));
  const scenariosSource = pick(source, 'scenarios', 'scenarioDataset', 'dataset');
  const scenariosValue = scenariosSource && typeof scenariosSource === 'object' && !Array.isArray(scenariosSource)
    ? (scenariosSource as JsonObject).scenarios
    : scenariosSource;
  const scenarios = array(scenariosValue, 'capability.scenarios')
    .map((entry, index) => parseScenario(entry, `capability.scenarios[${index}]`));
  if (apis.length !== 4 || features.length !== 1 || tools.length !== 1 || scenarios.length !== 9) {
    throw invalid(`Expected canonical cardinality 4 APIs, 1 Feature, 1 Tool, 9 scenarios; received ${apis.length}, ${features.length}, ${tools.length}, ${scenarios.length}.`);
  }
  const baseline = pick(source, 'canonicalBaseline', 'baseline');
  const tutorialBranch = pick(source, 'tutorialBranch', 'tutorial');
  const capabilityRef = exactRef(pick(source, 'technicalRef', 'ref', 'assetRef'));
  return {
    capability: {
      name: text(pick(source, 'name', 'label', 'displayName', 'capabilityName', 'packName'), 'capability.name'),
      summary: optionalText(pick(source, 'summary', 'description', 'purpose')) ?? tools[0].summary,
      owner: capabilityOwner,
      readiness: text(pick(source, 'readiness', 'status', 'lifecycle'), 'capability.readiness'),
      technicalRef: capabilityRef.display ?? stringValue(pick(source, 'packId', 'id')),
      fingerprint: capabilityRef.fingerprint ?? stringValue(pick(source, 'packFingerprint', 'fingerprint')),
    },
    assets: { apis, features, tools },
    scenarios,
    baseline: parseBranch(baseline, 'capability.baseline'),
    tutorialBranch: parseBranch(tutorialBranch, 'capability.tutorialBranch'),
    acceptanceStatus: optionalText(pick(root, 'acceptanceStatus', 'acceptance')) ?? 'NO_GO',
    protocolVersion: stringValue(root.protocolVersion)
      ?? (typeof root.schemaVersion === 'number' ? `v${root.schemaVersion}` : stringValue(root.schemaVersion)),
  };
}

const scenarioRefKinds: ScenarioRefKind[] = [
  'API', 'FEATURE', 'TOOL', 'GRAPH_DRAFT', 'CONTRACT', 'DATASET', 'DATA_CASE', 'BEHAVIOR_PROFILE',
  'BINDING_PLAN', 'CAPABILITY_SNAPSHOT', 'SOURCE', 'ORACLE', 'FIXTURE_BUNDLE', 'TEST_SUITE', 'MIRROR_PLAN', 'EVIDENCE',
];

const scenarioCategories: ScenarioCaseCategory[] = ['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'FAULT', 'REGRESSION', 'SECURITY'];
const scenarioDatasetLifecycles: ScenarioDatasetLifecycle[] = ['DRAFT', 'REVIEW_READY', 'ACTIVE', 'STALE', 'RETIRED'];
const scenarioCaseLifecycles: ScenarioCaseLifecycle[] = ['DRAFT', 'ACTIVE', 'STALE', 'RETIRED'];
const scenarioQualityStates: ScenarioCaseQualityState[] = ['DESIGNED_NOT_RUN', 'READY', 'STALE', 'BLOCKED'];
const scenarioBehaviors: ScenarioBehavior[] = ['RETURN', 'ERROR', 'DELAY', 'TIMEOUT', 'REPLAY', 'OBSERVE', 'MUST_NOT_CALL'];

function strictObject(value: unknown, path: string, fields: string[]): JsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalidScenarioDataset(`Expected an object at ${path}.`);
  const source = value as JsonObject;
  const allowed = new Set(fields);
  const unknown = Object.keys(source).find((key) => !allowed.has(key));
  if (unknown) throw invalidScenarioDataset(`Unknown field ${path}.${unknown}.`);
  return source;
}

function strictArray(value: unknown, path: string, minimum = 0): unknown[] {
  if (!Array.isArray(value) || value.length < minimum) throw invalidScenarioDataset(`Expected at least ${minimum} entries at ${path}.`);
  return value;
}

function strictString(value: unknown, path: string, maximum = 256): string {
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) {
    throw invalidScenarioDataset(`Invalid ${path}.`);
  }
  return value;
}

function strictIdentifier(value: unknown, path: string): string {
  const parsed = strictString(value, path);
  if (!/^[A-Za-z0-9][A-Za-z0-9._:/#@-]*$/.test(parsed)) throw invalidScenarioDataset(`Invalid ${path}.`);
  return parsed;
}

function strictEnum<T extends string>(value: unknown, values: readonly T[], path: string): T {
  if (typeof value !== 'string' || !values.includes(value as T)) throw invalidScenarioDataset(`Invalid ${path}.`);
  return value as T;
}

function strictInteger(value: unknown, path: string, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw invalidScenarioDataset(`Invalid ${path}.`);
  }
  return value as number;
}

function parseScenarioScope(value: unknown, path: string): ScenarioScope {
  const source = strictObject(value, path, ['tenantId', 'organizationId', 'projectId', 'environmentId', 'region']);
  return {
    tenantId: strictIdentifier(source.tenantId, `${path}.tenantId`),
    organizationId: strictIdentifier(source.organizationId, `${path}.organizationId`),
    projectId: strictIdentifier(source.projectId, `${path}.projectId`),
    environmentId: strictIdentifier(source.environmentId, `${path}.environmentId`),
    region: strictIdentifier(source.region, `${path}.region`),
  };
}

function parseScenarioExactRef(value: unknown, path: string, expectedKind?: ScenarioRefKind): ScenarioExactRef {
  const source = strictObject(value, path, ['kind', 'id', 'revision', 'fingerprint', 'authority', 'scope']);
  const kind = strictEnum(source.kind, scenarioRefKinds, `${path}.kind`);
  if (expectedKind && kind !== expectedKind) throw invalidScenarioDataset(`Invalid ${path}.kind.`);
  const fingerprint = strictString(source.fingerprint, `${path}.fingerprint`);
  if (!/^sha256:[a-f0-9]{64}$/.test(fingerprint)) throw invalidScenarioDataset(`Invalid ${path}.fingerprint.`);
  return {
    kind,
    id: strictIdentifier(source.id, `${path}.id`),
    revision: strictInteger(source.revision, `${path}.revision`, 1),
    fingerprint,
    authority: strictIdentifier(source.authority, `${path}.authority`),
    scope: parseScenarioScope(source.scope, `${path}.scope`),
  };
}

function parseScenarioOwner(value: unknown, path: string): ScenarioOwner {
  const source = strictObject(value, path, ['id', 'name']);
  return { id: strictIdentifier(source.id, `${path}.id`), name: strictString(source.name, `${path}.name`) };
}

function nullable<T>(value: unknown, parser: (entry: unknown, path: string) => T, path: string): T | null {
  return value === null ? null : parser(value, path);
}

function parseScenarioSource(value: unknown, path: string): ScenarioSource {
  const source = strictObject(value, path, ['displayName', 'type']);
  return { displayName: strictString(source.displayName, `${path}.displayName`), type: strictString(source.type, `${path}.type`, 128) };
}

function parseScenarioOracle(value: unknown, path: string): ScenarioOracle {
  const source = strictObject(value, path, ['displayName', 'summary']);
  return { displayName: strictString(source.displayName, `${path}.displayName`), summary: strictString(source.summary, `${path}.summary`, 4000) };
}

function parseScenarioBehaviorProfile(value: unknown, path: string): ScenarioBehaviorProfile {
  const source = strictObject(value, path, ['behaviorRef', 'dependencyRef', 'purpose', 'behavior', 'summary']);
  return {
    behaviorRef: parseScenarioExactRef(source.behaviorRef, `${path}.behaviorRef`, 'BEHAVIOR_PROFILE'),
    dependencyRef: parseScenarioExactRef(source.dependencyRef, `${path}.dependencyRef`),
    purpose: strictEnum(source.purpose, ['RUNTIME_CONTROL', 'BUSINESS_EXPECTATION'], `${path}.purpose`),
    behavior: strictEnum(source.behavior, scenarioBehaviors, `${path}.behavior`),
    summary: strictString(source.summary, `${path}.summary`, 2000),
  };
}

function parseScenarioCase(value: unknown, path: string): ScenarioCase {
  const source = strictObject(value, path, [
    'caseRef', 'name', 'businessIntent', 'category', 'lifecycle', 'qualityState', 'owner', 'sourceRef', 'source',
    'oracleRef', 'oracle', 'applicableContractRefs', 'behaviorProfiles',
  ]);
  return {
    caseRef: parseScenarioExactRef(source.caseRef, `${path}.caseRef`, 'DATA_CASE'),
    name: strictString(source.name, `${path}.name`),
    businessIntent: strictString(source.businessIntent, `${path}.businessIntent`, 2000),
    category: strictEnum(source.category, scenarioCategories, `${path}.category`),
    lifecycle: strictEnum(source.lifecycle, scenarioCaseLifecycles, `${path}.lifecycle`),
    qualityState: strictEnum(source.qualityState, scenarioQualityStates, `${path}.qualityState`),
    owner: nullable(source.owner, parseScenarioOwner, `${path}.owner`),
    sourceRef: nullable(source.sourceRef, (entry, entryPath) => {
      const ref = parseScenarioExactRef(entry, entryPath);
      if (ref.kind !== 'SOURCE' && ref.kind !== 'FIXTURE_BUNDLE') throw invalidScenarioDataset(`Invalid ${entryPath}.kind.`);
      return ref;
    }, `${path}.sourceRef`),
    source: nullable(source.source, parseScenarioSource, `${path}.source`),
    oracleRef: nullable(source.oracleRef, (entry, entryPath) => {
      const ref = parseScenarioExactRef(entry, entryPath);
      if (ref.kind !== 'ORACLE' && ref.kind !== 'TEST_SUITE') throw invalidScenarioDataset(`Invalid ${entryPath}.kind.`);
      return ref;
    }, `${path}.oracleRef`),
    oracle: nullable(source.oracle, parseScenarioOracle, `${path}.oracle`),
    applicableContractRefs: strictArray(source.applicableContractRefs, `${path}.applicableContractRefs`, 1)
      .map((entry, index) => parseScenarioExactRef(entry, `${path}.applicableContractRefs[${index}]`, 'CONTRACT')),
    behaviorProfiles: strictArray(source.behaviorProfiles, `${path}.behaviorProfiles`)
      .map((entry, index) => parseScenarioBehaviorProfile(entry, `${path}.behaviorProfiles[${index}]`)),
  };
}

function parseScenarioQuality(value: unknown, path: string): ScenarioDatasetQuality {
  const source = strictObject(value, path, [
    'status', 'totalCaseCount', 'activeCaseCount', 'staleCaseCount', 'ownerCoveragePercent', 'sourceCoveragePercent',
    'oracleCoveragePercent', 'contractCoveragePercent', 'behaviorClosurePercent',
  ]);
  return {
    status: strictEnum(source.status, ['READY', 'STALE', 'BLOCKED'], `${path}.status`),
    totalCaseCount: strictInteger(source.totalCaseCount, `${path}.totalCaseCount`),
    activeCaseCount: strictInteger(source.activeCaseCount, `${path}.activeCaseCount`),
    staleCaseCount: strictInteger(source.staleCaseCount, `${path}.staleCaseCount`),
    ownerCoveragePercent: strictInteger(source.ownerCoveragePercent, `${path}.ownerCoveragePercent`, 0, 100),
    sourceCoveragePercent: strictInteger(source.sourceCoveragePercent, `${path}.sourceCoveragePercent`, 0, 100),
    oracleCoveragePercent: strictInteger(source.oracleCoveragePercent, `${path}.oracleCoveragePercent`, 0, 100),
    contractCoveragePercent: strictInteger(source.contractCoveragePercent, `${path}.contractCoveragePercent`, 0, 100),
    behaviorClosurePercent: strictInteger(source.behaviorClosurePercent, `${path}.behaviorClosurePercent`, 0, 100),
  };
}

export function parseScenarioDatasetProjection(payload: unknown): ScenarioDataset {
  const source = strictObject(payload, 'scenarioDataset', [
    'schemaVersion', 'datasetRef', 'name', 'description', 'lifecycle', 'classification', 'owner', 'targetRef', 'contractRefs', 'cases', 'quality',
  ]);
  const cases = strictArray(source.cases, 'scenarioDataset.cases', 1).map((entry, index) => parseScenarioCase(entry, `scenarioDataset.cases[${index}]`));
  const quality = parseScenarioQuality(source.quality, 'scenarioDataset.quality');
  if (quality.totalCaseCount !== cases.length) throw invalidScenarioDataset('scenarioDataset.quality.totalCaseCount does not match cases.');
  const dataset: ScenarioDataset = {
    schemaVersion: source.schemaVersion === 'resource-gateway.capability-studio.scenario-dataset.v1'
      ? source.schemaVersion
      : (() => { throw invalidScenarioDataset('Invalid scenarioDataset.schemaVersion.'); })(),
    datasetRef: parseScenarioExactRef(source.datasetRef, 'scenarioDataset.datasetRef', 'DATASET'),
    name: strictString(source.name, 'scenarioDataset.name'),
    description: strictString(source.description, 'scenarioDataset.description', 4000),
    lifecycle: strictEnum(source.lifecycle, scenarioDatasetLifecycles, 'scenarioDataset.lifecycle'),
    classification: strictEnum(source.classification, ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'], 'scenarioDataset.classification'),
    owner: parseScenarioOwner(source.owner, 'scenarioDataset.owner'),
    targetRef: parseScenarioExactRef(source.targetRef, 'scenarioDataset.targetRef'),
    contractRefs: strictArray(source.contractRefs, 'scenarioDataset.contractRefs', 1)
      .map((entry, index) => parseScenarioExactRef(entry, `scenarioDataset.contractRefs[${index}]`, 'CONTRACT')),
    cases,
    quality,
  };
  validateScenarioDatasetSemantics(dataset);
  return dataset;
}

function validateScenarioDatasetSemantics(dataset: ScenarioDataset): void {
  const expectedScope = scenarioScopeIdentity(dataset.datasetRef.scope);
  const references = [
    dataset.datasetRef,
    dataset.targetRef,
    ...dataset.contractRefs,
    ...dataset.cases.flatMap((scenario) => [
      scenario.caseRef,
      ...(scenario.sourceRef ? [scenario.sourceRef] : []),
      ...(scenario.oracleRef ? [scenario.oracleRef] : []),
      ...scenario.applicableContractRefs,
      ...scenario.behaviorProfiles.flatMap((profile) => [profile.behaviorRef, profile.dependencyRef]),
    ]),
  ];
  if (references.some((ref) => scenarioScopeIdentity(ref.scope) !== expectedScope)) {
    throw invalidScenarioDataset('Scenario Dataset contains a cross-scope reference.');
  }

  const contractIdentities = new Set<string>();
  dataset.contractRefs.forEach((ref) => {
    const identity = scenarioRefIdentity(ref);
    if (contractIdentities.has(identity)) {
      throw invalidScenarioDataset('Scenario Dataset contains a duplicate contract reference.');
    }
    contractIdentities.add(identity);
  });

  const caseIds = new Set<string>();
  const behaviorIdentities = new Set<string>();
  dataset.cases.forEach((scenario) => {
    if (caseIds.has(scenario.caseRef.id)) {
      throw invalidScenarioDataset('Scenario Dataset contains a duplicate case reference.');
    }
    caseIds.add(scenario.caseRef.id);
    scenario.applicableContractRefs.forEach((ref) => {
      if (!contractIdentities.has(scenarioRefIdentity(ref))) {
        throw invalidScenarioDataset('Scenario Dataset contract closure is incomplete.');
      }
    });
    scenario.behaviorProfiles.forEach(({ behaviorRef }) => {
      const identity = scenarioRefIdentity(behaviorRef);
      if (behaviorIdentities.has(identity)) {
        throw invalidScenarioDataset('Scenario Dataset contains a duplicate behavior reference.');
      }
      behaviorIdentities.add(identity);
    });
    if (scenario.lifecycle === 'ACTIVE' && (
      !scenario.owner
      || !scenario.sourceRef
      || !scenario.oracleRef
      || scenario.applicableContractRefs.length === 0
      || !scenario.behaviorProfiles.some((profile) => profile.purpose === 'RUNTIME_CONTROL')
      || scenario.qualityState !== 'READY'
    )) {
      throw invalidScenarioDataset('Scenario Dataset contains an incomplete active case.');
    }
  });

  const total = dataset.cases.length;
  const active = dataset.cases.filter((scenario) => scenario.lifecycle === 'ACTIVE').length;
  const stale = dataset.cases.filter((scenario) => scenario.lifecycle === 'STALE').length;
  const covered = (predicate: (scenario: ScenarioCase) => boolean) => percentage(
    dataset.cases.filter(predicate).length,
    total,
  );
  const qualityMatches = dataset.quality.totalCaseCount === total
    && dataset.quality.activeCaseCount === active
    && dataset.quality.staleCaseCount === stale
    && dataset.quality.ownerCoveragePercent === covered((scenario) => Boolean(scenario.owner))
    && dataset.quality.sourceCoveragePercent === covered((scenario) => Boolean(scenario.sourceRef))
    && dataset.quality.oracleCoveragePercent === covered((scenario) => Boolean(scenario.oracleRef))
    && dataset.quality.contractCoveragePercent === covered((scenario) => scenario.applicableContractRefs.length > 0)
    && dataset.quality.behaviorClosurePercent === covered((scenario) => (
      scenario.behaviorProfiles.some((profile) => profile.purpose === 'RUNTIME_CONTROL')
    ));
  if (!qualityMatches) {
    throw invalidScenarioDataset('Scenario Dataset quality metrics do not match its cases.');
  }
  if (dataset.lifecycle === 'ACTIVE' && (
    dataset.quality.status !== 'READY'
    || stale !== 0
    || dataset.quality.ownerCoveragePercent !== 100
    || dataset.quality.sourceCoveragePercent !== 100
    || dataset.quality.oracleCoveragePercent !== 100
    || dataset.quality.contractCoveragePercent !== 100
  )) {
    throw invalidScenarioDataset('Active Scenario Dataset is not ready.');
  }
}

function scenarioScopeIdentity(scope: ScenarioScope): string {
  return [scope.tenantId, scope.organizationId, scope.projectId, scope.environmentId, scope.region].join('|');
}

function scenarioRefIdentity(ref: ScenarioExactRef): string {
  return [ref.kind, ref.id, ref.revision, ref.fingerprint, ref.authority].join('|');
}

function percentage(covered: number, total: number): number {
  return total === 0 ? 0 : Math.round((covered * 100) / total);
}

const featureNodeStatuses: FeatureRehearsalNodeStatus[] = [
  'SUCCESS', 'FAILED', 'TIMEOUT', 'SKIPPED', 'PARTIAL', 'MOCKED', 'CANCELLED',
  'FALLBACK', 'NOT_INVOKED',
];
const featureEdgeStatuses: FeatureRehearsalEdgeStatus[] = [
  'TRANSFERRED', 'SKIPPED', 'NOT_TRANSFERRED',
];
const featureRunStatuses: FeatureRehearsalRunStatus[] = [
  'PASSED', 'ASSERTION_FAILED', 'EXECUTION_FAILED', 'CONTROL_PLAN_REJECTED',
  'FIXTURE_UNMATCHED', 'FIXTURE_UNUSED', 'CONTROL_PLAN_UNAVAILABLE',
  'EVIDENCE_INCOMPLETE', 'CANCELLED', 'TIMED_OUT',
];

function featureObject(value: unknown, path: string, fields: string[]): JsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalidFeatureRehearsal(`Expected an object at ${path}.`);
  const source = value as JsonObject;
  const allowed = new Set(fields);
  const unknown = Object.keys(source).find((key) => !allowed.has(key));
  if (unknown) throw invalidFeatureRehearsal(`Unknown field ${path}.${unknown}.`);
  return source;
}

function featureArray(value: unknown, path: string, minimum = 0): unknown[] {
  if (!Array.isArray(value) || value.length < minimum) throw invalidFeatureRehearsal(`Expected at least ${minimum} entries at ${path}.`);
  return value;
}

function featureString(value: unknown, path: string, maximum = 4000): string {
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) throw invalidFeatureRehearsal(`Invalid ${path}.`);
  return value;
}

function featureOptionalString(value: unknown, path: string, maximum = 4000): string {
  if (typeof value !== 'string' || value.length > maximum) throw invalidFeatureRehearsal(`Invalid ${path}.`);
  return value;
}

function featureIdentifier(value: unknown, path: string): string {
  const parsed = featureString(value, path, 256);
  if (!/^[A-Za-z0-9][A-Za-z0-9._:/#@><-]*$/.test(parsed)) throw invalidFeatureRehearsal(`Invalid ${path}.`);
  return parsed;
}

function featureCoordinate(value: unknown, path: string): string {
  const parsed = featureString(value, path, 256);
  if (!/^\/[A-Za-z0-9][A-Za-z0-9._:/#@><-]*$/.test(parsed)) throw invalidFeatureRehearsal(`Invalid ${path}.`);
  return parsed;
}

function featureFingerprint(value: unknown, path: string): string {
  const parsed = featureString(value, path, 80);
  if (!/^sha256:[a-f0-9]{64}$/.test(parsed)) throw invalidFeatureRehearsal(`Invalid ${path}.`);
  return parsed;
}

function featureOptionalFingerprint(value: unknown, path: string): string {
  if (value === '') return '';
  return featureFingerprint(value, path);
}

function featureLocalized(value: unknown, path: string): LocalizedValue {
  if (typeof value === 'string' && value.trim()) return value;
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const candidate = value as JsonObject;
    if (['en', 'zh-CN', 'zh'].some((key) => typeof candidate[key] === 'string' && (candidate[key] as string).trim())) return value as LocalizedValue;
  }
  throw invalidFeatureRehearsal(`Invalid ${path}.`);
}

function featureEnum<T extends string>(value: unknown, values: readonly T[], path: string): T {
  if (typeof value !== 'string' || !values.includes(value as T)) throw invalidFeatureRehearsal(`Invalid ${path}.`);
  return value as T;
}

function featureInteger(value: unknown, path: string, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) throw invalidFeatureRehearsal(`Invalid ${path}.`);
  return value as number;
}

function featureBoolean(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') throw invalidFeatureRehearsal(`Invalid ${path}.`);
  return value;
}

function featureNullableJson(value: unknown, path: string): unknown | null {
  if (value === undefined) throw invalidFeatureRehearsal(`Missing ${path}.`);
  return value;
}

function parseFeatureRehearsalAttempt(value: unknown, path: string): FeatureRehearsalAttempt {
  const source = featureObject(value, path, [
    'attempt', 'status', 'fidelity', 'input', 'inputFingerprint', 'output',
    'outputFingerprint', 'errorCode', 'durationMs',
  ]);
  return {
    attempt: featureInteger(source.attempt, `${path}.attempt`, 0, 100),
    status: featureEnum(source.status, featureNodeStatuses, `${path}.status`),
    fidelity: featureOptionalString(source.fidelity, `${path}.fidelity`, 128),
    input: featureNullableJson(source.input, `${path}.input`),
    inputFingerprint: featureOptionalFingerprint(source.inputFingerprint, `${path}.inputFingerprint`),
    output: featureNullableJson(source.output, `${path}.output`),
    outputFingerprint: featureOptionalFingerprint(source.outputFingerprint, `${path}.outputFingerprint`),
    errorCode: featureOptionalString(source.errorCode, `${path}.errorCode`, 128),
    durationMs: featureInteger(source.durationMs, `${path}.durationMs`, 0, 86_400_000),
  };
}

function parseFeatureRehearsalNode(value: unknown, path: string): FeatureRehearsalNode {
  const source = featureObject(value, path, [
    'nodeId', 'operatorRef', 'status', 'fidelity', 'graphPath', 'invocationSite',
    'correlation', 'occurrence', 'graphOccurrence', 'input', 'inputFingerprint',
    'output', 'outputFingerprint', 'errorCode', 'durationMs', 'attempts', 'retryCount',
    'fallbackStatus',
  ]);
  return {
    nodeId: featureIdentifier(source.nodeId, `${path}.nodeId`),
    operatorRef: featureIdentifier(source.operatorRef, `${path}.operatorRef`),
    status: featureEnum(source.status, featureNodeStatuses, `${path}.status`),
    fidelity: featureOptionalString(source.fidelity, `${path}.fidelity`, 128),
    graphPath: featureCoordinate(source.graphPath, `${path}.graphPath`),
    invocationSite: featureCoordinate(source.invocationSite, `${path}.invocationSite`),
    correlation: featureOptionalString(source.correlation, `${path}.correlation`, 256),
    occurrence: featureInteger(source.occurrence, `${path}.occurrence`, 0, 1_000_000),
    graphOccurrence: featureInteger(source.graphOccurrence, `${path}.graphOccurrence`, 0, 1_000_000),
    input: featureNullableJson(source.input, `${path}.input`),
    inputFingerprint: featureOptionalFingerprint(source.inputFingerprint, `${path}.inputFingerprint`),
    output: featureNullableJson(source.output, `${path}.output`),
    outputFingerprint: featureOptionalFingerprint(source.outputFingerprint, `${path}.outputFingerprint`),
    errorCode: featureOptionalString(source.errorCode, `${path}.errorCode`, 128),
    durationMs: featureInteger(source.durationMs, `${path}.durationMs`, 0, 86_400_000),
    attempts: featureArray(source.attempts, `${path}.attempts`).map((entry, index) =>
      parseFeatureRehearsalAttempt(entry, `${path}.attempts[${index}]`)),
    retryCount: featureInteger(source.retryCount, `${path}.retryCount`, 0, 100),
    fallbackStatus: source.fallbackStatus === null
      ? null : featureOptionalString(source.fallbackStatus, `${path}.fallbackStatus`, 128),
  };
}

function parseFeatureRehearsalEdge(value: unknown, path: string): FeatureRehearsalEdge {
  const source = featureObject(value, path, [
    'edgeId', 'status', 'graphPath', 'correlation', 'graphOccurrence',
    'fromInvocationSite', 'toInvocationSite', 'value', 'valueFingerprint',
  ]);
  return {
    edgeId: featureIdentifier(source.edgeId, `${path}.edgeId`),
    status: featureEnum(source.status, featureEdgeStatuses, `${path}.status`),
    graphPath: featureCoordinate(source.graphPath, `${path}.graphPath`),
    correlation: featureOptionalString(source.correlation, `${path}.correlation`, 256),
    graphOccurrence: featureInteger(source.graphOccurrence, `${path}.graphOccurrence`, 0, 1_000_000),
    fromInvocationSite: featureCoordinate(source.fromInvocationSite, `${path}.fromInvocationSite`),
    toInvocationSite: featureCoordinate(source.toInvocationSite, `${path}.toInvocationSite`),
    value: featureNullableJson(source.value, `${path}.value`),
    valueFingerprint: featureOptionalFingerprint(source.valueFingerprint, `${path}.valueFingerprint`),
  };
}

function parseFeatureFirstDifference(value: unknown, path: string): FeatureRehearsalFirstDifference | null {
  if (value === null) return null;
  const source = featureObject(value, path, [
    'source', 'locator', 'scope', 'path', 'expected', 'expectedFingerprint',
    'actual', 'actualFingerprint',
  ]);
  return {
    source: featureIdentifier(source.source, `${path}.source`),
    locator: featureCoordinate(source.locator, `${path}.locator`),
    scope: featureOptionalString(source.scope, `${path}.scope`, 256),
    path: featureOptionalString(source.path, `${path}.path`, 512),
    expected: featureNullableJson(source.expected, `${path}.expected`),
    expectedFingerprint: featureOptionalFingerprint(source.expectedFingerprint, `${path}.expectedFingerprint`),
    actual: featureNullableJson(source.actual, `${path}.actual`),
    actualFingerprint: featureOptionalFingerprint(source.actualFingerprint, `${path}.actualFingerprint`),
  };
}

export function parseFeatureRehearsalProjection(payload: unknown): FeatureRehearsalProjection {
  const root = featureObject(payload, 'featureRehearsal', ['schemaVersion', 'scenario', 'graph', 'run', 'dataLens']);
  if (root.schemaVersion !== 'resource-gateway.capability-studio.feature-rehearsal.v1') throw invalidFeatureRehearsal('Invalid schemaVersion.');
  const scenario = featureObject(root.scenario, 'featureRehearsal.scenario', ['id', 'name', 'expectedResult']);
  const graph = featureObject(root.graph, 'featureRehearsal.graph', ['id', 'fingerprint']);
  const run = featureObject(root.run, 'featureRehearsal.run', ['runId', 'status', 'semanticFingerprint', 'realExternalCallCount', 'bindingMode']);
  const lens = featureObject(root.dataLens, 'featureRehearsal.dataLens', ['schemaVersion', 'runId', 'runStatus', 'permissionMode', 'nodes', 'edges', 'firstDifference', 'truncation', 'fingerprint']);
  const nodes = featureArray(lens.nodes, 'featureRehearsal.dataLens.nodes', 1).map((entry, index) => parseFeatureRehearsalNode(entry, `featureRehearsal.dataLens.nodes[${index}]`));
  const edges = featureArray(lens.edges, 'featureRehearsal.dataLens.edges').map((entry, index) => parseFeatureRehearsalEdge(entry, `featureRehearsal.dataLens.edges[${index}]`));
  const nodeIds = new Set<string>();
  nodes.forEach((node) => { if (nodeIds.has(node.nodeId)) throw invalidFeatureRehearsal(`Duplicate nodeId ${node.nodeId}.`); nodeIds.add(node.nodeId); });
  const invocationSites = new Set(nodes.map((node) => node.invocationSite));
  const edgeIds = new Set<string>();
  edges.forEach((edge) => {
    if (edgeIds.has(edge.edgeId)) throw invalidFeatureRehearsal(`Duplicate edgeId ${edge.edgeId}.`);
    if (!invocationSites.has(edge.fromInvocationSite) || !invocationSites.has(edge.toInvocationSite)) throw invalidFeatureRehearsal(`Edge ${edge.edgeId} references an unknown invocation site.`);
    edgeIds.add(edge.edgeId);
  });
  const truncation = featureObject(lens.truncation, 'featureRehearsal.dataLens.truncation', [
    'nodesTruncated', 'omittedNodes', 'edgesTruncated', 'omittedEdges',
    'attemptsTruncated', 'omittedAttempts',
  ]);
  const parsed: FeatureRehearsalProjection = {
    schemaVersion: root.schemaVersion,
    scenario: { id: featureIdentifier(scenario.id, 'featureRehearsal.scenario.id'), name: featureLocalized(scenario.name, 'featureRehearsal.scenario.name'), expectedResult: featureLocalized(scenario.expectedResult, 'featureRehearsal.scenario.expectedResult') },
    graph: { id: featureIdentifier(graph.id, 'featureRehearsal.graph.id'), fingerprint: featureFingerprint(graph.fingerprint, 'featureRehearsal.graph.fingerprint') },
    run: { runId: featureIdentifier(run.runId, 'featureRehearsal.run.runId'), status: featureEnum(run.status, featureRunStatuses, 'featureRehearsal.run.status'), semanticFingerprint: featureFingerprint(run.semanticFingerprint, 'featureRehearsal.run.semanticFingerprint'), realExternalCallCount: featureInteger(run.realExternalCallCount, 'featureRehearsal.run.realExternalCallCount', 0, 0), bindingMode: featureEnum(run.bindingMode, ['FIXTURE_CONTROLLED_NON_PRODUCTION'], 'featureRehearsal.run.bindingMode') },
    dataLens: {
      schemaVersion: lens.schemaVersion === 'resource-gateway.capability-studio.data-lens.v1' ? lens.schemaVersion : (() => { throw invalidFeatureRehearsal('Invalid dataLens.schemaVersion.'); })(),
      runId: featureIdentifier(lens.runId, 'featureRehearsal.dataLens.runId'),
      runStatus: featureEnum(lens.runStatus, featureRunStatuses, 'featureRehearsal.dataLens.runStatus'),
      permissionMode: featureEnum(lens.permissionMode, ['STRUCTURE_ONLY', 'PAYLOAD_VISIBLE'], 'featureRehearsal.dataLens.permissionMode'),
      nodes,
      edges,
      firstDifference: parseFeatureFirstDifference(lens.firstDifference, 'featureRehearsal.dataLens.firstDifference'),
      truncation: {
        nodesTruncated: featureBoolean(truncation.nodesTruncated, 'featureRehearsal.dataLens.truncation.nodesTruncated'),
        omittedNodes: featureInteger(truncation.omittedNodes, 'featureRehearsal.dataLens.truncation.omittedNodes'),
        edgesTruncated: featureBoolean(truncation.edgesTruncated, 'featureRehearsal.dataLens.truncation.edgesTruncated'),
        omittedEdges: featureInteger(truncation.omittedEdges, 'featureRehearsal.dataLens.truncation.omittedEdges'),
        attemptsTruncated: featureBoolean(truncation.attemptsTruncated, 'featureRehearsal.dataLens.truncation.attemptsTruncated'),
        omittedAttempts: featureInteger(truncation.omittedAttempts, 'featureRehearsal.dataLens.truncation.omittedAttempts'),
      },
      fingerprint: featureFingerprint(lens.fingerprint, 'featureRehearsal.dataLens.fingerprint'),
    },
  };
  if (parsed.run.runId !== parsed.dataLens.runId || parsed.run.status !== parsed.dataLens.runStatus) throw invalidFeatureRehearsal('Run and Data Lens identity do not match.');
  if (parsed.dataLens.permissionMode === 'STRUCTURE_ONLY') {
    const nodePayloadVisible = nodes.some((node) => node.input !== null || node.output !== null
      || node.attempts.some((attempt) => attempt.input !== null || attempt.output !== null));
    const edgePayloadVisible = edges.some((edge) => edge.value !== null);
    const differencePayloadVisible = parsed.dataLens.firstDifference !== null
      && (parsed.dataLens.firstDifference.expected !== null || parsed.dataLens.firstDifference.actual !== null);
    if (nodePayloadVisible || edgePayloadVisible || differencePayloadVisible) throw invalidFeatureRehearsal('STRUCTURE_ONLY cannot contain payload values.');
  }
  return parsed;
}

const governedBaselineRoundStatuses: GovernedBaselineRoundStatus[] = [
  'PASSED', 'FAILED_CLOSED', 'COMPLETED_WITH_FAILURES', 'PARTIAL', 'EVIDENCE_INCOMPLETE',
];
const governedBaselineCaseStatuses: GovernedBaselineCaseStatus[] = [
  'PASSED', 'FAILED', 'FAILED_CLOSED', 'ASSERTION_FAILED', 'EXECUTION_FAILED',
  'CONTROL_PLAN_REJECTED', 'FIXTURE_UNMATCHED', 'FIXTURE_UNUSED',
  'CONTROL_PLAN_UNAVAILABLE', 'EVIDENCE_INCOMPLETE', 'CANCELLED', 'TIMED_OUT',
  'NOT_SCHEDULED',
];
const governedBaselineRequiredLimitations = [
  'IMMUTABLE_RELEASE_CANDIDATE_NOT_BOUND',
  'RUNTIME_ENVIRONMENT_NOT_ATTESTED',
  'CERTIFIABLE_EVIDENCE_NOT_ESTABLISHED',
  'DEPLOYMENT_EGRESS_NOT_OBSERVED',
  'OWNER_SIGNOFF_NOT_PRESENT',
] as const;
const governedBaselineCanonicalCaseIds = [
  'case-city-policy-missing',
  'case-compensation-history-empty',
  'case-compensation-history-timeout',
  'case-driver-responsible',
  'case-duplicate-cancellation',
  'case-forbidden-write-effect',
  'case-policy-revision-regression',
  'case-rider-not-responsible',
  'case-standard-cancellation-fee',
] as const;

function governedObject(value: unknown, path: string, fields: string[]): JsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw invalidGovernedBaseline(`Expected an object at ${path}.`);
  }
  const source = value as JsonObject;
  const allowed = new Set(fields);
  const unknown = Object.keys(source).find((key) => !allowed.has(key));
  if (unknown) throw invalidGovernedBaseline(`Unknown field ${path}.${unknown}.`);
  return source;
}

function governedArray(value: unknown, path: string, expectedLength?: number): unknown[] {
  if (!Array.isArray(value) || (expectedLength !== undefined && value.length !== expectedLength)) {
    throw invalidGovernedBaseline(`Invalid ${path}.`);
  }
  return value;
}

function governedString(value: unknown, path: string): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw invalidGovernedBaseline(`Invalid ${path}.`);
  }
  return value;
}

function governedFingerprint(value: unknown, path: string): string {
  const parsed = governedString(value, path);
  if (!/^sha256:[0-9a-f]{64}$/.test(parsed)) throw invalidGovernedBaseline(`Invalid ${path}.`);
  return parsed;
}

function governedInteger(value: unknown, path: string, minimum = 0): number {
  if (!Number.isInteger(value) || (value as number) < minimum) {
    throw invalidGovernedBaseline(`Invalid ${path}.`);
  }
  return value as number;
}

function governedEnum<T extends string>(value: unknown, values: readonly T[], path: string): T {
  if (typeof value !== 'string' || !values.includes(value as T)) {
    throw invalidGovernedBaseline(`Invalid ${path}.`);
  }
  return value as T;
}

function governedStringArray(value: unknown, path: string): string[] {
  return governedArray(value, path).map((entry, index) => governedString(entry, `${path}[${index}]`));
}

function parseGovernedBaselineSuiteRef(value: unknown, path: string): GovernedBaselineSuiteRef {
  const source = governedObject(value, path, ['kind', 'id', 'revision', 'fingerprint']);
  return {
    kind: governedEnum(source.kind, ['TEST_SUITE'], `${path}.kind`),
    id: governedString(source.id, `${path}.id`),
    revision: governedInteger(source.revision, `${path}.revision`, 1),
    fingerprint: governedFingerprint(source.fingerprint, `${path}.fingerprint`),
  };
}

function parseGovernedBaselineRound(value: unknown, path: string): GovernedBaselineRound {
  const source = governedObject(value, path, [
    'round', 'suiteRunId', 'evidenceFingerprint', 'status', 'childRunCount',
  ]);
  return {
    round: governedInteger(source.round, `${path}.round`, 1),
    suiteRunId: governedString(source.suiteRunId, `${path}.suiteRunId`),
    evidenceFingerprint: governedFingerprint(source.evidenceFingerprint, `${path}.evidenceFingerprint`),
    status: governedEnum(source.status, governedBaselineRoundStatuses, `${path}.status`),
    childRunCount: governedInteger(source.childRunCount, `${path}.childRunCount`),
  };
}

function parseGovernedBaselineCaseRound(value: unknown, path: string): GovernedBaselineCaseRound {
  const source = governedObject(value, path, [
    'round', 'runId', 'status', 'fixtureBundleId', 'fixtureRevision', 'fixtureFingerprint',
    'evidenceFingerprint', 'semanticResultFingerprint', 'assertionsEvaluated',
    'assertionsPassed', 'fixtureControlsEvaluated', 'fixtureControlsSatisfied',
  ]);
  const assertionsEvaluated = governedInteger(source.assertionsEvaluated, `${path}.assertionsEvaluated`);
  const assertionsPassed = governedInteger(source.assertionsPassed, `${path}.assertionsPassed`);
  const fixtureControlsEvaluated = governedInteger(source.fixtureControlsEvaluated, `${path}.fixtureControlsEvaluated`, 1);
  const fixtureControlsSatisfied = governedInteger(source.fixtureControlsSatisfied, `${path}.fixtureControlsSatisfied`, 1);
  if (assertionsEvaluated !== 1 || assertionsPassed !== 1) {
    throw invalidGovernedBaseline(`Invalid ${path} business assertion counts.`);
  }
  if (fixtureControlsEvaluated !== fixtureControlsSatisfied) {
    throw invalidGovernedBaseline(`Invalid ${path} fixture control counts.`);
  }
  return {
    round: governedInteger(source.round, `${path}.round`, 1),
    runId: governedString(source.runId, `${path}.runId`),
    status: governedEnum(source.status, governedBaselineCaseStatuses, `${path}.status`),
    fixtureBundleId: governedString(source.fixtureBundleId, `${path}.fixtureBundleId`),
    fixtureRevision: governedInteger(source.fixtureRevision, `${path}.fixtureRevision`, 1),
    fixtureFingerprint: governedFingerprint(source.fixtureFingerprint, `${path}.fixtureFingerprint`),
    evidenceFingerprint: governedFingerprint(source.evidenceFingerprint, `${path}.evidenceFingerprint`),
    semanticResultFingerprint: governedFingerprint(source.semanticResultFingerprint, `${path}.semanticResultFingerprint`),
    assertionsEvaluated: 1,
    assertionsPassed: 1,
    fixtureControlsEvaluated,
    fixtureControlsSatisfied,
  };
}

function parseGovernedBaselineCase(value: unknown, path: string): GovernedBaselineCase {
  const source = governedObject(value, path, [
    'caseId', 'oracleId', 'oracleStatus', 'semanticResultFingerprint',
    'assertionsEvaluated', 'assertionsPassed', 'fixtureControlsEvaluated',
    'fixtureControlsSatisfied', 'proofs', 'rounds',
  ]);
  const caseId = governedString(source.caseId, `${path}.caseId`);
  const oracleId = governedString(source.oracleId, `${path}.oracleId`);
  const oracleStatus = governedEnum(source.oracleStatus, ['PASS'], `${path}.oracleStatus`);
  const semanticResultFingerprint = governedFingerprint(
    source.semanticResultFingerprint, `${path}.semanticResultFingerprint`,
  );
  const assertionsEvaluated = governedInteger(source.assertionsEvaluated, `${path}.assertionsEvaluated`);
  const assertionsPassed = governedInteger(source.assertionsPassed, `${path}.assertionsPassed`);
  const fixtureControlsEvaluated = governedInteger(source.fixtureControlsEvaluated, `${path}.fixtureControlsEvaluated`, 1);
  const fixtureControlsSatisfied = governedInteger(source.fixtureControlsSatisfied, `${path}.fixtureControlsSatisfied`, 1);
  const proofs = governedStringArray(source.proofs, `${path}.proofs`);
  const rounds = governedArray(source.rounds, `${path}.rounds`, 3)
    .map((entry, index) => parseGovernedBaselineCaseRound(entry, `${path}.rounds[${index}]`));
  if (oracleId !== `oracle-${caseId.replace(/^case-/, '')}` || assertionsEvaluated !== 3
    || assertionsPassed !== 3 || fixtureControlsEvaluated !== fixtureControlsSatisfied
    || rounds.some((round) => round.semanticResultFingerprint !== semanticResultFingerprint)
    || rounds.reduce((total, round) => total + round.assertionsEvaluated, 0) !== assertionsEvaluated
    || rounds.reduce((total, round) => total + round.assertionsPassed, 0) !== assertionsPassed
    || rounds.reduce((total, round) => total + round.fixtureControlsEvaluated, 0) !== fixtureControlsEvaluated
    || rounds.reduce((total, round) => total + round.fixtureControlsSatisfied, 0) !== fixtureControlsSatisfied) {
    throw invalidGovernedBaseline(`Invalid ${path} business Oracle closure.`);
  }
  const expectedProofs = governedBaselineProofs(caseId);
  if (proofs.length !== expectedProofs.length
    || proofs.some((proof, index) => proof !== expectedProofs[index])) {
    throw invalidGovernedBaseline(`Invalid ${path} business Oracle proofs.`);
  }
  return {
    caseId,
    oracleId,
    oracleStatus,
    semanticResultFingerprint,
    assertionsEvaluated: 3,
    assertionsPassed: 3,
    fixtureControlsEvaluated,
    fixtureControlsSatisfied,
    proofs,
    rounds,
  };
}

function governedBaselineProofs(caseId: string): string[] {
  const common = [
    'BUSINESS_ASSERTION_PASSED',
    'SEMANTIC_RESULT_STABLE',
    'FIXTURE_CONTROL_SATISFIED',
    'ZERO_REAL_EXTERNAL_CALLS',
  ];
  if (caseId === 'case-compensation-history-timeout') return [...common, 'TIMEOUT_FALLBACK_CONFIRMED'];
  if (caseId === 'case-duplicate-cancellation') return [...common, 'DUPLICATE_IDEMPOTENCY_CONFIRMED'];
  if (caseId === 'case-forbidden-write-effect') return [...common, 'FORBIDDEN_WRITE_EFFECT_ABSENT'];
  return common;
}

function requireGovernedRoundSequence(rounds: GovernedBaselineRound[], path: string): void {
  const numbers = rounds.map((round) => round.round);
  if (numbers.join(',') !== '1,2,3') throw invalidGovernedBaseline(`Invalid ${path} round sequence.`);
  if (new Set(rounds.map((round) => round.suiteRunId)).size !== 3) {
    throw invalidGovernedBaseline(`Duplicate ${path} suiteRunId.`);
  }
}

function requireGovernedCaseRoundSequence(rounds: GovernedBaselineCaseRound[], path: string): void {
  const numbers = rounds.map((round) => round.round);
  if (numbers.join(',') !== '1,2,3') throw invalidGovernedBaseline(`Invalid ${path} round sequence.`);
}

export function parseGovernedBaselineProjection(payload: unknown): GovernedBaselineProjection {
  const root = governedObject(payload, 'governedBaseline', [
    'schemaVersion', 'evidenceKind', 'baselineId', 'status', 'verificationScope',
    'releaseGateStatus', 'evidenceClass', 'caseCount', 'roundCount', 'suiteRunCount', 'childRunCount',
    'oraclePassCount', 'businessCheckCount', 'businessCheckPassCount',
    'realExternalCallCount', 'compilationFingerprint', 'sourceMapFingerprint',
    'provenanceFingerprint', 'publication', 'rounds', 'cases', 'limitations', 'diagnostics',
  ]);
  const schemaVersion = governedEnum(
    root.schemaVersion,
    ['resource-gateway.capability-studio.governed-baseline.v2'],
    'governedBaseline.schemaVersion',
  );
  const evidenceKind = governedEnum(root.evidenceKind, ['DEVELOPMENT_TEST_OWNED'], 'governedBaseline.evidenceKind');
  const status = governedEnum(root.status, ['PASSED', 'FAILED_CLOSED'], 'governedBaseline.status');
  const verificationScope = governedEnum(root.verificationScope, ['GOVERNED_SUITE_ASSERTIONS_AND_BUSINESS_ORACLES'], 'governedBaseline.verificationScope');
  const releaseGateStatus = governedEnum(root.releaseGateStatus, ['NO_GO'], 'governedBaseline.releaseGateStatus');
  const baselineId = governedString(root.baselineId, 'governedBaseline.baselineId');
  const caseCount = governedInteger(root.caseCount, 'governedBaseline.caseCount');
  const roundCount = governedInteger(root.roundCount, 'governedBaseline.roundCount');
  const suiteRunCount = governedInteger(root.suiteRunCount, 'governedBaseline.suiteRunCount');
  const childRunCount = governedInteger(root.childRunCount, 'governedBaseline.childRunCount');
  const oraclePassCount = governedInteger(root.oraclePassCount, 'governedBaseline.oraclePassCount');
  const businessCheckCount = governedInteger(root.businessCheckCount, 'governedBaseline.businessCheckCount');
  const businessCheckPassCount = governedInteger(root.businessCheckPassCount, 'governedBaseline.businessCheckPassCount');
  const limitations = governedStringArray(root.limitations, 'governedBaseline.limitations');
  const diagnostics = governedStringArray(root.diagnostics, 'governedBaseline.diagnostics');

  if (caseCount !== 9) throw invalidGovernedBaseline('Case count is not nine.');
  if (roundCount !== 3) throw invalidGovernedBaseline('Round count is not three.');
  if (limitations.length !== governedBaselineRequiredLimitations.length
    || governedBaselineRequiredLimitations.some((limitation, index) => limitations[index] !== limitation)) {
    throw invalidGovernedBaseline('Required governed baseline limitation is missing.');
  }
  if (status === 'FAILED_CLOSED') {
    const rounds = governedArray(root.rounds, 'governedBaseline.rounds', 0);
    const cases = governedArray(root.cases, 'governedBaseline.cases', 0);
    if (suiteRunCount !== 0 || childRunCount !== 0 || oraclePassCount !== 0
      || businessCheckCount !== 0 || businessCheckPassCount !== 0
      || root.evidenceClass !== null
      || root.realExternalCallCount !== null
      || root.compilationFingerprint !== null
      || root.sourceMapFingerprint !== null
      || root.provenanceFingerprint !== null
      || root.publication !== null
      || diagnostics.length === 0) {
      throw invalidGovernedBaseline('A failed-closed baseline contains fabricated or incomplete failure evidence.');
    }
    return {
      schemaVersion,
      evidenceKind,
      baselineId,
      status,
      verificationScope,
      releaseGateStatus,
      caseCount: 9,
      roundCount: 3,
      suiteRunCount: 0,
      childRunCount: 0,
      evidenceClass: null,
      oraclePassCount: 0,
      businessCheckCount: 0,
      businessCheckPassCount: 0,
      realExternalCallCount: null,
      compilationFingerprint: null,
      sourceMapFingerprint: null,
      provenanceFingerprint: null,
      publication: null,
      rounds: rounds as [],
      cases: cases as [],
      limitations,
      diagnostics,
    };
  }

  const realExternalCallCount = governedInteger(root.realExternalCallCount, 'governedBaseline.realExternalCallCount');
  const evidenceClass = governedEnum(root.evidenceClass, ['EXPLORATORY'], 'governedBaseline.evidenceClass');
  const compilationFingerprint = governedFingerprint(root.compilationFingerprint, 'governedBaseline.compilationFingerprint');
  const sourceMapFingerprint = governedFingerprint(root.sourceMapFingerprint, 'governedBaseline.sourceMapFingerprint');
  const provenanceFingerprint = governedFingerprint(root.provenanceFingerprint, 'governedBaseline.provenanceFingerprint');
  const publication = governedObject(root.publication, 'governedBaseline.publication', [
    'receiptFingerprint', 'suiteRef', 'fixtureCount',
  ]);
  const receiptFingerprint = governedFingerprint(publication.receiptFingerprint, 'governedBaseline.publication.receiptFingerprint');
  const suiteRef = parseGovernedBaselineSuiteRef(publication.suiteRef, 'governedBaseline.publication.suiteRef');
  const fixtureCount = governedInteger(publication.fixtureCount, 'governedBaseline.publication.fixtureCount');
  const rounds = governedArray(root.rounds, 'governedBaseline.rounds', 3)
    .map((entry, index) => parseGovernedBaselineRound(entry, `governedBaseline.rounds[${index}]`));
  const cases = governedArray(root.cases, 'governedBaseline.cases', 9)
    .map((entry, index) => parseGovernedBaselineCase(entry, `governedBaseline.cases[${index}]`));

  if (cases.length !== caseCount) throw invalidGovernedBaseline('Case count is not nine.');
  if (rounds.length !== roundCount) throw invalidGovernedBaseline('Round count is not three.');
  if (suiteRunCount !== rounds.length) throw invalidGovernedBaseline('Suite run count does not match rounds.');
  if (childRunCount !== 27 || rounds.reduce((total, round) => total + round.childRunCount, 0) !== childRunCount) {
    throw invalidGovernedBaseline('Child run count does not close over rounds.');
  }
  if (oraclePassCount !== 9 || businessCheckCount !== 27 || businessCheckPassCount !== 27
    || cases.filter((entry) => entry.oracleStatus === 'PASS').length !== oraclePassCount
    || cases.reduce((total, entry) => total + entry.assertionsEvaluated, 0) !== businessCheckCount
    || cases.reduce((total, entry) => total + entry.assertionsPassed, 0) !== businessCheckPassCount) {
    throw invalidGovernedBaseline('Business Oracle counts do not close over cases.');
  }
  if (fixtureCount !== 9) throw invalidGovernedBaseline('Fixture count is not nine.');
  requireGovernedRoundSequence(rounds, 'governedBaseline.rounds');
  const caseIds = new Set(cases.map((entry) => entry.caseId));
  if (caseIds.size !== cases.length) throw invalidGovernedBaseline('Duplicate governed baseline caseId.');
  if (cases.some((entry, index) => entry.caseId !== governedBaselineCanonicalCaseIds[index])) {
    throw invalidGovernedBaseline('Governed baseline canonical case order does not match.');
  }
  const runIds = new Set<string>();
  cases.forEach((entry, caseIndex) => {
    requireGovernedCaseRoundSequence(entry.rounds, `governedBaseline.cases[${caseIndex}].rounds`);
    entry.rounds.forEach((round) => {
      if (runIds.has(round.runId)) throw invalidGovernedBaseline(`Duplicate governed baseline runId ${round.runId}.`);
      runIds.add(round.runId);
    });
  });
  if (runIds.size !== childRunCount) throw invalidGovernedBaseline('Child run identity count does not close.');
  if (diagnostics.length !== 0 || realExternalCallCount !== 0
    || rounds.some((round) => round.status !== 'PASSED')
    || cases.some((entry) => entry.rounds.some((round) => round.status !== 'PASSED'))) {
    throw invalidGovernedBaseline('A passed governed baseline has incomplete or failed evidence.');
  }

  return {
    schemaVersion,
    evidenceKind,
    baselineId,
    status: 'PASSED',
    verificationScope,
    releaseGateStatus,
    caseCount: 9,
    roundCount: 3,
    suiteRunCount: 3,
    childRunCount: 27,
    evidenceClass,
    oraclePassCount: 9,
    businessCheckCount: 27,
    businessCheckPassCount: 27,
    realExternalCallCount,
    compilationFingerprint,
    sourceMapFingerprint,
    provenanceFingerprint,
    publication: { receiptFingerprint, suiteRef, fixtureCount: 9 },
    rounds: rounds as GovernedBaselineSuccessProjection['rounds'],
    cases: cases as GovernedBaselineSuccessProjection['cases'],
    limitations,
    diagnostics,
  };
}
