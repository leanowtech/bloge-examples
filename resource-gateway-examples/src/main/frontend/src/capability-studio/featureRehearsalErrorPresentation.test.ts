import { describe, expect, it } from 'vitest';

import { CapabilityStudioRequestError } from './api';
import { featureRehearsalErrorPresentation } from './featureRehearsalErrorPresentation';

describe('Feature rehearsal authorization presentation', () => {
  it.each([
    ['RG.INTEGRATION.AUTHENTICATION_REQUIRED', 401, '无法验证本次数据视图请求的身份'],
    ['RG.INTEGRATION.PURPOSE_FORBIDDEN', 403, '缺少允许的 Capability Studio 演练用途'],
    ['RG.CAPABILITY_STUDIO.PAYLOAD_CLEARANCE_REQUIRED', 403, '不具备查看受控数据所需的 CONFIDENTIAL 权限'],
    ['RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH', 403, '身份声明与已验证凭证不一致'],
  ])('localizes %s without relying on server recovery fields', (code, status, expected) => {
    const error = new CapabilityStudioRequestError(
      code,
      'English server title.',
      'Generic transport impact.',
      'Generic transport recovery.',
      status,
    );

    const presentation = featureRehearsalErrorPresentation(error, 'zh-CN');

    expect(presentation.whatHappened).toContain(expected);
    expect(presentation.impact).not.toBe('Generic transport impact.');
    expect(presentation.recoveryAction).not.toBe('Generic transport recovery.');
  });
});
