// @vitest-environment jsdom
import { act, type ComponentProps } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AsyncReferenceCombobox from './AsyncReferenceCombobox';
import type {
  ReferenceCandidate,
  ReferenceCandidateSearch,
  ReferencePage,
  ReferenceQuery,
} from './types';

describe('AsyncReferenceCombobox', () => {
  let host: HTMLDivElement;
  let root: Root | null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.useFakeTimers();
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    host.remove();
    vi.useRealTimers();
  });

  it('cancels the old request and ignores its late response after a new debounced query', async () => {
    const oldRequest = deferred<ReferencePage>();
    const newRequest = deferred<ReferencePage>();
    const requests: Array<{ query: ReferenceQuery; signal: AbortSignal }> = [];
    const loadCandidates = vi.fn<ReferenceCandidateSearch>((query, signal) => {
      requests.push({ query, signal });
      return requests.length === 1 ? oldRequest.promise : newRequest.promise;
    });

    await render(loadCandidates);
    await search('old');
    expect(requests).toHaveLength(1);

    await setInput('new');
    expect(requests[0].signal.aborted).toBe(true);
    await advanceDebounce();
    expect(requests[1].query).toMatchObject({ query: 'new', cursor: null });

    await act(async () => {
      oldRequest.resolve(page([candidate('stale')]));
      newRequest.resolve(page([candidate('current')]));
      await Promise.resolve();
    });

    expect(host.textContent).toContain('current');
    expect(host.textContent).not.toContain('stale');
  });

  it('loads cursor pages and removes duplicate exact references', async () => {
    const secondPage = deferred<ReferencePage>();
    const loadCandidates = vi.fn<ReferenceCandidateSearch>();
    loadCandidates.mockResolvedValueOnce(page([candidate('alpha'), candidate('beta')], 'cursor-2'));
    loadCandidates.mockReturnValueOnce(secondPage.promise);

    await render(loadCandidates);
    await search('asset');
    expect(optionNames()).toEqual(['alpha', 'beta']);

    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="reference-picker-load-more"]')?.click());
    expect(loadCandidates).toHaveBeenNthCalledWith(
      2,
      { query: 'asset', cursor: 'cursor-2', limit: 20 },
      expect.any(AbortSignal),
    );

    await act(async () => {
      secondPage.resolve(page([candidate('beta'), candidate('gamma')]));
      await Promise.resolve();
    });
    expect(optionNames()).toEqual(['alpha', 'beta', 'gamma']);
  });

  it('selects an enabled option with the keyboard while skipping disabled candidates', async () => {
    const onChange = vi.fn();
    const loadCandidates = vi.fn<ReferenceCandidateSearch>().mockResolvedValue(
      page([candidate('blocked', true), candidate('usable')]),
    );

    await render(loadCandidates, { onChange });
    await search('asset');
    const input = query<HTMLInputElement>('input[role="combobox"]');

    await act(async () => {
      query<HTMLLIElement>('[role="option"]').click();
      input.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'ArrowDown' }));
    });
    await act(async () => input.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Enter' })));

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ displayName: 'usable' }));
    expect(host.querySelector('[data-testid="reference-picker-selection"]')?.textContent).toContain('usable');
    expect(host.querySelector('[data-testid="reference-picker-selection"]')?.textContent).toContain('sha256:usable');
  });

  it('shows a retryable error and recovers when retry succeeds', async () => {
    const loadCandidates = vi.fn<ReferenceCandidateSearch>()
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValueOnce(page([candidate('recovered')]));

    await render(loadCandidates);
    await search('asset');
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('References could not be loaded.');

    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="reference-picker-retry"]')?.click());
    await flushPromises();
    expect(optionNames()).toEqual(['recovered']);
  });

  it('distinguishes an unavailable directory and never renders a candidate payload', async () => {
    const loadCandidates = vi.fn<ReferenceCandidateSearch>().mockRejectedValue({ status: 'unavailable' });

    await render(loadCandidates, {
      labels: { unavailable: '目录暂不可用', retry: '重试' },
    });
    await search('asset');
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('目录暂不可用');

    loadCandidates.mockResolvedValueOnce(page([
      { ...candidate('safe'), payload: { secret: 'should-not-render' } } as ReferenceCandidate,
    ]));
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="reference-picker-retry"]')?.click());
    await flushPromises();
    expect(host.textContent).toContain('safe');
    expect(host.textContent).not.toContain('should-not-render');
  });

  async function render(
    loadCandidates: ReferenceCandidateSearch,
    props: Partial<ComponentProps<typeof AsyncReferenceCombobox>> = {},
  ) {
    await act(async () => {
      root = createRoot(host);
      root.render(<AsyncReferenceCombobox loadCandidates={loadCandidates} {...props} />);
    });
  }

  async function search(value: string) {
    const input = query<HTMLInputElement>('input[role="combobox"]');
    await act(async () => input.focus());
    await setInput(value);
    await advanceDebounce();
    await flushPromises();
  }

  async function setInput(value: string) {
    await act(async () => {
      const input = query<HTMLInputElement>('input[role="combobox"]');
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
  }

  async function advanceDebounce() {
    await act(async () => {
      vi.advanceTimersByTime(250);
      await Promise.resolve();
    });
  }

  async function flushPromises() {
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
  }

  function optionNames(): string[] {
    return [...host.querySelectorAll('[role="option"]')].map((option) => option.querySelector('strong')?.textContent ?? '');
  }

  function query<T extends Element = HTMLElement>(selector: string): T {
    const element = host.querySelector<T>(selector);
    if (!element) throw new Error(`Missing ${selector}`);
    return element;
  }
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function candidate(name: string, disabled = false): ReferenceCandidate {
  return {
    schemaVersion: 'bloge.referenceCandidate.v1',
    kind: 'GRAPH',
    id: `id-${name}`,
    displayName: name,
    description: `${name} description`,
    revision: 7,
    fingerprint: `sha256:${name}`,
    authority: 'resource-gateway://graph-drafts',
    scope: {
      tenantId: 'tenant-a',
      organizationId: 'org-a',
      projectId: 'project-a',
      environmentId: 'test',
      region: 'local',
    },
    lifecycle: 'ACTIVE',
    owner: { stableId: 'platform-team', displayName: 'Platform Team' },
    labels: ['demo'],
    compatibility: disabled ? 'INCOMPATIBLE' : 'COMPATIBLE',
    disabledReasonCode: disabled ? 'RG.REFERENCE.SCOPE_MISMATCH' : '',
  };
}

function page(items: readonly ReferenceCandidate[], nextCursor: string | null = null): ReferencePage {
  return {
    schemaVersion: 'bloge.referencePage.v1',
    items,
    nextCursor,
    queryFingerprint: 'sha256:query',
    catalogGeneration: 1,
  };
}
