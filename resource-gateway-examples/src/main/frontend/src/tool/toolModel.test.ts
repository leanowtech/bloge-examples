import { describe, expect, it } from 'vitest';

import {
  publicationOperatorRef,
  toolSignatureFromDraft,
  type ToolDraftLike,
} from './toolModel';
import type { ToolCoordinate } from '../spine/authorSpine';

const draft: ToolDraftLike = {
  graphName: 'loan-tool',
  status: 'DRAFT',
  inputSchema: { format: 'json-schema', version: '2020-12', schema: { type: 'object' } },
  outputSchema: { format: 'json-schema', version: '2020-12', schema: { type: 'string' } },
  nodes: [{ id: 'n1', operatorRef: 'resource:loan' }],
};

describe('toolSignatureFromDraft', () => {
  it('projects only identity, draft I/O schemas, and draft state', () => {
    expect(toolSignatureFromDraft(draft, { toolId: 'tool-loan', toolName: 'Loan tool' })).toEqual({
      toolId: 'tool-loan',
      toolName: 'Loan tool',
      input: draft.inputSchema,
      output: draft.outputSchema,
      state: 'draft',
      schemaState: 'typed',
    });
  });

  it('projects a published status and immutable publication metadata', () => {
    expect(toolSignatureFromDraft(
      { ...draft, status: 'PUBLISHED', publicationId: 'pub-42', publicationRevision: 3 },
      'tool-loan',
      'Loan tool',
    )).toEqual({
      toolId: 'tool-loan',
      toolName: 'Loan tool',
      input: draft.inputSchema,
      output: draft.outputSchema,
      state: 'published',
      schemaState: 'typed',
      publicationId: 'pub-42',
      publicationRevision: 3,
    });
  });

  it('uses the navigation coordinate and stays honest when draft facts are missing', () => {
    const coordinate: ToolCoordinate = {
      toolId: 'tool-loan',
      toolName: 'Loan tool',
      stage: 'publish',
    };
    expect(toolSignatureFromDraft(undefined, coordinate)).toEqual({
      toolId: 'tool-loan',
      toolName: 'Loan tool',
      state: 'unknown',
      schemaState: 'unknown',
    });
  });

  it('marks a real draft with incomplete schemas as opaque instead of typed', () => {
    expect(toolSignatureFromDraft(
      { graphName: 'loan-tool', status: 'DRAFT', inputSchema: draft.inputSchema },
      { toolId: 'tool-loan', toolName: 'Loan tool', stage: 'define' },
    )).toEqual({
      toolId: 'tool-loan',
      toolName: 'Loan tool',
      input: draft.inputSchema,
      state: 'draft',
      schemaState: 'opaque',
    });
  });

  it('uses the actual publication receipt as the only published transition', () => {
    expect(toolSignatureFromDraft(
      { ...draft, status: 'DRAFT' },
      { toolId: 'tool-loan', toolName: 'Loan tool', stage: 'publish' },
      { publicationId: 'pub-42', publicationRevision: 3 },
    )).toEqual({
      toolId: 'tool-loan',
      toolName: 'Loan tool',
      input: draft.inputSchema,
      output: draft.outputSchema,
      state: 'published',
      schemaState: 'typed',
      publicationId: 'pub-42',
      publicationRevision: 3,
    });
  });
});

describe('publicationOperatorRef', () => {
  it('returns the existing publication coordinate without inventing a protocol', () => {
    expect(publicationOperatorRef('pub-42', 3)).toBe('publication:pub-42');
  });

  it.each([
    ['', 1],
    ['pub-42', 0],
    ['pub-42', -1],
    ['pub-42', 1.5],
    ['pub-42', Number.NaN],
  ])('rejects invalid publication %j revision %j', (id, revision) => {
    expect(() => publicationOperatorRef(id, revision)).toThrow();
  });
});
