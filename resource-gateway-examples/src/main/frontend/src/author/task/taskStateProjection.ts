import type { AuthorMode, AuthorPrimaryAction } from '../shell/authorWorkspaceState';
import { resolveAuthorPrimaryAction } from '../shell/authorWorkspaceState';
import type { MessageId } from '../../i18n/messageCatalog';

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
  scenarioId: string;
  scenarioRevision: number;
  scenarioFingerprint: string;
  operatorClosureFingerprint: string;
}

export interface AuthorCommandScope {
  kind: 'CASE';
  count: number;
  targetIds: string[];
  fingerprint: string;
}

export interface AuthorCommandRemediation {
  label: string;
  labelId?: MessageId;
  mode: AuthorMode;
}

export interface AuthorCommandAvailability {
  commandId: 'RUN_CURRENT_SCENARIO' | 'PRIMARY_TASK_ACTION';
  state: 'READY' | 'RUNNING' | 'BLOCKED';
  enabled: boolean;
  label: string;
  labelId?: MessageId;
  reasonCode: string;
  message: string;
  messageId?: MessageId;
  remediation?: AuthorCommandRemediation;
  owner?: 'GLOBAL' | 'TASK_SURFACE';
  scope?: AuthorCommandScope;
}

export interface AuthorTaskBlocker {
  code: string;
  message: string;
  messageId?: MessageId;
  remediation: AuthorCommandRemediation;
}

export interface AuthorTaskStateInput {
  activeMode?: AuthorMode;
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
    : nonRunPrimaryCommand(primaryAction, input.busy, input.activeMode);

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
      'author.blocker.graphEmpty',
      'Add first operator',
      'author.command.addFirstOperator',
      'compose',
    ));
  }
  if (input.hasInputErrors) {
    blockers.push(blocker(
      'RG.AUTHOR.RUN.INPUT_INVALID',
      'Resolve the highlighted input values before running.',
      'author.blocker.inputInvalid',
      'Fix required input',
      'author.command.fixRequiredInput',
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
      'author.blocker.scenarioMissing',
      'Create Scenario',
      'author.command.createScenario',
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
      coordinateIncomplete
        ? 'author.blocker.coordinatePreparing'
        : 'author.blocker.scenarioStale',
      coordinateIncomplete ? 'Open Scenarios' : 'Review compatibility',
      coordinateIncomplete ? 'author.command.openScenarios' : 'author.command.reviewCompatibility',
      coordinateIncomplete ? 'scenarios' : 'contract',
    ));
  }
  return blockers;
}

function runCommand(
  input: AuthorTaskStateInput,
  blockers: AuthorTaskBlocker[],
): AuthorCommandAvailability {
  const commandContext = {
    owner: input.activeMode && input.activeMode !== 'compose'
      ? 'TASK_SURFACE' as const
      : 'GLOBAL' as const,
    scope: {
      kind: 'CASE' as const,
      count: input.coordinate.scenarioId ? 1 : 0,
      targetIds: input.coordinate.scenarioId ? [input.coordinate.scenarioId] : [],
      fingerprint: input.coordinate.scenarioFingerprint,
    },
  };
  if (input.busy) {
    return {
      ...commandContext,
      commandId: 'RUN_CURRENT_SCENARIO',
      state: 'RUNNING',
      enabled: false,
      label: 'Running...',
      labelId: 'author.command.running',
      reasonCode: 'RG.AUTHOR.RUN.IN_PROGRESS',
      message: 'The current Scenario run is still in progress.',
      messageId: 'author.blocker.runInProgress',
    };
  }
  const first = blockers[0];
  if (first) {
    return {
      ...commandContext,
      commandId: 'RUN_CURRENT_SCENARIO',
      state: 'BLOCKED',
      enabled: false,
      label: input.evidenceStale ? 'Rerun & Compare' : 'Run & Compare',
      labelId: input.evidenceStale ? 'author.command.rerun' : 'author.command.run',
      reasonCode: first.code,
      message: first.message,
      messageId: first.messageId,
      remediation: first.remediation,
    };
  }
  return {
    ...commandContext,
    commandId: 'RUN_CURRENT_SCENARIO',
    state: 'READY',
    enabled: true,
    label: input.evidenceStale ? 'Rerun & Compare' : 'Run & Compare',
    labelId: input.evidenceStale ? 'author.command.rerun' : 'author.command.run',
    reasonCode: '',
    message: input.coordinate.targetId && input.coordinate.targetRevision > 0
      ? 'Runs the exact saved Scenario coordinate and records durable current evidence.'
      : 'Runs an immutable sandbox snapshot and records current exploratory evidence.',
    messageId: input.coordinate.targetId && input.coordinate.targetRevision > 0
      ? 'author.command.savedRunDetail'
      : 'author.command.sandboxRunDetail',
  };
}

function nonRunPrimaryCommand(
  primaryAction: AuthorPrimaryAction,
  busy: boolean,
  activeMode: AuthorMode | undefined,
): AuthorCommandAvailability {
  return {
    commandId: 'PRIMARY_TASK_ACTION',
    state: busy ? 'RUNNING' : 'READY',
    enabled: !busy,
    label: primaryAction.label,
    labelId: primaryActionMessageId(primaryAction),
    reasonCode: busy ? 'RG.AUTHOR.COMMAND.IN_PROGRESS' : '',
    message: busy ? 'Wait for the active authoring command to finish.' : '',
    messageId: busy ? 'author.command.waitForActive' : undefined,
    owner: activeMode && activeMode !== 'compose' ? 'TASK_SURFACE' : 'GLOBAL',
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
  messageId: MessageId,
  label: string,
  labelId: MessageId,
  mode: AuthorMode,
): AuthorTaskBlocker {
  return { code, message, messageId, remediation: { label, labelId, mode } };
}

function primaryActionMessageId(action: AuthorPrimaryAction): MessageId {
  switch (action.kind) {
    case 'focus-palette': return 'author.command.addFirstOperator';
    case 'fix-input': return 'author.command.fixRequiredInput';
    case 'review-failures': return 'author.command.reviewFailures';
    case 'review-result': return 'author.command.reviewResult';
    case 'run': return 'author.command.run';
  }
}

function uniqueRemediations(blockers: AuthorTaskBlocker[]): AuthorCommandRemediation[] {
  return blockers.filter((item, index) => blockers.findIndex((candidate) => (
    candidate.remediation.label === item.remediation.label
    && candidate.remediation.mode === item.remediation.mode
  )) === index).map((item) => item.remediation);
}
