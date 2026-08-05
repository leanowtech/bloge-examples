import { describe, expect, it } from 'vitest';

import { localizeRehearsalText } from './generatedProductText';
import { translate } from './i18n';

const zh = (source: string, values?: Record<string, string | number>) =>
  translate('zh-CN', source, values);
const en = (source: string, values?: Record<string, string | number>) =>
  translate('en', source, values);

describe('generated rehearsal product text', () => {
  it('localizes runtime assertion and warning summaries', () => {
    expect(localizeRehearsalText(zh, '2 failed and 1 indeterminate blocker assertions'))
      .toBe('2 个失败、1 个不确定的阻断断言');
    expect(localizeRehearsalText(zh, '1 failed and 0 indeterminate warnings'))
      .toBe('1 个失败、0 个不确定警告');
  });

  it('localizes mutable status projections without changing English grammar', () => {
    expect(localizeRehearsalText(zh, 'Mutable running projection')).toBe('可变的运行中投影');
    expect(localizeRehearsalText(en, 'Mutable running projection')).toBe('Mutable running projection');
  });

  it('localizes generated owner roles while preserving project identifiers', () => {
    expect(localizeRehearsalText(zh, 'customer-service-copilot rehearsal owner'))
      .toBe('customer-service-copilot 演练负责人');
  });

  it('localizes generated contact guidance recursively', () => {
    expect(localizeRehearsalText(
      zh,
      'This plan does not advertise an Author source. Contact tool-studio owner.',
    )).toBe('该计划未声明编排来源，请联系 tool-studio 负责人。');
  });
});
