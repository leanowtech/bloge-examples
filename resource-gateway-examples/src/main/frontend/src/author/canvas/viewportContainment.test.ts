import { describe, expect, it } from 'vitest';

import { containedViewportTransform, containmentTranslation } from './viewportContainment';

describe('containmentTranslation', () => {
  const viewport = { left: 100, right: 900, top: 200, bottom: 700 };

  it('moves a bottom edge label into the semantic safe area without changing zoom', () => {
    expect(containmentTranslation(
      viewport,
      { left: 150, right: 850, top: 240, bottom: 724 },
      8,
    )).toEqual({ x: 0, y: -32 });
  });

  it('leaves an already contained graph stable', () => {
    expect(containmentTranslation(
      viewport,
      { left: 150, right: 850, top: 250, bottom: 650 },
    )).toEqual({ x: 0, y: 0 });
  });

  it('does not invent a pan when content is larger than the available viewport', () => {
    expect(containmentTranslation(
      viewport,
      { left: 50, right: 950, top: 150, bottom: 750 },
    )).toEqual({ x: 0, y: 0 });
  });

  it('uses the smallest readable zoom reduction before containing semantic labels', () => {
    const result = containedViewportTransform(
      viewport,
      { left: 150, right: 850, top: 190, bottom: 710 },
      { x: 100, y: 80, zoom: 0.9 },
      0.8,
      2,
    );

    expect(result.zoom).toBeCloseTo(0.8585, 3);
    expect(result.x).toBeCloseTo(114, 0);
    expect(result.y).toBeCloseTo(87.8, 0);
  });

  it('never sacrifices the configured minimum zoom for oversized content', () => {
    expect(containedViewportTransform(
      viewport,
      { left: 0, right: 1000, top: 100, bottom: 800 },
      { x: 100, y: 80, zoom: 0.81 },
      0.8,
    ).zoom).toBeCloseTo(0.8, 6);
  });
});
