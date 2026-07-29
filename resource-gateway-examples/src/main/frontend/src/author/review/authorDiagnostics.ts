import type {
  GovernanceGateView,
  SimulationResponse,
  VisualDiagnostic,
  VisualValidationResult,
} from '../../types';
import type { SimulationTableCaseResult } from '../../draftModel';
import type { EffectiveContractProjection } from '../contract/effectiveContractProjection';

export type AuthorDiagnosticSeverity = 'BLOCKING' | 'ERROR' | 'WARNING' | 'INFO';
export type AuthorDiagnosticScope =
  | 'GRAPH'
  | 'CONTRACT'
  | 'NODE'
  | 'EDGE'
  | 'SCENARIO'
  | 'RUN'
  | 'GOVERNANCE'
  | 'DSL';

export interface AuthorDiagnosticItem {
  id: string;
  severity: AuthorDiagnosticSeverity;
  scope: AuthorDiagnosticScope;
  source: string;
  code: string;
  message: string;
  coordinate: string;
  coordinates: string[];
  nodeId: string;
  recommendedAction: string;
  occurrenceCount: number;
}

export interface AuthorDiagnosticsInput {
  error: string;
  validation: VisualValidationResult | null;
  run: SimulationResponse | null;
  scenarioResults: Record<string, SimulationTableCaseResult>;
  governance: GovernanceGateView | null;
  dslDiagnostics: VisualDiagnostic[];
  effectiveContract?: EffectiveContractProjection | null;
  readinessReasons?: Array<{
    code: string;
    dimension: string;
    message: string;
    action: { label: string };
  }>;
}

function severityOf(level: string | undefined): AuthorDiagnosticSeverity {
  const normalized = level?.trim().toUpperCase();
  if (normalized === 'BLOCKING' || normalized === 'BLOCKED' || normalized === 'FATAL') {
    return 'BLOCKING';
  }
  if (normalized === 'ERROR' || normalized === 'FAILED' || normalized === 'FAILURE') {
    return 'ERROR';
  }
  if (normalized === 'WARNING' || normalized === 'WARN') {
    return 'WARNING';
  }
  return 'INFO';
}

function nodeIdFromCoordinate(coordinate: string): string {
  const pointerMatch = coordinate.match(/\/nodes\/([^/]+)/);
  if (pointerMatch) {
    return decodeURIComponent(pointerMatch[1].replace(/~1/g, '/').replace(/~0/g, '~'));
  }
  const nodeMatch = coordinate.match(/(?:^|[.:/])node(?:Id)?[=:/.]([A-Za-z0-9_.:-]+)/i);
  return nodeMatch?.[1] ?? '';
}

function visualDiagnostic(
  diagnostic: VisualDiagnostic,
  index: number,
  scope: AuthorDiagnosticScope,
  source: string,
): AuthorDiagnosticItem {
  const coordinate = diagnostic.target?.trim() || (
    [diagnostic.line, diagnostic.column].some((value) => value !== undefined)
      ? `line ${diagnostic.line ?? '?'}:${diagnostic.column ?? '?'}`
      : ''
  );
  const metadataNodeId = typeof diagnostic.metadata?.nodeId === 'string'
    ? diagnostic.metadata.nodeId
    : '';
  const message = diagnostic.message || 'No diagnostic message.';
  const messageNodeId = message.match(/\bPath\s+['"`]([^.'"`]+)\./i)?.[1] ?? '';
  return {
    id: `${source}:${diagnostic.code || index}:${index}`,
    severity: severityOf(diagnostic.level),
    scope,
    source,
    code: diagnostic.code || 'DIAGNOSTIC',
    message,
    coordinate,
    coordinates: coordinate ? [coordinate] : [],
    nodeId: metadataNodeId || nodeIdFromCoordinate(coordinate) || messageNodeId,
    recommendedAction: '',
    occurrenceCount: 1,
  };
}

/**
 * Projects scattered protocol results into one ordered, scope-aware review queue.
 *
 * This is intentionally a projection only: it never mutates or reinterprets the authoritative
 * GraphDraft, run, Contract, or governance records.
 */
export function projectAuthorDiagnostics(input: AuthorDiagnosticsInput): AuthorDiagnosticItem[] {
  const items: AuthorDiagnosticItem[] = [];
  if (input.error.trim()) {
    items.push({
      id: 'client:error',
      severity: 'ERROR',
      scope: 'RUN',
      source: 'client',
      code: 'REQUEST_FAILED',
      message: input.error.trim(),
      coordinate: '',
      coordinates: [],
      nodeId: '',
      recommendedAction: 'Review the request and retry.',
      occurrenceCount: 1,
    });
  }
  input.validation?.diagnostics.forEach((diagnostic, index) => {
    items.push(visualDiagnostic(diagnostic, index, 'CONTRACT', 'graph-validation'));
  });
  input.run?.diagnostics.forEach((diagnostic, index) => {
    items.push(visualDiagnostic(diagnostic, index, 'RUN', 'runtime'));
  });
  input.run?.errors.forEach((message, index) => {
    items.push({
      id: `runtime:error:${index}`,
      severity: 'ERROR',
      scope: 'RUN',
      source: 'runtime',
      code: 'RUN_FAILED',
      message,
      coordinate: '',
      coordinates: [],
      nodeId: '',
      recommendedAction: 'Inspect the failed trace and rerun the same Scenario.',
      occurrenceCount: 1,
    });
  });
  Object.values(input.scenarioResults)
    .filter((result) => result.status === 'failed')
    .forEach((result) => {
      items.push({
        id: `scenario:${result.id}`,
        severity: 'ERROR',
        scope: 'SCENARIO',
        source: 'assertion',
        code: 'ASSERTION_FAILED',
        message: `${result.name}: ${result.detail}`,
        coordinate: result.id,
        coordinates: [result.id],
        nodeId: '',
        recommendedAction: 'Open Test and compare expected with actual output.',
        occurrenceCount: 1,
      });
    });
  input.governance?.result?.issues.forEach((issue) => {
    const coordinate = issue.targetPath || issue.deepLink || issue.issueId;
    items.push({
      id: `governance:${issue.issueId}`,
      severity: severityOf(issue.severity),
      scope: 'GOVERNANCE',
      source: 'governance',
      code: issue.code || issue.issueId,
      message: issue.message,
      coordinate,
      coordinates: coordinate ? [coordinate] : [],
      nodeId: nodeIdFromCoordinate(coordinate),
      recommendedAction: issue.recommendedAction || '',
      occurrenceCount: 1,
    });
  });
  input.dslDiagnostics.forEach((diagnostic, index) => {
    items.push(visualDiagnostic(diagnostic, index, 'DSL', 'dsl-import'));
  });
  input.effectiveContract?.conflicts.forEach((conflict) => {
    const coordinate = `/nodes/${input.effectiveContract?.target.nodeId}/contract/${conflict.path}`;
    items.push({
      id: `effective-contract:${conflict.code}:${conflict.path}`,
      severity: 'ERROR',
      scope: 'CONTRACT',
      source: 'effective-contract',
      code: `EFFECTIVE_CONTRACT_${conflict.code}`,
      message: conflict.message,
      coordinate,
      coordinates: [coordinate],
      nodeId: input.effectiveContract?.target.nodeId ?? '',
      recommendedAction: conflict.code === 'MULTIPLE_SOURCES'
        ? 'Keep one authoritative source for this target field.'
        : 'Align the bound source type with the declared target type.',
      occurrenceCount: 1,
    });
  });
  input.readinessReasons
    ?.filter((reason) => /CONFLICTED|DIRTY|STALE/.test(reason.code))
    .forEach((reason) => {
      const scope = reason.dimension === 'DRAFT'
        ? 'GRAPH'
        : reason.dimension === 'ASSERTIONS'
          ? 'SCENARIO'
          : reason.dimension as AuthorDiagnosticScope;
      items.push({
        id: `readiness:${reason.code}`,
        severity: 'ERROR',
        scope,
        source: 'readiness',
        code: reason.code,
        message: reason.message,
        coordinate: '',
        coordinates: [],
        nodeId: '',
        recommendedAction: reason.action.label,
        occurrenceCount: 1,
      });
    });

  const rank: Record<AuthorDiagnosticSeverity, number> = {
    BLOCKING: 0,
    ERROR: 1,
    WARNING: 2,
    INFO: 3,
  };
  const grouped = new Map<string, AuthorDiagnosticItem>();
  items.forEach((item) => {
    const key = [
      item.severity,
      item.scope,
      item.source,
      item.code,
      item.message,
      item.nodeId,
      item.recommendedAction,
    ].join('\u001f');
    const existing = grouped.get(key);
    if (!existing) {
      grouped.set(key, item);
      return;
    }
    existing.occurrenceCount += item.occurrenceCount;
    item.coordinates.forEach((coordinate) => {
      if (!existing.coordinates.includes(coordinate)) {
        existing.coordinates.push(coordinate);
      }
    });
  });
  return [...grouped.values()].sort(
    (left, right) => rank[left.severity] - rank[right.severity],
  );
}
