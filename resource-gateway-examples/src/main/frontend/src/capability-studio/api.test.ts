import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  resetOperatorTestHeadersProvider,
  setOperatorTestHeadersProvider,
} from '../api';

import {
  CapabilityStudioRequestError,
  fetchFeatureRehearsal,
  fetchGovernedRunEvidence,
  fetchScenarioDataset,
  fetchScenarioQualityImpact,
  preflightTutorialBranch,
  runGovernedBaseline,
  saveTutorialBehavior,
  type CapabilityStudioFetcher,
} from './api';
import {
  featureRehearsalProjectionFixture,
  governedBaselineProjectionFixture,
  scenarioDatasetProjectionFixture,
} from './testFixtures';

afterEach(() => {
  resetOperatorTestHeadersProvider();
});

describe('Capability Studio tutorial branch API', () => {
  it('sends only the business behavior fields required by GP-04', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async (_input, _init) => json({
      branchId: 'tutorial-compensation-history-timeout',
      revision: 2,
      fingerprint: `sha256:${'2'.repeat(64)}`,
      canonicalBaselineFingerprint: `sha256:${'a'.repeat(64)}`,
      behavior: {
        dependencyId: 'api-compensation-history',
        dependencyName: 'Compensation history lookup',
        condition: 'WHEN_COMPENSATION_HISTORY_IS_REQUESTED',
        behavior: 'TIMEOUT',
        durationMs: 4200,
      },
    }));

    await saveTutorialBehavior({
      condition: 'WHEN_COMPENSATION_HISTORY_IS_REQUESTED',
      behavior: 'TIMEOUT',
      durationMs: 4200,
      expectedRevision: 1,
    }, fetcher);

    const [, init] = fetcher.mock.calls[0];
    expect(init?.method).toBe('PUT');
    expect(JSON.parse(String(init?.body))).toEqual({
      condition: 'WHEN_COMPENSATION_HISTORY_IS_REQUESTED',
      behavior: 'TIMEOUT',
      durationMs: 4200,
      expectedRevision: 1,
    });
    expect(String(init?.body)).not.toMatch(/fixture|payload|mock/i);
  });

  it('preserves the server recovery contract on optimistic revision conflict', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async (_input, _init) => json({
      code: 'RG.CAPABILITY_STUDIO.REVISION_CONFLICT',
      whatHappened: 'The tutorial branch changed in another session.',
      impact: 'The submitted change was not saved.',
      recoveryAction: 'Reload the latest revision and apply the change again.',
      field: 'expectedRevision',
    }, 409));

    const error = await saveTutorialBehavior({
      condition: 'WHEN_COMPENSATION_HISTORY_IS_REQUESTED',
      behavior: 'TIMEOUT',
      durationMs: 3000,
      expectedRevision: 1,
    }, fetcher).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(CapabilityStudioRequestError);
    expect(error).toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.REVISION_CONFLICT',
      status: 409,
      field: 'expectedRevision',
      impact: 'The submitted change was not saved.',
      recoveryAction: 'Reload the latest revision and apply the change again.',
    });
  });

  it('classifies a transport failure without inventing a preflight result', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async (_input, _init) => { throw new TypeError('connection refused'); });

    const error = await preflightTutorialBranch(fetcher).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(CapabilityStudioRequestError);
    expect(error).toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.NETWORK_UNAVAILABLE',
      status: 0,
      impact: 'The tutorial branch was not changed.',
    });
  });
});

describe('Capability Studio Scenario Dataset API', () => {
  it('uses the dedicated GP-09 quality-impact endpoint and fails closed on a malformed projection', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async (input, init) => {
      expect(String(input)).toBe('/api/capability-studio/scenario-dataset/quality-impact');
      const headers = new Headers(init?.headers);
      expect(headers.get('Accept')).toBe('application/json');
      expect(headers.get('Authorization')).toBe('Bearer bloge-aneke-demo-token');
      expect(headers.get('X-Purpose')).toBe('CAPABILITY_STUDIO_REHEARSAL');
      return json({ schemaVersion: 'wrong' });
    });

    await expect(fetchScenarioQualityImpact(fetcher)).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.INVALID_SCENARIO_QUALITY_IMPACT',
      impact: 'The scenario quality and impact projection cannot be trusted or displayed.',
    });
  });

  it('loads and strictly parses the dedicated scenario-dataset endpoint', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async (input, init) => {
      expect(String(input)).toBe('/api/capability-studio/scenario-dataset');
      expect(init?.headers).toMatchObject({ Accept: 'application/json' });
      return json(scenarioDatasetProjectionFixture);
    });

    const dataset = await fetchScenarioDataset(fetcher);

    expect(dataset.cases).toHaveLength(9);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('reuses structured recovery data when the Dataset endpoint rejects the request', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => json({
      code: 'RG.CAPABILITY_STUDIO.DATASET_UNAVAILABLE',
      whatHappened: 'The scenario dataset is not published.',
      impact: 'GP-03 cannot show governed cases.',
      recoveryAction: 'Publish the projection and retry.',
    }, 503));

    await expect(fetchScenarioDataset(fetcher)).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.DATASET_UNAVAILABLE',
      impact: 'GP-03 cannot show governed cases.',
      recoveryAction: 'Publish the projection and retry.',
    });
  });

  it('describes Dataset impact instead of referring to the tutorial branch on transport failure', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => { throw new TypeError('connection refused'); });

    await expect(fetchScenarioDataset(fetcher)).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.NETWORK_UNAVAILABLE',
      impact: 'The scenario dataset was not loaded or changed.',
      recoveryAction: 'Check that the local demo service is running, then retry.',
    });
  });

  it('uses Dataset-specific recovery when the endpoint returns a non-object body', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => json(['not', 'a', 'dataset']));

    await expect(fetchScenarioDataset(fetcher)).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.INVALID_RESPONSE',
      impact: 'The scenario dataset cannot be trusted or displayed.',
      recoveryAction: 'Reload the scenario dataset before continuing.',
    });
  });
});

describe('Capability Studio Feature rehearsal API', () => {
  it('loads one exact canonical case and permission projection', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async (input, init) => {
      expect(String(input)).toBe('/api/capability-studio/feature-rehearsal?caseId=case-compensation-history-timeout&permission=STRUCTURE_ONLY');
      const headers = new Headers(init?.headers);
      expect(headers.get('Accept')).toBe('application/json');
      expect(headers.get('Authorization')).toBe('Bearer bloge-aneke-demo-token');
      expect(headers.get('X-Purpose')).toBe('CAPABILITY_STUDIO_REHEARSAL');
      return json(featureRehearsalProjectionFixture());
    });

    const result = await fetchFeatureRehearsal(
      'case-compensation-history-timeout', 'STRUCTURE_ONLY', fetcher,
    );

    expect(result.run.status).toBe('TIMED_OUT');
    expect(result.run.realExternalCallCount).toBe(0);
    expect(result.dataLens.permissionMode).toBe('STRUCTURE_ONLY');
  });

  it('preserves a stable server recovery response for an unknown canonical case', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => json({
      code: 'RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_NOT_FOUND',
      whatHappened: 'The selected canonical case does not exist.',
      impact: 'The current DAG evidence cannot be loaded.',
      recoveryAction: 'Choose one of the published scenarios.',
      field: 'caseId',
    }, 404));

    await expect(fetchFeatureRehearsal('case-does-not-exist', 'STRUCTURE_ONLY', fetcher))
      .rejects.toMatchObject({
        code: 'RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_NOT_FOUND',
        status: 404,
        field: 'caseId',
        recoveryAction: 'Choose one of the published scenarios.',
      });
  });

  it('rejects a non-picker case id before issuing a request', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>();

    await expect(fetchFeatureRehearsal('../unsafe case', 'STRUCTURE_ONLY', fetcher))
      .rejects.toMatchObject({ code: 'RG.CAPABILITY_STUDIO.INVALID_CASE_ID', field: 'caseId' });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('uses host-provided credentials while keeping the server purpose fixed', async () => {
    setOperatorTestHeadersProvider(() => ({
      Authorization: 'Bearer host-capability-token',
      'X-Clearance': 'CONFIDENTIAL',
      'X-Purpose': 'CALLER_CANNOT_OVERRIDE_PURPOSE',
    }));
    const fetcher = vi.fn<CapabilityStudioFetcher>(async (_input, init) => {
      const headers = new Headers(init?.headers);
      expect(headers.get('Authorization')).toBe('Bearer host-capability-token');
      expect(headers.get('X-Clearance')).toBe('CONFIDENTIAL');
      expect(headers.get('X-Purpose')).toBe('CAPABILITY_STUDIO_REHEARSAL');
      return json(featureRehearsalProjectionFixture('PAYLOAD_VISIBLE'));
    });

    await fetchFeatureRehearsal(
      'case-compensation-history-timeout', 'PAYLOAD_VISIBLE', fetcher,
    );

    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('preserves the real integration problem title and adds useful authorization recovery', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => json({
      schemaVersion: 'toolStudio.resourceGateway.problem.v1',
      title: 'The verified identity cannot view rehearsal payloads.',
      status: 403,
      code: 'RG.CAPABILITY_STUDIO.PAYLOAD_CLEARANCE_REQUIRED',
      details: {
        requiredClearance: 'CONFIDENTIAL',
      },
    }, 403));

    await expect(fetchFeatureRehearsal(
      'case-compensation-history-timeout', 'PAYLOAD_VISIBLE', fetcher,
    )).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.PAYLOAD_CLEARANCE_REQUIRED',
      whatHappened: 'The verified identity cannot view rehearsal payloads.',
      impact: 'The Feature rehearsal was not changed.',
      recoveryAction: 'Use an identity authorized for this Capability Studio action, or choose an allowed view.',
      status: 403,
    });
  });
});

describe('Capability Studio exact governed evidence API', () => {
  it('reads one exact run with the governed evidence endpoint and fixed read purpose', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async (input, init) => {
      expect(String(input)).toBe('/api/capability-studio/governed-runs/child-run-1-1/evidence?expectedCaseId=case-standard-cancellation-fee');
      expect(init?.method).toBeUndefined();
      const headers = new Headers(init?.headers);
      expect(headers.get('Accept')).toBe('application/json');
      expect(headers.get('Authorization')).toBe('Bearer bloge-aneke-demo-token');
      expect(headers.get('X-Purpose')).toBe('CAPABILITY_STUDIO_REHEARSAL');
      return json(governedRunEvidencePayload('child-run-1-1', 'case-standard-cancellation-fee'));
    });

    const result = await fetchGovernedRunEvidence('child-run-1-1', 'case-standard-cancellation-fee', fetcher);

    expect(result.verificationStatus).toBe('EXACT_VERIFIED');
    expect(result.run.runId).toBe('child-run-1-1');
    expect(result.scenario.caseId).toBe('case-standard-cancellation-fee');
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('rejects unsafe exact identities before issuing a GET', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>();

    await expect(fetchGovernedRunEvidence('../run', 'case-standard-cancellation-fee', fetcher))
      .rejects.toMatchObject({ code: 'RG.CAPABILITY_STUDIO.INVALID_RUN_ID', field: 'runId' });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('rejects a valid-shaped response whose run identity drifts from the requested URL', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => json(
      governedRunEvidencePayload('different-run', 'case-standard-cancellation-fee'),
    ));

    await expect(fetchGovernedRunEvidence('child-run-1-1', 'case-standard-cancellation-fee', fetcher))
      .rejects.toMatchObject({
        code: 'RG.CAPABILITY_STUDIO.EXACT_EVIDENCE_IDENTITY_DRIFT',
        status: 200,
      });
  });
});

describe('Capability Studio governed baseline API', () => {
  it('runs the governed baseline with POST and no request body', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async (input, init) => {
      expect(String(input)).toBe('/api/capability-studio/governed-baseline');
      expect(init?.method).toBe('POST');
      expect(init?.body).toBeUndefined();
      expect(init?.headers).toEqual({ Accept: 'application/json' });
      return json(governedBaselineProjectionFixture);
    });

    const result = await runGovernedBaseline(fetcher);

    expect(result).toMatchObject({
      status: 'PASSED',
      caseCount: 9,
      roundCount: 3,
      childRunCount: 27,
      realExternalCallCount: 0,
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('preserves retryable HTTP context without implying that assets changed', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => json({
      code: 'RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_UNAVAILABLE',
      whatHappened: 'The governed baseline runner is unavailable.',
      impact: 'Development validation was not established; existing Capability Studio assets were not changed.',
      recoveryAction: 'Retry the governed baseline request.',
    }, 503));

    await expect(runGovernedBaseline(fetcher)).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_UNAVAILABLE',
      status: 503,
      impact: 'Development validation was not established; existing Capability Studio assets were not changed.',
      recoveryAction: 'Retry the governed baseline request.',
    });
  });

  it('turns a valid failed-closed receipt into a recoverable operation error', async () => {
    const failed = {
      ...structuredClone(governedBaselineProjectionFixture),
      status: 'FAILED_CLOSED',
      suiteRunCount: 0,
      childRunCount: 0,
      oraclePassCount: 0,
      businessCheckCount: 0,
      businessCheckPassCount: 0,
      verificationLevel: 'NOT_VERIFIED',
      evidenceClass: null,
      realExternalCallCount: null,
      compilationFingerprint: null,
      sourceMapFingerprint: null,
      provenanceFingerprint: null,
      candidateIntentFingerprint: null,
      publication: null,
      rounds: [],
      cases: [],
      limitations: [
        'RUNTIME_ENVIRONMENT_NOT_ATTESTED',
        'CERTIFIABLE_EVIDENCE_NOT_ESTABLISHED',
        'DEPLOYMENT_EGRESS_NOT_OBSERVED',
        'OWNER_SIGNOFF_NOT_PRESENT',
      ],
      diagnostics: ['REAL_EXTERNAL_CALL_FORBIDDEN'],
    };
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => json(failed));

    await expect(runGovernedBaseline(fetcher)).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_FAILED_CLOSED',
      status: 200,
      whatHappened: 'The governed baseline failed closed (REAL_EXTERNAL_CALL_FORBIDDEN).',
      impact: 'Development validation was not established; existing Capability Studio assets were not changed.',
      recoveryAction: 'Retry the governed baseline request.',
    });
  });

  it('classifies a transport failure as retryable and does not invent evidence', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => {
      throw new TypeError('connection refused');
    });

    await expect(runGovernedBaseline(fetcher)).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.NETWORK_UNAVAILABLE',
      status: 0,
      impact: 'Development validation was not established; existing Capability Studio assets were not changed.',
      recoveryAction: 'Check that the local demo service is running, then retry.',
    });
  });

  it('rejects an invalid or tampered successful response as untrusted evidence', async () => {
    const tampered = structuredClone(governedBaselineProjectionFixture);
    tampered.cases[0].rounds[0].runId = tampered.cases[1].rounds[0].runId;
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => json(tampered));

    await expect(runGovernedBaseline(fetcher)).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.INVALID_GOVERNED_BASELINE',
      status: 200,
      impact: 'Development validation was not established; existing Capability Studio assets were not changed.',
      recoveryAction: 'Retry the governed baseline request.',
    });
  });

  it('uses the same governed recovery context for a non-object 404 response', async () => {
    const fetcher = vi.fn<CapabilityStudioFetcher>(async () => json(['not', 'a', 'baseline'], 404));

    await expect(runGovernedBaseline(fetcher)).rejects.toMatchObject({
      code: 'RG.CAPABILITY_STUDIO.HTTP_404',
      status: 404,
      impact: 'Development validation was not established; existing Capability Studio assets were not changed.',
      recoveryAction: 'Retry the governed baseline request.',
    });
  });
});

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } });
}

function governedRunEvidencePayload(runId: string, caseId: string) {
  const dataLens = structuredClone(featureRehearsalProjectionFixture().dataLens);
  dataLens.runId = runId;
  const ref = (kind: string, id: string, seed: string) => ({
    kind,
    id,
    revision: 1,
    fingerprint: `sha256:${seed.repeat(64).slice(0, 64)}`,
  });
  const caseRef = ref('DATA_CASE', caseId, '1');
  const contractRef = ref('CONTRACT', 'contract-cancellation-fee', '2');
  return {
    schemaVersion: 'resource-gateway.capability-studio.governed-run-evidence.v1',
    verificationStatus: 'EXACT_VERIFIED',
    baselineId: 'capability-studio-governed-9x3-v1',
    projectionFingerprint: `sha256:${'3'.repeat(64)}`,
    scenario: {
      caseId,
      name: 'Standard cancellation fee',
      businessIntent: 'Return an explainable fee decision.',
      category: 'GOLDEN',
      lifecycle: 'ACTIVE',
      qualityState: 'READY',
      owner: { id: 'customer-service-platform', name: 'Customer Service Platform' },
      scenarioRef: ref('SCENARIO', caseId, '4'),
      caseRef,
      sourceRef: ref('SOURCE', 'source-cancellation-fee', '5'),
      oracleRef: ref('ORACLE', 'oracle-cancellation-fee', '6'),
      applicableContractRefs: [contractRef],
    },
    graphRef: ref('FEATURE', 'feature-cancellation-dispute-context', '7'),
    capabilityRef: ref('TOOL', 'tool-cancellation-resolution', '8'),
    contractRef,
    datasetRef: ref('DATASET', 'cancellation-fee-scenarios', '9'),
    caseRef,
    runtimeTarget: { kind: 'OPERATOR', id: 'tool-cancellation-resolution', fingerprint: `sha256:${'a'.repeat(64)}` },
    bindingPlan: {
      ref: ref('BINDING_PLAN', 'binding-cancellation-fee', 'b'),
      fixtureBundleRef: ref('FIXTURE_BUNDLE', 'fixture-cancellation-fee', 'c'),
      effectiveExecutionPlanFingerprint: `sha256:${'d'.repeat(64)}`,
      behaviorRefs: [ref('BEHAVIOR_PROFILE', 'behavior-cancellation-fee', 'e')],
      dependencyRefs: [ref('API', 'api-order-lookup', 'f')],
      fallbackToReal: false,
      sourceMapFingerprint: `sha256:${'1'.repeat(64)}`,
      provenanceFingerprint: `sha256:${'2'.repeat(64)}`,
    },
    run: {
      runId,
      status: 'TIMED_OUT',
      evidenceClass: 'CERTIFIABLE',
      evidenceFingerprint: `sha256:${'4'.repeat(64)}`,
      semanticResultFingerprint: `sha256:${'5'.repeat(64)}`,
      assertionsEvaluated: 1,
      assertionsPassed: 1,
      fixtureControlsEvaluated: 1,
      fixtureControlsSatisfied: 1,
    },
    focusNodeId: 'compensationHistoryLookup',
    dataLens,
  };
}
