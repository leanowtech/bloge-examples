// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import I18nProvider from '../i18n/I18nProvider';
import ScenarioCaseStepRail from './ScenarioCaseStepRail';

describe('ScenarioCaseStepRail', () => {
  const hosts: HTMLDivElement[] = [];

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
  });

  afterEach(() => hosts.splice(0).forEach((host) => host.remove()));

  it('keeps the same four task steps and exact anchors for every test subject', async () => {
    const host = document.createElement('div');
    hosts.push(host);
    document.body.appendChild(host);
    const root = createRoot(host);
    await act(async () => root.render(<I18nProvider><ScenarioCaseStepRail
      anchorPrefix="operator-case-1"
      givenCount={3}
      dependencyCount={1}
      assertionCount={0}
      reviewState="BLOCKED"
    /></I18nProvider>));

    expect(Array.from(host.querySelectorAll('a')).map((link) => link.getAttribute('href'))).toEqual([
      '#operator-case-1-given',
      '#operator-case-1-dependencies',
      '#operator-case-1-then',
      '#operator-case-1-review',
    ]);
    expect(host.textContent).toContain('Given3 input fields');
    expect(host.textContent).toContain('ThenNeeds oracle');
    expect(host.textContent).toContain('Review & runBlocked');
    await act(async () => root.unmount());
  });
});
