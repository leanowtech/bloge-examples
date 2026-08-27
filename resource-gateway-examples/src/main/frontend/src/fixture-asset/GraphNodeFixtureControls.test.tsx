// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { GraphNodeFixturePromoteRequest } from './graphNodeFixtureModel';
import {
  FixtureStalenessNotice,
  GraphNodeFixturePicker,
  ProvenanceBadge,
  ResourceFidelitySelect,
  SimulationFixtureControls,
} from './GraphNodeFixtureControls';

const promoted = {
  fixtureAssetId: 'profile.v1',
  revision: 2,
  lifecycle: 'DRAFT',
  assetRef: { id: 'material-1', fingerprint: 'sha256:m' },
  schemaRef: { id: 'schema-1', revision: 3, fingerprint: 'sha256:s' },
  provenance: 'governed',
};

const expectedRequest: GraphNodeFixturePromoteRequest = {
  schemaVersion: 'bloge.graphNodeFixturePromote.v1',
  fixtureAssetId: 'profile.v1',
  classification: 'RESTRICTED',
  retentionDays: 7,
  redactionPaths: ['/email', '/phone'],
};

describe('graph-node fixture controls', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => act(() => root.unmount()));

  it('pins a captured sample once and shows its lifecycle transition', () => {
    const onPin = vi.fn();
    renderControls({ onPin });
    click('[data-testid="pin-fixture-node_1"]');
    expect(onPin).toHaveBeenCalledOnce();
    expect(document.querySelector('[data-testid="fixture-provenance"]')?.getAttribute('data-provenance')).toBe('sample');
    act(() => root.render(<SimulationFixtureControls {...baseProps()} fixture={{ output: { score: 760 }, expectedInput: {} }} />));
    expect(host.querySelector('[data-testid="fixture-provenance"]')?.getAttribute('data-provenance')).toBe('pinned');
  });

  it('submits the bounded promote contract and adopts the governed reference', async () => {
    const promoter = vi.fn(async (_draftId: string, _nodeId: string, request: GraphNodeFixturePromoteRequest) => {
      expect(request).toEqual(expectedRequest);
      return promoted;
    });
    const onGoverned = vi.fn();
    renderControls({ promoter, onGoverned });
    act(() => document.querySelector<HTMLButtonElement>('[data-testid="promote-fixture-node_1"]')?.click());
    set('#promote-fixture-id', 'profile.v1');
    set('[data-testid="governed-fixture-promote-dialog"] [data-testid="promote-fixture-classification"]', 'RESTRICTED', 'change');
    set('[data-testid="promote-fixture-retention"]', '7', 'input');
    set('[data-testid="promote-fixture-redactions"', '/phone\n/email\n/phone', 'input');
    await act(async () => document.querySelector<HTMLButtonElement>('[data-testid="submit-promote-fixture"]')?.click());
    expect(promoter).toHaveBeenCalledWith('draft-1', 'node_1', expectedRequest);
    expect(onGoverned).toHaveBeenCalledWith({
      nodeId: 'node_1', fixtureAssetId: 'profile.v1', revision: 2, schemaFingerprint: 'sha256:s',
    });
    expect(document.querySelector('[data-testid="governed-fixture-promote-dialog"]')).toBeNull();
  });

  it('keeps the retryable dialog open on a safe transport failure', async () => {
    const promoter = vi.fn(async () => { throw new Error('Governed fixture promotion failed (409).'); });
    renderControls({ promoter });
    act(() => document.querySelector<HTMLButtonElement>('[data-testid="promote-fixture-node_1"]')?.click());
    set('#promote-fixture-id', 'profile.v1');
    await act(async () => document.querySelector<HTMLButtonElement>('[data-testid="submit-promote-fixture"]')?.click());
    expect(document.querySelector('[data-testid="promote-fixture-error"]')?.textContent).toContain('409');
    expect(document.querySelector('[data-testid="governed-fixture-promote-dialog"]')).not.toBeNull();
  });

  it('offers only reusable governed assets and records the exact selection', () => {
    const onSelect = vi.fn();
    const assets = [
      { fixtureAssetId: 'z-active', revision: 4, name: 'Zulu profile', schemaFingerprint: 'fp-z', usageCount: 8 },
      { fixtureAssetId: 'a-draft', revision: 1, name: 'Alpha draft', schemaFingerprint: 'fp-a', lifecycle: 'DRAFT' },
    ];
    act(() => root.render(<GraphNodeFixturePicker assets={assets} onSelect={onSelect} />));
    expect(host.textContent).toContain('Zulu profile');
    expect(host.textContent).not.toContain('Alpha draft');
    act(() => host.querySelector<HTMLButtonElement>('[data-testid="reuse-fixture-z-active"]')?.click());
    expect(onSelect).toHaveBeenCalledWith(assets[0]);
  });

  it('renders three fidelities and reports staleness only when changed', () => {
    const onChange = vi.fn();
    const onRecapture = vi.fn();
    act(() => root.render(
      <>
        <ResourceFidelitySelect value="PROTOCOL_DERIVED" onChange={onChange} />
        <FixtureStalenessNotice stale onRecapture={onRecapture} />
      </>,
    ));
    const select = host.querySelector<HTMLSelectElement>('[data-testid="resource-fidelity-select"]')!;
    expect([...select.options].map((option) => option.value)).toEqual([
      'OUTPUT_LEVEL', 'PROTOCOL_DERIVED', 'TRANSPORT_LEVEL',
    ]);
    act(() => {
      select.value = 'TRANSPORT_LEVEL';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(onChange).toHaveBeenCalledWith('TRANSPORT_LEVEL');
    click('[data-testid="recapture-fixture"]');
    expect(onRecapture).toHaveBeenCalledOnce();
  });

  it('surfaces opaque output and stale contract evidence without exposing captured material', () => {
    renderControls({ opaque: true, schemaStale: true });
    expect(host.querySelector('[data-testid="fixture-opaque-warning"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="fixture-schema-stale"]')).not.toBeNull();
    expect(host.textContent).not.toContain('760');
  });

  function baseProps() {
    return { ...{ draftId: 'draft-1', nodeId: 'node_1', label: 'Applicant', operatorRef: 'resource:applicant', output: { score: 760 } } };
  }

  function renderControls(props: Partial<Parameters<typeof SimulationFixtureControls>[0]> = {}) {
    const initial = props.fixture;
    if (initial) {
      act(() => root.render(<ProvenanceBadge fixture={initial} />));
      return;
    }
    act(() => root.render(<SimulationFixtureControls {...baseProps()} {...props} />));
  }

  function click(selector: string): void {
    act(() => host.querySelector<HTMLButtonElement>(selector)?.click());
  }

  function set(selector: string, value: string, event: string = 'input'): void {
    const element = host.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(selector);
    if (!element) {
      const currentSelectors = [...host.querySelectorAll<HTMLElement>('[data-testid], [id]')]
        .map((item) => item.getAttribute('data-testid') ?? item.id);
      throw new Error(`Missing element: ${selector}; mounted=${currentSelectors.join(',')}`);
    }
    element.value = value;
    element.dispatchEvent(new Event(event, { bubbles: true }));
  }
});
