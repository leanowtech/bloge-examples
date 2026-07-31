// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import TopologyContextRail from './TopologyContextRail';

describe('TopologyContextRail', () => {
  let host: HTMLDivElement;
  let root: Root | null = null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
    }
    host.remove();
  });

  it('keeps the exact node and its direct topology closure beside a task surface', async () => {
    const onSelectNode = vi.fn();
    await act(async () => {
      root = createRoot(host);
      root.render(
        <TopologyContextRail
          mode="evidence"
          graphName="loanPolicy"
          nodes={[
            { id: 'profile', label: 'Applicant profile', operatorRef: 'loan:profile' },
            { id: 'score', label: 'Credit score', operatorRef: 'loan:score' },
            { id: 'decision', label: 'Decision', operatorRef: 'loan:decision' },
          ]}
          edges={[
            { source: 'profile', target: 'score' },
            { source: 'score', target: 'decision' },
          ]}
          selectedNodeId="score"
          scenarioId="approved"
          runId="run-7"
          onSelectNode={onSelectNode}
          onRevealInCompose={vi.fn()}
        />,
      );
    });

    expect(host.textContent).toContain('Credit score');
    expect(host.textContent).toContain('Upstream · 1');
    expect(host.textContent).toContain('Applicant profile');
    expect(host.textContent).toContain('Downstream · 1');
    expect(host.textContent).toContain('Decision');
    expect(host.textContent).toContain('approved');
    expect(host.textContent).toContain('run-7');

    await act(async () => {
      button('Decision').click();
    });
    expect(onSelectNode).toHaveBeenCalledWith('decision');
  });

  it('projects graph context without inventing a selected node', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <TopologyContextRail
          mode="contract"
          graphName="customerSupport"
          nodes={[]}
          edges={[]}
          selectedNodeId=""
          scenarioId=""
          runId=""
          onSelectNode={vi.fn()}
          onRevealInCompose={vi.fn()}
        />,
      );
    });

    expect(host.textContent).toContain('Graph contract');
    expect(host.textContent).toContain('Contract context');
    expect(host.querySelector('[role="tablist"]')).toBeNull();
  });
});

function button(label: string): HTMLButtonElement {
  const candidate = Array.from(document.querySelectorAll('button'))
    .find((entry) => entry.textContent?.trim() === label);
  if (!(candidate instanceof HTMLButtonElement)) {
    throw new Error(`Missing button: ${label}`);
  }
  return candidate;
}
