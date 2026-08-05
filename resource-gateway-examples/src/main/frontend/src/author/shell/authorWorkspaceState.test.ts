import { describe, expect, it } from 'vitest';

import { resolveAuthorPrimaryAction } from './authorWorkspaceState';

describe('author workspace primary action', () => {
  it('focuses composition for an empty graph', () => {
    expect(resolveAuthorPrimaryAction({
      nodeCount: 0,
      busy: false,
      hasInputErrors: false,
      hasRunResult: false,
      runSuccessful: false,
    })).toEqual({
      kind: 'focus-palette',
      label: 'Add first operator',
      targetMode: 'compose',
    });
  });

  it('routes invalid runtime values to Scenarios before offering execution', () => {
    expect(resolveAuthorPrimaryAction({
      nodeCount: 3,
      busy: false,
      hasInputErrors: true,
      hasRunResult: false,
      runSuccessful: false,
    })).toMatchObject({
      kind: 'fix-input',
      targetMode: 'scenarios',
    });
  });

  it('offers one run command only after a graph is runnable', () => {
    expect(resolveAuthorPrimaryAction({
      nodeCount: 3,
      busy: false,
      hasInputErrors: false,
      hasRunResult: false,
      runSuccessful: false,
    })).toMatchObject({
      kind: 'run',
      label: 'Run scenario',
    });
  });

  it('uses the same primary action identity while a run is pending', () => {
    expect(resolveAuthorPrimaryAction({
      nodeCount: 3,
      busy: true,
      hasInputErrors: false,
      hasRunResult: false,
      runSuccessful: false,
    })).toMatchObject({
      kind: 'run',
      label: 'Running scenario...',
    });

    expect(resolveAuthorPrimaryAction({
      nodeCount: 3,
      busy: true,
      hasInputErrors: false,
      hasRunResult: true,
      runSuccessful: true,
    })).toMatchObject({
      kind: 'run',
      label: 'Running scenario...',
    });
  });

  it('routes failed and successful runs into Evidence with honest labels', () => {
    expect(resolveAuthorPrimaryAction({
      nodeCount: 3,
      busy: false,
      hasInputErrors: false,
      hasRunResult: true,
      runSuccessful: false,
    }).label).toBe('Review failures');
    expect(resolveAuthorPrimaryAction({
      nodeCount: 3,
      busy: false,
      hasInputErrors: false,
      hasRunResult: true,
      runSuccessful: true,
    }).label).toBe('Review result');
    expect(resolveAuthorPrimaryAction({
      nodeCount: 3,
      busy: false,
      hasInputErrors: false,
      hasRunResult: true,
      runSuccessful: true,
    }).targetMode).toBe('evidence');
  });

  it('reruns retained stale evidence instead of presenting it as the current result', () => {
    expect(resolveAuthorPrimaryAction({
      nodeCount: 3,
      busy: false,
      hasInputErrors: false,
      hasRunResult: true,
      runSuccessful: true,
      runStale: true,
    })).toEqual({
      kind: 'run',
      label: 'Rerun current scenario',
      targetMode: 'scenarios',
    });
  });
});
