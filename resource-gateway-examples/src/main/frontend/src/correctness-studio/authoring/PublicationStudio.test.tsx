// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import * as api from '../api/correctnessAuthoringApi';
import type {
  CorrectnessCompilationReport,
  CorrectnessPublicationCommit,
  StoredScenarioDraftSetV2,
} from '../model/authoring';
import CorrectnessI18nProvider from '../CorrectnessI18nProvider';
import { envelope, exactRef, workspaceProjection } from '../testFixtures';
import PublicationStudio from './PublicationStudio';

vi.mock('../api/correctnessAuthoringApi', () => ({
  fetchScenarioDraftSet: vi.fn(),
  previewCorrectnessCompilation: vi.fn(),
  publishCorrectness: vi.fn(),
}));

describe('PublicationStudio', () => {
  let host: HTMLDivElement;
  let root: Root;
  const onPublished = vi.fn();

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    vi.clearAllMocks();
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('requires a publishable deterministic preview before committing the saga', async () => {
    const scenarios = scenarioAsset();
    const report = compilationReport();
    vi.mocked(api.fetchScenarioDraftSet).mockResolvedValue(envelope(scenarios));
    vi.mocked(api.previewCorrectnessCompilation).mockResolvedValue(envelope(report));
    vi.mocked(api.publishCorrectness).mockResolvedValue(envelope(commit(report)));

    await render();
    expect(button('Publish revision').disabled).toBe(true);
    await click(button('Compile preview'));

    expect(api.previewCorrectnessCompilation).toHaveBeenCalledWith(expect.objectContaining({
      definitionRef: workspaceProjection().definition.definitionRef,
      inventoryRef: workspaceProjection().coverage.inventoryRef,
      scenarioDraftSetRef: workspaceProjection().cases.scenarioDraftSetRef,
      oracleRefs: [exactRef('BUSINESS_ORACLE', 'approve-prime', 2)],
      assertionSetRefs: [exactRef('ASSERTION_SET', 'approve-prime', 2)],
      fixtureAssetRefs: [workspaceProjection().fixtures.rows[0]!.descriptorRef],
    }), expect.any(String));
    expect(button('Publish revision').disabled).toBe(false);

    await click(button('Publish revision'));
    expect(api.publishCorrectness).toHaveBeenCalledWith(report.coordinate, expect.any(String));
    expect(host.textContent).toContain('publication-new');
    expect(onPublished).toHaveBeenCalledOnce();
  });

  async function render() {
    await act(async () => {
      root.render(<I18nProvider><CorrectnessI18nProvider>
        <PublicationStudio
          workspace={workspaceProjection()}
          compilationAvailable
          publicationAvailable
          onPublished={onPublished}
        />
      </CorrectnessI18nProvider></I18nProvider>);
      await Promise.resolve();
      await Promise.resolve();
    });
  }

  function button(label: string): HTMLButtonElement {
    const element = [...host.querySelectorAll('button')]
      .find((candidate) => candidate.textContent?.includes(label));
    if (!(element instanceof HTMLButtonElement)) throw new Error(`Missing button: ${label}`);
    return element;
  }
});

function scenarioAsset(): StoredScenarioDraftSetV2 {
  const workspace = workspaceProjection();
  const owner = workspace.definition.owner;
  return {
    schemaVersion: 'bloge.storedScenarioDraftSet.v2',
    scenarioDraftSetFingerprint: workspace.cases.scenarioDraftSetRef!.fingerprint,
    scenarioDraftSet: {
      schemaVersion: 'bloge.scenarioDraftSet.v2', scenarioDraftSetId: 'loan-cases', revision: 4,
      scope: {
        tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan-assist',
        environment: 'test', region: 'sg',
      },
      target: workspace.target, contractRef: exactRef('GRAPH_CONTRACT', 'loan-contract', 2),
      scenarios: [{
        scenarioId: 'eligible-prime', name: 'Eligible prime', businessIntent: 'Prove approval',
        description: '', caseType: 'GOLDEN', risk: 'HIGH', owner, lifecycle: 'CANONICAL',
        obligationRefs: [], oracleRefs: [exactRef('BUSINESS_ORACLE', 'approve-prime', 2)],
        assertionSetRefs: [exactRef('ASSERTION_SET', 'approve-prime', 2)], sourceRefs: [],
        given: { input: { kind: 'INLINE', value: {} } }, dependencies: [],
        review: {
          status: 'APPROVED', reviewer: owner, reviewedAt: '2026-08-15T00:00:00Z', comment: 'ok',
        },
        tags: [],
      }],
      metadata: {
        createdAt: '2026-08-15T00:00:00Z', updatedAt: '2026-08-15T00:00:00Z',
        createdBy: owner, updatedBy: owner,
      },
    },
  };
}

function compilationReport(): CorrectnessCompilationReport {
  const workspace = workspaceProjection();
  return {
    schemaVersion: 'bloge.correctnessCompilationReport.v1', publishable: true,
    compilerVersion: 'correctness-compiler-v1',
    coordinate: {
      definitionRef: workspace.definition.definitionRef,
      inventoryRef: workspace.coverage.inventoryRef!,
      scenarioDraftSetRef: workspace.cases.scenarioDraftSetRef!,
      oracleRefs: [exactRef('BUSINESS_ORACLE', 'approve-prime', 2)],
      assertionSetRefs: [exactRef('ASSERTION_SET', 'approve-prime', 2)],
      fixtureAssetRefs: [workspace.fixtures.rows[0]!.descriptorRef],
      target: workspace.target,
    },
    compilationFingerprint: fp('c'), sourceMap: [{ source: {}, output: {} }],
    compiledAssets: [{ assetRef: exactRef('TEST_SUITE', 'compiled-loan', 1), sourceElementCount: 1 }],
    diagnostics: [],
    riskSummary: {
      realDependencyCount: 0, controlledDependencyCount: 1, faultDependencyCount: 0,
      deniedDependencyCount: 0, fallbackToRealCount: 0, transportBoundaryCount: 0,
      logicalClockRequired: false, riskCodes: [],
    },
  };
}

function commit(report: CorrectnessCompilationReport): CorrectnessPublicationCommit {
  return {
    attempt: {
      schemaVersion: 'bloge.storedCorrectnessPublicationAttempt.v1',
      attempt: { attemptId: 'attempt-new', stateVersion: 4, stage: 'COMMITTED' },
      compilationReport: report,
    },
    publication: {
      schemaVersion: 'bloge.storedCorrectnessPublication.v1', publicationFingerprint: fp('p'),
      publication: {
        schemaVersion: 'bloge.correctnessPublication.v1', publicationId: 'publication-new',
        compilationFingerprint: report.compilationFingerprint, compilerVersion: report.compilerVersion,
      },
    },
  };
}

async function click(element: HTMLElement) {
  await act(async () => {
    element.click();
    await Promise.resolve();
    await Promise.resolve();
  });
}

function fp(seed: string): string {
  return `sha256:${seed.repeat(64)}`;
}
