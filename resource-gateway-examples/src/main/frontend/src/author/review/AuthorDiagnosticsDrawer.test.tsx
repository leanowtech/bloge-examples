// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import AuthorDiagnosticsDrawer from './AuthorDiagnosticsDrawer';
import type { AuthorDiagnosticItem } from './authorDiagnostics';

describe('AuthorDiagnosticsDrawer localization', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/author/?lang=zh-CN');
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    root = null;
    host.remove();
  });

  it('puts localized diagnosis and remediation before raw protocol detail', async () => {
    const item: AuthorDiagnosticItem = {
      id: 'scenario:decline',
      severity: 'ERROR',
      scope: 'SCENARIO',
      source: 'assertion',
      code: 'ASSERTION_FAILED',
      message: 'Expected approve but actual was decline.',
      coordinate: 'decline',
      coordinates: ['decline'],
      nodeId: '',
      recommendedAction: 'Open Test and compare values.',
      deepLink: '',
      requiredRole: '',
      owner: '',
      auditRequirement: '',
      expiresAt: '',
      occurrenceCount: 1,
    };
    await act(async () => {
      root = createRoot(host);
      root.render(<I18nProvider><AuthorDiagnosticsDrawer
        open
        items={[item]}
        onToggle={vi.fn()}
        onSelect={vi.fn()}
      /></I18nProvider>);
    });

    expect(host.textContent).toContain('业务断言失败');
    expect(host.textContent).toContain('实际结果与已定义的业务预期不一致。');
    expect(host.textContent).toContain('打开用例并比较预期值与实际值。');
    expect(host.textContent).toContain('协议代码: ASSERTION_FAILED');
    expect(host.querySelector('details')?.open).toBe(false);
    expect(host.querySelector('details')?.textContent).toContain('Expected approve but actual was decline.');
  });
});
