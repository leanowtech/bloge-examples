import { describe, expect, it } from 'vitest';

import { translateMessage } from '../i18n/messageCatalog';
import { OPERATOR_ARCHETYPES, presentOperatorArchetype } from './archetypePresentation';

describe('operator archetype presentation', () => {
  it('gives every built-in archetype a bilingual product descriptor', () => {
    for (const value of OPERATOR_ARCHETYPES) {
      const presentation = presentOperatorArchetype(value);
      expect(translateMessage('en', presentation.label.messageId)).not.toBe('');
      expect(translateMessage('zh-CN', presentation.label.messageId))
        .not.toBe(translateMessage('en', presentation.label.messageId));
      expect(translateMessage('zh-CN', presentation.summary.messageId)).not.toBe('');
    }
  });

  it('keeps an unknown wire value only as technical detail', () => {
    expect(presentOperatorArchetype('customer-specific')).toMatchObject({
      label: {
        messageId: 'library.archetype.unknown.label',
        rawCode: 'customer-specific',
      },
      external: true,
    });
  });
});
