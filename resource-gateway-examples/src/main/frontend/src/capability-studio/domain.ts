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
