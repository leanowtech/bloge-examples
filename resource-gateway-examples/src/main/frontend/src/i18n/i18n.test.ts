// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';

import {
  LOCALE_STORAGE_KEY,
  normalizeLocale,
  resolveInitialLocale,
  translate,
} from './i18n';

describe('i18n locale contract', () => {
  it('normalizes supported English and Chinese browser locales', () => {
    expect(normalizeLocale('zh-CN')).toBe('zh-CN');
    expect(normalizeLocale('zh-SG')).toBe('zh-CN');
    expect(normalizeLocale('en-US')).toBe('en');
    expect(normalizeLocale('fr-FR')).toBeNull();
  });

  it('prefers an explicit URL locale, then persisted preference, then browser locale', () => {
    expect(resolveInitialLocale({
      search: '?lang=zh-CN',
      storedLocale: 'en',
      browserLocales: ['en-US'],
    })).toBe('zh-CN');
    expect(resolveInitialLocale({
      search: '',
      storedLocale: 'zh-CN',
      browserLocales: ['en-US'],
    })).toBe('zh-CN');
    expect(resolveInitialLocale({
      search: '',
      storedLocale: null,
      browserLocales: ['zh-SG'],
    })).toBe('zh-CN');
    expect(resolveInitialLocale({
      search: '',
      storedLocale: null,
      browserLocales: ['fr-FR'],
    })).toBe('en');
  });

  it('translates known messages, interpolates values, and falls back to English source text', () => {
    expect(translate('zh-CN', 'Draft r{revision} · {nodes} nodes · {edges} edges', {
      revision: 3,
      nodes: 5,
      edges: 8,
    })).toBe('草稿 r3 · 5 个节点 · 8 条连线');
    expect(translate('en', 'Draft r{revision}', { revision: 2 })).toBe('Draft r2');
    expect(translate('zh-CN', 'Uncatalogued message')).toBe('Uncatalogued message');
  });

  it('uses a stable preference key', () => {
    expect(LOCALE_STORAGE_KEY).toBe('bloge.visual.locale');
  });
});
