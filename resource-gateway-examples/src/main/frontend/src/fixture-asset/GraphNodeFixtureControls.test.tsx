// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { FixtureAssetLifecycleActions } from './api';
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

  it('moves a promoted governed fixture through review-ready, approval, and activation', async () => {
    const promoter = vi.fn(async () => promoted);
    const lifecycle: FixtureAssetLifecycleActions = {
      reviewReady: vi.fn(async () => ({ revision: 3, lifecycle: 'PROPOSED' })),
      verifyReview: vi.fn(async (_fixtureAssetId, _revision, request) => {
        expect(request).toEqual({
          redactionReviewed: true,
          redactionVerified: true,
          comment: 'Redaction reviewed by reviewer',
        });
        return { revision: 4, lifecycle: 'PROPOSED' };
      }),
      approve: vi.fn(async () => ({ revision: 5, lifecycle: 'APPROVED' })),
      activate: vi.fn(async () => ({ revision: 6, lifecycle: 'ACTIVE' })),
    };
    renderControls({ promoter, lifecycleActions: lifecycle });

    click('[data-testid="promote-fixture-node_1"]');
    set('#promote-fixture-id', 'profile.v1');
    await act(async () => document.querySelector<HTMLButtonElement>('[data-testid="submit-promote-fixture"]')?.click());

    expect(host.querySelector('[data-testid="fixture-governance-lifecycle"]')?.getAttribute('data-lifecycle'))
      .toBe('DRAFT');
    await clickAsync('[data-testid="fixture-review-ready-node_1"]');
    expect(lifecycle.reviewReady).toHaveBeenCalledWith('profile.v1', 2);
    expect(host.querySelector('[data-testid="fixture-governance-lifecycle"]')?.getAttribute('data-lifecycle'))
      .toBe('PROPOSED');

    expect(host.querySelector('[data-testid="fixture-verify-review-node_1"]')).not.toBeNull();
    expect(host.querySelector<HTMLButtonElement>('[data-testid="fixture-verify-review-node_1"]')?.disabled)
      .toBe(true);
    check('[data-testid="fixture-redaction-reviewed-node_1"]');
    check('[data-testid="fixture-redaction-verified-node_1"]');
    setControlled('[data-testid="fixture-review-comment-node_1"]', 'Redaction reviewed by reviewer');
    await clickAsync('[data-testid="fixture-verify-review-node_1"]');
    expect(lifecycle.verifyReview).toHaveBeenCalledWith('profile.v1', 3, {
      redactionReviewed: true,
      redactionVerified: true,
      comment: 'Redaction reviewed by reviewer',
    });
    expect(host.querySelector('[data-testid="fixture-review-verified-node_1"]')?.textContent)
      .toContain('Review verified by server');

    const comment = host.querySelector<HTMLInputElement>('[data-testid="fixture-approval-comment-node_1"]')!;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(comment, 'Reviewed by data governance');
      comment.dispatchEvent(new Event('input', { bubbles: true }));
      comment.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await clickAsync('[data-testid="fixture-approve-node_1"]');
    expect(lifecycle.approve).toHaveBeenCalledWith(
      'profile.v1', 4, 'Reviewed by data governance', 'approve:profile.v1:4',
    );
    expect(host.querySelector('[data-testid="fixture-governance-lifecycle"]')?.getAttribute('data-lifecycle'))
      .toBe('APPROVED');

    await clickAsync('[data-testid="fixture-activate-node_1"]');
    expect(lifecycle.activate).toHaveBeenCalledWith('profile.v1', 5);
    expect(host.querySelector('[data-testid="fixture-governance-lifecycle"]')?.getAttribute('data-lifecycle'))
      .toBe('ACTIVE');
    expect(host.querySelector('[data-testid="fixture-approval-comment-node_1"]')).toBeNull();
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

  it('explains when no active governed fixture can be reused', () => {
    act(() => root.render(<GraphNodeFixturePicker assets={[]} onSelect={vi.fn()} />));
    expect(host.querySelector('[data-testid="fixture-picker-empty"]')?.textContent)
      .toContain('No ACTIVE governed fixtures available.');
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
    expect([...select.options].every((option) => !option.disabled)).toBe(true);
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

  it.each([0, false, ''])('keeps valid falsy output %j actionable', (output) => {
    const onPin = vi.fn();
    renderControls({ output, onPin });
    const pin = host.querySelector<HTMLButtonElement>('[data-testid="pin-fixture-node_1"]')!;
    expect(pin.disabled).toBe(false);
    click('[data-testid="pin-fixture-node_1"]');
    expect(onPin).toHaveBeenCalledOnce();
  });

  it('uses an explicit UI pin marker without adding provenance to GraphDraft fixtures', () => {
    act(() => root.render(<ProvenanceBadge fixture={{ output: false, pinned: true }} />));
    expect(host.querySelector('[data-testid="fixture-provenance"]')?.getAttribute('data-provenance'))
      .toBe('pinned');
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

  function check(selector: string): void {
    act(() => host.querySelector<HTMLInputElement>(selector)?.click());
  }

  async function clickAsync(selector: string): Promise<void> {
    await act(async () => {
      host.querySelector<HTMLButtonElement>(selector)?.click();
      await Promise.resolve();
    });
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

  function setControlled(selector: string, value: string): void {
    const element = host.querySelector<HTMLInputElement>(selector);
    if (!element) throw new Error(`Missing controlled element: ${selector}`);
    act(() => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(element, value);
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
    });
  }
});
