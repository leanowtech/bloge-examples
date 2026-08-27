// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ExternalApiAuthoring from './ExternalApiAuthoring';
import type { ExternalApiFormModel } from './externalApiModel';
import type { ExternalApiSaveResult } from './externalApiTransport';

const saved: ExternalApiSaveResult = {
  descriptor: {
    resourceId: 'orders.lookup', urlTemplate: 'https://api.example.test/orders/{id}', method: 'GET',
    defaultHeaders: { Accept: 'application/json' }, authStrategy: null, defaultTimeout: 'PT5S',
    parameterMapping: { pathExpressions: {}, queryExpressions: {}, headerExpressions: {}, cookieExpressions: {}, bodyExpression: null },
    responseProtocol: { type: 'httpStatus' }, payloadPath: '',
  },
  contract: {
    contractId: 'orders.lookup', resourceId: 'orders.lookup', displayName: 'Order lookup',
    description: '', tags: [], requestSchema: { format: 'json-schema', version: '2020-12', schema: {} },
    responseSchema: { format: 'json-schema', version: '2020-12', schema: { additionalProperties: true } },
    examples: {}, status: 'ACTIVE',
  },
  catalog: { operators: [{ operatorRef: 'resource:orders.lookup' }] },
};

describe('ExternalApiAuthoring', () => {
  let root: Root;
  let host: HTMLDivElement;
  let save: ReturnType<typeof vi.fn>;
  let onCatalogRefresh: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    save = vi.fn(async (_form: ExternalApiFormModel) => saved);
    onCatalogRefresh = vi.fn();
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
  });

  it('opens one external API authoring object with request, response, and output schema sections', async () => {
    await act(async () => root.render(<ExternalApiAuthoring save={save} onCatalogRefresh={onCatalogRefresh} />));
    expect(host.querySelector('[data-testid="external-api-form"]')).toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="add-external-api"]')?.click());
    expect(host.querySelector('[data-testid="external-api-form"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="external-api-request"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="external-api-response"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="external-api-output-schema"]')).not.toBeNull();
    expect(host.textContent).toContain('Add external API');
  });

  it('switches protocol fields and supports path/query/header parameters plus inference', async () => {
    await act(async () => root.render(<ExternalApiAuthoring save={save} onCatalogRefresh={onCatalogRefresh} />));
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="add-external-api"]')?.click());
    expect(host.querySelector('[data-testid="external-api-protocol-body-code"]')).toBeNull();
    await act(async () => {
      const select = host.querySelector<HTMLSelectElement>('[data-testid="external-api-protocol"]')!;
      select.value = 'BodyCode';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(host.querySelector('[data-testid="external-api-protocol-body-code"]')).not.toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="external-api-add-param"]')?.click());
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="external-api-add-param"]')?.click());
    expect(host.querySelectorAll('[data-testid^="external-api-param-location-"]')).toHaveLength(3);
    await act(async () => {
      const mode = host.querySelector<HTMLSelectElement>('[data-testid="external-api-schema-mode"]')!;
      mode.value = 'inferred';
      mode.dispatchEvent(new Event('change', { bubbles: true }));
    });
    const sample = host.querySelector<HTMLTextAreaElement>('[data-testid="external-api-inferred-sample"]')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(sample, '{"ok":true}');
      sample.dispatchEvent(new Event('input', { bubbles: true }));
      host.querySelector<HTMLButtonElement>('[data-testid="external-api-infer"]')?.click();
    });
    expect(host.querySelector('[data-testid="external-api-inference-ready"]')).not.toBeNull();
  });

  it('saves a typed schema from structured controls without a JSON editor', async () => {
    await act(async () => root.render(<ExternalApiAuthoring save={save} onCatalogRefresh={onCatalogRefresh} />));
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="add-external-api"]')?.click());
    const fill = (testId: string, value: string) => {
      const input = host.querySelector<HTMLInputElement>(`[data-testid="${testId}"]`)!;
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    };
    await act(async () => {
      fill('external-api-resource-id', 'orders.typed');
      fill('external-api-display-name', 'Typed order');
      fill('external-api-url', 'https://api.example.test/orders');
    });
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="external-api-add-schema-property"]')?.click());
    await act(async () => fill('external-api-schema-property-name-0', 'orderId'));
    await act(async () => {
      const type = host.querySelector<HTMLSelectElement>('[data-testid="external-api-schema-property-type-0"]')!;
      type.value = 'integer';
      type.dispatchEvent(new Event('change', { bubbles: true }));
      host.querySelector<HTMLInputElement>('[data-testid="external-api-schema-property-required-0"]')?.click();
      host.querySelector<HTMLButtonElement>('[data-testid="external-api-save"]')?.click();
    });
    expect(host.querySelector('[data-testid="external-api-manual-schema"]')).toBeNull();
    expect(save).toHaveBeenCalledWith(expect.objectContaining({
      outputSchema: {
        source: 'structured',
        schema: {
          type: 'object',
          properties: { orderId: { type: 'integer' } },
          required: ['orderId'],
          additionalProperties: false,
        },
      },
    }));
  });

  it('fails closed when a structured property is incomplete', async () => {
    await act(async () => root.render(<ExternalApiAuthoring save={save} onCatalogRefresh={onCatalogRefresh} />));
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="add-external-api"]')?.click());
    const fill = (testId: string, value: string) => {
      const input = host.querySelector<HTMLInputElement>(`[data-testid="${testId}"]`)!;
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    };
    await act(async () => {
      fill('external-api-resource-id', 'orders.invalid');
      fill('external-api-display-name', 'Invalid order');
      fill('external-api-url', 'https://api.example.test/orders');
    });
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="external-api-add-schema-property"]')?.click());
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="external-api-save"]')?.click());
    expect(save).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="external-api-error"]')?.textContent).toContain('needs a name');
  });

  it('saves once, refreshes the catalog, and shows an opaque-schema warning card', async () => {
    await act(async () => root.render(<ExternalApiAuthoring save={save} onCatalogRefresh={onCatalogRefresh} />));
    await act(async () => host.querySelector<HTMLButtonElement>('[data-testid="add-external-api"]')?.click());
    const fill = (testId: string, value: string) => {
      const input = host.querySelector<HTMLInputElement>(`[data-testid="${testId}"]`)!;
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    };
    await act(async () => {
      fill('external-api-resource-id', 'orders.lookup');
      fill('external-api-display-name', 'Order lookup');
      fill('external-api-url', 'https://api.example.test/orders/{id}');
    });
    await act(async () => {
      const mode = host.querySelector<HTMLSelectElement>('[data-testid="external-api-schema-mode"]')!;
      mode.value = 'manual';
      mode.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await act(async () => {
      host.querySelector<HTMLButtonElement>('[data-testid="external-api-save"]')?.click();
      await Promise.resolve();
    });
    expect(save).toHaveBeenCalledTimes(1);
    expect(onCatalogRefresh).toHaveBeenCalledWith(saved.catalog);
    expect(host.querySelector('[data-testid="external-api-card"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="external-api-opaque-warning"]')).not.toBeNull();
    expect(host.textContent).not.toContain('sensitive response');
  });
});
