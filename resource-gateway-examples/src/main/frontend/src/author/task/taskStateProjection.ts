import type { AuthorMode, AuthorPrimaryAction } from '../shell/authorWorkspaceState';
import { resolveAuthorPrimaryAction } from '../shell/authorWorkspaceState';

export type AuthorTaskCanonicalState =
  | 'EMPTY'
  | 'PREPARING'
  | 'BLOCKED'
  | 'RUNNABLE'
  | 'RUNNING'
  | 'EVIDENCE_CURRENT'
  | 'EVIDENCE_STALE';

export type AuthorTaskCurrentness = 'NOT_RUN' | 'CURRENT' | 'STALE';
export type AuthorTaskProofStrength = 'EXPLORATORY' | 'DURABLE' | 'GOVERNED';

export interface AuthorTaskCoordinate {
  targetKind: 'GRAPH' | 'OPERATOR';
  targetId: string;
  targetRevision: number;
  targetFingerprint: string;
  contractFingerprint: string;
  scenarioSetId: string;
  scenarioRevision: number;
  scenarioFingerprint: string;
  operatorClosureFingerprint: string;
}

export interface AuthorCommandRemediation {
  label: string;
  mode: AuthorMode;
}

export interface AuthorCommandAvailability {
  commandId: 'RUN_CURRENT_SCENARIO' | 'PRIMARY_TASK_ACTION';
  state: 'READY' | 'RUNNING' | 'BLOCKED';
  enabled: boolean;
  label: string;
  reasonCode: string;
  message: string;
  remediation?: AuthorCommandRemediation;
}

export interface AuthorTaskBlocker {
  code: string;
  message: string;
  remediation: AuthorCommandRemediation;
}

export interface AuthorTaskStateInput {
  nodeCount: number;
  busy: boolean;
  hasInputErrors: boolean;
  hasScenario: boolean;
  canonicalScenarioReady: boolean;
  hasRunResult: boolean;
  runSuccessful: boolean;
  evidenceStale: boolean;
  governanceApproved: boolean;
  coordinate: AuthorTaskCoordinate;
  interactionBlocker?: AuthorTaskBlocker;
}

export interface AuthorTaskStateProjection {
  taskCoordinate: AuthorTaskCoordinate;
  canonicalState: AuthorTaskCanonicalState;
  primaryAction: AuthorPrimaryAction;
  primaryCommand: AuthorCommandAvailability;
  commands: {
    runCurrentScenario: AuthorCommandAvailability;
  };
  blockingReasons: AuthorTaskBlocker[];
  remediationActions: AuthorCommandRemediation[];
  proofStrength: AuthorTaskProofStrength;
  currentness: AuthorTaskCurrentness;
}

/** Projects the one canonical authoring state consumed by every command surface. */
export function projectAuthorTaskState(input: AuthorTaskStateInput): AuthorTaskStateProjection {
  const blockingReasons = runBlockers(input);
  const runCurrentScenario = runCommand(input, blockingReasons);
  const primaryAction = resolveAuthorPrimaryAction({
    nodeCount: input.nodeCount,
    busy: input.busy,
    hasInputErrors: input.hasInputErrors,
    hasRunResult: input.hasRunResult,
    runSuccessful: input.runSuccessful,
    runStale: input.evidenceStale,
  });
  const currentness: AuthorTaskCurrentness = !input.hasRunResult
    ? 'NOT_RUN'
    : input.evidenceStale ? 'STALE' : 'CURRENT';
  const durable = Boolean(input.coordinate.targetId && input.coordinate.targetRevision > 0);
  const proofStrength: AuthorTaskProofStrength = durable
    ? input.governanceApproved && currentness === 'CURRENT' ? 'GOVERNED' : 'DURABLE'
    : 'EXPLORATORY';
  const primaryCommand = primaryAction.kind === 'run'
    ? runCurrentScenario
    : nonRunPrimaryCommand(primaryAction, input.busy);

  return {
    taskCoordinate: input.coordinate,
    canonicalState: canonicalState(input, blockingReasons, currentness),
    primaryAction,
    primaryCommand,
    commands: { runCurrentScenario },
    blockingReasons,
    remediationActions: uniqueRemediations(blockingReasons),
    proofStrength,
    currentness,
  };
}

function runBlockers(input: AuthorTaskStateInput): AuthorTaskBlocker[] {
  const blockers: AuthorTaskBlocker[] = [];
  if (input.nodeCount === 0) {
    blockers.push(blocker(
      'RG.AUTHOR.RUN.GRAPH_EMPTY',
      'Add at least one operator before running a Scenario.',
      'Add first operator',
      'compose',
    ));
  }
  if (input.hasInputErrors) {
    blockers.push(blocker(
      'RG.AUTHOR.RUN.INPUT_INVALID',
      'Resolve the highlighted input values before running.',
      'Fix required input',
      'scenarios',
    ));
  }
  if (input.interactionBlocker) {
    blockers.push(input.interactionBlocker);
  }
  if (!input.hasScenario) {
    blockers.push(blocker(
      'RG.AUTHOR.RUN.SCENARIO_MISSING',
      'Create a Scenario with business input and at least one expected outcome.',
      'Create Scenario',
      'scenarios',
    ));
  }
  if (input.hasScenario && !input.canonicalScenarioReady) {
    const coordinateIncomplete = !input.coordinate.targetFingerprint
      || !input.coordinate.contractFingerprint
      || !input.coordinate.scenarioFingerprint
      || !input.coordinate.operatorClosureFingerprint;
    blockers.push(blocker(
      coordinateIncomplete
        ? 'RG.AUTHOR.RUN.COORDINATE_PREPARING'
        : 'RG.AUTHOR.RUN.SCENARIO_STALE',
      coordinateIncomplete
        ? 'The canonical Scenario coordinate is still being prepared.'
        : 'This Scenario targets an older Graph or Contract and cannot create current evidence.',
      coordinateIncomplete ? 'Open Scenarios' : 'Review compatibility',
      coordinateIncomplete ? 'scenarios' : 'contract',
    ));
  }
  return blockers;
}

function runCommand(
  input: AuthorTaskStateInput,
  blockers: AuthorTaskBlocker[],
): AuthorCommandAvailability {
  if (input.busy) {
    return {
      commandId: 'RUN_CURRENT_SCENARIO',
      state: 'RUNNING',
      enabled: false,
      label: 'Running...',
      reasonCode: 'RG.AUTHOR.RUN.IN_PROGRESS',
      message: 'The current Scenario run is still in progress.',
    };
  }
  const first = blockers[0];
  if (first) {
    return {
      commandId: 'RUN_CURRENT_SCENARIO',
      state: 'BLOCKED',
      enabled: false,
      label: input.evidenceStale ? 'Rerun & Compare' : 'Run & Compare',
      reasonCode: first.code,
      message: first.message,
      remediation: first.remediation,
    };
  }
  return {
    commandId: 'RUN_CURRENT_SCENARIO',
    state: 'READY',
    enabled: true,
    label: input.evidenceStale ? 'Rerun & Compare' : 'Run & Compare',
    reasonCode: '',
    message: input.coordinate.targetId && input.coordinate.targetRevision > 0
      ? 'Runs the exact saved Scenario coordinate and records durable current evidence.'
      : 'Runs an immutable sandbox snapshot and records current exploratory evidence.',
  };
}

function nonRunPrimaryCommand(
  primaryAction: AuthorPrimaryAction,
  busy: boolean,
): AuthorCommandAvailability {
  return {
    commandId: 'PRIMARY_TASK_ACTION',
    state: busy ? 'RUNNING' : 'READY',
    enabled: !busy,
    label: primaryAction.label,
    reasonCode: busy ? 'RG.AUTHOR.COMMAND.IN_PROGRESS' : '',
    message: busy ? 'Wait for the active authoring command to finish.' : '',
  };
}

function canonicalState(
  input: AuthorTaskStateInput,
  blockers: AuthorTaskBlocker[],
  currentness: AuthorTaskCurrentness,
): AuthorTaskCanonicalState {
  if (input.nodeCount === 0) return 'EMPTY';
  if (input.busy) return 'RUNNING';
  if (blockers.some((item) => item.code === 'RG.AUTHOR.RUN.COORDINATE_PREPARING')) {
    return 'PREPARING';
  }
  if (blockers.length > 0) return 'BLOCKED';
  if (currentness === 'STALE') return 'EVIDENCE_STALE';
  if (currentness === 'CURRENT') return 'EVIDENCE_CURRENT';
  return 'RUNNABLE';
}

function blocker(
  code: string,
  message: string,
  label: string,
  mode: AuthorMode,
): AuthorTaskBlocker {
  return { code, message, remediation: { label, mode } };
}

function uniqueRemediations(blockers: AuthorTaskBlocker[]): AuthorCommandRemediation[] {
  return blockers.filter((item, index) => blockers.findIndex((candidate) => (
    candidate.remediation.label === item.remediation.label
    && candidate.remediation.mode === item.remediation.mode
  )) === index).map((item) => item.remediation);
}
