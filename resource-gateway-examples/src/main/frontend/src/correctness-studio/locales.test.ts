// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { hasChineseTranslation } from '../i18n/i18n';
import { CORRECTNESS_TRANSLATIONS } from './locales';

const SURFACES = [
  './CorrectnessStudio.tsx',
  './authoring/CoverageStudio.tsx',
  './authoring/CaseStudio.tsx',
  './authoring/OracleStudio.tsx',
  './authoring/FixtureStudio.tsx',
  './authoring/shared.tsx',
  './runs/RunCenter.tsx',
  './shared/FiveAxisVerdict.tsx',
] as const;
const STYLES = readFileSync(new URL('./styles.css', import.meta.url), 'utf8');

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

  it('keeps horizontal overflow inside task-owned navigation and table containers', () => {
    expect(STYLES).toMatch(/\.correctness-studio\s*\{[\s\S]*?overflow-x:\s*hidden/);
    expect(STYLES).toMatch(/\.correctness-workspace-context\s*\{[\s\S]*?contain:\s*inline-size layout paint[\s\S]*?overflow-x:\s*auto/);
    expect(STYLES).toMatch(/\.correctness-view-tabs\s*\{[\s\S]*?contain:\s*inline-size layout paint[\s\S]*?overflow-x:\s*auto/);
    expect(STYLES).toMatch(/\.correctness-table-scroll\s*\{[\s\S]*?contain:\s*inline-size layout paint[\s\S]*?overflow:\s*auto/);
  });
});

function placeholders(value: string): string[] {
  return [...value.matchAll(/\{([A-Za-z][A-Za-z0-9]*)\}/g)]
    .map((match) => match[1])
    .sort();
}
