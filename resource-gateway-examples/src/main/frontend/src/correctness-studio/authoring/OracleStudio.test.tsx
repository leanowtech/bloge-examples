// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import * as api from '../api/correctnessAuthoringApi';
import type {
  AssertionCompilationReport,
  StoredAssertionSet,
  StoredBusinessOracle,
  StoredScenarioDraftSetV2,
} from '../model/authoring';
import CorrectnessI18nProvider from '../CorrectnessI18nProvider';
import { envelope, exactRef, workspaceProjection } from '../testFixtures';
import OracleStudio from './OracleStudio';

vi.mock('../api/correctnessAuthoringApi', () => ({
  approveBusinessOracle: vi.fn(), fetchAssertionSet: vi.fn(), fetchBusinessOracle: vi.fn(),
  fetchScenarioDraftSet: vi.fn(), previewAssertionSet: vi.fn(), saveAssertionSet: vi.fn(),
  saveBusinessOracle: vi.fn(), validateAssertionSet: vi.fn(),
}));

describe('OracleStudio', () => {
  let host: HTMLDivElement;
  let root: Root;

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

  it('keeps business approval separate from executable assertion validation', async () => {
    const scenario = scenarioAsset();
    const oracle = oracleAsset();
    const assertions = assertionAsset();
    const compilation = compilationReport();
    vi.mocked(api.fetchScenarioDraftSet).mockResolvedValue(envelope(scenario));
    vi.mocked(api.fetchBusinessOracle).mockResolvedValue(envelope(oracle));
    vi.mocked(api.fetchAssertionSet).mockResolvedValue(envelope(assertions));
    vi.mocked(api.saveBusinessOracle).mockImplementation(async (candidate) => envelope({
      ...oracle, oracle: { ...candidate, revision: candidate.revision + 1 },
    }));
    vi.mocked(api.previewAssertionSet).mockResolvedValue(envelope(compilation));
    vi.mocked(api.validateAssertionSet).mockResolvedValue(envelope({
      stored: { ...assertions, assertionSet: { ...assertions.assertionSet, revision: 3, lifecycle: 'VALID' } },
      compilation,
    }));

    await render();
    await change(textarea('Correct outcome statement'), 'Eligible applicants are approved once.');
    await click(button('Save Oracle'));
    expect(api.saveBusinessOracle).toHaveBeenCalledWith(expect.objectContaining({
      statement: 'Eligible applicants are approved once.', lifecycle: 'PROPOSED',
    }));

    expect(button('Validate executable').disabled).toBe(true);
    await click(button('Preview compile'));
    expect(host.textContent).toContain('Executable preview');
    expect(button('Validate executable').disabled).toBe(false);
    await click(button('Validate executable'));
    expect(api.validateAssertionSet).toHaveBeenCalledWith('approve-prime', 2);
    expect(host.textContent).toContain('Assertion Set is executable');
  });

  async function render() {
    await act(async () => {
      root.render(<I18nProvider><CorrectnessI18nProvider>
        <OracleStudio workspace={workspaceProjection()} available />
      </CorrectnessI18nProvider></I18nProvider>);
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });
  }

  function field(label: string): HTMLLabelElement {
    const element = [...host.querySelectorAll('label')]
      .find((candidate) => candidate.firstChild?.textContent?.includes(label));
    if (!(element instanceof HTMLLabelElement)) throw new Error(`Missing label: ${label}`);
    return element;
  }

  function textarea(label: string): HTMLTextAreaElement {
    const element = field(label).querySelector('textarea');
    if (!(element instanceof HTMLTextAreaElement)) throw new Error(`Missing textarea: ${label}`);
    return element;
  }

  function button(label: string): HTMLButtonElement {
    const element = [...host.querySelectorAll('button')]
      .find((candidate) => candidate.textContent?.trim() === label);
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
      scope: scope(), target: workspace.target,
      contractRef: exactRef('GRAPH_CONTRACT', 'loan-contract', 2),
      scenarios: [{
        scenarioId: 'eligible-prime', name: 'Eligible prime', businessIntent: 'Prove approval',
        description: '', caseType: 'GOLDEN', risk: 'HIGH', owner, lifecycle: 'EXPLORATORY',
        obligationRefs: [], oracleRefs: [exactRef('BUSINESS_ORACLE', 'approve-prime', 2)],
        assertionSetRefs: [exactRef('ASSERTION_SET', 'approve-prime', 2)], sourceRefs: [],
        given: { input: { kind: 'INLINE', value: { score: 720 } } }, dependencies: [],
        review: { status: 'PENDING', reviewer: null, reviewedAt: null, comment: '' }, tags: [],
      }],
      metadata: metadata(owner),
    },
  };
}

function oracleAsset(): StoredBusinessOracle {
  const workspace = workspaceProjection();
  const owner = workspace.definition.owner;
  return {
    schemaVersion: 'bloge.storedBusinessOracle.v1', oracleFingerprint: fp('o'),
    oracle: {
      schemaVersion: 'bloge.businessOracle.v1', oracleId: 'approve-prime', revision: 2,
      scope: scope(), target: workspace.target, statement: 'Eligible applicants are approved.',
      forbiddenOutcomes: ['manual-review'], basisRefs: [exactRef('POLICY', 'credit-policy', 3)],
      owner, lifecycle: 'PROPOSED',
      approval: { status: 'PENDING', reviewer: null, reviewedAt: null, comment: '' },
      assertionSetRefs: [exactRef('ASSERTION_SET', 'approve-prime', 2)], metadata: metadata(owner),
    },
  };
}

function assertionAsset(): StoredAssertionSet {
  const workspace = workspaceProjection();
  const owner = workspace.definition.owner;
  return {
    schemaVersion: 'bloge.storedAssertionSet.v1', assertionSetFingerprint: fp('s'),
    assertionSet: {
      schemaVersion: 'bloge.assertionSet.v1', assertionSetId: 'approve-prime', revision: 2,
      target: workspace.target, oracleRef: exactRef('BUSINESS_ORACLE', 'approve-prime', 2),
      lifecycle: 'DRAFT', assertions: [{
        type: 'OUTPUT', assertionId: 'decision-approved', evaluationKind: 'RUNTIME',
        path: '/decision', operator: 'EQUALS', expected: 'APPROVED',
      }],
      compatibility: { supported: false, evaluatorVersion: '', capabilities: [], reasonCode: 'NOT_COMPILED' },
      metadata: metadata(owner),
    },
  };
}

function compilationReport(): AssertionCompilationReport {
  return {
    schemaVersion: 'bloge.assertionCompilationReport.v1', sourceFingerprint: fp('c'),
    compatibility: { supported: true, evaluatorVersion: 'fixture-v1', capabilities: ['OUTPUT'], reasonCode: '' },
    dispositions: [{
      assertionId: 'decision-approved', evaluationKind: 'RUNTIME', capability: 'OUTPUT_EQUALS',
      status: 'COMPILED_RUNTIME', reasonCode: '', loweredAssertionCount: 1,
    }],
    runtimeAssertions: [], evidenceAssertionCount: 0, gateExpectationCount: 0,
  };
}

function scope() {
  return {
    tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan-assist',
    environment: 'test', region: 'sg',
  };
}

function metadata(owner: ReturnType<typeof workspaceProjection>['definition']['owner']) {
  return {
    createdAt: '2026-08-15T00:00:00Z', updatedAt: '2026-08-15T00:00:00Z',
    createdBy: owner, updatedBy: owner,
  };
}

async function click(element: HTMLElement) {
  await act(async () => {
    element.click();
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function change(element: HTMLTextAreaElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
  await act(async () => {
    setter?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

function fp(seed: string): string {
  return `sha256:${seed.repeat(64)}`;
}
