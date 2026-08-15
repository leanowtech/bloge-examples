// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import * as api from '../api/correctnessAuthoringApi';
import type { FixtureMaterial, StoredFixtureAsset } from '../model/authoring';
import CorrectnessI18nProvider from '../CorrectnessI18nProvider';
import { envelope, exactRef, workspaceProjection } from '../testFixtures';
import FixtureStudio from './FixtureStudio';

vi.mock('../api/correctnessAuthoringApi', () => ({
  approveFixtureAsset: vi.fn(), fetchFixtureAsset: vi.fn(), fetchFixtureMaterial: vi.fn(),
  saveFixtureAsset: vi.fn(), transitionFixtureAsset: vi.fn(), writeFixtureMaterial: vi.fn(),
}));

describe('FixtureStudio', () => {
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

  it('never loads payload implicitly and rebinds only the material receipt', async () => {
    const stored = fixtureAsset();
    const material = fixtureMaterial();
    const nextMaterialRef = exactRef('FIXTURE_MATERIAL', 'eligible-prime', 4);
    vi.mocked(api.fetchFixtureAsset).mockResolvedValue(envelope(stored));
    vi.mocked(api.fetchFixtureMaterial).mockResolvedValue(material);
    vi.mocked(api.writeFixtureMaterial).mockResolvedValue({
      ...material.receipt,
      materialRef: nextMaterialRef,
      payloadFingerprint: nextMaterialRef.fingerprint,
    });
    vi.mocked(api.saveFixtureAsset).mockImplementation(async (candidate) => envelope({
      ...stored, descriptor: { ...candidate, revision: candidate.revision + 1 },
    }));

    await render();
    expect(api.fetchFixtureMaterial).not.toHaveBeenCalled();

    await click(button('Load protected data'));
    expect(api.fetchFixtureMaterial).toHaveBeenCalledWith(
      'eligible-prime', stored.descriptor.materialRef,
    );
    await change(inputByAria('score value'), '730');
    await click(button('Save protected data'));

    expect(api.writeFixtureMaterial).toHaveBeenCalledWith(expect.objectContaining({
      fixtureAssetId: 'eligible-prime',
      expectedRevision: 3,
      payload: { score: 730, country: 'SG' },
    }));
    expect(api.saveFixtureAsset).toHaveBeenCalledWith(expect.objectContaining({
      materialRef: nextMaterialRef,
    }));
    const materialWrite = vi.mocked(api.writeFixtureMaterial).mock.calls[0]?.[0];
    expect(JSON.stringify(materialWrite)).not.toContain('Bearer');
    expect(host.textContent).toContain('Protected material saved and rebound');
  });

  async function render() {
    await act(async () => {
      root.render(<I18nProvider><CorrectnessI18nProvider>
        <FixtureStudio workspace={workspaceProjection()} catalogAvailable materialAvailable />
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

  function inputByAria(label: string): HTMLInputElement {
    const element = host.querySelector(`input[aria-label="${label}"]`);
    if (!(element instanceof HTMLInputElement)) throw new Error(`Missing input: ${label}`);
    return element;
  }
});

function fixtureAsset(): StoredFixtureAsset {
  const workspace = workspaceProjection();
  const owner = workspace.definition.owner;
  return {
    schemaVersion: 'bloge.storedFixtureAsset.v1', descriptorFingerprint: fp('d'),
    descriptor: {
      schemaVersion: 'bloge.fixtureAssetDescriptor.v1', fixtureAssetId: 'eligible-prime',
      revision: 3, scope: {
        tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan-assist',
        environment: 'test', region: 'sg',
      },
      name: 'Eligible prime profile', source: { kind: 'SAMPLE', sourceRef: null },
      materialRef: exactRef('FIXTURE_MATERIAL', 'eligible-prime', 3),
      schemaRef: workspace.fixtures.rows[0]!.schemaRef,
      variantKey: 'prime-sg', lifecycle: 'DRAFT', classification: 'CONFIDENTIAL', owner,
      redaction: { profileVersion: 'pii-v2', redactedPaths: ['/phone'], reviewed: true },
      retention: { policyVersion: 'test-30d', retentionDays: 30, expiresAt: '2026-09-15T00:00:00Z' },
      quality: { schemaValid: true, redactionVerified: true, duplicateCandidateCount: 0, usageCount: 1 },
      tags: ['prime'], metadata: {
        createdAt: '2026-08-15T00:00:00Z', updatedAt: '2026-08-15T00:00:00Z',
        createdBy: owner, updatedBy: owner,
      },
    },
  };
}

function fixtureMaterial(): FixtureMaterial {
  const materialRef = exactRef('FIXTURE_MATERIAL', 'eligible-prime', 3);
  return {
    schemaVersion: 'bloge.fixtureMaterial.v2',
    receipt: {
      schemaVersion: 'bloge.fixtureMaterialReceipt.v2', fixtureAssetId: 'eligible-prime',
      materialRef, payloadFingerprint: materialRef.fingerprint,
      source: { kind: 'SAMPLE', sourceRef: null }, subject: { subjectId: 'prime-sg' },
      target: workspaceProjection().target, schemaRef: workspaceProjection().fixtures.rows[0]!.schemaRef,
      classification: 'CONFIDENTIAL',
      retention: { policyVersion: 'test-30d', retentionDays: 30, expiresAt: '2026-09-15T00:00:00Z' },
      redaction: { profileVersion: 'pii-v2', redactedPaths: ['/phone'], reviewed: true },
      payloadPersisted: true, payloadReturned: false,
    },
    payload: { score: 720, country: 'SG' }, payloadReturned: true,
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

function fp(seed: string): string {
  return `sha256:${seed.repeat(64)}`;
}
