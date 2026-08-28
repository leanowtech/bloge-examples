import type { ContractEffect, DependencyBehaviorDraft, ScenarioDraftSet } from '../../contract-scenario/domain';
import type { ProductMessageDescriptor, ProductMessageId } from '../../i18n/messageCatalog';
import type { GraphDraft } from '../../types';

export type PreflightRiskStatus = 'SAFE' | 'REVIEW' | 'BLOCKED';
export type PreflightInvocationMode = 'REAL' | 'MOCKED' | 'FAULT' | 'REPLAY' | 'OBSERVE' | 'DENIED';
type PreflightInvocationCountKey = 'real' | 'mocked' | 'fault' | 'replay' | 'observe' | 'denied';
export type PreflightRiskSeverity = 'WARNING' | 'BLOCKING';
export type PreflightRiskReasonCode =
  | 'EMPTY_SELECTION'
  | 'UNKNOWN_CASE_SELECTION'
  | 'PRODUCTION_ENVIRONMENT'
  | 'TARGET_WRITE_EFFECT'
  | 'TARGET_EFFECT_UNKNOWN'
  | 'REAL_DEPENDENCY'
  | 'OBSERVED_REAL_DEPENDENCY'
  | 'REAL_FALLBACK'
  | 'MISSING_ORACLE'
  | 'TRANSIENT_RUNTIME_UNSUPPORTED'
  | 'UNRESOLVED_DEPENDENCY';

export interface ScenarioRunPreflightInput {
  graphDraft: GraphDraft;
  draftSet: ScenarioDraftSet;
  targetEffect: ContractEffect;
  caseIds: string[];
}

export interface PreflightRiskReason {
  code: PreflightRiskReasonCode;
  severity: PreflightRiskSeverity;
  count: number;
  message: ProductMessageDescriptor;
}

export interface PreflightInvocationGroup {
  mode: PreflightInvocationMode;
  nodeId: string;
  operatorRef: string;
  source: 'SCENARIO' | 'GRAPH_FIXTURE' | 'SUBJECT' | 'UNCONTROLLED';
  behaviorKind: DependencyBehaviorDraft['behavior']['kind'];
  fallbackToReal: boolean;
  caseCount: number;
}

export interface ScenarioRunPreflightProjection {
  schemaVersion: 'bloge.correctnessPreflightProjection.v1';
  status: PreflightRiskStatus;
  environment: string;
  targetEffect: ContractEffect;
  selectedCaseCount: number;
  counts: {
    invocations: number;
    subjectReal: number;
    real: number;
    mocked: number;
    fault: number;
    replay: number;
    observe: number;
    denied: number;
    fallbackToReal: number;
    missingOracle: number;
  };
  reasons: PreflightRiskReason[];
  invocationGroups: PreflightInvocationGroup[];
  truncatedGroupCount: number;
}

const MAX_INVOCATION_GROUPS = 50;
const PRODUCTION_SEGMENT = /(^|[-_.])(prod|production|prd|live|online)([-_.]|$)/i;

/**
 * Produces a bounded, payload-free preview of the exact local Scenario execution semantics.
 *
 * This is an authoring guard, not a second runtime planner. The authoritative governed preflight
 * remains responsible for resolving runtime bindings immediately before admission.
 */
export function projectScenarioRunPreflight(
  input: ScenarioRunPreflightInput,
): ScenarioRunPreflightProjection {
  const selectedIds = unique(input.caseIds);
  const scenariosById = new Map(input.draftSet.scenarios.map((scenario) => [scenario.scenarioId, scenario]));
  const selectedScenarios = selectedIds.flatMap((caseId) => {
    const scenario = scenariosById.get(caseId);
    return scenario ? [scenario] : [];
  });
  const unknownSelectionCount = selectedIds.length - selectedScenarios.length;
  const reasonCounts = new Map<PreflightRiskReasonCode, { severity: PreflightRiskSeverity; count: number }>();
  const invocationCounts = emptyInvocationCounts();
  const groups = new Map<string, PreflightInvocationGroup>();

  if (selectedIds.length === 0) addReason(reasonCounts, 'EMPTY_SELECTION', 'BLOCKING', 1);
  if (unknownSelectionCount > 0) {
    addReason(reasonCounts, 'UNKNOWN_CASE_SELECTION', 'BLOCKING', unknownSelectionCount);
  }
  if (PRODUCTION_SEGMENT.test(input.draftSet.scope.environment.trim())) {
    addReason(reasonCounts, 'PRODUCTION_ENVIRONMENT', 'BLOCKING', 1);
  }
  if (input.targetEffect === 'WRITE') {
    addReason(reasonCounts, 'TARGET_WRITE_EFFECT', 'BLOCKING', 1);
  } else if (input.targetEffect === 'UNKNOWN') {
    addReason(reasonCounts, 'TARGET_EFFECT_UNKNOWN', 'WARNING', 1);
  }

  for (const scenario of selectedScenarios) {
    if (scenario.then.assertions.length === 0) {
      invocationCounts.missingOracle += 1;
      addReason(reasonCounts, 'MISSING_ORACLE', 'BLOCKING', 1);
    }
    const nodeDependencies = new Map<string, DependencyBehaviorDraft[]>();
    for (const dependency of scenario.dependencies) {
      const nodeId = resolveDependencyNodeId(dependency, input.graphDraft);
      if (!nodeId) {
        addReason(reasonCounts, 'UNRESOLVED_DEPENDENCY', 'BLOCKING', 1);
        addUnsupportedReason(reasonCounts, dependency);
        continue;
      }
      const resolvedDependency = dependency.selector.nodeId === nodeId
        ? dependency
        : { ...dependency, selector: { ...dependency.selector, nodeId: nodeId, operatorRef: '' } };
      const current = nodeDependencies.get(nodeId) ?? [];
      current.push(resolvedDependency);
      nodeDependencies.set(nodeId, current);
      if (fallbackToReal(resolvedDependency)) {
        invocationCounts.fallbackToReal += 1;
        addReason(reasonCounts, 'REAL_FALLBACK', 'BLOCKING', 1);
      }
    }

    for (const node of input.graphDraft.nodes) {
      const dependencies = nodeDependencies.get(node.id) ?? [];
      if (dependencies.length > 1) {
        addReason(reasonCounts, 'UNRESOLVED_DEPENDENCY', 'BLOCKING', dependencies.length);
      }
      const dependency = dependencies[0];
      const invocation = dependency
        ? invocationForDependency(dependency)
        : Object.prototype.hasOwnProperty.call(input.graphDraft.nodeFixtures ?? {}, node.id)
          ? {
              mode: 'MOCKED' as const,
              source: 'GRAPH_FIXTURE' as const,
              behaviorKind: 'RETURN' as const,
              fallbackToReal: false,
            }
          : node.id === input.graphDraft.output.nodeId
            ? {
                mode: 'REAL' as const,
                source: 'SUBJECT' as const,
                behaviorKind: 'REAL' as const,
                fallbackToReal: false,
              }
            : {
              mode: 'REAL' as const,
              source: 'UNCONTROLLED' as const,
              behaviorKind: 'REAL' as const,
              fallbackToReal: false,
            };
      invocationCounts.invocations += 1;
      if (invocation.source === 'SUBJECT') invocationCounts.subjectReal += 1;
      else invocationCounts[invocationCountKey(invocation.mode)] += 1;
      addInvocationGroup(groups, {
        ...invocation,
        nodeId: node.id,
        operatorRef: node.operatorRef,
        caseCount: 1,
      });
      if (invocation.mode === 'REAL' && invocation.source !== 'SUBJECT') {
        addReason(reasonCounts, 'REAL_DEPENDENCY', 'WARNING', 1);
      } else if (invocation.mode === 'OBSERVE') {
        addReason(reasonCounts, 'OBSERVED_REAL_DEPENDENCY', 'WARNING', 1);
      }
      if (dependency) addUnsupportedReason(reasonCounts, dependency);
    }
  }

  const reasons = [...reasonCounts.entries()].map(([code, value]) => ({
    code,
    severity: value.severity,
    count: value.count,
    message: reasonMessage(code, value.count),
  }));
  const allGroups = [...groups.values()].sort(compareInvocationGroups);
  const status = reasons.some((reason) => reason.severity === 'BLOCKING')
    ? 'BLOCKED'
    : reasons.length > 0 ? 'REVIEW' : 'SAFE';
  return {
    schemaVersion: 'bloge.correctnessPreflightProjection.v1',
    status,
    environment: input.draftSet.scope.environment,
    targetEffect: input.targetEffect,
    selectedCaseCount: selectedScenarios.length,
    counts: invocationCounts,
    reasons,
    invocationGroups: allGroups.slice(0, MAX_INVOCATION_GROUPS),
    truncatedGroupCount: Math.max(0, allGroups.length - MAX_INVOCATION_GROUPS),
  };
}

/** Resolves an immutable operator coordinate to its one executable graph node for preflight only. */
function resolveDependencyNodeId(
  dependency: DependencyBehaviorDraft,
  graphDraft: GraphDraft,
): string | null {
  if (dependency.selector.nodeId && graphDraft.nodes.some((node) => node.id === dependency.selector.nodeId)) {
    return dependency.selector.nodeId;
  }
  if (!dependency.selector.operatorRef) return null;
  const matches = graphDraft.nodes.filter((node) => node.operatorRef === dependency.selector.operatorRef);
  return matches.length === 1 ? matches[0]?.id ?? null : null;
}

function invocationForDependency(dependency: DependencyBehaviorDraft): Omit<
PreflightInvocationGroup,
'nodeId' | 'operatorRef' | 'caseCount'
> {
  return {
    mode: invocationMode(dependency.behavior.kind),
    source: 'SCENARIO',
    behaviorKind: dependency.behavior.kind,
    fallbackToReal: fallbackToReal(dependency),
  };
}

function invocationMode(
  behavior: DependencyBehaviorDraft['behavior']['kind'],
): PreflightInvocationMode {
  switch (behavior) {
    case 'REAL': return 'REAL';
    case 'RETURN': return 'MOCKED';
    case 'ERROR':
    case 'DELAY':
    case 'TIMEOUT': return 'FAULT';
    case 'REPLAY': return 'REPLAY';
    case 'OBSERVE': return 'OBSERVE';
    case 'MUST_NOT_CALL': return 'DENIED';
  }
}

function addUnsupportedReason(
  reasons: Map<PreflightRiskReasonCode, { severity: PreflightRiskSeverity; count: number }>,
  dependency: DependencyBehaviorDraft,
): void {
  if (!transientRepresentable(dependency)) {
    addReason(reasons, 'TRANSIENT_RUNTIME_UNSUPPORTED', 'BLOCKING', 1);
  }
}

function transientRepresentable(dependency: DependencyBehaviorDraft): boolean {
  return (dependency.behavior.kind === 'REAL' || dependency.behavior.kind === 'RETURN')
    && dependency.behavior.boundary === 'NODE'
    && dependency.selector.nodeId.length > 0
    && dependency.selector.operatorRef.length === 0
    && dependency.selector.resourceRef.length === 0
    && dependency.selector.functionRef.length === 0
    && dependency.selector.attempts.length === 0
    && dependency.selector.occurrences.length === 0
    && dependency.selector.correlationKey.length === 0
    && Object.keys(dependency.selector.pathEquals).length === 0;
}

function fallbackToReal(dependency: DependencyBehaviorDraft): boolean {
  return dependency.consumption.onExhausted === 'FALLBACK_TO_REAL'
    || dependency.consumption.onUnmatched === 'ALLOW_REAL';
}

function emptyInvocationCounts(): ScenarioRunPreflightProjection['counts'] {
  return {
    invocations: 0,
    subjectReal: 0,
    real: 0,
    mocked: 0,
    fault: 0,
    replay: 0,
    observe: 0,
    denied: 0,
    fallbackToReal: 0,
    missingOracle: 0,
  };
}

function invocationCountKey(
  mode: PreflightInvocationMode,
): PreflightInvocationCountKey {
  return mode.toLocaleLowerCase() as PreflightInvocationCountKey;
}

function addInvocationGroup(
  groups: Map<string, PreflightInvocationGroup>,
  group: PreflightInvocationGroup,
): void {
  const key = [
    group.mode,
    group.nodeId,
    group.operatorRef,
    group.source,
    group.behaviorKind,
    group.fallbackToReal,
  ].join('\u0000');
  const current = groups.get(key);
  groups.set(key, current ? { ...current, caseCount: current.caseCount + 1 } : group);
}

function addReason(
  reasons: Map<PreflightRiskReasonCode, { severity: PreflightRiskSeverity; count: number }>,
  code: PreflightRiskReasonCode,
  severity: PreflightRiskSeverity,
  count: number,
): void {
  const current = reasons.get(code);
  reasons.set(code, {
    severity: current?.severity === 'BLOCKING' ? 'BLOCKING' : severity,
    count: (current?.count ?? 0) + count,
  });
}

function reasonMessage(code: PreflightRiskReasonCode, count: number): ProductMessageDescriptor {
  const messageIds: Record<PreflightRiskReasonCode, ProductMessageId> = {
    EMPTY_SELECTION: 'correctness.preflight.reason.emptySelection',
    UNKNOWN_CASE_SELECTION: 'correctness.preflight.reason.unknownCaseSelection',
    PRODUCTION_ENVIRONMENT: 'correctness.preflight.reason.productionEnvironment',
    TARGET_WRITE_EFFECT: 'correctness.preflight.reason.targetWriteEffect',
    TARGET_EFFECT_UNKNOWN: 'correctness.preflight.reason.targetEffectUnknown',
    REAL_DEPENDENCY: 'correctness.preflight.reason.realDependency',
    OBSERVED_REAL_DEPENDENCY: 'correctness.preflight.reason.observedRealDependency',
    REAL_FALLBACK: 'correctness.preflight.reason.realFallback',
    MISSING_ORACLE: 'correctness.preflight.reason.missingOracle',
    TRANSIENT_RUNTIME_UNSUPPORTED: 'correctness.preflight.reason.transientRuntimeUnsupported',
    UNRESOLVED_DEPENDENCY: 'correctness.preflight.reason.unresolvedDependency',
  };
  return { messageId: messageIds[code], params: { count } };
}

function compareInvocationGroups(
  left: PreflightInvocationGroup,
  right: PreflightInvocationGroup,
): number {
  const priority: Record<PreflightInvocationMode, number> = {
    REAL: 0,
    OBSERVE: 1,
    FAULT: 2,
    REPLAY: 3,
    DENIED: 4,
    MOCKED: 5,
  };
  return priority[left.mode] - priority[right.mode]
    || left.nodeId.localeCompare(right.nodeId)
    || left.operatorRef.localeCompare(right.operatorRef);
}

function unique(values: string[]): string[] {
  return [...new Set(values.map((value) => value.trim()).filter(Boolean))];
}
