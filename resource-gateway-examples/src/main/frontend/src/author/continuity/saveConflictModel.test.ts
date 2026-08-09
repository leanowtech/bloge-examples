import { describe, expect, it } from 'vitest';

import { projectSaveConflictComparison, shortConflictFingerprint } from './saveConflictModel';

describe('save conflict model', () => {
  it('keeps domain fact order and identifies only material differences', () => {
    const rows = projectSaveConflictComparison({
      revision: 2,
      fingerprint: 'sha256:local',
      facts: [
        { id: 'name', label: 'Name', value: 'Claims flow' },
        { id: 'nodes', label: 'Nodes', value: 4 },
      ],
    }, {
      revision: 5,
      fingerprint: 'sha256:server',
      facts: [
        { id: 'name', label: 'Name', value: 'Claims flow' },
        { id: 'nodes', label: 'Nodes', value: 6 },
        { id: 'edges', label: 'Edges', value: 7 },
      ],
    });

    expect(rows).toEqual([
      { id: 'name', label: 'Name', localValue: 'Claims flow', authoritativeValue: 'Claims flow', changed: false },
      { id: 'nodes', label: 'Nodes', localValue: '4', authoritativeValue: '6', changed: true },
      { id: 'edges', label: 'Edges', localValue: '-', authoritativeValue: '7', changed: true },
    ]);
  });

  it('shortens long fingerprints while retaining both identifying ends', () => {
    expect(shortConflictFingerprint('sha256:1234567890abcdefghijklmnop'))
      .toBe('sha256:1234...klmnop');
    expect(shortConflictFingerprint('')).toBe('-');
  });
});
