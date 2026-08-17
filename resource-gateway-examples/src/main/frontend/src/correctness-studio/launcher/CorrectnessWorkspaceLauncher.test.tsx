// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import CorrectnessI18nProvider from '../CorrectnessI18nProvider';
import { deploymentCapabilities } from '../testFixtures';
import type { ReferenceCandidate, ReferencePage } from '../../shared/reference-picker/types';
import CorrectnessWorkspaceLauncher from './CorrectnessWorkspaceLauncher';

describe('CorrectnessWorkspaceLauncher', () => {
  let host: HTMLDivElement;
  let root: Root | null;
  const searchTargets = vi.fn();
  const searchDefinitions = vi.fn();
  const onOpen = vi.fn();

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/correctness/?lang=en');
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
    searchTargets.mockReset();
    searchDefinitions.mockReset();
    onOpen.mockReset();
    vi.useFakeTimers();
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    host.remove();
    vi.useRealTimers();
  });

  it('auto-selects the only correctness definition and opens exact coordinates', async () => {
    const target = candidate('GRAPH', 'loan-decision', 'Loan decision');
    const definition = candidate('CORRECTNESS_DEFINITION', 'loan-correctness', 'Loan correctness');
    searchTargets.mockResolvedValue(page([target]));
    searchDefinitions.mockResolvedValue(page([definition]));
    await render();

    await choose('Business target', 'Loan decision');

    expect(host.textContent).toContain('Automatically selected');
    expect(host.textContent).toContain('Loan correctness');
    await click(button('Open correctness workspace'));
    expect(onOpen).toHaveBeenCalledWith({
      targetKind: 'GRAPH',
      targetId: 'loan-decision',
      targetFingerprint: target.fingerprint,
      definitionId: 'loan-correctness',
      caseLimit: 100,
    });
  });

  it('requires an explicit choice when multiple definitions match', async () => {
    const target = candidate('GRAPH', 'loan-decision', 'Loan decision');
    const definitions = [
      candidate('CORRECTNESS_DEFINITION', 'loan-retail', 'Retail loan truth'),
      candidate('CORRECTNESS_DEFINITION', 'loan-enterprise', 'Enterprise loan truth'),
    ];
    searchTargets.mockResolvedValue(page([target]));
    searchDefinitions.mockResolvedValue(page(definitions));
    await render();

    await choose('Business target', 'Loan decision');

    expect(host.textContent).toContain('2 definitions match this exact target');
    expect(button('Open correctness workspace').disabled).toBe(true);
    await choose('Correctness definition', 'Enterprise loan truth');
    expect(button('Open correctness workspace').disabled).toBe(false);
  });

  it('explains a missing definition and prevents an invalid workspace request', async () => {
    searchTargets.mockResolvedValue(page([candidate('OPERATOR', 'risk-score', 'Risk score')]));
    searchDefinitions.mockResolvedValue(page([]));
    await render();

    await click(button('Operator'));
    await choose('Business target', 'Risk score');

    expect(host.textContent).toContain('has no correctness definition yet');
    expect(button('Open correctness workspace').disabled).toBe(true);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('uses capability truth and exposes advanced recovery when the catalog is absent', async () => {
    await render({ correctnessTargetCatalogApi: false, guidedWorkspaceLauncher: false });

    expect(host.textContent).toContain('does not enable guided target selection');
    expect(host.textContent).toContain('Advanced exact coordinates');
    expect(host.querySelector('[aria-label="Business target"]')).toBeNull();
    expect(searchTargets).not.toHaveBeenCalled();
  });

  async function render(overrides: Record<string, boolean> = {}) {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <I18nProvider>
          <CorrectnessI18nProvider>
            <CorrectnessWorkspaceLauncher
              deployment={deploymentCapabilities(overrides)}
              onOpen={onOpen}
              pickerDebounceMs={0}
              searchDefinitions={searchDefinitions}
              searchTargets={searchTargets}
            />
          </CorrectnessI18nProvider>
        </I18nProvider>,
      );
      await Promise.resolve();
    });
  }

  async function choose(inputLabel: string, optionLabel: string) {
    const input = host.querySelector(`[aria-label="${inputLabel}"]`);
    if (!(input instanceof HTMLInputElement)) throw new Error(`Missing combobox: ${inputLabel}`);
    await act(async () => {
      input.focus();
      await Promise.resolve();
    });
    await act(async () => {
      vi.runOnlyPendingTimers();
      await Promise.resolve();
      await Promise.resolve();
    });
    const option = [...host.querySelectorAll('[role="option"]')]
      .find((element) => element.textContent?.includes(optionLabel));
    if (!(option instanceof HTMLElement)) throw new Error(`Missing option: ${optionLabel}`);
    await click(option);
  }

  function button(label: string): HTMLButtonElement {
    const element = [...host.querySelectorAll('button')]
      .find((candidateButton) => candidateButton.textContent?.includes(label));
    if (!(element instanceof HTMLButtonElement)) throw new Error(`Missing button: ${label}`);
    return element;
  }
});

async function click(element: HTMLElement) {
  await act(async () => {
    element.click();
    await Promise.resolve();
    await Promise.resolve();
  });
}

function page(items: ReferenceCandidate[]): ReferencePage {
  return {
    schemaVersion: 'bloge.referencePage.v1',
    items,
    nextCursor: null,
    queryFingerprint: fingerprint('q'),
    catalogGeneration: 1,
  };
}

function candidate(kind: string, id: string, displayName: string): ReferenceCandidate {
  return {
    schemaVersion: 'bloge.referenceCandidate.v1',
    kind,
    id,
    displayName,
    description: `${displayName} description`,
    revision: 3,
    fingerprint: fingerprint(id.slice(0, 1)),
    authority: 'resource-gateway://correctness',
    scope: {
      tenantId: 'tenant-a', organizationId: 'org-a', projectId: 'project-a',
      environmentId: 'test', region: 'local',
    },
    lifecycle: 'ACTIVE',
    owner: { stableId: 'risk-team', displayName: 'Risk team' },
    labels: ['HIGH'],
    compatibility: 'COMPATIBLE',
    disabledReasonCode: '',
  };
}

function fingerprint(seed: string): string {
  return `sha256:${(seed || 'x').repeat(64).slice(0, 64)}`;
}
