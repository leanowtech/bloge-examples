// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import BusinessMirrorReferenceField from './BusinessMirrorReferenceField';
import type {
  ReferenceCandidate,
  ReferenceCandidateSearch,
  ReferencePage,
} from '../../shared/reference-picker/types';

describe('BusinessMirrorReferenceField', () => {
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

  it('loads and selects a kind-scoped candidate with business metadata only', async () => {
    const selected = candidate('订单取消规则');
    const loadCandidates = vi.fn<ReferenceCandidateSearch>().mockResolvedValue(page([
      selected,
      { ...candidate('wrong-kind'), kind: 'OPERATOR' },
    ]));
    const onChange = vi.fn();

    await render({ loadCandidates, onChange });
    await openPicker();

    expect(loadCandidates).toHaveBeenCalledWith(
      { query: '', cursor: null, limit: 20 },
      expect.any(AbortSignal),
    );
    expect(optionNames()).toEqual(['订单取消规则']);
    await act(async () => host.querySelector<HTMLLIElement>('[role="option"]')?.click());

    expect(onChange).toHaveBeenCalledWith(selected);
    expect(host.textContent).toContain('订单取消规则');
    expect(host.textContent).toContain('客服平台');
    expect(host.textContent).toContain('org-a / project-a / test / local');
    expect(host.textContent).toContain('ACTIVE');
    expect(host.textContent).not.toContain('SECRET-PAYLOAD');
  });

  it('clears a selected reference through the explicit clear action', async () => {
    const onChange = vi.fn();
    await render({ value: candidate('已选引用'), onChange });

    const clear = query<HTMLButtonElement>('[data-testid="business-mirror-reference-clear"]');
    expect(clear.getAttribute('aria-label')).toBe('Clear reference');
    await act(async () => clear.click());

    expect(onChange).toHaveBeenCalledWith(null);
  });

  it('shows the caller fallback when the capability is unavailable without loading', async () => {
    const loadCandidates = vi.fn<ReferenceCandidateSearch>();
    await render({
      capabilityAvailable: false,
      fallback: '请联系平台管理员启用引用目录。',
      loadCandidates,
      labels: { capabilityUnavailable: '当前部署不支持引用发现。' },
    });

    expect(query('[data-testid="business-mirror-reference-unavailable"]').textContent)
      .toContain('当前部署不支持引用发现。');
    expect(host.textContent).toContain('请联系平台管理员启用引用目录。');
    expect(host.querySelector('[role="combobox"]')).toBeNull();
    expect(loadCandidates).not.toHaveBeenCalled();
  });

  it('keeps the picker disabled and does not request candidates when disabled', async () => {
    const loadCandidates = vi.fn<ReferenceCandidateSearch>();
    await render({ disabled: true, loadCandidates });

    const input = query<HTMLInputElement>('[role="combobox"]');
    expect(input.disabled).toBe(true);
    await act(async () => input.focus());
    await advanceDebounce();
    expect(loadCandidates).not.toHaveBeenCalled();
  });

  it('accepts injected Chinese labels for the field and picker', async () => {
    await render({
      labels: {
        inputLabel: '选择业务图',
        placeholder: '搜索图名称或负责人',
        selected: '已选择业务图',
        exactReference: '技术详情',
        clear: '清除业务图',
      },
      value: candidate('中文业务图'),
    });

    expect(host.textContent).toContain('选择业务图');
    expect(host.textContent).toContain('技术详情');
    expect(query<HTMLButtonElement>('[data-testid="business-mirror-reference-clear"]')
      .getAttribute('aria-label')).toBe('清除业务图');
  });

  async function render(options: Partial<Parameters<typeof BusinessMirrorReferenceField>[0]> = {}) {
    const props = {
      label: '业务图引用',
      help: '用于绑定可治理的业务资产。',
      kind: 'GRAPH',
      loadCandidates: vi.fn<ReferenceCandidateSearch>().mockResolvedValue(page([])),
      onChange: vi.fn(),
      capabilityAvailable: true,
      ...options,
    };
    await act(async () => {
      root = createRoot(host);
      root.render(<BusinessMirrorReferenceField {...props} />);
    });
  }

  async function openPicker() {
    await act(async () => query<HTMLInputElement>('[role="combobox"]').focus());
    await advanceDebounce();
    await flushPromises();
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
    return [...host.querySelectorAll('[role="option"]')]
      .map((option) => option.querySelector('strong')?.textContent ?? '');
  }

  function query<T extends Element = HTMLElement>(selector: string): T {
    const element = host.querySelector<T>(selector);
    if (!element) throw new Error(`Missing ${selector}`);
    return element;
  }
});

function candidate(name: string): ReferenceCandidate {
  return {
    schemaVersion: 'bloge.referenceCandidate.v1',
    kind: 'GRAPH',
    id: `graph-${name}`,
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
    owner: { stableId: 'support-platform', displayName: '客服平台' },
    labels: ['demo'],
    compatibility: 'COMPATIBLE',
    disabledReasonCode: '',
    ...({ payload: 'SECRET-PAYLOAD' } as unknown as Pick<ReferenceCandidate, never>),
  };
}

function page(items: readonly ReferenceCandidate[]): ReferencePage {
  return {
    schemaVersion: 'bloge.referencePage.v1',
    items,
    nextCursor: null,
    queryFingerprint: 'sha256:query',
    catalogGeneration: 1,
  };
}
