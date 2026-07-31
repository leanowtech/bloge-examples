import { describe, expect, it } from 'vitest';

import { LIBRARY_AUTHORING_EXAMPLES } from './examples';

describe('library authoring examples', () => {
  it('ships three complete design-only examples with strong operator port types', () => {
    expect(LIBRARY_AUTHORING_EXAMPLES).toHaveLength(3);

    LIBRARY_AUTHORING_EXAMPLES.forEach((example) => {
      expect(example.deliveryMode).toBe('DESIGN_ONLY');
      expect(Object.keys(example.document.operators ?? {}).length).toBeGreaterThan(0);
      expect(Object.keys(example.document.functions ?? {}).length).toBeGreaterThan(0);
      expect(JSON.stringify(example.document.operators)).not.toMatch(/"any(?:\\[\\])?"/);
      expect(JSON.stringify(example.document)).not.toContain('"unknown"');
      Object.values(example.document.functions ?? {}).forEach((fn) => {
        (fn.signatures ?? []).forEach((signature) => {
          expect(signature).not.toMatch(/\b(any|unknown)\b/);
        });
      });
    });
  });

  it('keeps every named operator type resolvable inside its example library', () => {
    LIBRARY_AUTHORING_EXAMPLES.forEach((example) => {
      const declaredTypes = new Set(Object.keys(example.document.types ?? {}));
      Object.values(example.document.operators ?? {}).forEach((operator) => {
        Object.values({
          ...(operator.input ?? {}),
          ...(operator.output ?? {}),
        }).forEach((type) => {
          const typeName = typeof type === 'string'
            ? type.replace(/\[\]$/, '')
            : '';
          if (typeName && !PRIMITIVES.has(typeName)) {
            expect(declaredTypes.has(typeName), `${example.key} is missing ${typeName}`).toBe(true);
          }
        });
      });
    });
  });
});

const PRIMITIVES = new Set([
  'string',
  'number',
  'integer',
  'boolean',
  'datetime',
  'date',
  'object',
]);
