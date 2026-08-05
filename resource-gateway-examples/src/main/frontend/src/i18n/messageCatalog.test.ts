import { describe, expect, it } from 'vitest';

import { catalogedDiagnosticCodes, presentDiagnostic } from './diagnosticCatalog';
import {
  MESSAGE_CATALOG,
  messageCatalogErrors,
  statusMessageId,
  translateMessage,
} from './messageCatalog';

describe('typed product message catalog', () => {
  it('keeps every stable id complete and placeholder-compatible in both locales', () => {
    expect(messageCatalogErrors()).toEqual([]);
    expect(Object.keys(MESSAGE_CATALOG).length).toBeGreaterThanOrEqual(50);
  });

  it('translates command and status ids without using English source text as the lookup key', () => {
    expect(translateMessage('zh-CN', 'author.command.run')).toBe('运行并比较');
    expect(translateMessage('zh-CN', statusMessageId('CURRENT')!)).toBe('当前');
    expect(statusMessageId('BUSINESS_DEFINED_STATUS')).toBeNull();
  });

  it('presents cataloged protocol diagnostics as localized action guidance', () => {
    expect(catalogedDiagnosticCodes()).toEqual(expect.arrayContaining([
      'REQUEST_FAILED',
      'RUN_FAILED',
      'ASSERTION_FAILED',
      'RG.TABLE_RUN.ASSERTION_MISMATCH',
    ]));
    expect(presentDiagnostic(
      'zh-CN',
      'ASSERTION_FAILED',
      'expected approve, got decline',
      'Open Test',
    )).toMatchObject({
      cataloged: true,
      title: '业务断言失败',
      explanation: '实际结果与已定义的业务预期不一致。',
      remediation: '打开用例并比较预期值与实际值。',
      technicalDetail: 'expected approve, got decline',
    });
  });

  it('uses localized safe guidance for an uncataloged diagnostic', () => {
    expect(presentDiagnostic('zh-CN', 'RG.CUSTOMER.UNKNOWN', 'internal detail', 'Retry')).toMatchObject({
      cataloged: false,
      title: '系统诊断',
      remediation: '请结合协议代码和技术详情继续排查。',
    });
  });
});
