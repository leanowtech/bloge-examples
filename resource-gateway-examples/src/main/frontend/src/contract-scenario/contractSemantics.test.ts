import { describe, expect, it } from 'vitest';

import {
  contractDraftFromGraphDraft,
  graphDraftWithContractSemantics,
} from './domain';
import { graphDraft } from './testFixtures';

describe('graph Contract semantics projection', () => {
  it('persists author-edited semantics in the graph Contract layout without changing schemas', () => {
    const graph = graphDraft();
    const original = contractDraftFromGraphDraft(graph, fingerprint('a'));
    const edited = {
      ...original,
      errorContract: [{
        code: 'CRM_UNAVAILABLE',
        type: 'DEPENDENCY',
        description: 'CRM could not be reached.',
        retryable: true,
      }],
      executionSemantics: {
        effect: 'WRITE' as const,
        idempotency: 'IDEMPOTENCY_KEY:/requestId',
        streaming: false,
        durable: true,
        sideEffectProtocol: {
          protocol: 'bloge.sideEffectProtocol.v1',
          reconcilerRef: 'crm.reconcile',
          reversible: true,
          metadata: { owner: 'customer-platform' },
        },
      },
      invariants: [{
        invariantId: 'request-id-required',
        phase: 'PRECONDITION' as const,
        expression: 'exists(ctx.requestId)',
        description: 'Every write has an idempotency coordinate.',
        severity: 'ERROR' as const,
      }],
      compatibilityPolicy: {
        mode: 'BACKWARD' as const,
        unknownBlocksAutomaticMigration: true,
      },
    };

    const persisted = graphDraftWithContractSemantics(graph, edited);
    const projected = contractDraftFromGraphDraft(persisted, fingerprint('b'));

    expect(projected.inputSchema).toEqual(original.inputSchema);
    expect(projected.outputSchema).toEqual(original.outputSchema);
    expect(projected.errorContract).toEqual(edited.errorContract);
    expect(projected.executionSemantics).toEqual(edited.executionSemantics);
    expect(projected.invariants).toEqual(edited.invariants);
    expect(projected.compatibilityPolicy).toEqual(edited.compatibilityPolicy);
    expect(persisted.visualLayout).toMatchObject({
      graphContract: {
        contractSemantics: {
          schemaVersion: 'bloge.graphContractSemantics.v1',
        },
      },
    });
  });

  it('ignores malformed or future embedded semantics instead of inventing declarations', () => {
    const graph = graphDraft();
    const projected = contractDraftFromGraphDraft({
      ...graph,
      visualLayout: {
        graphContract: {
          contractSemantics: {
            schemaVersion: 'bloge.graphContractSemantics.v2',
            executionSemantics: { effect: 'PURE' },
          },
        },
      },
    }, fingerprint('a'));

    expect(projected.executionSemantics.effect).toBe('UNKNOWN');
    expect(projected.errorContract).toEqual([]);
  });
});

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
