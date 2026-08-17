import { afterEach, describe, expect, it } from 'vitest';

import { resetBlogeApiTransport, setBlogeApiTransport } from '../../api';
import {
  createOutcomeCalibrationProposal,
  executeCorrectnessRun,
  fetchCorrectnessCapabilities,
  fetchCorrectnessDefinitions,
  fetchCorrectnessEvidence,
  fetchCorrectnessGovernanceFeedback,
  fetchCorrectnessTargets,
  fetchCorrectnessWorkspace,
  preflightCorrectnessRun,
  publicationRef,
  selectionIntent,
} from './correctnessApi';
import type {
  CorrectnessWorkspaceProjection,
} from '../model/domain';

describe('correctnessApi', () => {
  afterEach(() => resetBlogeApiTransport());

  it('loads deployment capability truth before exposing the workspace', async () => {
    const requests: Array<{ input: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      requests.push({ input: String(input), init });
      return json({ payload: {
        schemaVersion: 'toolStudio.resourceGateway.capabilities.v1',
        features: { correctnessWorkspaceApi: true, correctnessRunApi: false },
        endpoints: [],
      } });
    });

    const capabilities = await fetchCorrectnessCapabilities();

    expect(capabilities.features.correctnessWorkspaceApi).toBe(true);
    expect(requests[0]).toMatchObject({ input: '/api/integration/capabilities' });
    expect(headers(requests[0]?.init).get('X-Purpose')).toBe('CORRECTNESS_READ');
  });

  it('loads one exact bounded metadata-only workspace coordinate', async () => {
    const requests: Array<{ input: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      requests.push({ input: String(input), init });
      return json({ data: { schemaVersion: 'bloge.correctnessWorkspaceProjection.v1' } });
    });

    await fetchCorrectnessWorkspace({
      targetKind: 'GRAPH', targetId: 'customer-resolution',
      targetFingerprint: fp('a'), definitionId: 'definition-1', caseLimit: 50,
    });

    expect(requests[0]?.input).toContain(
      '/api/visual/correctness-workspaces/GRAPH/customer-resolution?');
    expect(requests[0]?.input).toContain(`targetFingerprint=${encodeURIComponent(fp('a'))}`);
    expect(requests[0]?.input).toContain('definitionId=definition-1');
    expect(requests[0]?.input).toContain('caseLimit=50');
    expect(headers(requests[0]?.init).get('X-Purpose')).toBe('CORRECTNESS_READ');
  });

  it('discovers correctness targets with bounded query parameters and cancellation', async () => {
    const requests: Array<{ input: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      requests.push({ input: String(input), init });
      return json(referencePage([]));
    });
    const controller = new AbortController();

    await fetchCorrectnessTargets(
      'OPERATOR',
      { query: 'risk score', cursor: 'cursor-2', limit: 12 },
      controller.signal,
    );

    expect(requests[0]?.input).toContain('/api/visual/correctness-targets?');
    expect(requests[0]?.input).toContain('targetKind=OPERATOR');
    expect(requests[0]?.input).toContain('query=risk+score');
    expect(requests[0]?.input).toContain('cursor=cursor-2');
    expect(requests[0]?.input).toContain('limit=12');
    expect(requests[0]?.init?.signal).toBe(controller.signal);
    expect(headers(requests[0]?.init).get('X-Purpose')).toBe('CORRECTNESS_READ');
  });

  it('loads definitions for one exact target and filters their bounded local catalog', async () => {
    const requests: Array<{ input: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      requests.push({ input: String(input), init });
      return json(referencePage([
        referenceCandidate('definition-risk', 'Risk approval truth'),
        referenceCandidate('definition-loan', 'Loan approval truth'),
      ]));
    });
    const controller = new AbortController();

    const page = await fetchCorrectnessDefinitions(
      referenceCandidate('loan/operator', 'Loan operator', 'OPERATOR'),
      { query: 'loan', cursor: null, limit: 20 },
      controller.signal,
    );

    expect(requests[0]?.input).toContain(
      '/api/visual/correctness-targets/OPERATOR/loan%2Foperator/definitions?');
    expect(requests[0]?.input).toContain(`targetFingerprint=${encodeURIComponent(fp('r'))}`);
    expect(requests[0]?.init?.signal).toBe(controller.signal);
    expect(page.items.map((candidate) => candidate.id)).toEqual(['definition-loan']);
    expect(page.nextCursor).toBeNull();
  });

  it('maps a catalog service outage to the picker unavailable state', async () => {
    setBlogeApiTransport(async () => new Response(JSON.stringify({
      code: 'RG.REFERENCE.CATALOG_UNAVAILABLE',
      detail: 'Candidate directory is unavailable.',
    }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    }));

    await expect(fetchCorrectnessTargets(
      'GRAPH', { query: '', cursor: null, limit: 20 }, new AbortController().signal,
    )).rejects.toMatchObject({ status: 'unavailable', retryable: true });
  });

  it('submits an unresolved intent, then executes only the returned canonical selection', async () => {
    const requests: Array<{ input: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      requests.push({ input: String(input), init });
      return json({ data: {} });
    });
    const publication = { publicationId: 'publication-1', revision: 1 as const, fingerprint: fp('a') };

    await preflightCorrectnessRun({
      schemaVersion: 'bloge.correctnessPreflightRequest.v1',
      publicationRef: publication,
      selection: selectionIntent('SELECTED', ['case-b', 'case-a', 'case-a']),
    });
    await executeCorrectnessRun({
      schemaVersion: 'bloge.correctnessRunRequest.v1',
      publicationRef: publication,
      selection: { mode: 'SELECTED', caseIds: ['case-a', 'case-b'], selectionFingerprint: fp('b') },
      preflightFingerprint: fp('c'),
      clientRequestId: 'browser-request-1',
      strategy: 'COLLECT_ALL',
    });

    expect(JSON.parse(String(requests[0]?.init?.body))).toMatchObject({
      selection: {
        mode: 'SELECTED', caseIds: ['case-a', 'case-b'], expectedSelectionFingerprint: '',
      },
    });
    expect(requests[0]?.input).toBe('/api/visual/correctness-runs:preflight');
    expect(requests[1]?.input).toBe('/api/visual/correctness-runs');
    expect(headers(requests[1]?.init).get('X-Purpose')).toBe('TEST_EXECUTION');
  });

  it('reads terminal evidence through the governance evidence purpose', async () => {
    const requests: Array<{ input: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      requests.push({ input: String(input), init });
      return json({ data: {} });
    });

    await fetchCorrectnessEvidence('suite/run 1');

    expect(requests[0]?.input)
      .toBe('/api/visual/correctness-runs/suite%2Frun%201/evidence-companion');
    expect(headers(requests[0]?.init).get('X-Purpose'))
      .toBe('GOVERNANCE_EVIDENCE_INGESTION');
  });

  it('writes proposed calibration and reads exact governance feedback with separated purposes', async () => {
    const requests: Array<{ input: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      requests.push({ input: String(input), init });
      return json({ data: {} });
    });

    await createOutcomeCalibrationProposal({
      proposalId: 'proposal-1', suiteRunId: 'suite-run-1',
      evidenceCompanionFingerprint: fp('e'), affectedCaseIds: ['case-1'],
      affectedOracleIds: ['oracle-1'], mismatchKind: 'EXPECTED_OUTCOME_DIFFERED',
      reasonCode: 'OUTCOME_MISMATCH', businessRationale: 'Reviewed truth changed.',
      proposedRegressionTitle: 'Preserve reviewed truth',
    });
    await fetchCorrectnessGovernanceFeedback('publication/1');

    expect(requests[0]?.input)
      .toBe('/api/visual/correctness-outcome-calibration-proposals');
    expect(headers(requests[0]?.init).get('X-Purpose')).toBe('CORRECTNESS_WRITE');
    expect(requests[1]?.input)
      .toBe('/api/visual/correctness-publications/publication%2F1/governance-feedback');
    expect(headers(requests[1]?.init).get('X-Purpose')).toBe('CORRECTNESS_READ');
  });

  it('maps only immutable publication revision one into a run coordinate', () => {
    const projection = {
      lastPublication: {
        publicationRef: { kind: 'PUBLICATION', id: 'publication-1', revision: 1, fingerprint: fp('a') },
        lifecycle: 'PUBLISHED', publishedAt: '2026-08-15T00:00:00Z',
      },
    } as CorrectnessWorkspaceProjection;

    expect(publicationRef(projection)).toEqual({
      publicationId: 'publication-1', revision: 1, fingerprint: fp('a'),
    });
    projection.lastPublication!.publicationRef.revision = 2;
    expect(publicationRef(projection)).toBeNull();
  });
});

function json(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function headers(init?: RequestInit): Headers {
  return new Headers(init?.headers);
}

function fp(value: string): string {
  return `sha256:${value.repeat(64)}`;
}

function referencePage(items: ReturnType<typeof referenceCandidate>[]) {
  return {
    schemaVersion: 'bloge.referencePage.v1',
    items,
    nextCursor: null,
    queryFingerprint: fp('q'),
    catalogGeneration: 1,
  };
}

function referenceCandidate(id: string, displayName: string, kind = 'CORRECTNESS_DEFINITION') {
  return {
    schemaVersion: 'bloge.referenceCandidate.v1' as const,
    kind,
    id,
    displayName,
    description: '',
    revision: 1,
    fingerprint: fp('r'),
    authority: 'resource-gateway://correctness-definitions',
    scope: {
      tenantId: 'tenant-a', organizationId: 'org-a', projectId: 'project-a',
      environmentId: 'test', region: 'local',
    },
    lifecycle: 'ACTIVE' as const,
    owner: { stableId: 'owner-a', displayName: 'Risk team' },
    labels: [],
    compatibility: 'COMPATIBLE' as const,
    disabledReasonCode: '',
  };
}
