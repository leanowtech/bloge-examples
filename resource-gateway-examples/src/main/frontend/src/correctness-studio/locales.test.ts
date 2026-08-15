// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { hasChineseTranslation } from '../i18n/i18n';
import { CORRECTNESS_TRANSLATIONS } from './locales';

const SURFACES = [
  './CorrectnessStudio.tsx',
  './runs/RunCenter.tsx',
  './shared/FiveAxisVerdict.tsx',
] as const;

describe('Correctness Studio route locale', () => {
  it('covers every literal task string in the lazy route dictionary or global catalog', () => {
    const local = CORRECTNESS_TRANSLATIONS['zh-CN'] ?? {};
    const missing = SURFACES.flatMap((relativePath) => {
      const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8');
      const keys = [...source.matchAll(/\bt\(\s*'((?:\\'|[^'])+)'/g)]
        .map((match) => match[1].replace(/\\'/g, "'"));
      return [...new Set(keys)]
        .filter((key) => !local[key] && !hasChineseTranslation(key))
        .map((key) => `${relativePath}: ${key}`);
    });
    expect(missing).toEqual([]);
  });

  it('keeps route-local placeholders compatible with their English source keys', () => {
    const local = CORRECTNESS_TRANSLATIONS['zh-CN'] ?? {};
    const failures = Object.entries(local).flatMap(([source, translated]) => {
      const sourceArgs = placeholders(source);
      const translatedArgs = placeholders(translated);
      return sourceArgs.join('|') === translatedArgs.join('|') ? [] : [source];
    });
    expect(failures).toEqual([]);
  });
});

function placeholders(value: string): string[] {
  return [...value.matchAll(/\{([A-Za-z][A-Za-z0-9]*)\}/g)]
    .map((match) => match[1])
    .sort();
}
