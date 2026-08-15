// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import {
  approveScenario,
  fetchScenarioDraftSet,
  markScenarioReviewReady,
  saveScenarioDraftSet,
} from '../api/correctnessAuthoringApi';
import type { StoredScenarioDraftSetV2 } from '../model/authoring';
import CorrectnessI18nProvider from '../CorrectnessI18nProvider';
import { envelope, exactRef, workspaceProjection } from '../testFixtures';
import CaseStudio from './CaseStudio';

vi.mock('../api/correctnessAuthoringApi', () => ({
  approveScenario: vi.fn(),
  fetchScenarioDraftSet: vi.fn(),
  markScenarioReviewReady: vi.fn(),
  saveScenarioDraftSet: vi.fn(),
}));

describe('CaseStudio', () => {
  let host: HTMLDivElement;
  let root: Root;
  const fetchAsset = vi.mocked(fetchScenarioDraftSet);
  const save = vi.mocked(saveScenarioDraftSet);

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    fetchAsset.mockReset();
    save.mockReset();
    vi.mocked(markScenarioReviewReady).mockReset();
    vi.mocked(approveScenario).mockReset();
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('uses graphical input and dependency controls while preserving the Scenario aggregate', async () => {
    const stored = scenarioAsset();
    fetchAsset.mockResolvedValue(envelope(stored));
    save.mockImplementation(async (candidate) => envelope({
      ...stored,
      scenarioDraftSet: { ...candidate, revision: candidate.revision + 1 },
    }));

    await render();
    await change(input('Case name'), 'Prime approval with deterministic score');
    await click(button('Add input field'));
    await change(inputByAria('field1 value'), '720');
    await select(selectElement('Behavior'), 'TIMEOUT');
    await click(button('Save draft'));

    expect(save).toHaveBeenCalledWith(expect.objectContaining({
      revision: 4,
      scenarios: [expect.objectContaining({
        name: 'Prime approval with deterministic score',
        given: { input: { kind: 'INLINE', value: { field1: 720 } } },
        dependencies: [expect.objectContaining({
          behavior: expect.objectContaining({ kind: 'TIMEOUT' }),
        })],
      })],
    }));
    expect(host.textContent).toContain('Scenario set saved');
  });

  async function render() {
    await act(async () => {
      root.render(
        <I18nProvider><CorrectnessI18nProvider>
          <CaseStudio workspace={workspaceProjection()} available />
        </CorrectnessI18nProvider></I18nProvider>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });
  }

  function labelElement(label: string): HTMLLabelElement {
    const element = [...host.querySelectorAll('label')]
      .find((candidate) => candidate.firstChild?.textContent?.includes(label));
    if (!(element instanceof HTMLLabelElement)) throw new Error(`Missing label: ${label}`);
    return element;
  }

  function input(label: string): HTMLInputElement {
    const element = labelElement(label).querySelector('input');
    if (!(element instanceof HTMLInputElement)) throw new Error(`Missing input: ${label}`);
    return element;
  }

  function inputByAria(label: string): HTMLInputElement {
    const element = host.querySelector(`input[aria-label="${label}"]`);
    if (!(element instanceof HTMLInputElement)) throw new Error(`Missing input: ${label}`);
    return element;
  }

  function selectElement(label: string): HTMLSelectElement {
    const element = labelElement(label).querySelector('select');
    if (!(element instanceof HTMLSelectElement)) throw new Error(`Missing select: ${label}`);
    return element;
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
      schemaVersion: 'bloge.scenarioDraftSet.v2',
      scenarioDraftSetId: 'loan-cases', revision: 4,
      scope: {
        tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan-assist',
        environment: 'test', region: 'sg',
      },
      target: workspace.target,
      contractRef: exactRef('GRAPH_CONTRACT', 'loan-contract', 2),
      scenarios: [{
        scenarioId: 'eligible-prime', name: 'Eligible prime customer',
        businessIntent: 'Prove automatic approval.', description: '', caseType: 'GOLDEN',
        risk: 'HIGH', owner, lifecycle: 'EXPLORATORY', obligationRefs: [],
        oracleRefs: [exactRef('BUSINESS_ORACLE', 'approve-prime', 2)],
        assertionSetRefs: [exactRef('ASSERTION_SET', 'approve-prime', 2)],
        sourceRefs: [], given: { input: { kind: 'INLINE', value: {} } },
        dependencies: [{
          dependencyId: 'credit-score',
          selector: {
            graphPath: '/credit', nodeId: 'credit-score', operatorRef: 'risk:score',
            resourceRef: '', functionRef: '', attempts: [], occurrences: [],
            correlationKey: '', pathMatches: [],
          },
          behavior: {
            kind: 'RETURN', boundary: 'NODE', value: { kind: 'INLINE', value: { score: 720 } },
            errorCode: '', delayMs: 0,
          },
          consumption: {
            required: true, minUses: 1, maxUses: 1, onExhausted: 'FAIL', onUnmatched: 'FAIL',
          },
        }],
        review: { status: 'PENDING', reviewer: null, reviewedAt: null, comment: '' },
        tags: ['approval'],
      }],
      metadata: {
        createdAt: '2026-08-15T00:00:00Z', updatedAt: '2026-08-15T00:00:00Z',
        createdBy: owner, updatedBy: owner,
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

async function change(element: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  await act(async () => {
    setter?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

async function select(element: HTMLSelectElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set;
  await act(async () => {
    setter?.call(element, value);
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
}
