import { describe, expect, it } from 'vitest';

import { AUTHOR_UX_BASELINE_FIXTURES } from './uxBaselineFixtures';

describe('author UX baseline fixtures', () => {
  it.each([
    [0, 0],
    [5, 12],
    [25, 50],
    [100, 250],
  ] as const)('freezes the %i-node / %i-edge stress surface', (nodeCount, edgeCount) => {
    const fixture = AUTHOR_UX_BASELINE_FIXTURES[nodeCount];

    expect(fixture.key).toBe(`ux-${nodeCount}`);
    expect(fixture.nodes).toHaveLength(nodeCount);
    expect(fixture.edges).toHaveLength(edgeCount);
    expect(fixture.nodeCount).toBe(nodeCount);
    expect(fixture.edgeCount).toBe(edgeCount);
  });

  it('keeps every stress edge attached to a known node', () => {
    for (const fixture of Object.values(AUTHOR_UX_BASELINE_FIXTURES)) {
      const nodeIds = new Set(fixture.nodes.map((node) => node.id));
      for (const edge of fixture.edges) {
        expect(nodeIds.has(edge.source), `${fixture.key}:${edge.id}:source`).toBe(true);
        expect(nodeIds.has(edge.target), `${fixture.key}:${edge.id}:target`).toBe(true);
      }
    }
  });

  it('preserves the readability stressors needed by later visual gates', () => {
    const fixture = AUTHOR_UX_BASELINE_FIXTURES[100];
    const routePairs = fixture.edges.map((edge) => `${edge.source}->${edge.target}`);

    expect(fixture.nodes.some((node) => (node.label?.length ?? 0) > 48)).toBe(true);
    expect(new Set(routePairs).size).toBeLessThan(routePairs.length);
    expect(fixture.edges.some((edge) => edge.condition)).toBe(true);
    expect(fixture.edges.some((edge) => (edge.sourcePath?.length ?? 0) > 24)).toBe(true);
  });
});
