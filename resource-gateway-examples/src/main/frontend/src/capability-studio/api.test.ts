import { describe, expect, it, vi } from 'vitest';

import {
  CapabilityStudioRequestError,
  fetchScenarioDataset,
  preflightTutorialBranch,
  saveTutorialBehavior,
  type CapabilityStudioFetcher,
} from './api';
import { scenarioDatasetProjectionFixture } from './testFixtures';

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

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } });
}
