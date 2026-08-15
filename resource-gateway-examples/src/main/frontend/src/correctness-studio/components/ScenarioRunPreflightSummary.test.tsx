// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import type { ScenarioRunPreflightProjection } from '../model/preflightRiskProjection';
import ScenarioRunPreflightSummary from './ScenarioRunPreflightSummary';

describe('ScenarioRunPreflightSummary', () => {
  let host: HTMLDivElement;
  let root: Root | null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    host.remove();
  });

  it('shows exact real, mock, fault, fallback, effect, and blocking reasons before run', async () => {
    await render('en', projection());

    expect(host.textContent).toContain('Run blocked');
    expect(host.textContent).toContain('2 Cases · 2 SUBJECT · 2 REAL · 3 MOCKED · 1 FAULT');
    expect(host.textContent).toContain('1 dependency controls can fall back to a real call');
    expect(host.textContent).toContain('WRITE');
    expect(host.querySelector('[data-status="blocked"]')).not.toBeNull();
  });

  it('renders the same protocol facts in Chinese without changing coordinates', async () => {
    await render('zh-CN', projection());

    expect(host.textContent).toContain('运行已阻断');
    expect(host.textContent).toContain('2 个用例');
    expect(host.textContent).toContain('真实调用');
    expect(host.textContent).toContain('score');
  });

  async function render(locale: 'en' | 'zh-CN', value: ScenarioRunPreflightProjection) {
    window.history.replaceState({}, '', `/?lang=${locale}`);
    await act(async () => {
      root = createRoot(host);
      root.render(
        <I18nProvider>
          <ScenarioRunPreflightSummary projection={value} />
        </I18nProvider>,
      );
    });
  }
});

function projection(): ScenarioRunPreflightProjection {
  return {
    schemaVersion: 'bloge.correctnessPreflightProjection.v1',
    status: 'BLOCKED',
    environment: 'test',
    targetEffect: 'WRITE',
    selectedCaseCount: 2,
    counts: {
      invocations: 9, subjectReal: 2, real: 1, mocked: 3, fault: 1, replay: 0, observe: 1, denied: 1,
      fallbackToReal: 1, missingOracle: 0,
    },
    reasons: [{
      code: 'REAL_FALLBACK', severity: 'BLOCKING', count: 1,
      message: { messageId: 'correctness.preflight.reason.realFallback', params: { count: 1 } },
    }],
    invocationGroups: [{
      mode: 'REAL', nodeId: 'score', operatorRef: 'risk:score', source: 'UNCONTROLLED',
      behaviorKind: 'REAL', fallbackToReal: false, caseCount: 2,
    }],
    truncatedGroupCount: 0,
  };
}
