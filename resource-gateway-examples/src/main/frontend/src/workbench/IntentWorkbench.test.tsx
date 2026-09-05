// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import IntentWorkbench from './IntentWorkbench';

describe('business intent workbench', () => {
  let root: Root;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('lets a business owner compile a guided intent and inspect a no-code draft', async () => {
    const compile = vi.fn().mockResolvedValue({
      draftId: 'intent:cancel', status: 'READY_FOR_TEST',
      features: [{ name: '责任方', state: '就绪' }],
      rules: [{ when: '平台无责且在免费时段', then: '全额免除取消费' }],
      otherwise: '转人工复核', instructions: ['全额免除取消费', '转人工复核'],
      coverageGaps: [], diagnostics: [], contextFingerprint: 'sha256:context',
    });
    await act(async () => root.render(<IntentWorkbench sessionId="session-1" authorId="owner-1"
      contextFingerprint="sha256:context" compile={compile} />));

    await answer('intent-answer', '责任方、免费取消时段和争议订单');
    await click('intent-next');
    await answer('intent-answer', '平台无责且在免费时段全额免除，其余转人工');
    await click('intent-next');
    await answer('intent-answer', '全额免除、维持收费、转人工复核');
    await click('intent-compile');

    expect(compile).toHaveBeenCalledWith(expect.objectContaining({ proficiencyMode: 'GUIDED' }));
    expect(host.textContent).toContain('可进入测试');
    expect(host.textContent).toContain('平台无责且在免费时段');
    expect(host.textContent).not.toContain('featureYaml');
    expect(host.textContent?.toLowerCase()).not.toContain('dsl');
  });

  it('supports explicit expert mode without erasing guided answers', async () => {
    await act(async () => root.render(<IntentWorkbench sessionId="session-1" authorId="owner-1"
      contextFingerprint="sha256:context" compile={vi.fn()} />));
    await answer('intent-answer', '责任方');
    await click('mode-expert');
    expect((query('expert-utterance') as HTMLTextAreaElement).value).toBe('');
    await click('mode-guided');
    expect((query('intent-answer') as HTMLTextAreaElement).value).toBe('责任方');
  });

  async function answer(testId: string, value: string) {
    await act(async () => {
      const input = query(testId) as HTMLTextAreaElement;
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
  }
  async function click(testId: string) {
    await act(async () => query(testId).click());
  }
  function query(testId: string): HTMLElement {
    const element = host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
    if (!element) throw new Error(`Missing ${testId}`);
    return element;
  }
});
