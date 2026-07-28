import type { SimulationResponse } from '../types';
import type { ScenarioComparison } from './scenarioAuthoring';

export type EvidenceTone = 'success' | 'danger' | 'warning' | 'pending';
export type EvidenceDimensionKey = 'execution' | 'assertions' | 'contract' | 'governance';
export type EvidenceDimensionState = 'passed' | 'failed' | 'warning' | 'pending' | 'not-checked';

export interface ScenarioEvidenceDiagnostic {
  id: string;
  severity: string;
  scope: string;
  code: string;
  message: string;
  coordinate?: string;
  nodeId?: string;
}

export interface ScenarioEvidenceTrustContext {
  contractStatus: string;
  governanceStatus: string;
  diagnostics?: ScenarioEvidenceDiagnostic[];
}

export interface EvidenceDimension {
  key: EvidenceDimensionKey;
  label: string;
  status: string;
  state: EvidenceDimensionState;
  detail: string;
}

export interface EvidenceIssue {
  id: string;
  severity: 'blocking' | 'warning';
  scope: string;
  code: string;
  message: string;
  coordinate: string;
  nodeId: string;
  diagnostic?: ScenarioEvidenceDiagnostic;
}

export interface ScenarioEvidenceView {
  headline: string;
  summary: string;
  tone: EvidenceTone;
  dimensions: EvidenceDimension[];
  blockers: EvidenceIssue[];
  warnings: EvidenceIssue[];
  failedAssertions: ScenarioComparison['results'];
  passedAssertions: ScenarioComparison['results'];
}

const DEFAULT_TRUST_CONTEXT: ScenarioEvidenceTrustContext = {
  contractStatus: 'NOT CHECKED',
  governanceStatus: 'NOT CHECKED',
  diagnostics: [],
};

/**
 * Produces the fail-closed trust projection shared by Graph and Operator Scenario workspaces.
 *
 * A green assertion alone is deliberately insufficient: all four dimensions must pass and no
 * blocking or warning diagnostic may remain before the view can claim promotion readiness.
 */
export function scenarioEvidenceView(
  response: SimulationResponse | null,
  comparison: ScenarioComparison | null,
  trustContext: ScenarioEvidenceTrustContext = DEFAULT_TRUST_CONTEXT,
): ScenarioEvidenceView {
  const dimensions = [
    executionDimension(response),
    assertionDimension(comparison),
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
  const diagnosticIssues = diagnosticEvidence(response, comparison, trustContext.diagnostics ?? []);
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
        summary: `${blockers.length} blocking finding${blockers.length === 1 ? '' : 's'} must be resolved.`,
        tone: 'danger' as const,
      }
    : warnings.length > 0
      ? {
          headline: 'Review required',
          summary: `${warnings.length} warning${warnings.length === 1 ? '' : 's'} needs an explicit decision.`,
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
            summary: 'Execution, assertions, Contract, and Governance all passed.',
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
      ? `${response.mockedNodeIds.length} mocked and ${response.realNodeIds.length} real nodes completed.`
      : response.errors[0] ?? 'Execution did not produce conforming terminal output.',
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
      ? `${comparison.results.length} assertion${comparison.results.length === 1 ? '' : 's'} passed.`
      : `${failed}/${comparison.results.length} assertions failed.`,
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
    ? `${label} check passed for the current revision.`
    : state === 'failed'
      ? `${label} currently blocks promotion.`
      : state === 'warning'
        ? `${label} requires explicit review.`
        : state === 'pending'
          ? `${label} check is still running.`
          : uncheckedDetail;
  return dimension(key, label, normalized, state, detail);
}

function externalState(status: string): EvidenceDimensionState {
  if (/(BLOCK|DENY|REJECT|FAIL|INVALID|ERROR)/.test(status)) return 'failed';
  if (/(WARN|REVIEW|STALE|PARTIAL|CONDITIONAL|EXPIRED|UNVERIFIABLE|MISSING)/.test(status)) {
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
): EvidenceDimension {
  return { key, label, status, state, detail };
}

function dimensionIssue(dimension: EvidenceDimension): EvidenceIssue {
  return {
    id: `dimension:${dimension.key}`,
    severity: dimension.state === 'failed' ? 'blocking' : 'warning',
    scope: dimension.key.toUpperCase(),
    code: `${dimension.key.toUpperCase()}_${dimension.status.replace(/\W+/g, '_')}`,
    message: dimension.detail,
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
  const seen = new Set<string>();
  return issues.filter((issue) => {
    const key = `${issue.scope}:${issue.code}:${issue.message}:${issue.coordinate}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
