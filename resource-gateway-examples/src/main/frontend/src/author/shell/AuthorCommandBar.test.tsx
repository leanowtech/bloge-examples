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

  it('does not expose a hidden current-Case run when the task surface owns the command', async () => {
    await render('scenarios', false, {
      commandId: 'RUN_CURRENT_SCENARIO',
      state: 'READY',
      enabled: true,
      label: 'Run & Compare',
      reasonCode: '',
      message: '',
      owner: 'TASK_SURFACE',
      scope: {
        kind: 'CASE',
        count: 1,
        targetIds: ['approved'],
        fingerprint: 'sha256:approved',
      },
    });

    expect(host.querySelector('[data-testid="author-primary-action"]')).toBeNull();
    expect(host.querySelector('[data-testid="author-surface-command-handoff"]')).not.toBeNull();
    expect(text()).toContain('Use Scenarios actions');
    expect(text()).toContain('exact Case scope');
  });

  it('keeps the mobile readiness summary compact until the user asks for detail', async () => {
    await render('compose');

    const toggle = queryButton('ReadinessREADY');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(host.querySelector('.author-mobile-truth-detail')).toBeNull();

    await act(async () => toggle.click());

    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelectorAll('.author-mobile-truth-detail > span')).toHaveLength(5);
  });

  it('discloses secondary mobile commands without competing with the primary action', async () => {
    await render('compose');

    const toggle = queryButton('Tools4 commands');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(host.querySelector('.author-secondary-actions')?.classList.contains('mobile-open')).toBe(false);

    await act(async () => toggle.click());

    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelector('.author-secondary-actions')?.classList.contains('mobile-open')).toBe(true);
  });

  it('uses stable message ids for Chinese command, blocker, remediation, and status text', async () => {
    window.history.pushState({}, '', '/author/?lang=zh-CN');
    await render('scenarios', true, {
      commandId: 'RUN_CURRENT_SCENARIO',
      state: 'BLOCKED',
      enabled: false,
      label: 'mutable English label',
      labelId: 'author.command.run',
      reasonCode: 'RG.AUTHOR.RUN.INPUT_INVALID',
      message: 'mutable English explanation',
      messageId: 'author.blocker.inputInvalid',
      remediation: {
        label: 'mutable English remediation',
        labelId: 'author.command.fixRequiredInput',
        mode: 'scenarios',
      },
    });

    expect(text()).toContain('运行并比较');
    expect(text()).toContain('请先修复已标出的输入值，再运行。');
    expect(text()).toContain('修复必填输入');
    expect(text()).toContain('已保存');
    expect(text()).not.toContain('mutable English');
  });

  it('keeps command identity, state, and executability identical across locales', async () => {
    const blocked: AuthorCommandAvailability = {
      commandId: 'RUN_CURRENT_SCENARIO',
      state: 'BLOCKED',
      enabled: false,
      label: 'Run & Compare',
      labelId: 'author.command.run',
      reasonCode: 'RG.AUTHOR.RUN.INPUT_INVALID',
      message: 'Resolve the highlighted input values before running.',
      messageId: 'author.blocker.inputInvalid',
      remediation: {
        label: 'Fix required input',
        labelId: 'author.command.fixRequiredInput',
        mode: 'scenarios',
      },
    };

    window.history.replaceState({}, '', '/author/?lang=en');
    await render('scenarios', true, blocked);
    const english = localeInvariantState();

    await act(async () => root?.unmount());
    root = null;
    host.replaceChildren();
    window.history.replaceState({}, '', '/author/?lang=zh-CN');
    await render('scenarios', true, blocked);
    const chinese = localeInvariantState();

    expect(chinese).toEqual(english);
    expect(text()).toContain('运行并比较');
    expect(text()).toContain('修复必填输入');
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
        continuityStatus="RECOVERABLE"
        recoveryCapturedAt="2026-08-09T08:00:00.000Z"
        recoverySecurity="SESSION_EPHEMERAL"
        exportUrl="data:application/json,{}"
        exportName="risk-policy.json"
        exportDisabled={false}
        layoutDisabled={false}
        validationDisabled={false}
        saveDisabled={false}
        onModeChange={vi.fn()}
        onPrimaryAction={vi.fn()}
        onPrimaryRemediation={onPrimaryRemediation}
        onImport={vi.fn()}
        onAutoLayout={vi.fn()}
        onValidate={vi.fn()}
        onSave={vi.fn()}
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

  function localeInvariantState() {
    return Array.from(host.querySelectorAll<HTMLElement>('[data-testid]'))
      .filter((element) => element.matches('button, a, [data-state]'))
      .map((element) => ({
        id: element.dataset.testid,
        disabled: element instanceof HTMLButtonElement ? element.disabled : undefined,
        ariaDisabled: element.getAttribute('aria-disabled'),
        ariaPressed: element.getAttribute('aria-pressed'),
        state: element.dataset.state ?? null,
      }));
  }
});
