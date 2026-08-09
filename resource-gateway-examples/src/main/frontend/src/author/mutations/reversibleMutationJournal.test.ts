import { describe, expect, it } from 'vitest';

import {
  createMutation,
  initialMutationJournal,
  markSavedCheckpoint,
  mutationFingerprint,
  mutationJournalForRecovery,
  projectNodeDeletionImpact,
  recordMutation,
  redoMutation,
  restoreMutationJournal,
  undoMutation,
  type MutationKind,
} from './reversibleMutationJournal';

interface TestSnapshot {
  nodes: string[];
  fixtures: Record<string, string>;
  tests: Record<string, string[]>;
  outputNodeId: string;
}

const before: TestSnapshot = {
  nodes: ['profile', 'decision'],
  fixtures: { profile: '{"risk":12}' },
  tests: { profile: ['golden', 'boundary'] },
  outputNodeId: 'decision',
};

const after: TestSnapshot = {
  nodes: ['decision'],
  fixtures: {},
  tests: {},
  outputNodeId: 'decision',
};

describe('ReversibleMutationJournal', () => {
  it.each<MutationKind>([
    'ADD_NODE',
    'REMOVE_NODE',
    'MOVE_NODE',
    'ADD_EDGE',
    'REMOVE_EDGE',
    'INPUT_BINDING',
    'NODE_CONFIG',
    'FIXTURE',
    'DECISION_TABLE',
    'TRANSFORM',
    'TEST_SUITE',
    'CONTEXT',
    'GRAPH_CONTRACT',
    'GRAPH_METADATA',
    'RUNTIME_BINDING',
    'IMPORT',
    'AUTO_LAYOUT',
    'OUTPUT_BINDING',
    'SCENARIO',
    'OTHER',
  ])('round-trips the %s mutation class', (kind) => {
    const mutation = createMutation({
      mutationId: `mutation-${kind}`,
      kind,
      label: kind,
      subjectRef: 'graph',
      before,
      after,
      occurredAt: 100,
    });
    const recorded = recordMutation(initialMutationJournal<TestSnapshot>(), mutation);
    expect(undoMutation(recorded)?.snapshot).toEqual(before);
    expect(redoMutation(undoMutation(recorded)!.journal)?.snapshot).toEqual(after);
  });

  it('round-trips one atomic mutation without losing associated assets', () => {
    const mutation = createMutation({
      mutationId: 'mutation-1',
      kind: 'REMOVE_NODE',
      label: 'Delete profile',
      subjectRef: 'profile',
      before,
      after,
      impact: [{ kind: 'TEST_CASE', count: 2, refs: ['golden', 'boundary'], severity: 'DESTRUCTIVE' }],
      occurredAt: 100,
    });
    const recorded = recordMutation(initialMutationJournal<TestSnapshot>(), mutation);

    const undone = undoMutation(recorded);
    expect(undone?.snapshot).toEqual(before);
    expect(undone?.journal.past).toHaveLength(0);
    expect(undone?.journal.future).toHaveLength(1);

    const redone = redoMutation(undone!.journal);
    expect(redone?.snapshot).toEqual(after);
    expect(redone?.journal.past).toHaveLength(1);
    expect(redone?.journal.future).toHaveLength(0);
  });

  it('keeps 100 undo/redo cycles canonical and duplicate-free', () => {
    const mutation = createMutation({
      mutationId: 'mutation-1',
      kind: 'REMOVE_NODE',
      label: 'Delete profile',
      subjectRef: 'profile',
      before,
      after,
      occurredAt: 100,
    });
    let journal = recordMutation(initialMutationJournal<TestSnapshot>(), mutation);
    let snapshot = after;

    for (let index = 0; index < 100; index += 1) {
      const undone = undoMutation(journal);
      expect(undone).not.toBeNull();
      snapshot = undone!.snapshot;
      journal = undone!.journal;
      expect(snapshot).toEqual(before);

      const redone = redoMutation(journal);
      expect(redone).not.toBeNull();
      snapshot = redone!.snapshot;
      journal = redone!.journal;
      expect(snapshot).toEqual(after);
    }

    expect(snapshot.nodes).toEqual(['decision']);
    expect(Object.keys(snapshot.fixtures)).toHaveLength(0);
    expect(journal.past).toHaveLength(1);
  });

  it('coalesces rapid edits on the same subject while preserving the first before-state', () => {
    const first = createMutation({
      mutationId: 'mutation-1',
      kind: 'NODE_CONFIG',
      label: 'Edit decision',
      subjectRef: 'decision',
      coalesceKey: 'node-config:decision',
      before: { ...before, outputNodeId: '' },
      after: { ...before, outputNodeId: 'd' },
      occurredAt: 100,
    });
    const second = createMutation({
      mutationId: 'mutation-2',
      kind: 'NODE_CONFIG',
      label: 'Edit decision',
      subjectRef: 'decision',
      coalesceKey: 'node-config:decision',
      before: first.after,
      after: before,
      occurredAt: 500,
    });
    const journal = recordMutation(
      recordMutation(initialMutationJournal<TestSnapshot>(), first),
      second,
    );

    expect(journal.past).toHaveLength(1);
    expect(journal.past[0].before.outputNodeId).toBe('');
    expect(journal.past[0].after.outputNodeId).toBe('decision');
  });

  it('enforces both count and byte budgets and records dropped history', () => {
    let journal = initialMutationJournal<TestSnapshot>({ maxEntries: 2, maxBytes: 2_000 });
    for (let index = 0; index < 4; index += 1) {
      journal = recordMutation(journal, createMutation({
        mutationId: `mutation-${index}`,
        kind: 'OTHER',
        label: `Mutation ${index}`,
        subjectRef: 'graph',
        before: { ...before, outputNodeId: `${index}` },
        after: { ...before, outputNodeId: `${index + 1}` },
        occurredAt: index * 1_000,
      }));
    }

    expect(journal.past.length).toBeLessThanOrEqual(2);
    expect(journal.totalBytes).toBeLessThanOrEqual(2_000);
    expect(journal.droppedMutationCount).toBeGreaterThan(0);
  });

  it('marks the authoritative saved checkpoint without clearing undo history', () => {
    const mutation = createMutation({
      mutationId: 'mutation-1',
      kind: 'REMOVE_NODE',
      label: 'Delete profile',
      subjectRef: 'profile',
      before,
      after,
      occurredAt: 100,
    });
    const recorded = recordMutation(initialMutationJournal<TestSnapshot>(), mutation);
    const checkpointed = markSavedCheckpoint(recorded, mutation.afterFingerprint);

    expect(checkpointed.savedCheckpointFingerprint).toBe(mutation.afterFingerprint);
    expect(checkpointed.past).toHaveLength(1);
  });

  it('rejects malformed persisted history instead of trusting browser storage', () => {
    expect(restoreMutationJournal<TestSnapshot>({
      past: [{ kind: 'REMOVE_NODE', before: { credentials: 'leak' } }],
      future: 'not-an-array',
    })).toEqual(initialMutationJournal<TestSnapshot>());
  });

  it('rejects unknown mutation and impact enums from untrusted recovery storage', () => {
    const valid = recordMutation(initialMutationJournal<TestSnapshot>(), createMutation({
      mutationId: 'mutation-1',
      kind: 'REMOVE_NODE',
      label: 'Delete profile',
      subjectRef: 'profile',
      before,
      after,
    }));

    expect(restoreMutationJournal<TestSnapshot>({
      ...valid,
      past: [{ ...valid.past[0], kind: 'EXECUTE_SCRIPT' }],
    })).toEqual(initialMutationJournal<TestSnapshot>());
    expect(restoreMutationJournal<TestSnapshot>({
      ...valid,
      past: [{
        ...valid.past[0],
        impact: [{ kind: 'CREDENTIAL', refs: ['secret'], severity: 'DESTRUCTIVE' }],
      }],
    })).toEqual(initialMutationJournal<TestSnapshot>());
  });

  it('lets the owning domain reject a structurally valid mutation with invalid snapshots', () => {
    const valid = recordMutation(initialMutationJournal<TestSnapshot>(), createMutation({
      mutationId: 'mutation-1',
      kind: 'REMOVE_NODE',
      label: 'Delete profile',
      subjectRef: 'profile',
      before,
      after,
      occurredAt: 100,
    }));
    const corrupted = {
      ...valid,
      past: [{ ...valid.past[0], before: { nodes: 'not-an-array' } }],
    };
    const isTestSnapshot = (value: unknown): value is TestSnapshot => Boolean(
      value
      && typeof value === 'object'
      && Array.isArray((value as TestSnapshot).nodes)
      && typeof (value as TestSnapshot).fixtures === 'object'
      && typeof (value as TestSnapshot).tests === 'object'
      && typeof (value as TestSnapshot).outputNodeId === 'string',
    );

    expect(restoreMutationJournal(corrupted, isTestSnapshot))
      .toEqual(initialMutationJournal<TestSnapshot>());
  });

  it('recomputes fingerprints when restoring legacy or hot-reloaded history', () => {
    const valid = recordMutation(initialMutationJournal<TestSnapshot>(), createMutation({
      mutationId: 'mutation-1',
      kind: 'REMOVE_NODE',
      label: 'Delete profile',
      subjectRef: 'profile',
      before,
      after,
      occurredAt: 100,
    }));
    const persisted = {
      ...valid,
      past: [{
        ...valid.past[0],
        beforeFingerprint: JSON.stringify(before),
        afterFingerprint: 'obsolete-fingerprint-format',
      }],
    };

    const restored = restoreMutationJournal<TestSnapshot>(persisted);

    expect(restored.past[0].beforeFingerprint).toBe(mutationFingerprint(before));
    expect(restored.past[0].afterFingerprint).toBe(mutationFingerprint(after));
    expect(restored.past[0].beforeFingerprint).not.toBe(persisted.past[0].beforeFingerprint);
  });

  it('uses compact deterministic fingerprints instead of retaining canonical snapshots twice', () => {
    const first = mutationFingerprint({ z: 1, a: ['x', 'y'] });
    const reordered = mutationFingerprint({ a: ['x', 'y'], z: 1 });
    const changed = mutationFingerprint({ a: ['x', 'z'], z: 1 });

    expect(first).toBe(reordered);
    expect(first).not.toBe(changed);
    expect(first).toMatch(/^mfp1:[0-9a-f]+:[0-9a-f]{8}:[0-9a-f]{8}$/);
    expect(first.length).toBeLessThan(40);
  });

  it('persists only the history nearest the current state within a recovery budget', () => {
    let journal = initialMutationJournal<TestSnapshot>();
    for (let index = 0; index < 8; index += 1) {
      journal = recordMutation(journal, createMutation({
        mutationId: `mutation-${index}`,
        kind: 'OTHER',
        label: `Mutation ${index}`,
        subjectRef: 'graph',
        before: { ...before, outputNodeId: `${index}` },
        after: { ...before, outputNodeId: `${index + 1}` },
        occurredAt: index * 1_000,
      }));
    }
    journal = undoMutation(journal)!.journal;
    journal = undoMutation(journal)!.journal;

    const recovery = mutationJournalForRecovery(journal, { maxEntries: 4, maxBytes: 100_000 });

    expect(recovery.past.map((mutation) => mutation.mutationId)).toEqual(['mutation-4', 'mutation-5']);
    expect(recovery.future.map((mutation) => mutation.mutationId)).toEqual(['mutation-7', 'mutation-6']);
    expect(recovery.past.length + recovery.future.length).toBeLessThanOrEqual(4);
    expect(recovery.droppedMutationCount).toBeGreaterThan(0);
  });
});

describe('projectNodeDeletionImpact', () => {
  it('enumerates graph, fixture, test, result, publication, and output impacts', () => {
    const impact = projectNodeDeletionImpact(['profile'], {
      edges: [
        { id: 'e1', source: 'profile', target: 'decision' },
        { id: 'e2', source: 'input', target: 'profile' },
      ],
      fixtureDrafts: { profile: '{"risk":12}' },
      fixtureInputDrafts: { profile: '{"customerId":"c-1"}' },
      operatorTestSuites: { profile: [{ id: 'golden' }, { id: 'boundary' }] },
      operatorTestResults: { profile: { golden: { status: 'passed' } } },
      operatorTestPublications: { profile: { publicationId: 'pub-1' } },
      explicitOutputNodeId: 'profile',
    });

    expect(impact.edgeIds).toEqual(['e1', 'e2']);
    expect(impact.requiresConfirmation).toBe(true);
    expect(impact.items.map((item) => [item.kind, item.count])).toEqual([
      ['NODE', 1],
      ['EDGE', 2],
      ['FIXTURE_OUTPUT', 1],
      ['FIXTURE_INPUT', 1],
      ['TEST_CASE', 2],
      ['TEST_RESULT', 1],
      ['TEST_PUBLICATION', 1],
      ['OUTPUT_BINDING', 1],
    ]);
  });

  it('allows an asset-free isolated node to use immediate deletion with Undo', () => {
    const impact = projectNodeDeletionImpact(['empty'], {
      edges: [],
      fixtureDrafts: {},
      fixtureInputDrafts: {},
      operatorTestSuites: {},
      operatorTestResults: {},
      operatorTestPublications: {},
      explicitOutputNodeId: '',
    });

    expect(impact.requiresConfirmation).toBe(false);
    expect(impact.items).toEqual([
      { kind: 'NODE', count: 1, refs: ['empty'], severity: 'DESTRUCTIVE' },
    ]);
  });
});
