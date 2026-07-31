// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthorCommandBar from './AuthorCommandBar';
import type { AuthorMode } from './authorWorkspaceState';

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

  async function render(mode: AuthorMode) {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <AuthorCommandBar
          graphName="riskPolicy"
          draftRevision={2}
          nodeCount={5}
          edgeCount={7}
          mode={mode}
          primaryAction={{ kind: 'run', label: 'Run scenario', targetMode: 'scenarios' }}
          primaryDisabled={false}
          draftStatus="SAVED"
          executionStatus="NOT RUN"
          assertionStatus="NOT RUN"
          contractStatus="VALID"
          governanceStatus="NOT CHECKED"
          promotionStatus="NOT EVALUATED"
          promotionSummary="Run the canonical Scenario."
          exportUrl="data:application/json,{}"
          exportName="risk-policy.json"
          exportDisabled={false}
          layoutDisabled={false}
          validationDisabled={false}
          onModeChange={vi.fn()}
          onPrimaryAction={vi.fn()}
          onImport={vi.fn()}
          onAutoLayout={vi.fn()}
          onValidate={vi.fn()}
        />,
      );
    });
  }

  function text() {
    return host.textContent ?? '';
  }
});
