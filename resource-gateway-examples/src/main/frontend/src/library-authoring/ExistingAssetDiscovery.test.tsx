// @vitest-environment jsdom
import { act } from 'react';
import type { ComponentProps } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DSL_AUTHOR_HANDOFF_KEY } from '../author/dslAuthorHandoff';
import type { VisualAuthoringFactProjection } from '../types';
import ExistingAssetDiscovery from './ExistingAssetDiscovery';

const apiMocks = vi.hoisted(() => ({
  discover: vi.fn(),
}));

vi.mock('../api', () => ({
  discoverLibraryAuthoringAssets: apiMocks.discover,
}));

describe('ExistingAssetDiscovery', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    window.sessionStorage.clear();
    apiMocks.discover.mockReset();
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
  });

  it('shows runtime uncertainty and opens a safe generated draft from capability discovery', async () => {
    const onStart = vi.fn();
    apiMocks.discover
      .mockResolvedValueOnce(runtimeProjection())
      .mockResolvedValueOnce(capabilityProjection());
    await render(onStart);

    await click('[data-testid="library-discovery-run"]');

    expect(apiMocks.discover).toHaveBeenNthCalledWith(1, 'runtime', {});
    expect(query('[data-testid="library-discovery-result"]').textContent)
      .toContain('RUNTIME DISCOVERED');
    expect(query('[data-testid="library-discovery-result"]').textContent)
      .toContain('Runtime functions were discovered');
    expect(host.querySelector('[data-testid="library-discovery-open-draft"]')).toBeNull();

    await click('[data-testid="library-discovery-mode:capability-catalog"]');
    expect(query<HTMLTextAreaElement>('[data-testid="library-discovery-source"]').value)
      .toContain('bloge.capabilityCatalog.v1');
    await click('[data-testid="library-discovery-run"]');

    expect(apiMocks.discover).toHaveBeenNthCalledWith(
      2,
      'capability-catalog',
      expect.objectContaining({
        sourceId: 'support-capabilities.json',
        catalog: expect.objectContaining({ catalogId: 'support-capabilities' }),
      }),
    );
    expect(query('[data-testid="library-discovery-result"]').textContent)
      .toContain('support.normalize');
    await click('[data-testid="library-discovery-open-draft"]');

    expect(onStart).toHaveBeenCalledWith(
      expect.objectContaining({
        schemaVersion: 'bloge.visualLibraryAuthoring.v1',
        library: expect.objectContaining({ id: 'support-capabilities' }),
      }),
      'discovery:capability-catalog',
    );
  });

  it('reports malformed capability JSON before making a request', async () => {
    await render(vi.fn());
    await click('[data-testid="library-discovery-mode:capability-catalog"]');
    await act(async () => {
      const textarea = query<HTMLTextAreaElement>('[data-testid="library-discovery-source"]');
      const setter = Object.getOwnPropertyDescriptor(
        HTMLTextAreaElement.prototype,
        'value',
      )?.set;
      setter?.call(textarea, '{');
      textarea.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await click('[data-testid="library-discovery-run"]');

    expect(apiMocks.discover).not.toHaveBeenCalled();
    expect(query('[role="alert"]').textContent).toContain('JSON');
  });

  it('stages discovered DSL for the Author workspace before navigation', async () => {
    apiMocks.discover.mockResolvedValueOnce(dslProjection());
    await render(vi.fn());
    await click('[data-testid="library-discovery-mode:dsl"]');
    await click('[data-testid="library-discovery-run"]');

    const link = query<HTMLAnchorElement>('[data-testid="library-discovery-open-author"]');
    link.addEventListener('click', (event) => event.preventDefault());
    await act(async () => {
      link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });

    expect(JSON.parse(window.sessionStorage.getItem(DSL_AUTHOR_HANDOFF_KEY) ?? '{}'))
      .toMatchObject({
        schemaVersion: 'bloge.dslAuthorHandoff.v1',
        sourceId: 'support-routing.bloge',
        dsl: expect.stringContaining('graph supportRouting'),
      });
  });

  async function render(onStart: ComponentProps<typeof ExistingAssetDiscovery>['onStart']) {
    await act(async () => {
      root = createRoot(host);
      root.render(<ExistingAssetDiscovery onStart={onStart} />);
    });
  }

  async function click(selector: string) {
    await act(async () => {
      query<HTMLButtonElement>(selector).click();
      await Promise.resolve();
    });
  }

  function query<T extends Element = HTMLElement>(selector: string): T {
    const element = host.querySelector<T>(selector);
    if (!element) {
      throw new Error(`Missing ${selector}`);
    }
    return element;
  }
});

function runtimeProjection(): VisualAuthoringFactProjection {
  return {
    schemaVersion: 'bloge.visualAuthoringFactProjection.v1',
    sourceKind: 'RUNTIME_INVENTORY',
    sourceId: 'process-local',
    sourceFingerprint: 'sha256:runtime',
    projectionFingerprint: 'sha256:projection-runtime',
    accepted: true,
    summary: {
      operatorFactCount: 0,
      functionFactCount: 1,
      graphFactCount: 0,
      boundCount: 0,
      driftedCount: 0,
      unresolvedCount: 1,
      runtimeReady: false,
    },
    facts: [{
      factId: 'runtime:function:coalesce',
      assetKind: 'FUNCTION',
      assetRef: 'coalesce',
      factKind: 'RUNTIME',
      evidenceLevel: 'UNKNOWN',
      contractFingerprint: '',
      sourcePath: '/functions',
      occurrences: 1,
      dependencies: [],
      attributes: {},
    }],
    runtimeParity: [{
      assetKind: 'FUNCTION',
      assetRef: 'coalesce',
      runtimeProfile: 'bloge-core/default',
      state: 'RUNTIME_DISCOVERED',
      executableReady: false,
      declaredFingerprint: '',
      runtimeFingerprint: 'sha256:function',
      reasonCode: 'RG.AUTHORING.RUNTIME_FUNCTION_SIGNATURE_UNKNOWN',
      message: 'Runtime callable discovered without authoritative signature metadata.',
    }],
    reviewItems: [{
      code: 'RG.AUTHORING.RUNTIME_SIGNATURES_REQUIRED',
      level: 'WARNING',
      assetKind: 'SOURCE',
      assetRef: 'process-local',
      message: 'Runtime functions were discovered, but none exposes an authoritative signature contract.',
      action: 'Register a provider.',
    }],
    diagnostics: [],
  };
}

function capabilityProjection(): VisualAuthoringFactProjection {
  return {
    ...runtimeProjection(),
    sourceKind: 'CAPABILITY_CATALOG',
    sourceId: 'support-capabilities.json',
    facts: [{
      ...runtimeProjection().facts[0],
      factId: 'capability:function:support.normalize',
      assetRef: 'support.normalize',
      factKind: 'DECLARATION',
      evidenceLevel: 'DECLARED',
    }],
    runtimeParity: [{
      ...runtimeProjection().runtimeParity[0],
      assetRef: 'support.normalize',
      state: 'DOCUMENTED_ONLY',
    }],
    authoringDocument: {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: {
        id: 'support-capabilities',
        name: 'Support Capabilities',
        version: '1.0.0',
      },
      functions: {
        'support.normalize': {
          signatures: ['(value: string) -> string'],
        },
      },
    },
  };
}

function dslProjection(): VisualAuthoringFactProjection {
  return {
    ...runtimeProjection(),
    sourceKind: 'DSL',
    sourceId: 'support-routing.bloge',
    facts: [{
      ...runtimeProjection().facts[0],
      factId: 'dsl:graph:supportRouting',
      assetKind: 'GRAPH',
      assetRef: 'supportRouting',
      factKind: 'TOPOLOGY',
      evidenceLevel: 'DECLARED',
    }],
    runtimeParity: [],
    reviewItems: [],
  };
}
