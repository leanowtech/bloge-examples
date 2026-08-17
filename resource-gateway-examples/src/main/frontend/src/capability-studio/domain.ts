export type LocalizedValue = string | {
  en?: string;
  'zh-CN'?: string;
  zh?: string;
};

export type CapabilityAssetKind = 'API' | 'FEATURE' | 'TOOL';

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
  const source = strictObject(value, path, ['behaviorRef', 'dependencyRef', 'behavior', 'summary']);
  return {
    behaviorRef: parseScenarioExactRef(source.behaviorRef, `${path}.behaviorRef`, 'BEHAVIOR_PROFILE'),
    dependencyRef: parseScenarioExactRef(source.dependencyRef, `${path}.dependencyRef`),
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
    && dataset.quality.behaviorClosurePercent === covered((scenario) => scenario.behaviorProfiles.length > 0);
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
