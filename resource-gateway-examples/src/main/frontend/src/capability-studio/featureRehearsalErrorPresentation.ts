import { CapabilityStudioRequestError } from './api';

export interface FeatureRehearsalErrorPresentation {
  whatHappened: string;
  impact: string;
  recoveryAction: string;
}

type Locale = 'en' | 'zh-CN';

const knownAuthorizationErrors = {
  authentication: {
    en: {
      whatHappened: 'Capability Studio could not authenticate the Data Lens request.',
      impact: 'The existing Data Lens remains visible and no additional data was disclosed.',
      recoveryAction: 'Reconnect the authenticated host or restore the local demo credential, then retry.',
    },
    'zh-CN': {
      whatHappened: 'Capability Studio 无法验证本次数据视图请求的身份。',
      impact: '现有数据视图保持可见，且未披露任何额外数据。',
      recoveryAction: '重新连接已认证的宿主，或恢复本地演示凭证后重试。',
    },
  },
  purpose: {
    en: {
      whatHappened: 'The authenticated request is missing an allowed Capability Studio rehearsal purpose.',
      impact: 'The existing Data Lens remains visible and the rehearsal was not replaced.',
      recoveryAction: 'Restore the Capability Studio rehearsal purpose in the host request, then retry.',
    },
    'zh-CN': {
      whatHappened: '已认证请求缺少允许的 Capability Studio 演练用途。',
      impact: '现有数据视图保持可见，本次演练结果未被替换。',
      recoveryAction: '由宿主恢复 Capability Studio 演练用途后重试。',
    },
  },
  payloadClearance: {
    en: {
      whatHappened: 'The verified identity does not have CONFIDENTIAL clearance for payload view.',
      impact: 'The existing Structure Data Lens remains visible and unchanged.',
      recoveryAction: 'Keep Structure selected, or reconnect with a CONFIDENTIAL identity and retry.',
    },
    'zh-CN': {
      whatHappened: '已验证身份不具备查看受控数据所需的 CONFIDENTIAL 权限。',
      impact: '现有结构视图继续可见且保持不变。',
      recoveryAction: '保持使用结构视图，或重新连接具备 CONFIDENTIAL 权限的身份后重试。',
    },
  },
  claimMismatch: {
    en: {
      whatHappened: 'A caller-supplied identity claim conflicts with the verified credential.',
      impact: 'The existing Data Lens remains visible and no caller claim was trusted.',
      recoveryAction: 'Remove stale identity headers and let the authenticated host supply the request claims.',
    },
    'zh-CN': {
      whatHappened: '调用方提供的身份声明与已验证凭证不一致。',
      impact: '现有数据视图保持可见，且系统未信任调用方声明。',
      recoveryAction: '移除过期的身份请求头，并由已认证宿主提供请求声明。',
    },
  },
} as const;

export function featureRehearsalErrorPresentation(
  error: Error,
  locale: Locale,
): FeatureRehearsalErrorPresentation {
  if (!(error instanceof CapabilityStudioRequestError)) {
    return {
      whatHappened: error.message,
      impact: locale === 'zh-CN'
        ? '当前场景的 DAG 和 Data Lens 保持不变。'
        : 'The current scenario DAG and Data Lens remain unchanged.',
      recoveryAction: locale === 'zh-CN'
        ? '保持场景选择不变，重试加载。'
        : 'Keep the scenario selected and retry the load.',
    };
  }

  if (error.status === 401) {
    return knownAuthorizationErrors.authentication[locale];
  }
  if (error.code.startsWith('RG.INTEGRATION.PURPOSE_')) {
    return knownAuthorizationErrors.purpose[locale];
  }
  if (error.code === 'RG.CAPABILITY_STUDIO.PAYLOAD_CLEARANCE_REQUIRED') {
    return knownAuthorizationErrors.payloadClearance[locale];
  }
  if (error.code === 'RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH') {
    return knownAuthorizationErrors.claimMismatch[locale];
  }
  return {
    whatHappened: error.whatHappened,
    impact: error.impact,
    recoveryAction: error.recoveryAction,
  };
}
