import { describe, expect, it } from 'vitest';

import type { VisualLibraryAuthoringDocument } from '../../types';
import {
  compactValueSchema,
  functionArgsArray,
  functionArgsObject,
  functionSignatureSchema,
  operatorInputSchema,
  operatorOutputSchema,
} from './libraryAssetSchema';

const document: VisualLibraryAuthoringDocument = {
  schemaVersion: 'bloge.visualLibraryAuthoring.v1',
  library: { id: 'support' },
  types: {
    Ticket: {
      fields: {
        id: 'string',
        'tier?': { enum: ['free', 'pro'] },
      },
    },
  },
  operators: {
    'support:classify': {
      input: { ticket: 'Ticket', tags: 'string[]' },
      output: { decision: 'string | null' },
    },
  },
  functions: {
    'support.first': {
      signatures: ['(values: Ticket[], fallback?: string) -> Ticket'],
    },
  },
};

describe('Library asset schema projection', () => {
  it('resolves named records, optional fields, arrays, unions, and enums', () => {
    expect(operatorInputSchema(document, 'support:classify').schema).toMatchObject({
      type: 'object',
      required: ['ticket', 'tags'],
      properties: {
        ticket: {
          type: 'object',
          required: ['id'],
          properties: {
            id: { type: 'string' },
            tier: { type: 'string', enum: ['free', 'pro'] },
          },
        },
        tags: { type: 'array', items: { type: 'string' } },
      },
    });
    expect(operatorOutputSchema(document, 'support:classify').schema).toMatchObject({
      properties: {
        decision: {
          oneOf: [{ type: 'string' }, { type: 'null' }],
        },
      },
    });
    expect(compactValueSchema('MissingType')).toMatchObject({
      type: 'object',
      title: 'MissingType',
    });
  });

  it('projects function arguments as named fields and round-trips the ordered wire array', () => {
    const projection = functionSignatureSchema(document, 'support.first');
    expect(projection.parameters).toEqual([
      { name: 'values', optional: false },
      { name: 'fallback', optional: true },
    ]);
    expect(projection.inputSchema.schema).toMatchObject({
      required: ['values'],
      properties: {
        values: { type: 'array' },
        fallback: { type: 'string' },
      },
    });
    expect(projection.outputSchema.schema).toMatchObject({
      type: 'object',
      required: ['id'],
    });

    const object = functionArgsObject([[{ id: 't-1' }], 'default'], projection);
    expect(object).toEqual({ values: [{ id: 't-1' }], fallback: 'default' });
    expect(functionArgsArray(object, projection)).toEqual([[{ id: 't-1' }], 'default']);
    expect(functionArgsArray({ values: [] }, projection)).toEqual([[]]);
  });
});
