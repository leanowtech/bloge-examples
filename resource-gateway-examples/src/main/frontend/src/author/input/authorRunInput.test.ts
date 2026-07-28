import { describe, expect, it } from 'vitest';

import type { SchemaEnvelope } from '../../types';
import {
  assessRunInput,
  compileTaskRunContext,
  graphInputFields,
  isSensitiveSchema,
  reconcileRunInputWithSchema,
} from './authorRunInput';

const schema: SchemaEnvelope = {
  format: 'json-schema',
  version: '2020-12',
  schema: {
    type: 'object',
    additionalProperties: false,
    required: ['customerId', 'request'],
    properties: {
      customerId: { type: 'string', minLength: 2, description: 'Stable customer key' },
      request: {
        type: 'object',
        required: ['amount'],
        properties: {
          amount: { type: 'number', minimum: 1 },
        },
      },
      password: { type: 'string', format: 'password' },
    },
  },
};

describe('author run input', () => {
  it('projects bindable graph fields and sensitive metadata', () => {
    expect(graphInputFields(schema)).toEqual([
      {
        name: 'customerId',
        path: 'customerId',
        type: 'string',
        required: true,
        description: 'Stable customer key',
        sensitive: false,
      },
      {
        name: 'request',
        path: 'request',
        type: 'object',
        required: true,
        description: '',
        sensitive: false,
      },
      {
        name: 'password',
        path: 'password',
        type: 'string',
        required: false,
        description: '',
        sensitive: true,
      },
    ]);
  });

  it('reports exact missing and invalid paths instead of a generic readiness badge', () => {
    const assessment = assessRunInput(schema, {
      customerId: 'x',
      request: { amount: 0 },
      unexpected: true,
    });

    expect(assessment.ready).toBe(false);
    expect(assessment.requiredFieldCount).toBe(3);
    expect(assessment.missingRequired).toEqual([]);
    expect(assessment.issues.map((issue) => [issue.path, issue.code])).toEqual([
      ['customerId', 'constraint'],
      ['request.amount', 'constraint'],
      ['unexpected', 'additional-property'],
    ]);
  });

  it('reports nested missing required fields and accepts a valid value', () => {
    expect(assessRunInput(schema, {
      customerId: 'customer-7',
      request: {},
    }).missingRequired).toEqual(['request.amount']);

    expect(assessRunInput(schema, {
      customerId: 'customer-7',
      request: { amount: 25 },
    })).toMatchObject({
      ready: true,
      issues: [],
      requiredFieldCount: 3,
    });
  });

  it('merges optional extras without allowing them to shadow contract input', () => {
    expect(compileTaskRunContext({
      runInput: { customerId: 'customer-7' },
      extras: { value: { trace: { id: 'trace-1' } } },
      raw: { value: {} },
      rawMode: false,
    })).toEqual({
      value: {
        customerId: 'customer-7',
        trace: { id: 'trace-1' },
      },
      source: 'structured',
      conflicts: [],
    });

    expect(compileTaskRunContext({
      runInput: { customerId: 'customer-7' },
      extras: { value: { customerId: 'shadowed' } },
      raw: { value: {} },
      rawMode: false,
    })).toMatchObject({
      value: { customerId: 'customer-7' },
      conflicts: ['customerId'],
      error: 'Context Extras cannot replace Graph Input: customerId.',
    });
  });

  it('uses raw JSON only after explicit takeover and preserves parse errors', () => {
    expect(compileTaskRunContext({
      runInput: { customerId: 'structured' },
      extras: { value: { traceId: 'trace-1' } },
      raw: { value: { customerId: 'raw' }, error: 'raw is invalid' },
      rawMode: true,
    })).toEqual({
      value: { customerId: 'raw' },
      error: 'raw is invalid',
      source: 'raw',
      conflicts: [],
    });
  });

  it('recognizes governance-sensitive schema extensions', () => {
    expect(isSensitiveSchema({ type: 'string', writeOnly: true })).toBe(true);
    expect(isSensitiveSchema({ type: 'string', 'x-classification': 'restricted' })).toBe(true);
    expect(isSensitiveSchema({ type: 'string' })).toBe(false);
  });

  it('reconciles contract changes without losing authored values', () => {
    expect(reconcileRunInputWithSchema(
      schema,
      {
        customerId: 'authored',
        legacy: 'remove-me',
      },
      {
        customerId: 'sample',
        request: { amount: 10 },
        password: '',
      },
    )).toEqual({
      customerId: 'authored',
      request: { amount: 10 },
      password: '',
    });
  });
});
