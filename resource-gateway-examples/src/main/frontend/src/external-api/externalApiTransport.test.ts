import { describe, expect, it, vi } from 'vitest';

import { saveExternalApi, type ExternalApiRequester } from './externalApiTransport';
import type { ExternalApiFormModel } from './externalApiModel';

const form: ExternalApiFormModel = {
  resourceId: 'orders.lookup',
  displayName: 'Order lookup',
  urlTemplate: 'https://api.example.test/orders/{id}',
  method: 'GET',
  params: [{ name: 'id', in: 'path', from: 'ctx.params.id' }],
  responseProtocol: { kind: 'HttpStatus' },
  payloadPath: 'data',
  outputSchema: { source: 'manual', schema: { type: 'object', additionalProperties: true } },
};

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('saveExternalApi', () => {
  it('performs descriptor PUT, contract PUT, then catalog refresh in order', async () => {
    const calls: string[] = [];
    const request: ExternalApiRequester = vi.fn(async (input, init) => {
      const url = String(input);
      calls.push(`${init?.method ?? 'GET'} ${url}`);
      if (url === '/api/visual/operators') return response({ operators: [{ operatorRef: 'resource:orders.lookup' }] });
      return response({ resourceId: form.resourceId });
    });

    const result = await saveExternalApi(form, request);

    expect(calls).toEqual([
      'PUT /admin/resources/orders.lookup',
      'PUT /admin/resource-design-contracts/orders.lookup',
      'GET /api/visual/operators',
    ]);
    expect(result.catalog.operators).toEqual([{ operatorRef: 'resource:orders.lookup' }]);
  });

  it('stops after descriptor failure without leaking response body or refreshing', async () => {
    const request: ExternalApiRequester = vi.fn(async () => response({ secret: 'do-not-show' }, 422));
    await expect(saveExternalApi(form, request)).rejects.toThrow('External API descriptor save failed (422).');
    await expect(saveExternalApi(form, request)).rejects.not.toThrow('do-not-show');
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('stops after contract failure and leaves retryable error context', async () => {
    const request: ExternalApiRequester = vi.fn()
      .mockResolvedValueOnce(response({ resourceId: form.resourceId }))
      .mockResolvedValueOnce(response({ detail: 'sensitive response' }, 409));

    await expect(saveExternalApi(form, request)).rejects.toThrow('External API contract save failed (409).');
    expect(request).toHaveBeenCalledTimes(2);
    expect(request).not.toHaveBeenCalledWith('/api/visual/operators', expect.anything());
  });
});
