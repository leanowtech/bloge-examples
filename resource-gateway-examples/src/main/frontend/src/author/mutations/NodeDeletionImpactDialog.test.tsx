// @vitest-environment jsdom
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import NodeDeletionImpactDialog from './NodeDeletionImpactDialog';
import MutationNotice from './MutationNotice';

describe('NodeDeletionImpactDialog', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
    window.history.replaceState({}, '', '/author/?lang=en');
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    root = null;
    host.remove();
    vi.unstubAllGlobals();
  });

  it('explains every affected asset before allowing destructive deletion', async () => {
    const confirm = vi.fn();
    await render(
      <NodeDeletionImpactDialog
        open
        nodeLabels={['Applicant profile']}
        impact={{
          edgeIds: ['e1', 'e2'],
          requiresConfirmation: true,
          items: [
            { kind: 'NODE', count: 1, refs: ['profile'], severity: 'DESTRUCTIVE' },
            { kind: 'EDGE', count: 2, refs: ['e1', 'e2'], severity: 'WARNING' },
            { kind: 'FIXTURE_OUTPUT', count: 1, refs: ['profile'], severity: 'DESTRUCTIVE' },
            { kind: 'TEST_CASE', count: 2, refs: ['profile:1', 'profile:2'], severity: 'DESTRUCTIVE' },
            { kind: 'OUTPUT_BINDING', count: 1, refs: ['profile'], severity: 'DESTRUCTIVE' },
          ],
        }}
        productionSafeguard
        onCancel={vi.fn()}
        onConfirm={confirm}
      />,
    );

    expect(host.querySelector('[role="dialog"]')).not.toBeNull();
    expect(host.textContent).toContain('Applicant profile');
    expect(host.textContent).toContain('2 connected edges');
    expect(host.textContent).toContain('1 fixture output');
    expect(host.textContent).toContain('2 operator test cases');
    expect(host.textContent).toContain('Graph output binding');
    expect(host.textContent).toContain('Production safeguard');

    await act(async () => queryButton('Delete node and assets').click());
    expect(confirm).toHaveBeenCalledOnce();
  });

  it('renders a Chinese confirmation without changing its destructive semantics', async () => {
    window.history.replaceState({}, '', '/author/?lang=zh-CN');
    await render(
      <NodeDeletionImpactDialog
        open
        nodeLabels={['申请人画像']}
        impact={{
          edgeIds: [],
          requiresConfirmation: true,
          items: [
            { kind: 'NODE', count: 1, refs: ['profile'], severity: 'DESTRUCTIVE' },
            { kind: 'TEST_CASE', count: 1, refs: ['profile:1'], severity: 'DESTRUCTIVE' },
          ],
        }}
        onCancel={vi.fn()}
        onConfirm={vi.fn()}
      />,
    );

    expect(host.textContent).toContain('删除前检查影响');
    expect(host.textContent).toContain('1 个算子测试用例');
    expect(queryButton('删除节点及关联资产')).not.toBeNull();
  });

  it('offers one-click Undo after an immediate reversible mutation', async () => {
    const undo = vi.fn();
    await render(
      <MutationNotice
        message="Deleted Empty node."
        action="undo"
        onAction={undo}
        onDismiss={vi.fn()}
      />,
    );

    expect(host.querySelector('[role="status"]')?.textContent).toBe('Deleted Empty node.');
    await act(async () => queryButton('Undo').click());
    expect(undo).toHaveBeenCalledOnce();
  });

  it('offers Redo after Undo has already restored the mutation', async () => {
    const redo = vi.fn();
    await render(
      <MutationNotice
        message="Undid Delete Empty node."
        action="redo"
        onAction={redo}
        onDismiss={vi.fn()}
      />,
    );

    await act(async () => queryButton('Redo').click());
    expect(redo).toHaveBeenCalledOnce();
  });

  async function render(element: ReactNode) {
    await act(async () => {
      root = createRoot(host);
      root.render(<I18nProvider>{element}</I18nProvider>);
    });
  }

  function queryButton(label: string): HTMLButtonElement {
    const button = Array.from(host.querySelectorAll('button'))
      .find((candidate) => candidate.textContent === label);
    if (!button) throw new Error(`Button not found: ${label}`);
    return button;
  }
});
