import { describe, expect, it } from 'vitest';

import { localizeRehearsalText } from './generatedProductText';
import { translateRegisteredDynamic } from './i18n';
import { translateMessage } from './messageCatalog';

const zhDynamic = (source: string, values?: Record<string, string | number>) =>
  translateRegisteredDynamic('zh-CN', source, values);
const enDynamic = (source: string, values?: Record<string, string | number>) =>
  translateRegisteredDynamic('en', source, values);
const zhMessage = (messageId: Parameters<typeof translateMessage>[1], values?: Record<string, string | number>) =>
  translateMessage('zh-CN', messageId, values);
const enMessage = (messageId: Parameters<typeof translateMessage>[1], values?: Record<string, string | number>) =>
  translateMessage('en', messageId, values);

describe('generated rehearsal product text', () => {
  it('localizes runtime assertion and warning summaries', () => {
    expect(localizeRehearsalText(zhDynamic, zhMessage, '2 failed and 1 indeterminate blocker assertions'))
      .toBe('2 个失败、1 个不确定的阻断断言');
    expect(localizeRehearsalText(zhDynamic, zhMessage, '1 failed and 0 indeterminate warnings'))
      .toBe('1 个失败、0 个不确定警告');
  });

  it('localizes mutable status projections without changing English grammar', () => {
    expect(localizeRehearsalText(zhDynamic, zhMessage, 'Mutable running projection')).toBe('可变的运行中投影');
    expect(localizeRehearsalText(enDynamic, enMessage, 'Mutable running projection')).toBe('Mutable running projection');
  });

  it('localizes generated owner roles while preserving project identifiers', () => {
    expect(localizeRehearsalText(zhDynamic, zhMessage, 'customer-service-copilot rehearsal owner'))
      .toBe('customer-service-copilot 演练负责人');
  });

  it('localizes generated contact guidance recursively', () => {
    expect(localizeRehearsalText(
      zhDynamic,
      zhMessage,
      'This plan does not advertise an Author source. Contact tool-studio owner.',
    )).toBe('该计划未声明编排来源，请联系 tool-studio 负责人。');
  });

  it('blocks an unregistered generated sentence', () => {
    expect(localizeRehearsalText(zhDynamic, zhMessage, 'A server-added sentence.'))
      .toBe('未识别的产品状态，请查看技术详情。');
  });
});
