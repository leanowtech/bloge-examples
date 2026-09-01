import { describe, expect, it } from 'vitest';

import {
  buildApiResourceSaveCommand,
  formDraftFromLegacyPreview,
  formDraftFromOpenApiOperation,
  formDraftFromSpec,
  type ApiResourceSpec,
  type OpenApiPreview,
  type LegacyApiResourceReauthorPreview,
} from './model';

describe('simple API Resource authoring model', () => {
  it('turns flat examples into one server-owned Resource and Default Fixture command', () => {
    const command = buildApiResourceSaveCommand({
      resourceId: 'customer-profile',
      displayName: 'Customer profile',
      connectionId: 'crm',
      method: 'GET',
      path: '/customers/{id}',
      requestExample: '{"id":"c-1","active":true}',
      responseExample: '{"name":"Ada","score":42.5}',
      importedResource: null,
    });

    expect(command).toEqual({
      schemaVersion: 'bloge.apiResourceSaveCommand.v1',
      connection: { mode: 'EXISTING', connectionId: 'crm' },
      resource: {
        displayName: 'Customer profile',
        operation: {
          method: 'GET',
          path: '/customers/{id}',
          bindings: [
            { from: '$.id', to: { location: 'QUERY', name: 'id' } },
            { from: '$.active', to: { location: 'QUERY', name: 'active' } },
          ],
        },
        contract: {
          input: {
            format: 'json-schema', version: '2020-12',
            schema: {
              type: 'object',
              properties: { id: { type: 'string' }, active: { type: 'boolean' } },
              required: ['id', 'active'], additionalProperties: false,
            },
          },
          output: {
            format: 'json-schema', version: '2020-12',
            schema: {
              type: 'object',
              properties: { name: { type: 'string' }, score: { type: 'number' } },
              required: ['name', 'score'], additionalProperties: false,
            },
          },
        },
        response: { success: { kind: 'HTTP_STATUS', codes: [200] } },
        effect: { kind: 'READ_ONLY' },
        examples: [{ name: 'default', input: { id: 'c-1', active: true }, output: { name: 'Ada', score: 42.5 } }],
      },
      defaultFixture: {
        kind: 'FROM_EXAMPLES', displayName: 'Customer profile default', exampleNames: ['default'],
      },
    });
  });

  it('keeps non-GET operations fixture-only and rejects unsupported example shapes', () => {
    const base = {
      resourceId: 'update-profile', displayName: 'Update profile', connectionId: 'crm',
      method: 'POST' as const, path: '/customers', requestExample: '{"name":"Ada"}',
      responseExample: '{"ok":true}',
      importedResource: null,
    };
    expect(buildApiResourceSaveCommand(base).resource.effect).toEqual({ kind: 'FIXTURE_ONLY_WRITE' });
    expect(() => buildApiResourceSaveCommand({ ...base, requestExample: '{"items":[]}' }))
      .toThrow('supports named string, number, boolean, or object fields');
  });

  it('restores the concise form from committed authority without exposing projections', () => {
    const command = buildApiResourceSaveCommand({
      resourceId: 'profile', displayName: 'Profile', connectionId: 'crm', method: 'GET', path: '/profile',
      requestExample: '{"id":"c-1"}', responseExample: '{"name":"Ada"}',
      importedResource: null,
    });
    const spec = {
      schemaVersion: 'bloge.apiResourceSpec.v1', resourceId: 'profile', revision: 3,
      fingerprint: `sha256:${'a'.repeat(64)}`, connectionId: 'crm', status: 'DRAFT',
      ...command.resource,
    } as ApiResourceSpec;

    expect(formDraftFromSpec(spec)).toEqual({
      resourceId: 'profile', displayName: 'Profile', connectionId: 'crm', method: 'GET', path: '/profile',
      requestExample: '{\n  "id": "c-1"\n}', responseExample: '{\n  "name": "Ada"\n}',
      importedResource: expect.objectContaining({ displayName: 'Profile' }),
    });
  });

  it('applies a previewed operation without losing its exact transport bindings', () => {
    const base = buildApiResourceSaveCommand({
      resourceId: 'manual', displayName: 'Manual', connectionId: 'crm', method: 'GET', path: '/customers',
      requestExample: '{"customerId":"c-1"}', responseExample: '{"name":"Ada"}', importedResource: null,
    });
    const suggested = {
      ...base.resource,
      displayName: 'Get customer',
      operation: {
        method: 'GET' as const, path: '/customers/{customerId}',
        bindings: [{ from: '$.customerId', to: { location: 'PATH' as const, name: 'customerId' } }],
      },
      examples: [{ name: 'openapi-example', input: { customerId: 'c-1' }, output: { name: 'Ada' } }],
    };
    const operation = {
      operationId: 'getCustomer', method: 'GET' as const, path: '/customers/{customerId}',
      suggestedResource: suggested, diagnostics: [],
    } satisfies OpenApiPreview['operations'][number];

    const draft = formDraftFromOpenApiOperation({
      resourceId: '', displayName: '', connectionId: 'crm', method: 'GET', path: '/',
      requestExample: '{}', responseExample: '{}', importedResource: null,
    }, operation);

    expect(draft).toMatchObject({
      resourceId: 'getCustomer', displayName: 'Get customer', connectionId: 'crm',
      method: 'GET', path: '/customers/{customerId}',
    });
    expect(buildApiResourceSaveCommand(draft)).toMatchObject({
      resource: { operation: { bindings: suggested.operation.bindings } },
      defaultFixture: { exampleNames: ['openapi-example'] },
    });
  });

  it('applies a legacy preview while requiring a fresh visible Connection choice', () => {
    const base = buildApiResourceSaveCommand({
      resourceId: 'customer.get', displayName: 'Customer', connectionId: 'old-connection', method: 'GET',
      path: '/customers/{customerId}', requestExample: '{"customerId":"c-1"}',
      responseExample: '{"name":"Ada"}', importedResource: null,
    });
    const preview = {
      schemaVersion: 'bloge.legacyApiResourceReauthorPreview.v1',
      source: { kind: 'API_RESOURCE', resourceId: 'customer.get', sourceRevision: 0 },
      suggestedResource: {
        ...base.resource,
        operation: { ...base.resource.operation, bindings: [{
          from: '$.customerId', to: { location: 'PATH' as const, name: 'customerId' },
        }] },
        response: { success: { kind: 'BODY_MATCH' as const, path: '$.code', values: [0] },
          outputPath: '$.data' },
        examples: [{ name: 'legacy-example', input: { customerId: 'c-1' }, output: { name: 'Ada' } }],
      },
      diagnostics: [{ code: 'CONNECTION_SELECTION_REQUIRED', message: 'Choose a committed Connection.' }],
    } satisfies LegacyApiResourceReauthorPreview;

    const draft = formDraftFromLegacyPreview(preview);

    expect(draft).toMatchObject({
      resourceId: 'customer.get', displayName: 'Customer', connectionId: '', method: 'GET',
      path: '/customers/{customerId}', importedResource: preview.suggestedResource,
    });
    expect(() => buildApiResourceSaveCommand(draft)).toThrow('Connection ID must be a simple identifier.');
    expect(buildApiResourceSaveCommand({ ...draft, connectionId: 'crm' })).toMatchObject({
      connection: { mode: 'EXISTING', connectionId: 'crm' },
      resource: {
        operation: { bindings: preview.suggestedResource.operation.bindings },
        response: preview.suggestedResource.response,
      },
      defaultFixture: { exampleNames: ['legacy-example'] },
    });
  });
});
