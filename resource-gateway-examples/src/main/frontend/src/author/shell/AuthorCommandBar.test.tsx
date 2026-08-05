// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthorCommandBar from './AuthorCommandBar';
import type { AuthorMode } from './authorWorkspaceState';
import type { AuthorCommandAvailability } from '../task/taskStateProjection';
import I18nProvider from '../../i18n/I18nProvider';

describe('AuthorCommandBar', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
  });

  it('keeps canvas-only commands in Compose', async () => {
    await render('compose');

    expect(text()).toContain('Import');
    expect(text()).toContain('Auto layout');
    expect(text()).toContain('Validate graph');
    expect(text()).toContain('Export draft');
  });

  it.each<AuthorMode>(['contract', 'scenarios', 'evidence'])(
    'removes canvas-only commands from %s',
    async (mode) => {
      await render(mode);

      expect(text()).not.toContain('Import');
      expect(text()).not.toContain('Auto layout');
      expect(text()).toContain('Validate graph');
      expect(text()).toContain('Export draft');
    },
  );

  it('renders the command surface and dynamic readiness values in Chinese', async () => {
    window.history.pushState({}, '', '/author/?lang=zh-CN');
    await render('compose', true);

    expect(text()).toContain('草稿 r2 · 5 个节点 · 7 条连线');
    expect(text()).toContain('自动布局');
    expect(text()).toContain('校验编排图');
    expect(text()).toContain('运行场景');
    expect(text()).toContain('未运行');
  });

  it('explains a blocked command in place and exposes its exact remediation', async () => {
    const remediate = vi.fn();
    await render('scenarios', false, {
      commandId: 'RUN_CURRENT_SCENARIO',
      state: 'BLOCKED',
      enabled: false,
      label: 'Run & Compare',
      reasonCode: 'RG.AUTHOR.RUN.INPUT_INVALID',
      message: 'Resolve the highlighted input values before running.',
      remediation: { label: 'Fix required input', mode: 'scenarios' },
    }, remediate);

    expect(queryButton('Run & Compare').disabled).toBe(true);
    expect(text()).toContain('Resolve the highlighted input values before running.');
    await act(async () => queryButton('Fix required input').click());
    expect(remediate).toHaveBeenCalledOnce();
  });

  async function render(
    mode: AuthorMode,
    localized = false,
    primaryCommand: AuthorCommandAvailability = {
      commandId: 'RUN_CURRENT_SCENARIO',
      state: 'READY',
      enabled: true,
      label: 'Run scenario',
      reasonCode: '',
      message: '',
    },
    onPrimaryRemediation = vi.fn(),
  ) {
    const commandBar = (
      <AuthorCommandBar
        graphName="riskPolicy"
        draftRevision={2}
        nodeCount={5}
        edgeCount={7}
        mode={mode}
        primaryCommand={primaryCommand}
        draftStatus="SAVED"
        contractStatus="VALID"
        runStatus="RUNNABLE"
        evidenceStatus="NOT RUN"
        proofStrength="EXPLORATORY"
        promotionStatus="NOT EVALUATED"
        promotionSummary="Run the canonical Scenario."
        exportUrl="data:application/json,{}"
        exportName="risk-policy.json"
        exportDisabled={false}
        layoutDisabled={false}
        validationDisabled={false}
        onModeChange={vi.fn()}
        onPrimaryAction={vi.fn()}
        onPrimaryRemediation={onPrimaryRemediation}
        onImport={vi.fn()}
        onAutoLayout={vi.fn()}
        onValidate={vi.fn()}
      />
    );
    await act(async () => {
      root = createRoot(host);
      root.render(localized ? <I18nProvider>{commandBar}</I18nProvider> : commandBar);
    });
  }

  function text() {
    return host.textContent ?? '';
  }

  function queryButton(label: string): HTMLButtonElement {
    const button = Array.from(host.querySelectorAll('button'))
      .find((candidate) => candidate.textContent === label);
    if (!button) throw new Error(`Button not found: ${label}`);
    return button;
  }
});
