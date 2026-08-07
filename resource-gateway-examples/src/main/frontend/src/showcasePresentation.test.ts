import { describe, expect, it } from 'vitest';

import { translateMessage } from './i18n/messageCatalog';
import { presentShowcaseScenario } from './showcasePresentation';

describe('showcase presentation', () => {
  it('covers every built-in graph with typed bilingual metadata', () => {
    const graphNames = [
      'userDashboard',
      'loanDecisionPolicy',
      'productDetail',
      'enrichOrderList',
      'creditScore',
      'resourceDispatch',
      'aiEnrichedSearch',
    ];

    for (const graphName of graphNames) {
      const presentation = presentShowcaseScenario({ graphName });
      expect(presentation).not.toBeNull();
      expect(translateMessage('zh-CN', presentation!.title.messageId)).not.toBe(
        translateMessage('en', presentation!.title.messageId),
      );
      expect(presentation!.concepts.length).toBeGreaterThan(0);
    }
  });

  it('preserves unknown server-owned scenario metadata as an explicit caller fallback', () => {
    expect(presentShowcaseScenario({ graphName: 'customerOwnedGraph' })).toBeNull();
  });
});
