// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import {
  fetchCoverageInventory,
  freezeCoverageInventory,
  saveCoverageInventory,
} from '../api/correctnessAuthoringApi';
import type { StoredCoverageInventory } from '../model/authoring';
import CorrectnessI18nProvider from '../CorrectnessI18nProvider';
import { envelope, workspaceProjection } from '../testFixtures';
import CoverageStudio from './CoverageStudio';

vi.mock('../api/correctnessAuthoringApi', () => ({
  fetchCoverageInventory: vi.fn(),
  freezeCoverageInventory: vi.fn(),
  saveCoverageInventory: vi.fn(),
}));

describe('CoverageStudio', () => {
  let host: HTMLDivElement;
  let root: Root;
  const fetchAsset = vi.mocked(fetchCoverageInventory);
  const save = vi.mocked(saveCoverageInventory);
  const freeze = vi.mocked(freezeCoverageInventory);

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    fetchAsset.mockReset();
    save.mockReset();
    freeze.mockReset();
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('edits the exact aggregate through CAS and blocks premature freezing', async () => {
    const stored = coverageAsset();
    fetchAsset.mockResolvedValue(envelope(stored));
    save.mockImplementation(async (candidate) => envelope({
      ...stored,
      inventory: { ...candidate, revision: candidate.revision + 1 },
    }));
    freeze.mockResolvedValue(envelope({
      stored: { ...stored, inventory: { ...stored.inventory, revision: 6, lifecycle: 'FROZEN' } },
      replayed: false,
    }));

    await render();

    expect(fetchAsset).toHaveBeenCalledWith(workspaceProjection().coverage.inventoryRef);
    expect(button('Freeze denominator').disabled).toBe(true);

    await change(input('Title'), 'Policy boundary is explicit');
    await click(button('Save draft'));

    expect(save).toHaveBeenCalledWith(expect.objectContaining({
      revision: 4,
      obligations: [expect.objectContaining({ title: 'Policy boundary is explicit' })],
    }));

    await select(selectElement('Lifecycle'), 'FROZEN');
    await change(input('Review comment'), 'All reviewed obligations are represented.');
    expect(button('Freeze denominator').disabled).toBe(false);
    await click(button('Freeze denominator'));

    expect(freeze).toHaveBeenCalledWith(
      'loan-obligations', 5, 'All reviewed obligations are represented.', expect.any(String),
    );
    expect(host.textContent).toContain('Coverage denominator frozen');
  });

  async function render() {
    await act(async () => {
      root.render(
        <I18nProvider>
          <CorrectnessI18nProvider>
            <CoverageStudio workspace={workspaceProjection()} available />
          </CorrectnessI18nProvider>
        </I18nProvider>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });
  }

  function input(label: string): HTMLInputElement {
    const element = labelElement(label).querySelector('input');
    if (!(element instanceof HTMLInputElement)) throw new Error(`Missing input: ${label}`);
    return element;
  }

  function selectElement(label: string): HTMLSelectElement {
    const element = labelElement(label).querySelector('select');
    if (!(element instanceof HTMLSelectElement)) throw new Error(`Missing select: ${label}`);
    return element;
  }

  function labelElement(label: string): HTMLLabelElement {
    const element = [...host.querySelectorAll('label')]
      .find((candidate) => candidate.firstChild?.textContent?.includes(label));
    if (!(element instanceof HTMLLabelElement)) throw new Error(`Missing label: ${label}`);
    return element;
  }

  function button(label: string): HTMLButtonElement {
    const element = [...host.querySelectorAll('button')]
      .find((candidate) => candidate.textContent?.includes(label));
    if (!(element instanceof HTMLButtonElement)) throw new Error(`Missing button: ${label}`);
    return element;
  }
});

function coverageAsset(): StoredCoverageInventory {
  const workspace = workspaceProjection();
  const owner = workspace.definition.owner;
  return {
    schemaVersion: 'bloge.storedCoverageInventory.v1',
    inventoryFingerprint: workspace.coverage.inventoryRef!.fingerprint,
    inventory: {
      schemaVersion: 'bloge.coverageInventory.v1',
      inventoryId: 'loan-obligations', revision: 4,
      scope: {
        tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan-assist',
        environment: 'test', region: 'sg',
      },
      target: workspace.target,
      lifecycle: 'DRAFT',
      obligations: [{
        obligationId: 'policy-boundary', dimension: 'POLICY', title: 'Policy boundary',
        statement: 'Boundary decisions follow approved policy.', risk: 'HIGH', owner,
        source: 'BUSINESS', lifecycle: 'PROPOSED', waiver: null, tags: ['policy'],
      }],
      derivationSources: [{ kind: 'POLICY', id: 'credit-policy', revision: 2, fingerprint: fp('a') }],
      freezeReview: { status: 'PENDING', reviewer: null, reviewedAt: null, comment: '' },
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

function fp(seed: string): string {
  return `sha256:${seed.repeat(64)}`;
}
