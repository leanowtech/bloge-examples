// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../i18n/I18nProvider';
import RemediationActionList from './RemediationActionList';
import type { RemediationAction } from './remediationAction';

describe('RemediationActionList', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    window.history.replaceState({}, '', '/author/?lang=en');
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('counts only executable and handoff actions as paths to a trusted result', async () => {
    const onInvoke = vi.fn();
    await render([
      action({ id: 'execute', available: true, navigation: 'DIAGNOSTIC' }),
      action({ id: 'explain', available: false, navigation: 'UNAVAILABLE' }),
    ], onInvoke);

    expect(host.textContent).toContain('1 path to a trusted result');
    expect(host.querySelector('[data-capability="execute"]')).not.toBeNull();
    expect(host.querySelector('[data-capability="explain"]')).not.toBeNull();
    expect(host.querySelectorAll('button')).toHaveLength(1);

    await act(async () => host.querySelector<HTMLButtonElement>('button')?.click());
    expect(onInvoke).toHaveBeenCalledWith(expect.objectContaining({ id: 'execute' }));
  });

  it('labels explain-only guidance honestly instead of claiming an action path', async () => {
    window.history.replaceState({}, '', '/author/?lang=zh-CN');
    await render([action({ id: 'explain', available: false, navigation: 'UNAVAILABLE' })], vi.fn());

    expect(host.textContent).toContain('处理建议');
    expect(host.textContent).toContain('当前部署没有可直接执行的操作。');
    expect(host.textContent).not.toContain('通往可信结果');
    expect(host.querySelector('button, a')).toBeNull();
  });

  async function render(actions: RemediationAction[], onInvoke: (action: RemediationAction) => void) {
    await act(async () => root.render(
      <I18nProvider>
        <RemediationActionList actions={actions} onInvoke={onInvoke} />
      </I18nProvider>,
    ));
  }
});

function action(overrides: Partial<RemediationAction>): RemediationAction {
  return {
    id: 'action',
    source: 'RUN_FAILURE',
    severity: 'BLOCKING',
    target: { kind: 'REHEARSAL', id: 'target', label: 'Target' },
    rootCause: 'Execution failed',
    businessImpact: 'The run cannot be used as correctness evidence until this failure is resolved.',
    actionKind: 'OPEN_DIAGNOSTIC',
    actionLabel: 'Open failure source',
    deepLink: '',
    navigation: 'DIAGNOSTIC',
    requiredRole: 'Rehearsal operator',
    owner: 'Evidence platform owner',
    auditRequirement: 'Rerun the exact Scenario after repair.',
    expiresAt: '',
    available: true,
    unavailableReason: '',
    diagnosticId: '',
    technicalCode: 'RUN_FAILED',
    technicalCoordinate: 'job/items/0',
    ...overrides,
  };
}
