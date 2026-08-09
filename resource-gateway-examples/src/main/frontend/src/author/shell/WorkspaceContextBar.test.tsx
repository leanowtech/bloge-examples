// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import type { TaskCommandPolicy } from '../task/commandAuthority';
import type { TaskCoordinate } from '../task/taskCoordinate';
import WorkspaceContextBar from './WorkspaceContextBar';

describe('WorkspaceContextBar', () => {
  let host: HTMLDivElement;
  let root: Root | null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    await act(async () => root?.unmount());
    host.remove();
  });

  it('makes target, tenant, environment, role, revision, and command scope visible together', async () => {
    await render();

    expect(host.textContent).toContain('Risk policy');
    expect(host.textContent).toContain('tenant-a');
    expect(host.textContent).toContain('risk');
    expect(host.textContent).toContain('PRODUCTION');
    expect(host.textContent).toContain('OWNER');
    expect(host.textContent).toContain('CASE · 3');
    expect(host.textContent).toContain('revision 7');
    expect(query().dataset.environmentTone).toBe('danger');
  });

  it('shows production confirmation and read-only denial without hiding the context', async () => {
    await render({
      commandId: 'DELETE_NODE',
      decision: 'REQUIRE_CONFIRMATION',
      enabled: true,
      reasonCode: 'RG.AUTHOR.COMMAND.PRODUCTION_CONFIRMATION',
      environmentTone: 'DANGER',
      requiresExplicitConfirmation: true,
    });
    expect(host.textContent).toContain('Production safeguard');

    await act(async () => root?.unmount());
    root = null;
    host.replaceChildren();
    await render({
      commandId: 'SAVE',
      decision: 'DENY',
      enabled: false,
      reasonCode: 'RG.AUTHOR.COMMAND.ROLE_VIEWER',
      environmentTone: 'DANGER',
      requiresExplicitConfirmation: false,
    });
    expect(host.textContent).toContain('Read-only role');
    expect(query().dataset.commandPolicy).toBe('deny');
  });

  it('localizes the same enterprise coordinate in Chinese', async () => {
    window.history.replaceState({}, '', '/author/?lang=zh-CN');
    await render();

    expect(host.textContent).toContain('租户');
    expect(host.textContent).toContain('环境');
    expect(host.textContent).toContain('角色');
    expect(host.textContent).toContain('范围');
  });

  async function render(commandPolicy?: TaskCommandPolicy) {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <I18nProvider>
          <WorkspaceContextBar
            coordinate={coordinate()}
            objectLabel="Risk policy"
            owner="risk-platform"
            lifecycle={{ label: 'Saved', state: 'SAVED' }}
            commandScope={{ kind: 'CASE', count: 3 }}
            commandPolicy={commandPolicy}
          />
        </I18nProvider>,
      );
    });
  }

  function query(): HTMLElement {
    const element = host.querySelector<HTMLElement>('[data-testid="workspace-context-bar"]');
    if (!element) throw new Error('Context bar not found');
    return element;
  }
});

function coordinate(): TaskCoordinate {
  return {
    tenantId: 'tenant-a',
    namespace: 'risk',
    environment: 'production',
    draftId: 'draft-7',
    revision: 7,
    surface: 'SCENARIO',
    subjectKind: 'CASE',
    subjectRef: 'decline',
    selectionFingerprint: 'selection:1',
    role: 'OWNER',
    capabilityFingerprint: 'cap:1',
    selection: { nodeId: 'policy', caseId: 'decline', runId: 'run-9' },
  };
}
