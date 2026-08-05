import { describe, expect, it } from 'vitest';

import { CANVAS_EXAMPLE_TEMPLATES } from './canvasExamples';

describe('complete canvas example seeds', () => {
  it('ship a meaningful golden, negative, and boundary path for every graph', () => {
    expect(CANVAS_EXAMPLE_TEMPLATES).toHaveLength(3);
    for (const template of CANVAS_EXAMPLE_TEMPLATES) {
      expect(template.testCases?.map((testCase) => testCase.caseType)).toEqual([
        'GOLDEN',
        'NEGATIVE',
        'BOUNDARY',
      ]);
      expect(template.testCases).toHaveLength(3);
      expect(template.testCases?.every((testCase) => (
        testCase.name.length >= 12
        && Object.keys(testCase.context).length > 0
        && testCase.expectedOutput !== null
      ))).toBe(true);
    }
  });

  it('uses explicit business values instead of schema placeholders', () => {
    const serialized = JSON.stringify(CANVAS_EXAMPLE_TEMPLATES.flatMap(
      (template) => template.testCases ?? [],
    ));
    expect(serialized).not.toContain('"string"');
    expect(serialized).toContain('manual_review');
    expect(serialized).toContain('zero notification boundary');
  });
});
