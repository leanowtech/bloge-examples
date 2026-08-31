import { describe, expect, it } from 'vitest';

import { buildApiResourceSaveCommand, formDraftFromSpec, type ApiResourceSpec } from './model';

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
    };
    expect(buildApiResourceSaveCommand(base).resource.effect).toEqual({ kind: 'FIXTURE_ONLY_WRITE' });
    expect(() => buildApiResourceSaveCommand({ ...base, requestExample: '{"items":[]}' }))
      .toThrow('supports named string, number, boolean, or object fields');
  });

  it('restores the concise form from committed authority without exposing projections', () => {
    const command = buildApiResourceSaveCommand({
      resourceId: 'profile', displayName: 'Profile', connectionId: 'crm', method: 'GET', path: '/profile',
      requestExample: '{"id":"c-1"}', responseExample: '{"name":"Ada"}',
    });
    const spec = {
      schemaVersion: 'bloge.apiResourceSpec.v1', resourceId: 'profile', revision: 3,
      fingerprint: `sha256:${'a'.repeat(64)}`, connectionId: 'crm', status: 'DRAFT',
      ...command.resource,
    } as ApiResourceSpec;

    expect(formDraftFromSpec(spec)).toEqual({
      resourceId: 'profile', displayName: 'Profile', connectionId: 'crm', method: 'GET', path: '/profile',
      requestExample: '{\n  "id": "c-1"\n}', responseExample: '{\n  "name": "Ada"\n}',
    });
  });
});
