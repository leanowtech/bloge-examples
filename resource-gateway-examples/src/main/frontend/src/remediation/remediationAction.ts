import type {
  EvidenceDimension,
  EvidenceIssue,
  ScenarioEvidenceTrustContext,
  ScenarioEvidenceView,
} from '../contract-scenario/evidenceModel';

export type RemediationSource =
  | 'CONTRACT_WARNING'
  | 'SCENARIO_COMPILE'
  | 'RUN_FAILURE'
  | 'EVIDENCE_STALE'
  | 'RUNTIME_DRIFT'
  | 'REHEARSAL_TIMEOUT'
  | 'ANEKE_GATE_BLOCKER';

export type RemediationActionKind =
  | 'SAVE_DRAFT'
  | 'RUN_SCENARIO'
  | 'EDIT_ASSERTIONS'
  | 'VALIDATE_CONTRACT'
  | 'OPEN_DIAGNOSTIC'
  | 'REQUEST_GOVERNANCE'
  | 'REVIEW_RUNTIME_DRIFT'
  | 'RETRY_REHEARSAL';

export type RemediationNavigation =
  | 'SCENARIOS'
  | 'INTERFACE'
  | 'COMPOSE'
  | 'DIAGNOSTIC'
  | 'EXTERNAL'
  | 'UNAVAILABLE';

export interface RemediationTarget {
  kind: 'GRAPH_DRAFT' | 'OPERATOR' | 'FUNCTION' | 'SCENARIO' | 'RUN' | 'REHEARSAL';
  id: string;
  label: string;
  draftId?: string;
  revision?: number;
  nodeId?: string;
  scenarioId?: string;
  runId?: string;
}

/**
 * Stable user-action projection shared by Evidence, Rehearsal, and lifecycle surfaces.
 *
 * Machine diagnostics remain attached as technical coordinates; the first-class fields describe
 * why a person should care, who can act, and where the exact repair context lives.
 */
export interface RemediationAction {
  id: string;
  source: RemediationSource;
  severity: 'BLOCKING' | 'WARNING' | 'INFO';
  target: RemediationTarget;
  rootCause: string;
  businessImpact: string;
  actionKind: RemediationActionKind;
  actionLabel: string;
  deepLink: string;
  navigation: RemediationNavigation;
  requiredRole: string;
  owner: string;
  auditRequirement: string;
  expiresAt: string;
  available: boolean;
  unavailableReason: string;
  diagnosticId: string;
  technicalCode: string;
  technicalCoordinate: string;
}

export function evidenceRemediationActions(
  evidence: ScenarioEvidenceView,
  trustContext: ScenarioEvidenceTrustContext = {
    contractStatus: 'NOT CHECKED',
    governanceStatus: 'NOT CHECKED',
  },
  currentHref = 'http://localhost/author/',
): RemediationAction[] {
  const target = evidenceTarget(trustContext);
  const issues = [...evidence.blockers, ...evidence.warnings];
  const actions: RemediationAction[] = [];

  issues.forEach((issue) => actions.push(actionForIssue(issue, target, trustContext, currentHref)));
  evidence.dimensions
    .filter((dimension) => dimension.state === 'pending' || dimension.state === 'not-checked')
    .forEach((dimension) => actions.push(actionForIncompleteDimension(
      dimension,
      target,
      trustContext,
      currentHref,
    )));

  return uniqueActions(actions);
}

function actionForIssue(
  issue: EvidenceIssue,
  target: RemediationTarget,
  trustContext: ScenarioEvidenceTrustContext,
  currentHref: string,
): RemediationAction {
  const normalizedScope = issue.scope.toUpperCase();
  const normalizedCode = issue.code.toUpperCase();
  const severity = issue.severity === 'blocking' ? 'BLOCKING' : 'WARNING';
  if (normalizedScope === 'GOVERNANCE') {
    const externalLink = issue.diagnostic?.deepLink?.trim() ?? '';
    const owner = issue.diagnostic?.owner?.trim() || 'Governance owner';
    return {
      ...baseAction(issue, target, severity),
      source: 'ANEKE_GATE_BLOCKER',
      rootCause: issue.message,
      businessImpact: 'This exact revision cannot pass the publish gate until governance accepts it.',
      actionKind: 'REQUEST_GOVERNANCE',
      actionLabel: issue.diagnostic?.recommendedAction?.trim() || 'Open governance decision',
      deepLink: externalLink,
      navigation: externalLink ? 'EXTERNAL' : issue.diagnostic ? 'DIAGNOSTIC' : 'UNAVAILABLE',
      requiredRole: issue.diagnostic?.requiredRole?.trim() || 'Governance reviewer',
      owner,
      auditRequirement: issue.diagnostic?.auditRequirement?.trim()
        || 'Approval must be retained against this exact draft revision.',
      expiresAt: issue.diagnostic?.expiresAt?.trim() || '',
      available: Boolean(externalLink || issue.diagnostic),
      unavailableReason: externalLink || issue.diagnostic
        ? ''
        : `No governed handoff link was supplied. Contact ${owner}.`,
    };
  }
  if (normalizedScope === 'CONTRACT') {
    return {
      ...baseAction(issue, target, severity),
      source: 'CONTRACT_WARNING',
      rootCause: issue.message,
      businessImpact: 'Input/output compatibility is not proven for the revision under review.',
      actionKind: 'VALIDATE_CONTRACT',
      actionLabel: 'Review current Contract',
      deepLink: authorDeepLink(currentHref, trustContext, 'contract'),
      navigation: 'INTERFACE',
      requiredRole: 'Contract author',
      owner: 'Contract owner',
      auditRequirement: 'Save the validated Contract fingerprint with the next run.',
      expiresAt: '',
      available: true,
      unavailableReason: '',
    };
  }
  if (normalizedScope === 'ASSERTIONS' || normalizedCode.includes('ASSERTION')) {
    return {
      ...baseAction(issue, target, severity),
      source: normalizedCode.includes('COMPILE') ? 'SCENARIO_COMPILE' : 'RUN_FAILURE',
      rootCause: issue.message,
      businessImpact: 'The observed business result does not satisfy the Scenario expectation.',
      actionKind: 'EDIT_ASSERTIONS',
      actionLabel: 'Repair Scenario assertions',
      deepLink: authorDeepLink(currentHref, trustContext, 'scenarios'),
      navigation: 'SCENARIOS',
      requiredRole: 'Scenario author',
      owner: 'Scenario owner',
      auditRequirement: 'Rerun the same Scenario and retain fresh evidence.',
      expiresAt: '',
      available: true,
      unavailableReason: '',
    };
  }
  if (normalizedCode.includes('STALE') || normalizedCode.includes('FINGERPRINT_CLOSURE')) {
    return {
      ...baseAction(issue, target, severity),
      source: 'EVIDENCE_STALE',
      rootCause: issue.message,
      businessImpact: 'The retained result cannot prove the behavior of the currently visible revision.',
      actionKind: 'RUN_SCENARIO',
      actionLabel: 'Run current Scenario',
      deepLink: authorDeepLink(currentHref, trustContext, 'scenarios'),
      navigation: 'SCENARIOS',
      requiredRole: 'Scenario runner',
      owner: 'Scenario owner',
      auditRequirement: 'Retain the new run against the current source fingerprint.',
      expiresAt: '',
      available: true,
      unavailableReason: '',
    };
  }
  if (normalizedScope === 'DRAFT') {
    return {
      ...baseAction(issue, target, severity),
      source: 'EVIDENCE_STALE',
      rootCause: issue.message,
      businessImpact: 'Evidence cannot be promoted while the authoring state is not a durable revision.',
      actionKind: 'SAVE_DRAFT',
      actionLabel: 'Save graph in Compose',
      deepLink: authorDeepLink(currentHref, trustContext, 'compose'),
      navigation: 'COMPOSE',
      requiredRole: 'Draft author',
      owner: 'Draft owner',
      auditRequirement: 'Save or resolve the draft before rerunning evidence.',
      expiresAt: '',
      available: true,
      unavailableReason: '',
    };
  }
  return {
    ...baseAction(issue, target, severity),
    source: normalizedCode.includes('COMPILE') ? 'SCENARIO_COMPILE' : 'RUN_FAILURE',
    rootCause: issue.message,
    businessImpact: 'The run cannot be used as correctness evidence until this failure is resolved.',
    actionKind: 'OPEN_DIAGNOSTIC',
    actionLabel: issue.diagnostic?.recommendedAction?.trim() || 'Open failure source',
    deepLink: authorDeepLink(currentHref, trustContext, 'evidence', issue.nodeId),
    navigation: issue.diagnostic ? 'DIAGNOSTIC' : 'SCENARIOS',
    requiredRole: 'Graph author',
    owner: 'Graph owner',
    auditRequirement: 'Rerun the exact Scenario after repair.',
    expiresAt: '',
    available: true,
    unavailableReason: '',
  };
}

function actionForIncompleteDimension(
  dimension: EvidenceDimension,
  target: RemediationTarget,
  trustContext: ScenarioEvidenceTrustContext,
  currentHref: string,
): RemediationAction {
  const issue: EvidenceIssue = {
    id: `incomplete:${dimension.key}`,
    severity: 'warning',
    scope: dimension.key.toUpperCase(),
    code: `${dimension.key.toUpperCase()}_${dimension.status.replace(/\W+/g, '_')}`,
    message: dimension.detail,
    coordinate: '',
    nodeId: '',
  };
  if (dimension.key === 'contract') {
    return actionForIssue(issue, target, trustContext, currentHref);
  }
  if (dimension.key === 'governance') {
    return {
      ...baseAction(issue, target, 'WARNING'),
      source: 'ANEKE_GATE_BLOCKER',
      rootCause: dimension.detail,
      businessImpact: 'Promotion readiness is unknown until a reviewer evaluates this exact revision.',
      actionKind: 'REQUEST_GOVERNANCE',
      actionLabel: 'Request governance review',
      deepLink: '',
      navigation: 'UNAVAILABLE',
      requiredRole: 'Governance reviewer',
      owner: 'Governance owner',
      auditRequirement: 'Submit and retain a gate decision bound to the exact draft fingerprint.',
      expiresAt: '',
      available: false,
      unavailableReason: 'No governance handoff link has been advertised for this revision.',
    };
  }
  return {
    ...actionForIssue(issue, target, trustContext, currentHref),
    actionKind: 'RUN_SCENARIO',
    actionLabel: 'Run current Scenario',
    navigation: 'SCENARIOS',
  };
}

function baseAction(
  issue: EvidenceIssue,
  target: RemediationTarget,
  severity: RemediationAction['severity'],
): RemediationAction {
  return {
    id: `remediation:${issue.id}`,
    source: 'RUN_FAILURE',
    severity,
    target,
    rootCause: issue.message,
    businessImpact: '',
    actionKind: 'OPEN_DIAGNOSTIC',
    actionLabel: 'Open failure source',
    deepLink: '',
    navigation: 'UNAVAILABLE',
    requiredRole: '',
    owner: '',
    auditRequirement: '',
    expiresAt: '',
    available: false,
    unavailableReason: '',
    diagnosticId: issue.diagnostic?.id ?? '',
    technicalCode: issue.code,
    technicalCoordinate: issue.coordinate,
  };
}

function evidenceTarget(trustContext: ScenarioEvidenceTrustContext): RemediationTarget {
  const coordinate = trustContext.coordinate;
  return {
    kind: coordinate?.scenarioId ? 'SCENARIO' : 'GRAPH_DRAFT',
    id: coordinate?.scenarioId || coordinate?.draftId || 'exploratory',
    label: coordinate?.scenarioId
      ? `Scenario ${coordinate.scenarioId} r${coordinate.scenarioRevision}`
      : coordinate?.draftId
        ? `Draft ${coordinate.draftId} r${coordinate.draftRevision}`
        : 'Exploratory graph',
    draftId: coordinate?.draftId,
    revision: coordinate?.draftRevision,
    scenarioId: coordinate?.scenarioId,
  };
}

function authorDeepLink(
  currentHref: string,
  trustContext: ScenarioEvidenceTrustContext,
  mode: 'compose' | 'contract' | 'scenarios' | 'evidence',
  nodeId = '',
): string {
  const url = new URL(currentHref, 'http://localhost');
  url.pathname = url.pathname.includes('/author') ? url.pathname : '/author/';
  url.searchParams.set('authorMode', mode);
  if (mode === 'compose') {
    url.searchParams.delete('workspaceView');
  } else {
    url.searchParams.set('workspaceView', mode === 'contract' ? 'interface' : mode);
  }
  const coordinate = trustContext.coordinate;
  if (coordinate?.draftId) url.searchParams.set('draftId', coordinate.draftId);
  if (coordinate?.scenarioId) url.searchParams.set('scenarioId', coordinate.scenarioId);
  if (nodeId) url.searchParams.set('nodeId', nodeId);
  return `${url.pathname}${url.search}${url.hash}`;
}

function uniqueActions(actions: RemediationAction[]): RemediationAction[] {
  const unique = new Map<string, RemediationAction>();
  actions.forEach((action) => {
    const key = [
      action.actionKind,
      action.target.id,
      action.actionKind === 'OPEN_DIAGNOSTIC' ? action.technicalCoordinate : '',
    ].join(':');
    const existing = unique.get(key);
    if (!existing || actionQuality(action) > actionQuality(existing)) {
      unique.set(key, action);
    }
  });
  return Array.from(unique.values()).sort((left, right) => (
    severityRank(left.severity) - severityRank(right.severity)
  ));
}

function severityRank(severity: RemediationAction['severity']): number {
  if (severity === 'BLOCKING') return 0;
  if (severity === 'WARNING') return 1;
  return 2;
}

function actionQuality(action: RemediationAction): number {
  return (action.diagnosticId ? 4 : 0)
    + (action.deepLink ? 2 : 0)
    + (action.available ? 1 : 0);
}
