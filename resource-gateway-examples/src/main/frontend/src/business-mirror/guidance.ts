import type { BusinessMirrorGap, BusinessMirrorTaskId } from './domain';

export type GuidanceMessageId = string;

export type RemediationActionKind =
  | 'FOCUS_FIELD'
  | 'OPEN_PICKER'
  | 'OPEN_AUTHOR'
  | 'OPEN_REHEARSAL'
  | 'OPEN_CORRECTNESS'
  | 'OPEN_GOVERNANCE';

export type RemediationFallback =
  | 'ADVANCED_EXACT_INPUT'
  | 'REQUEST_ACCESS'
  | 'SHOW_UNAVAILABLE';

export interface StepInputContract {
  id: string;
  fieldPath: string;
  required: boolean;
  capability: string;
}

export interface StepPrimaryAction {
  actionKind: RemediationActionKind;
  anchor: string;
  fieldPath: string;
  capability: string;
  fallback: RemediationFallback;
}

export interface StepContract {
  id: BusinessMirrorTaskId;
  question: GuidanceMessageId;
  why: GuidanceMessageId;
  inputs: readonly StepInputContract[];
  primaryAction: StepPrimaryAction;
  completionCodes: readonly string[];
  nextStep: BusinessMirrorTaskId | null;
}

export interface RemediationDescriptor {
  gapCode: string;
  category: string;
  taskId: BusinessMirrorTaskId;
  anchor: string;
  surfaceAnchor: string;
  actionKind: RemediationActionKind;
  fieldPath: string;
  capability: string;
  capabilityRequired: string;
  titleMessageId: GuidanceMessageId;
  impactMessageId: GuidanceMessageId;
  instructionMessageId: GuidanceMessageId;
  completionPredicate: string;
  fallback: RemediationFallback;
}

export const BUSINESS_MIRROR_TASK_ORDER = [
  'problem',
  'boundary',
  'capabilities',
  'scenarios',
  'rehearsal',
  'evidence',
  'calibrate',
] as const satisfies readonly BusinessMirrorTaskId[];

export const BUSINESS_MIRROR_STEP_CONTRACTS = {
  problem: {
    id: 'problem',
    question: 'businessMirror.guidance.problem.question',
    why: 'businessMirror.guidance.problem.why',
    inputs: [
      input('domain', '/businessDefinition/domainId', 'businessDomainDirectory'),
      input('taxonomy', '/businessDefinition/problemTaxonomyRef', 'problemTaxonomyCatalog'),
      input('problemCode', '/businessDefinition/problemCode', 'businessMirrorAuthoring'),
      input('businessGoal', '/businessDefinition/businessGoal', 'businessMirrorAuthoring'),
      input('expectedOutcome', '/businessDefinition/expectedOutcome', 'businessMirrorAuthoring'),
      input('accountableOwner', '/businessDefinition/accountableOwner', 'ownerDirectoryApi'),
    ],
    primaryAction: stepAction(
      'OPEN_PICKER',
      'business-mirror.problem.domain',
      '/businessDefinition/domainId',
      'businessDomainDirectory',
      'REQUEST_ACCESS',
    ),
    completionCodes: ['BUSINESS_DEFINITION_COMPLETE'],
    nextStep: 'boundary',
  },
  boundary: {
    id: 'boundary',
    question: 'businessMirror.guidance.boundary.question',
    why: 'businessMirror.guidance.boundary.why',
    inputs: [
      input('contract', '/packageContractRef', 'contractCatalog'),
      input('state', '/stateModelRefs', 'stateModelCatalog'),
      input('effect', '/effectModelRefs', 'effectModelCatalog'),
      input('ownerConfirmation', '/provenance/approvedBy', 'ownerDirectoryApi'),
    ],
    primaryAction: stepAction(
      'OPEN_PICKER',
      'business-mirror.boundary.contract',
      '/packageContractRef',
      'contractCatalog',
      'ADVANCED_EXACT_INPUT',
    ),
    completionCodes: ['BOUNDARY_CONTRACT_COMPLETE'],
    nextStep: 'capabilities',
  },
  capabilities: {
    id: 'capabilities',
    question: 'businessMirror.guidance.capabilities.question',
    why: 'businessMirror.guidance.capabilities.why',
    inputs: [
      input('executable', '/capabilityRefs', 'capabilityCatalog'),
      input('solution', '/solutionRefs', 'solutionCatalog'),
      input('carrier', '/carrierRefs', 'carrierCatalog'),
      input('channel', '/channelRefs', 'channelCatalog'),
    ],
    primaryAction: stepAction(
      'OPEN_AUTHOR',
      'business-mirror.capabilities.executable',
      '/capabilityRefs',
      'capabilityCatalog',
      'SHOW_UNAVAILABLE',
    ),
    completionCodes: ['CAPABILITY_CLOSURE_COMPLETE'],
    nextStep: 'scenarios',
  },
  scenarios: {
    id: 'scenarios',
    question: 'businessMirror.guidance.scenarios.question',
    why: 'businessMirror.guidance.scenarios.why',
    inputs: [
      input('scenarioInventory', '/scenarioInventoryRef', 'scenarioInventoryCatalog'),
      input('scenarioPack', '/scenarioPackRefs', 'scenarioPackCatalog'),
      input('discoveredSuiteDisposition', '/scenarioInventoryRef', 'correctnessScenarioGovernance'),
    ],
    primaryAction: stepAction(
      'OPEN_CORRECTNESS',
      'business-mirror.scenarios.inventory',
      '/scenarioInventoryRef',
      'scenarioInventoryCatalog',
      'SHOW_UNAVAILABLE',
    ),
    completionCodes: ['SCENARIO_DENOMINATOR_FROZEN'],
    nextStep: 'rehearsal',
  },
  rehearsal: {
    id: 'rehearsal',
    question: 'businessMirror.guidance.rehearsal.question',
    why: 'businessMirror.guidance.rehearsal.why',
    inputs: [input('mirrorPlan', '/mirrorPlanRef', 'rehearsalCatalog')],
    primaryAction: stepAction(
      'OPEN_REHEARSAL',
      'business-mirror.rehearsal.mirror-plan',
      '/mirrorPlanRef',
      'rehearsalCatalog',
      'SHOW_UNAVAILABLE',
    ),
    completionCodes: ['MIRROR_PLAN_PREFLIGHT_COMPLETE'],
    nextStep: 'evidence',
  },
  evidence: {
    id: 'evidence',
    question: 'businessMirror.guidance.evidence.question',
    why: 'businessMirror.guidance.evidence.why',
    inputs: [
      input('evidencePortfolio', '/evidence', 'evidenceProjection'),
      input('ownerTasks', '/evidence/ownerTasks', 'evidenceProjection'),
    ],
    primaryAction: stepAction(
      'FOCUS_FIELD',
      'business-mirror.evidence.portfolio',
      '/evidence',
      'evidenceProjection',
      'SHOW_UNAVAILABLE',
    ),
    completionCodes: ['EVIDENCE_PORTFOLIO_CURRENT'],
    nextStep: 'calibrate',
  },
  calibrate: {
    id: 'calibrate',
    question: 'businessMirror.guidance.calibrate.question',
    why: 'businessMirror.guidance.calibrate.why',
    inputs: [
      input('fidelity', '/fidelityInventoryRef', 'fidelityCatalog'),
      input('outcome', '/outcomeDefinitionRefs', 'outcomeCatalog'),
      input('approval', '/provenance/approvedBy', 'ownerDirectoryApi'),
    ],
    primaryAction: stepAction(
      'OPEN_GOVERNANCE',
      'business-mirror.calibrate.approval',
      '/provenance/approvedBy',
      'ownerDirectoryApi',
      'REQUEST_ACCESS',
    ),
    completionCodes: ['CALIBRATION_SUBMITTED'],
    nextStep: null,
  },
} as const satisfies Record<BusinessMirrorTaskId, StepContract>;

export const STEP_CONTRACTS = BUSINESS_MIRROR_STEP_CONTRACTS;

export type KnownBusinessMirrorGapCode =
  | 'ACCOUNTABLE_OWNER_MISSING'
  | 'BUSINESS_DOMAIN_MISSING'
  | 'BUSINESS_GOAL_MISSING'
  | 'EXPECTED_OUTCOME_MISSING'
  | 'PROBLEM_CODE_MISSING'
  | 'PROBLEM_TAXONOMY_MISSING'
  | 'PACKAGE_CONTRACT_MISSING'
  | 'GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING'
  | 'HIGH_RISK_EFFECT_MODEL_MISSING'
  | 'HIGH_RISK_STATE_MODEL_MISSING'
  | 'EXECUTABLE_PROJECTION_MISSING'
  | 'SOLUTION_BINDING_MISSING'
  | 'SERVICE_CARRIER_BINDING_MISSING'
  | 'CHANNEL_BINDING_MISSING'
  | 'SCENARIO_INVENTORY_MISSING'
  | 'SCENARIO_PACK_MISSING'
  | 'DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE'
  | 'MIRROR_PLAN_MISSING'
  | 'FIDELITY_INVENTORY_MISSING'
  | 'OUTCOME_DEFINITION_MISSING'
  | 'LEGACY_PROJECTION_OWNER_APPROVAL_MISSING';

export const KNOWN_BUSINESS_MIRROR_BLOCKER_CODES = [
  'ACCOUNTABLE_OWNER_MISSING',
  'BUSINESS_DOMAIN_MISSING',
  'BUSINESS_GOAL_MISSING',
  'EXPECTED_OUTCOME_MISSING',
  'PROBLEM_CODE_MISSING',
  'PROBLEM_TAXONOMY_MISSING',
  'PACKAGE_CONTRACT_MISSING',
  'GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING',
  'HIGH_RISK_EFFECT_MODEL_MISSING',
  'HIGH_RISK_STATE_MODEL_MISSING',
  'EXECUTABLE_PROJECTION_MISSING',
  'SOLUTION_BINDING_MISSING',
  'SERVICE_CARRIER_BINDING_MISSING',
  'CHANNEL_BINDING_MISSING',
  'SCENARIO_INVENTORY_MISSING',
  'SCENARIO_PACK_MISSING',
  'DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE',
  'MIRROR_PLAN_MISSING',
  'FIDELITY_INVENTORY_MISSING',
  'OUTCOME_DEFINITION_MISSING',
  'LEGACY_PROJECTION_OWNER_APPROVAL_MISSING',
] as const satisfies readonly KnownBusinessMirrorGapCode[];

type DescriptorSpec = Omit<RemediationDescriptor, 'gapCode' | 'titleMessageId'
  | 'impactMessageId' | 'instructionMessageId' | 'surfaceAnchor' | 'capabilityRequired'>;

const DESCRIPTOR_SPECS: Record<KnownBusinessMirrorGapCode, DescriptorSpec> = {
  ACCOUNTABLE_OWNER_MISSING: spec('BUSINESS_CONTEXT', 'problem', 'business-mirror.problem.owner', 'OPEN_PICKER', '/businessDefinition/accountableOwner', 'ownerDirectoryApi', 'businessDefinition.accountableOwner != empty', 'REQUEST_ACCESS'),
  BUSINESS_DOMAIN_MISSING: spec('BUSINESS_CONTEXT', 'problem', 'business-mirror.problem.domain', 'OPEN_PICKER', '/businessDefinition/domainId', 'businessDomainDirectory', 'businessDefinition.domainId != empty', 'REQUEST_ACCESS'),
  BUSINESS_GOAL_MISSING: spec('BUSINESS_CONTEXT', 'problem', 'business-mirror.problem.goal', 'FOCUS_FIELD', '/businessDefinition/businessGoal', 'businessMirrorAuthoring', 'businessDefinition.businessGoal != empty', 'ADVANCED_EXACT_INPUT'),
  EXPECTED_OUTCOME_MISSING: spec('BUSINESS_CONTEXT', 'problem', 'business-mirror.problem.outcome', 'FOCUS_FIELD', '/businessDefinition/expectedOutcome', 'businessMirrorAuthoring', 'businessDefinition.expectedOutcome != empty', 'ADVANCED_EXACT_INPUT'),
  PROBLEM_CODE_MISSING: spec('BUSINESS_CONTEXT', 'problem', 'business-mirror.problem.code', 'FOCUS_FIELD', '/businessDefinition/problemCode', 'businessMirrorAuthoring', 'businessDefinition.problemCode != empty', 'ADVANCED_EXACT_INPUT'),
  PROBLEM_TAXONOMY_MISSING: spec('BUSINESS_CONTEXT', 'problem', 'business-mirror.problem.taxonomy', 'OPEN_PICKER', '/businessDefinition/problemTaxonomyRef', 'problemTaxonomyCatalog', 'businessDefinition.problemTaxonomyRef != null', 'REQUEST_ACCESS'),
  PACKAGE_CONTRACT_MISSING: spec('CONTRACT', 'boundary', 'business-mirror.boundary.contract', 'OPEN_PICKER', '/packageContractRef', 'contractCatalog', 'packageContractRef != null', 'ADVANCED_EXACT_INPUT'),
  GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING: spec('CONTRACT', 'boundary', 'business-mirror.boundary.owner-confirmation', 'OPEN_PICKER', '/provenance/approvedBy', 'ownerDirectoryApi', 'provenance.approvedBy != empty', 'REQUEST_ACCESS'),
  HIGH_RISK_EFFECT_MODEL_MISSING: spec('EXECUTION_MODEL', 'boundary', 'business-mirror.boundary.effect', 'OPEN_PICKER', '/effectModelRefs', 'effectModelCatalog', 'effectModelRefs.length > 0', 'SHOW_UNAVAILABLE'),
  HIGH_RISK_STATE_MODEL_MISSING: spec('EXECUTION_MODEL', 'boundary', 'business-mirror.boundary.state', 'OPEN_PICKER', '/stateModelRefs', 'stateModelCatalog', 'stateModelRefs.length > 0', 'SHOW_UNAVAILABLE'),
  EXECUTABLE_PROJECTION_MISSING: spec('SERVICE_ASSET', 'capabilities', 'business-mirror.capabilities.executable', 'OPEN_AUTHOR', '/capabilityRefs', 'capabilityCatalog', 'capabilityRefs.length > 0 || graphRefs.length > 0', 'SHOW_UNAVAILABLE'),
  SOLUTION_BINDING_MISSING: spec('SERVICE_ASSET', 'capabilities', 'business-mirror.capabilities.solution', 'OPEN_PICKER', '/solutionRefs', 'solutionCatalog', 'solutionRefs.length > 0', 'SHOW_UNAVAILABLE'),
  SERVICE_CARRIER_BINDING_MISSING: spec('SERVICE_ASSET', 'capabilities', 'business-mirror.capabilities.carrier', 'OPEN_PICKER', '/carrierRefs', 'carrierCatalog', 'carrierRefs.length > 0', 'SHOW_UNAVAILABLE'),
  CHANNEL_BINDING_MISSING: spec('SERVICE_ASSET', 'capabilities', 'business-mirror.capabilities.channel', 'OPEN_PICKER', '/channelRefs', 'channelCatalog', 'channelRefs.length > 0', 'SHOW_UNAVAILABLE'),
  SCENARIO_INVENTORY_MISSING: spec('SCENARIO', 'scenarios', 'business-mirror.scenarios.inventory', 'OPEN_CORRECTNESS', '/scenarioInventoryRef', 'scenarioInventoryCatalog', 'scenarioInventoryRef != null', 'SHOW_UNAVAILABLE'),
  SCENARIO_PACK_MISSING: spec('SCENARIO', 'scenarios', 'business-mirror.scenarios.pack', 'OPEN_CORRECTNESS', '/scenarioPackRefs', 'scenarioPackCatalog', 'scenarioPackRefs.length > 0', 'SHOW_UNAVAILABLE'),
  DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE: spec('SCENARIO', 'scenarios', 'business-mirror.scenarios.discovered-suite', 'OPEN_CORRECTNESS', '/scenarioInventoryRef', 'correctnessScenarioGovernance', 'scenarioInventoryRef != null && scenarioPackRefs.length > 0', 'SHOW_UNAVAILABLE'),
  MIRROR_PLAN_MISSING: spec('EXECUTION_MODEL', 'rehearsal', 'business-mirror.rehearsal.mirror-plan', 'OPEN_REHEARSAL', '/mirrorPlanRef', 'rehearsalCatalog', 'mirrorPlanRef != null', 'SHOW_UNAVAILABLE'),
  FIDELITY_INVENTORY_MISSING: spec('FIDELITY', 'calibrate', 'business-mirror.calibrate.fidelity', 'OPEN_GOVERNANCE', '/fidelityInventoryRef', 'fidelityCatalog', 'fidelityInventoryRef != null', 'SHOW_UNAVAILABLE'),
  OUTCOME_DEFINITION_MISSING: spec('OUTCOME', 'calibrate', 'business-mirror.calibrate.outcome', 'OPEN_GOVERNANCE', '/outcomeDefinitionRefs', 'outcomeCatalog', 'outcomeDefinitionRefs.length > 0', 'SHOW_UNAVAILABLE'),
  LEGACY_PROJECTION_OWNER_APPROVAL_MISSING: spec('APPROVAL', 'calibrate', 'business-mirror.calibrate.approval', 'OPEN_GOVERNANCE', '/provenance/approvedBy', 'ownerDirectoryApi', 'provenance.approvedBy != empty', 'REQUEST_ACCESS'),
};

export const BUSINESS_MIRROR_REMEDIATION_DESCRIPTORS = Object.fromEntries(
  Object.entries(DESCRIPTOR_SPECS).map(([gapCode, descriptorSpec]) => [
    gapCode,
    createDescriptor(gapCode, descriptorSpec),
  ]),
) as Record<KnownBusinessMirrorGapCode, RemediationDescriptor>;

export const REMEDIATION_DESCRIPTORS = BUSINESS_MIRROR_REMEDIATION_DESCRIPTORS;

const CATEGORY_TASKS: Record<string, BusinessMirrorTaskId> = {
  BUSINESS_CONTEXT: 'problem',
  CONTRACT: 'boundary',
  EXECUTION_MODEL: 'boundary',
  SERVICE_ASSET: 'capabilities',
  SCENARIO: 'scenarios',
  FIDELITY: 'calibrate',
  OUTCOME: 'calibrate',
  APPROVAL: 'calibrate',
};

export function getBusinessMirrorStepContract(taskId: BusinessMirrorTaskId): StepContract {
  return BUSINESS_MIRROR_STEP_CONTRACTS[taskId];
}

export function remediationDescriptorForGap(gap: Pick<BusinessMirrorGap, 'code' | 'category' | 'draftPath'>): RemediationDescriptor {
  const known = BUSINESS_MIRROR_REMEDIATION_DESCRIPTORS[gap.code as KnownBusinessMirrorGapCode];
  if (known) return known;

  const category = normalizeMachineSegment(gap.category) || 'unknown';
  const code = normalizeMachineSegment(gap.code) || 'unknown';
  const taskId = CATEGORY_TASKS[gap.category] ?? 'calibrate';
  const fieldPath = gap.draftPath || `/${category}`;
  return createDescriptor(gap.code, {
    category: gap.category || 'UNKNOWN',
    taskId,
    anchor: `business-mirror.unavailable.${category}.${code}`,
    actionKind: 'FOCUS_FIELD',
    fieldPath,
    capability: `category:${category}`,
    completionPredicate: 'descriptor.fallback != SHOW_UNAVAILABLE',
    fallback: 'SHOW_UNAVAILABLE',
  }, true);
}

export const getRemediationDescriptor = remediationDescriptorForGap;

function input(id: string, fieldPath: string, capability: string): StepInputContract {
  return { id, fieldPath, required: true, capability };
}

function stepAction(
  actionKind: RemediationActionKind,
  anchor: string,
  fieldPath: string,
  capability: string,
  fallback: RemediationFallback,
): StepPrimaryAction {
  return { actionKind, anchor, fieldPath, capability, fallback };
}

function spec(
  category: string,
  taskId: BusinessMirrorTaskId,
  anchor: string,
  actionKind: RemediationActionKind,
  fieldPath: string,
  capability: string,
  completionPredicate: string,
  fallback: RemediationFallback,
): DescriptorSpec {
  return { category, taskId, anchor, actionKind, fieldPath, capability, completionPredicate, fallback };
}

function createDescriptor(
  gapCode: string,
  descriptorSpec: DescriptorSpec,
  unavailable = false,
): RemediationDescriptor {
  const messageBase = unavailable
    ? 'businessMirror.remediation.unknownGap'
    : `businessMirror.remediation.${messageSegment(gapCode)}`;
  return {
    ...descriptorSpec,
    gapCode,
    surfaceAnchor: descriptorSpec.anchor,
    capabilityRequired: descriptorSpec.capability,
    titleMessageId: `${messageBase}.title`,
    impactMessageId: `${messageBase}.impact`,
    instructionMessageId: `${messageBase}.instruction`,
  };
}

function messageSegment(code: string): string {
  return code.toLowerCase().replace(/_([a-z0-9])/g, (_, character: string) => character.toUpperCase());
}

function normalizeMachineSegment(value: string): string {
  return value.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}
