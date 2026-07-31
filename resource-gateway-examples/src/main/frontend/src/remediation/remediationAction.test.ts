import { describe, expect, it } from 'vitest';

import { scenarioEvidenceView } from '../contract-scenario/evidenceModel';
import { successfulResponse } from '../contract-scenario/testFixtures';
import type { ScenarioComparison } from '../contract-scenario/scenarioAuthoring';
import { evidenceRemediationActions } from './remediationAction';

describe('evidenceRemediationActions', () => {
  it('turns stale evidence into an exact rerun action without exposing fingerprints first', () => {
    const context = {
      draftStatus: 'SAVED',
      evidenceFreshness: 'STALE' as const,
      contractStatus: 'VALID',
      governanceStatus: 'APPROVED',
      coordinate: coordinate(),
    };
    const actions = evidenceRemediationActions(
      scenarioEvidenceView(successfulResponse(), passingComparison(), context),
      context,
      'http://localhost:18080/author/?authorMode=evidence',
    );

    expect(actions[0]).toEqual(expect.objectContaining({
      source: 'EVIDENCE_STALE',
      actionKind: 'RUN_SCENARIO',
      actionLabel: 'Run current Scenario',
      businessImpact: expect.stringContaining('currently visible revision'),
      deepLink: expect.stringContaining('draftId=loan-draft'),
    }));
    expect(actions[0].deepLink).toContain('scenarioId=approved');
  });

  it('preserves an advertised governance handoff and its accountable owner', () => {
    const context = {
      contractStatus: 'VALID',
      governanceStatus: 'BLOCKED',
      coordinate: coordinate(),
      diagnostics: [{
        id: 'gate-owner',
        severity: 'BLOCKING',
        scope: 'GOVERNANCE',
        code: 'OWNER_APPROVAL_MISSING',
        message: 'Business owner approval is missing.',
        recommendedAction: 'Request owner approval',
        deepLink: 'https://governance.example/gates/gate-owner',
        requiredRole: 'Business owner',
        owner: 'Customer Operations',
        auditRequirement: 'Retain signed approval.',
      }],
    };
    const actions = evidenceRemediationActions(
      scenarioEvidenceView(successfulResponse(), passingComparison(), context),
      context,
    );

    expect(actions).toContainEqual(expect.objectContaining({
      source: 'ANEKE_GATE_BLOCKER',
      actionLabel: 'Request owner approval',
      owner: 'Customer Operations',
      requiredRole: 'Business owner',
      deepLink: 'https://governance.example/gates/gate-owner',
      available: true,
    }));
    expect(actions.filter((action) => action.source === 'ANEKE_GATE_BLOCKER')).toHaveLength(1);
  });

  it('does not render a fake governance action when no handoff is available', () => {
    const context = {
      contractStatus: 'VALID',
      governanceStatus: 'NOT CHECKED',
      coordinate: coordinate(),
    };
    const actions = evidenceRemediationActions(
      scenarioEvidenceView(successfulResponse(), passingComparison(), context),
      context,
    );
    const governance = actions.find((action) => action.source === 'ANEKE_GATE_BLOCKER');

    expect(governance).toEqual(expect.objectContaining({
      available: false,
      owner: 'Governance owner',
      unavailableReason: expect.stringContaining('No governance handoff link'),
    }));
  });
});

function coordinate() {
  return {
    draftId: 'loan-draft',
    draftRevision: 4,
    draftFingerprint: 'sha256:draft',
    contractFingerprint: 'sha256:contract',
    scenarioId: 'approved',
    scenarioRevision: 2,
    scenarioFingerprint: 'sha256:scenario',
    closureFingerprint: 'sha256:closure',
    requestFingerprint: 'sha256:request',
  };
}

function passingComparison(): ScenarioComparison {
  return {
    passed: true,
    diagnostics: [],
    results: [{
      assertionId: 'approved',
      passed: true,
      path: 'decision.approved',
      expected: true,
      actual: true,
      detail: 'Matched.',
    }],
  };
}
