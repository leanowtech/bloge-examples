import { describe, expect, it } from 'vitest';

import { parseSampleText } from './SampleInferenceReview';

describe('parseSampleText', () => {
  it('accepts a JSON array without changing sample values', () => {
    expect(parseSampleText('[{"id":1},{"id":2}]')).toEqual([{ id: 1 }, { id: 2 }]);
  });

  it('accepts one JSON value per line as NDJSON', () => {
    expect(parseSampleText('{"id":1}\n{"id":2}')).toEqual([{ id: 1 }, { id: 2 }]);
  });

  it('wraps one representative object as a one-sample batch', () => {
    expect(parseSampleText('{"id":1}')).toEqual([{ id: 1 }]);
  });

  it('rejects empty and oversized batches before a network request', () => {
    expect(() => parseSampleText('[]')).toThrow('at least one');
    expect(() => parseSampleText(JSON.stringify(Array.from({ length: 101 }, (_, id) => ({ id })))))
      .toThrow('at most 100');
  });
});
