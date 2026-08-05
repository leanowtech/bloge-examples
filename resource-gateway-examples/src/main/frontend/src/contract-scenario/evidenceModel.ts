import type { SimulationResponse } from '../types';
import type { TranslationValues } from '../i18n/i18n';
import type { ScenarioComparison } from './scenarioAuthoring';

export type EvidenceTone = 'success' | 'danger' | 'warning' | 'pending';
export type EvidenceDimensionKey = 'draft' | 'execution' | 'assertions' | 'contract' | 'governance';
export type EvidenceDimensionState = 'passed' | 'failed' | 'warning' | 'pending' | 'not-checked';

export interface ScenarioEvidenceDiagnostic {
  id: string;
  severity: string;
  scope: string;
  code: string;
  message: string;
  coordinate?: string;
  nodeId?: string;
  recommendedAction?: string;
  deepLink?: string;
  requiredRole?: string;
  owner?: string;
  auditRequirement?: string;
  expiresAt?: string;
}

export interface ScenarioEvidenceTrustContext {
  draftStatus?: string;
  evidenceFreshness?: 'CURRENT' | 'STALE';
  contractStatus: string;
  governanceStatus: string;
  coordinate?: {
    targetKind?: 'GRAPH' | 'OPERATOR';
    targetId?: string;
    targetRevision?: number;
    draftId: string;
    draftRevision: number;
    draftFingerprint: string;
    contractFingerprint: string;
    scenarioId: string;
    scenarioRevision: number;
    scenarioFingerprint: string;
    closureFingerprint: string;
    requestFingerprint: string;
    editorSnapshotFingerprint?: string;
    compiledPlanSourceFingerprint?: string;
    requestSourceFingerprint?: string;
    evidenceSourceFingerprint?: string;
  };
  diagnostics?: ScenarioEvidenceDiagnostic[];
}

export interface EvidenceDimension {
  key: EvidenceDimensionKey;
  label: string;
  status: string;
  state: EvidenceDimensionState;
  detail: string;
  detailValues?: TranslationValues;
}

export interface EvidenceIssue {
  id: string;
  severity: 'blocking' | 'warning';
  scope: string;
  code: string;
  message: string;
  messageValues?: TranslationValues;
  coordinate: string;
  nodeId: string;
  occurrences?: number;
  diagnostic?: ScenarioEvidenceDiagnostic;
}

export interface ScenarioEvidenceView {
  headline: string;
  summary: string;
  summaryValues?: TranslationValues;
  tone: EvidenceTone;
  dimensions: EvidenceDimension[];
  blockers: EvidenceIssue[];
  warnings: EvidenceIssue[];
  failedAssertions: ScenarioComparison['results'];
  passedAssertions: ScenarioComparison['results'];
}

export interface AssertionDiffRow {
  path: string;
  expected: unknown;
  actual: unknown;
}

const DEFAULT_TRUST_CONTEXT: ScenarioEvidenceTrustContext = {
  draftStatus: 'SAVED',
  evidenceFreshness: 'CURRENT',
  contractStatus: 'NOT CHECKED',
  governanceStatus: 'NOT CHECKED',
  diagnostics: [],
};

/**
 * Produces the fail-closed trust projection shared by Graph and Operator Scenario workspaces.
 *
 * A green assertion alone is deliberately insufficient: all five dimensions must pass and no
 * blocking or warning diagnostic may remain before the view can claim promotion readiness.
 */
export function scenarioEvidenceView(
  response: SimulationResponse | null,
  comparison: ScenarioComparison | null,
  trustContext: ScenarioEvidenceTrustContext = DEFAULT_TRUST_CONTEXT,
): ScenarioEvidenceView {
  const evidenceStale = trustContext.evidenceFreshness === 'STALE';
  const dimensions = [
    draftDimension(trustContext.draftStatus || 'SAVED'),
    withEvidenceFreshness(executionDimension(response), evidenceStale),
    withEvidenceFreshness(assertionDimension(comparison), evidenceStale),
    externalDimension(
      'contract',
      'Contract',
      trustContext.contractStatus || 'NOT CHECKED',
      'Validate the exact Graph or Operator Contract revision.',
    ),
    externalDimension(
      'governance',
      'Governance',
      trustContext.governanceStatus || 'NOT CHECKED',
      'Obtain a current publish-gate decision for this exact revision.',
    ),
  ];
  const diagnosticIssues = [
    ...diagnosticEvidence(response, comparison, trustContext.diagnostics ?? []),
    ...fingerprintClosureEvidence(trustContext),
  ];
  const blockers = uniqueIssues([
    ...dimensions
      .filter((dimension) => dimension.state === 'failed')
      .map(dimensionIssue),
    ...diagnosticIssues.filter((issue) => issue.severity === 'blocking'),
  ]);
  const warnings = uniqueIssues([
    ...dimensions
      .filter((dimension) => dimension.state === 'warning')
      .map(dimensionIssue),
    ...diagnosticIssues.filter((issue) => issue.severity === 'warning'),
  ]);
  const incomplete = dimensions.some((dimension) => (
    dimension.state === 'pending' || dimension.state === 'not-checked'
  ));
  const allPassed = dimensions.every((dimension) => dimension.state === 'passed');

  const outcome = blockers.length > 0
    ? {
        headline: 'Promotion blocked',
        summary: '{count} blocking findings must be resolved.',
        summaryValues: { count: blockers.length },
        tone: 'danger' as const,
      }
    : warnings.length > 0
      ? {
          headline: 'Review required',
          summary: '{count} warnings need an explicit decision.',
          summaryValues: { count: warnings.length },
          tone: 'warning' as const,
        }
      : incomplete || !allPassed
        ? {
            headline: 'Evidence incomplete',
            summary: 'Run every trust dimension before using this result as promotion evidence.',
            tone: 'pending' as const,
          }
        : {
            headline: 'Ready for promotion',
            summary: 'Draft, execution, assertions, Contract, and Governance all passed.',
            tone: 'success' as const,
          };

  return {
    ...outcome,
    dimensions,
    blockers,
    warnings,
    failedAssertions: comparison?.results.filter((result) => !result.passed) ?? [],
    passedAssertions: comparison?.results.filter((result) => result.passed) ?? [],
  };
}

/** Produces bounded, path-level differences for a failed business assertion. */
export function scenarioAssertionDiff(
  expected: unknown,
  actual: unknown,
  rootPath = '$',
  limit = 24,
): AssertionDiffRow[] {
  const rows: AssertionDiffRow[] = [];
  collectAssertionDiff(expected, actual, rootPath || '$', rows, Math.max(1, limit));
  return rows;
}

function draftDimension(status: string): EvidenceDimension {
  const normalized = status.trim().toUpperCase() || 'EPHEMERAL';
  if (normalized === 'SAVED') {
    return dimension('draft', 'Draft', normalized, 'passed', 'Evidence targets a saved revision.');
  }
  if (normalized === 'DIRTY' || normalized === 'CONFLICTED') {
    return dimension(
      'draft',
      'Draft',
      normalized,
      'failed',
      normalized === 'DIRTY'
        ? 'Save the current graph before promotion.'
        : 'Resolve the concurrent save conflict before promotion.',
    );
  }
  return dimension(
    'draft',
    'Draft',
    normalized,
    'warning',
    'Exploratory evidence is not attached to a durable draft revision.',
  );
}

function withEvidenceFreshness(
  source: EvidenceDimension,
  stale: boolean,
): EvidenceDimension {
  if (!stale || (source.key !== 'execution' && source.key !== 'assertions')) {
    return source;
  }
  return {
    ...source,
    status: 'STALE',
    state: 'failed',
    detail: 'Retained {label} evidence targets an older authoring snapshot.',
    detailValues: { label: source.label },
  };
}

function executionDimension(response: SimulationResponse | null): EvidenceDimension {
  if (!response) {
    return dimension('execution', 'Execution', 'NOT RUN', 'not-checked', 'Run the selected Scenario.');
  }
  const passed = response.validated
    && response.compiled
    && response.success
    && response.terminalOutputConforms
    && response.errors.length === 0;
  return dimension(
    'execution',
    'Execution',
    passed ? 'PASSED' : 'FAILED',
    passed ? 'passed' : 'failed',
    passed
      ? '{mocked} mocked and {real} real nodes completed.'
      : response.errors[0] ?? 'Execution did not produce conforming terminal output.',
    passed ? { mocked: response.mockedNodeIds.length, real: response.realNodeIds.length } : undefined,
  );
}

function assertionDimension(comparison: ScenarioComparison | null): EvidenceDimension {
  if (!comparison) {
    return dimension('assertions', 'Assertions', 'NOT RUN', 'not-checked', 'Run and compare the Scenario.');
  }
  if (comparison.results.length === 0) {
    return dimension(
      'assertions',
      'Assertions',
      'NOT CONFIGURED',
      'warning',
      'Add at least one business assertion before promotion.',
    );
  }
  const failed = comparison.results.filter((result) => !result.passed).length;
  return dimension(
    'assertions',
    'Assertions',
    failed === 0 && comparison.passed ? 'PASSED' : 'FAILED',
    failed === 0 && comparison.passed ? 'passed' : 'failed',
    failed === 0
      ? comparison.results.length === 1
        ? '{count} assertion passed.'
        : '{count} assertions passed.'
      : '{failed}/{total} assertions failed.',
    failed === 0
      ? { count: comparison.results.length }
      : { failed, total: comparison.results.length },
  );
}

function externalDimension(
  key: Extract<EvidenceDimensionKey, 'contract' | 'governance'>,
  label: string,
  status: string,
  uncheckedDetail: string,
): EvidenceDimension {
  const normalized = status.trim().toUpperCase() || 'NOT CHECKED';
  const state = externalState(normalized);
  const detail = state === 'passed'
    ? '{label} check passed for the current revision.'
    : state === 'failed'
      ? '{label} currently blocks promotion.'
      : state === 'warning'
        ? '{label} requires explicit review.'
        : state === 'pending'
          ? '{label} check is still running.'
          : uncheckedDetail;
  return dimension(
    key,
    label,
    normalized,
    state,
    detail,
    state === 'not-checked' ? undefined : { label },
  );
}

function externalState(status: string): EvidenceDimensionState {
  if (/(BLOCK|DENY|REJECT|FAIL|INVALID|ERROR|STALE)/.test(status)) return 'failed';
  if (/(WARN|REVIEW|PARTIAL|CONDITIONAL|EXPIRED|UNVERIFIABLE|MISSING)/.test(status)) {
    return 'warning';
  }
  if (/(RUNNING|CHECKING|PENDING|LOADING)/.test(status)) return 'pending';
  if (/(PASS|VALID|APPROV|ALLOW|CURRENT|SUCCESS|READY)/.test(status)) return 'passed';
  return 'not-checked';
}

function dimension(
  key: EvidenceDimensionKey,
  label: string,
  status: string,
  state: EvidenceDimensionState,
  detail: string,
  detailValues?: TranslationValues,
): EvidenceDimension {
  return { key, label, status, state, detail, detailValues };
}

function dimensionIssue(dimension: EvidenceDimension): EvidenceIssue {
  return {
    id: `dimension:${dimension.key}`,
    severity: dimension.state === 'failed' ? 'blocking' : 'warning',
    scope: dimension.key.toUpperCase(),
    code: `${dimension.key.toUpperCase()}_${dimension.status.replace(/\W+/g, '_')}`,
    message: dimension.detail,
    messageValues: dimension.detailValues,
    coordinate: '',
    nodeId: '',
  };
}

function diagnosticEvidence(
  response: SimulationResponse | null,
  comparison: ScenarioComparison | null,
  external: ScenarioEvidenceDiagnostic[],
): EvidenceIssue[] {
  const issues: EvidenceIssue[] = [];
  response?.errors.forEach((message, index) => {
    issues.push({
      id: `run-error:${index}`,
      severity: 'blocking',
      scope: 'RUN',
      code: 'RUN_FAILED',
      message,
      coordinate: '',
      nodeId: '',
    });
  });
  response?.diagnostics.forEach((diagnostic, index) => {
    const severity = issueSeverity(diagnostic.level);
    if (severity) {
      issues.push({
        id: `run-diagnostic:${diagnostic.code}:${index}`,
        severity,
        scope: 'RUN',
        code: diagnostic.code || 'RUN_DIAGNOSTIC',
        message: diagnostic.message || 'Runtime diagnostic did not include a message.',
        coordinate: diagnostic.target ?? '',
        nodeId: '',
      });
    }
  });
  comparison?.diagnostics.forEach((diagnostic, index) => {
    issues.push({
      id: `comparison:${diagnostic.code}:${index}`,
      severity: diagnostic.level === 'ERROR' ? 'blocking' : 'warning',
      scope: 'ASSERTIONS',
      code: diagnostic.code,
      message: diagnostic.message,
      coordinate: diagnostic.target,
      nodeId: '',
    });
  });
  external.forEach((diagnostic) => {
    const severity = issueSeverity(diagnostic.severity);
    if (severity) {
      issues.push({
        id: diagnostic.id,
        severity,
        scope: diagnostic.scope,
        code: diagnostic.code,
        message: diagnostic.message,
        coordinate: diagnostic.coordinate ?? '',
        nodeId: diagnostic.nodeId ?? '',
        diagnostic,
      });
    }
  });
  return issues;
}

function issueSeverity(level: string | undefined): EvidenceIssue['severity'] | null {
  const normalized = level?.trim().toUpperCase() ?? '';
  if (/(BLOCK|ERROR|FAIL|FATAL)/.test(normalized)) return 'blocking';
  if (/(WARN|REVIEW)/.test(normalized)) return 'warning';
  return null;
}

function uniqueIssues(issues: EvidenceIssue[]): EvidenceIssue[] {
  const grouped = new Map<string, EvidenceIssue>();
  issues.forEach((issue) => {
    const key = `${issue.scope}:${issue.code}:${issue.message}:${issue.coordinate}`;
    const existing = grouped.get(key);
    if (existing) {
      existing.occurrences = (existing.occurrences ?? 1) + 1;
      return;
    }
    grouped.set(key, { ...issue, occurrences: issue.occurrences ?? 1 });
  });
  return Array.from(grouped.values());
}

function fingerprintClosureEvidence(
  trustContext: ScenarioEvidenceTrustContext,
): EvidenceIssue[] {
  const coordinate = trustContext.coordinate;
  if (!coordinate) {
    return [];
  }
  const sourceFingerprints = [
    coordinate.editorSnapshotFingerprint,
    coordinate.compiledPlanSourceFingerprint,
    coordinate.requestSourceFingerprint,
    coordinate.evidenceSourceFingerprint,
  ];
  if (sourceFingerprints.every((fingerprint) => fingerprint === undefined)) {
    return [];
  }
  const complete = sourceFingerprints.every((fingerprint) => Boolean(fingerprint));
  const consistent = complete && new Set(sourceFingerprints).size === 1;
  if (consistent) {
    return [];
  }
  return [{
    id: 'scenario-fingerprint-closure',
    severity: 'blocking',
    scope: 'SCENARIO',
    code: 'SCENARIO_FINGERPRINT_CLOSURE_MISMATCH',
    message: 'Visible editor state, compiled plan, execution request, and retained evidence do not share one source fingerprint.',
    coordinate: `/scenarios/${coordinate.scenarioId}`,
    nodeId: '',
    occurrences: 1,
  }];
}

function collectAssertionDiff(
  expected: unknown,
  actual: unknown,
  path: string,
  rows: AssertionDiffRow[],
  limit: number,
): void {
  if (rows.length >= limit || Object.is(expected, actual)) {
    return;
  }
  if (isRecord(expected) && isRecord(actual)) {
    const keys = Array.from(new Set([...Object.keys(expected), ...Object.keys(actual)])).sort();
    keys.forEach((key) => {
      if (rows.length < limit) {
        collectAssertionDiff(expected[key], actual[key], `${path}.${key}`, rows, limit);
      }
    });
    return;
  }
  if (Array.isArray(expected) && Array.isArray(actual)) {
    const length = Math.max(expected.length, actual.length);
    for (let index = 0; index < length && rows.length < limit; index += 1) {
      collectAssertionDiff(expected[index], actual[index], `${path}[${index}]`, rows, limit);
    }
    return;
  }
  rows.push({ path, expected, actual });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}
