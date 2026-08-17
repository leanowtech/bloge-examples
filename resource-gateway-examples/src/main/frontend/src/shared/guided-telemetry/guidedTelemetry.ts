import { sha256FingerprintSync } from '../../contract-scenario/fingerprint';

export const GUIDED_AUTHORING_TELEMETRY_EVENT_TYPE = 'bloge:guided-authoring-telemetry';

export type GuidedTelemetryEventName =
  | 'WORKSPACE_LAUNCHER_OPENED'
  | 'REFERENCE_SEARCH_COMPLETED'
  | 'REFERENCE_RESOLVE_COMPLETED'
  | 'GUIDED_STEP_VIEWED'
  | 'REMEDIATION_STARTED'
  | 'REMEDIATION_COMPLETED'
  | 'CROSS_WORKSPACE_LINK_RESOLVED'
  | 'ADVANCED_EXACT_MODE_USED';

export type GuidedTelemetryDurationBucket =
  | 'LT_100_MS'
  | '100_500_MS'
  | '500_2_S'
  | '2_5_S'
  | 'GT_5_S';

export type GuidedTelemetryGapCode =
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
  | 'LEGACY_PROJECTION_OWNER_APPROVAL_MISSING'
  | 'UNKNOWN';

export type GuidedTelemetryMetadata = {
  surface?: 'CORRECTNESS' | 'BUSINESS_MIRROR';
  entryKind?: 'GUIDED' | 'ADVANCED';
  scopeHash?: string;
  kind?: 'GRAPH' | 'OPERATOR' | 'FUNCTION' | 'CORRECTNESS_DEFINITION';
  latencyBucket?: GuidedTelemetryDurationBucket;
  resultCountBucket?: 'ZERO' | 'ONE' | 'TWO_TO_FIVE' | 'SIX_TO_TWENTY' | 'GT_20';
  outcome?: 'MATCHED' | 'EMPTY' | 'ERROR' | 'UNAVAILABLE' | 'SUCCESS' | 'FAILED' | 'TARGETED'
    | 'RESOLVED' | 'STILL_BLOCKED' | 'CANCELLED' | 'NAVIGATED';
  drifted?: boolean;
  workspace?: 'CORRECTNESS' | 'BUSINESS_MIRROR';
  step?: 'PROBLEM' | 'BOUNDARY' | 'CAPABILITIES' | 'SCENARIOS' | 'REHEARSAL' | 'EVIDENCE' | 'CALIBRATE'
    | 'VERDICT' | 'DEFINE_CORRECTNESS' | 'RUN_AND_EVIDENCE';
  status?: 'READY' | 'BLOCKED' | 'REVIEW';
  gapCode?: GuidedTelemetryGapCode;
  actionKind?: 'FOCUS_FIELD' | 'OPEN_PICKER' | 'OPEN_AUTHOR' | 'OPEN_REHEARSAL'
    | 'OPEN_CORRECTNESS' | 'OPEN_GOVERNANCE';
  sameStep?: boolean;
  durationBucket?: GuidedTelemetryDurationBucket;
  targetWorkspace?: 'AUTHOR' | 'REHEARSAL' | 'CORRECTNESS' | 'GOVERNANCE';
  resolutionKind?: 'CANDIDATE' | 'EXACT' | 'REMEDIATION';
  reasonCode?: 'DEEP_LINK_RECOVERY' | 'PROTOCOL_TROUBLESHOOTING' | 'CATALOG_UNAVAILABLE';
};

export interface GuidedTelemetryScope {
  tenantId?: string;
  organizationId?: string;
  projectId?: string;
  environmentId?: string;
  region?: string;
}

export interface GuidedTelemetryEvent {
  schema: 'bloge.guidedAuthoringTelemetry.v1';
  name: GuidedTelemetryEventName;
  occurredAt: string;
  metadata: Record<string, string | boolean>;
}

export interface GuidedAuthoringTelemetrySink {
  (event: GuidedTelemetryEvent): void;
}

export interface GuidedAuthoringTelemetry {
  record(name: GuidedTelemetryEventName, metadata?: GuidedTelemetryMetadata): GuidedTelemetryEvent | null;
}

const ALLOWED_METADATA: Record<GuidedTelemetryEventName, ReadonlySet<string>> = {
  WORKSPACE_LAUNCHER_OPENED: new Set(['surface', 'entryKind', 'scopeHash']),
  REFERENCE_SEARCH_COMPLETED: new Set(['kind', 'latencyBucket', 'resultCountBucket', 'outcome']),
  REFERENCE_RESOLVE_COMPLETED: new Set(['kind', 'outcome', 'drifted', 'latencyBucket']),
  GUIDED_STEP_VIEWED: new Set(['workspace', 'step', 'status']),
  REMEDIATION_STARTED: new Set(['gapCode', 'actionKind', 'sameStep']),
  REMEDIATION_COMPLETED: new Set(['gapCode', 'outcome', 'durationBucket']),
  CROSS_WORKSPACE_LINK_RESOLVED: new Set(['targetWorkspace', 'resolutionKind', 'outcome']),
  ADVANCED_EXACT_MODE_USED: new Set(['targetWorkspace', 'reasonCode']),
};

const STRING_ENUMS: Record<string, ReadonlySet<string>> = {
  surface: new Set(['CORRECTNESS', 'BUSINESS_MIRROR']),
  entryKind: new Set(['GUIDED', 'ADVANCED']),
  kind: new Set(['GRAPH', 'OPERATOR', 'FUNCTION', 'CORRECTNESS_DEFINITION']),
  latencyBucket: new Set(['LT_100_MS', '100_500_MS', '500_2_S', '2_5_S', 'GT_5_S']),
  durationBucket: new Set(['LT_100_MS', '100_500_MS', '500_2_S', '2_5_S', 'GT_5_S']),
  resultCountBucket: new Set(['ZERO', 'ONE', 'TWO_TO_FIVE', 'SIX_TO_TWENTY', 'GT_20']),
  outcome: new Set([
    'MATCHED', 'EMPTY', 'ERROR', 'UNAVAILABLE', 'SUCCESS', 'FAILED', 'TARGETED',
    'RESOLVED', 'STILL_BLOCKED', 'CANCELLED', 'NAVIGATED',
  ]),
  workspace: new Set(['CORRECTNESS', 'BUSINESS_MIRROR']),
  step: new Set([
    'PROBLEM', 'BOUNDARY', 'CAPABILITIES', 'SCENARIOS', 'REHEARSAL', 'EVIDENCE', 'CALIBRATE',
    'VERDICT', 'DEFINE_CORRECTNESS', 'RUN_AND_EVIDENCE',
  ]),
  status: new Set(['READY', 'BLOCKED', 'REVIEW']),
  actionKind: new Set(['FOCUS_FIELD', 'OPEN_PICKER', 'OPEN_AUTHOR', 'OPEN_REHEARSAL', 'OPEN_CORRECTNESS', 'OPEN_GOVERNANCE']),
  targetWorkspace: new Set(['AUTHOR', 'REHEARSAL', 'CORRECTNESS', 'GOVERNANCE']),
  resolutionKind: new Set(['CANDIDATE', 'EXACT', 'REMEDIATION']),
  reasonCode: new Set(['DEEP_LINK_RECOVERY', 'PROTOCOL_TROUBLESHOOTING', 'CATALOG_UNAVAILABLE']),
};

const GAP_CODES = new Set<GuidedTelemetryGapCode>([
  'ACCOUNTABLE_OWNER_MISSING', 'BUSINESS_DOMAIN_MISSING', 'BUSINESS_GOAL_MISSING',
  'EXPECTED_OUTCOME_MISSING', 'PROBLEM_CODE_MISSING', 'PROBLEM_TAXONOMY_MISSING',
  'PACKAGE_CONTRACT_MISSING', 'GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING',
  'HIGH_RISK_EFFECT_MODEL_MISSING', 'HIGH_RISK_STATE_MODEL_MISSING',
  'EXECUTABLE_PROJECTION_MISSING', 'SOLUTION_BINDING_MISSING',
  'SERVICE_CARRIER_BINDING_MISSING', 'CHANNEL_BINDING_MISSING',
  'SCENARIO_INVENTORY_MISSING', 'SCENARIO_PACK_MISSING',
  'DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE', 'MIRROR_PLAN_MISSING',
  'FIDELITY_INVENTORY_MISSING', 'OUTCOME_DEFINITION_MISSING',
  'LEGACY_PROJECTION_OWNER_APPROVAL_MISSING', 'UNKNOWN',
]);

const BOOLEAN_METADATA = new Set(['drifted', 'sameStep']);

const FORBIDDEN_KEY = /(query|fixture|payload|context|schema|dsl|config|input|output|secret|token|credential|(^|_)id$|fingerprint)/i;
const SCOPE_HASH = /^sha256:[0-9a-f]{16}$/;

/** Builds the only event shape that guided authoring is allowed to emit. */
export function createGuidedTelemetryEvent(
  name: GuidedTelemetryEventName,
  metadata: GuidedTelemetryMetadata = {},
  now: Date = new Date(),
): GuidedTelemetryEvent {
  const allowed = ALLOWED_METADATA[name];
  const entries = Object.entries(metadata)
    .filter((entry): entry is [string, string | boolean] => entry[1] !== undefined);
  for (const [key, value] of entries) {
    if (!allowed.has(key) || FORBIDDEN_KEY.test(key)) {
      throw new Error(`Guided telemetry metadata "${key}" is not allowed for ${name}.`);
    }
    if (typeof value !== 'string' && typeof value !== 'boolean') {
      throw new Error(`Guided telemetry metadata "${key}" has an unsupported value type.`);
    }
    if (typeof value === 'boolean' && !BOOLEAN_METADATA.has(key)) {
      throw new Error(`Guided telemetry metadata "${key}" must be an enum value.`);
    }
    if (typeof value === 'string') {
      if (key === 'scopeHash') {
        if (!SCOPE_HASH.test(value)) throw new Error('Guided telemetry scopeHash must be a short SHA-256 hash.');
      } else if (key === 'gapCode') {
        if (!GAP_CODES.has(value as GuidedTelemetryGapCode)) {
          throw new Error('Guided telemetry gapCode must be a known machine enum.');
        }
      } else if (!STRING_ENUMS[key]?.has(value)) {
        throw new Error(`Guided telemetry metadata "${key}" has an unsupported enum value.`);
      }
    }
  }
  return {
    schema: 'bloge.guidedAuthoringTelemetry.v1',
    name,
    occurredAt: now.toISOString(),
    metadata: Object.fromEntries(entries),
  };
}

/** Creates a sink-backed recorder. The default is deliberately a no-op and never sends network traffic. */
export function createGuidedAuthoringTelemetry(
  sink: GuidedAuthoringTelemetrySink = () => undefined,
): GuidedAuthoringTelemetry {
  return {
    record(name, metadata = {}) {
      try {
        const event = createGuidedTelemetryEvent(name, metadata);
        sink(event);
        return event;
      } catch {
        return null;
      }
    },
  };
}

export const noopGuidedAuthoringTelemetry = createGuidedAuthoringTelemetry();

export function guidedTelemetryDurationBucket(durationMs: number): GuidedTelemetryDurationBucket {
  if (durationMs < 100) return 'LT_100_MS';
  if (durationMs < 500) return '100_500_MS';
  if (durationMs < 2_000) return '500_2_S';
  if (durationMs < 5_000) return '2_5_S';
  return 'GT_5_S';
}

export function guidedTelemetryResultCountBucket(count: number): GuidedTelemetryMetadata['resultCountBucket'] {
  if (count <= 0) return 'ZERO';
  if (count === 1) return 'ONE';
  if (count <= 5) return 'TWO_TO_FIVE';
  if (count <= 20) return 'SIX_TO_TWENTY';
  return 'GT_20';
}

/** Keeps forward-compatible server gap codes in the telemetry enum without leaking the raw code. */
export function guidedTelemetryGapCode(code: string): GuidedTelemetryGapCode {
  return GAP_CODES.has(code as GuidedTelemetryGapCode)
    ? code as GuidedTelemetryGapCode
    : 'UNKNOWN';
}

/** Hashes only an enterprise scope and truncates the existing SHA-256 coordinate before telemetry use. */
export function guidedTelemetryScopeHash(scope: GuidedTelemetryScope): string {
  const safeScope = {
    tenantId: scope.tenantId ?? '',
    organizationId: scope.organizationId ?? '',
    projectId: scope.projectId ?? '',
    environmentId: scope.environmentId ?? '',
    region: scope.region ?? '',
  };
  return sha256FingerprintSync(safeScope).slice(0, 'sha256:'.length + 16);
}
