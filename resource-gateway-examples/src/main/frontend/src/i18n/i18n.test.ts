// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';

import {
  LOCALE_STORAGE_KEY,
  normalizeLocale,
  resolveInitialLocale,
  translate,
  translateRegisteredDynamic,
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

  it('blocks unregistered dynamic product text instead of leaking raw English', () => {
    expect(translateRegisteredDynamic('zh-CN', 'RUNNING')).toBe('运行中');
    expect(translateRegisteredDynamic('en', 'RUNNING')).toBe('RUNNING');
    expect(translateRegisteredDynamic('zh-CN', 'A new server sentence.'))
      .toBe('未识别的产品状态，请查看技术详情。');
    expect(translateRegisteredDynamic('en', 'A new server sentence.'))
      .toBe('Unrecognized product status. Review technical details.');
  });

  it('presents rehearsal lifecycle states as product labels in Chinese', () => {
    expect([
      'RUNNING',
      'SUCCEEDED',
      'FAILED',
      'CANCELLED',
      'PARTIAL',
      'INDETERMINATE',
      'QUARANTINED',
      'EXPIRED',
      'PASSED',
    ]
      .every((status) => translate('zh-CN', status) !== status)).toBe(true);
    expect(translate('zh-CN', '{failed} failed and {indeterminate} indeterminate blocker assertions', {
      failed: 2,
      indeterminate: 1,
    })).toBe('2 个失败、1 个不确定的阻断断言');
    expect(translate('zh-CN', 'All evaluated cases passed')).toBe('所有已评估用例均已通过');
    expect(translateRegisteredDynamic('zh-CN', 'DEPENDENCY_TIMEOUT')).toBe('依赖调用超时');
    expect(translateRegisteredDynamic('zh-CN', 'OWNER_APPROVAL_REQUIRED')).toBe('需要负责人批准');
  });
});
