import { describe, expect, it } from 'vitest';

import {
  externalApiFormToDescriptor,
  inferSchema,
  MAX_STRUCTURED_PROPERTIES,
  structuredObjectSchema,
  toDesignContract,
  type ExternalApiFormModel,
} from './externalApiModel';

const baseForm = (responseProtocol: ExternalApiFormModel['responseProtocol']): ExternalApiFormModel => ({
  resourceId: 'loan.getProfile',
  displayName: 'Get profile',
  urlTemplate: 'https://api.example.test/{applicantId}',
  method: 'GET',
  params: [
    { name: 'applicantId', in: 'path', from: 'ctx.params.applicantId' },
    { name: 'page', in: 'query', from: 'ctx.params.page' },
    { name: 'X-Request-Id', in: 'header', from: 'ctx.params.requestId' },
  ],
  responseProtocol,
  payloadPath: 'data',
  outputSchema: {
    source: 'manual',
    schema: { type: 'object', properties: { score: { type: 'integer' } }, required: ['score'] },
  },
});

describe('externalApiFormToDescriptor', () => {
  it.each([
    ['HttpStatus', { kind: 'HttpStatus' }],
    ['StatusCodes', { kind: 'StatusCodes', success: [200, 201] }],
    ['BodyFlag', { kind: 'BodyFlag', flagField: 'ok' }],
    ['BodyCode', { kind: 'BodyCode', codeField: 'code', successCodes: [0, 'SUCCESS'], messageField: 'message' }],
  ] as const)('maps the %s protocol and all HTTP parameter locations', (_name, protocol) => {
    expect(externalApiFormToDescriptor(baseForm(protocol))).toEqual({
      resourceId: 'loan.getProfile',
      urlTemplate: 'https://api.example.test/{applicantId}',
      method: 'GET',
      defaultHeaders: { Accept: 'application/json' },
      authStrategy: null,
      defaultTimeout: 'PT5S',
      parameterMapping: {
        pathExpressions: { applicantId: 'ctx.params.applicantId' },
        queryExpressions: { page: 'ctx.params.page' },
        headerExpressions: { 'X-Request-Id': 'ctx.params.requestId' },
        cookieExpressions: {},
        bodyExpression: null,
      },
      responseProtocol: protocol.kind === 'HttpStatus'
        ? { type: 'httpStatus' }
        : protocol.kind === 'StatusCodes'
          ? { type: 'statusCodes', successCodes: [200, 201] }
          : protocol.kind === 'BodyFlag'
            ? { type: 'bodyFlag', flagPath: 'ok' }
            : { type: 'bodyCode', codePath: 'code', successValues: [0, 'SUCCESS'], messagePath: 'message' },
      payloadPath: 'data',
    });
  });

  it('maps an inferred response schema without mutating the form', () => {
    const sampleResponse = { data: { accepted: true, count: 2 } };
    const form: ExternalApiFormModel = {
      ...baseForm({ kind: 'HttpStatus' }),
      outputSchema: { source: 'inferred', sampleResponse, schema: inferSchema(sampleResponse) },
    };
    const before = structuredClone(form);
    expect(externalApiFormToDescriptor(form).responseProtocol).toEqual({ type: 'httpStatus' });
    expect(form).toEqual(before);
  });
});

describe('toDesignContract', () => {
  it('emits the backend visual contract envelope with request and response schemas', () => {
    expect(toDesignContract(baseForm({ kind: 'HttpStatus' }))).toEqual({
      contractId: 'loan.getProfile',
      resourceId: 'loan.getProfile',
      displayName: 'Get profile',
      description: '',
      tags: [],
      requestSchema: {
        format: 'json-schema',
        version: '2020-12',
        schema: {
          type: 'object',
          properties: {
            'X-Request-Id': { type: 'string' },
            applicantId: { type: 'string' },
            page: { type: 'string' },
          },
          required: ['X-Request-Id', 'applicantId', 'page'],
          additionalProperties: false,
        },
      },
      responseSchema: {
        format: 'json-schema',
        version: '2020-12',
        schema: { type: 'object', properties: { score: { type: 'integer' } }, required: ['score'] },
      },
      examples: {},
      status: 'ACTIVE',
    });
  });
});

describe('inferSchema', () => {
  it.each([
    [null, { type: 'null' }],
    [[], { type: 'array', items: {} }],
    [1, { type: 'integer' }],
    [1.5, { type: 'number' }],
    [true, { type: 'boolean' }],
    ['hello', { type: 'string' }],
  ])('infers %j as %j', (sample, expected) => expect(inferSchema(sample)).toEqual(expected));

  it('requires only non-null object properties and uses the first array item', () => {
    expect(inferSchema({ nullable: null, active: true, items: [{ id: 1 }, { id: 'ignored' }] })).toEqual({
      type: 'object',
      properties: {
        active: { type: 'boolean' },
        items: { type: 'array', items: { type: 'object', properties: { id: { type: 'integer' } }, required: ['id'], additionalProperties: false } },
        nullable: { type: 'null' },
      },
      required: ['active', 'items'],
      additionalProperties: false,
    });
  });

  it('fails safe at the depth and node boundaries, deterministically and immutably', () => {
    const deep: Record<string, unknown> = {};
    let cursor = deep;
    for (let i = 0; i < 8; i += 1) {
      cursor.next = {};
      cursor = cursor.next as Record<string, unknown>;
    }
    const wide = Object.fromEntries(Array.from({ length: 505 }, (_, i) => [`field${i}`, i]));
    const sample = { deep, wide };
    const before = structuredClone(sample);
    const first = inferSchema(sample);
    expect(first).toEqual(inferSchema(sample));
    expect(sample).toEqual(before);
    expect(first.properties).toMatchObject({ deep: { type: 'object' }, wide: { type: 'object' } });
    expect((first.properties as Record<string, any>).deep.properties.next.properties.next.properties.next.properties.next.properties.next.properties.next).toEqual({
      additionalProperties: true,
    });
    expect(Object.values((first.properties as Record<string, any>).wide.properties)).toContainEqual({ additionalProperties: true });
  });
});

describe('structuredObjectSchema', () => {
  it('emits a deterministic typed object schema from property rows', () => {
    expect(structuredObjectSchema([
      { name: 'name', type: 'string', required: true },
      { name: 'score', type: 'integer', required: false },
    ])).toEqual({
      type: 'object',
      properties: { name: { type: 'string' }, score: { type: 'integer' } },
      required: ['name'],
      additionalProperties: false,
    });
  });

  it('orders schema properties by Unicode code point regardless of locale', () => {
    const rows = [
      { name: 'b', type: 'string' as const, required: false },
      { name: 'a😀', type: 'string' as const, required: false },
      { name: 'a𐀀', type: 'string' as const, required: false },
      { name: 'a', type: 'string' as const, required: false },
    ];
    const first = structuredObjectSchema(rows);
    const second = structuredObjectSchema([...rows].reverse());
    expect(Object.keys(first.properties as Record<string, unknown>)).toEqual(['a', 'a𐀀', 'a😀', 'b']);
    expect(JSON.stringify(first)).toBe(JSON.stringify(second));
  });

  it('rejects invalid rows instead of silently dropping them', () => {
    expect(() => structuredObjectSchema([
      { name: ' ', type: 'string', required: false },
    ])).toThrow();
    expect(() => structuredObjectSchema([
      { name: 'name', type: 'string', required: false },
      { name: 'name', type: 'boolean', required: true },
    ])).toThrow();
  });

  it('enforces the bounded property budget', () => {
    const rows = Array.from({ length: MAX_STRUCTURED_PROPERTIES + 1 }, (_, index) => ({
      name: `field${index}`,
      type: 'string' as const,
      required: false,
    }));
    expect(() => structuredObjectSchema(rows)).toThrow('too many properties');
  });
});
