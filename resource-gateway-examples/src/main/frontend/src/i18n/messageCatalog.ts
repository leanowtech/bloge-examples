import type { Locale, TranslationValues } from './i18n';

type LocalizedMessage = Record<Locale, string>;

export const MESSAGE_CATALOG = {
  'author.command.run': { en: 'Run & Compare', 'zh-CN': '运行并比较' },
  'author.command.rerun': { en: 'Rerun & Compare', 'zh-CN': '重新运行并比较' },
  'author.command.running': { en: 'Running...', 'zh-CN': '正在运行...' },
  'author.command.addFirstOperator': { en: 'Add first operator', 'zh-CN': '添加第一个算子' },
  'author.command.fixRequiredInput': { en: 'Fix required input', 'zh-CN': '修复必填输入' },
  'author.command.createScenario': { en: 'Create Scenario', 'zh-CN': '创建测试场景' },
  'author.command.openScenarios': { en: 'Open Scenarios', 'zh-CN': '打开测试场景' },
  'author.command.reviewCompatibility': { en: 'Review compatibility', 'zh-CN': '检查兼容性' },
  'author.command.reviewLayout': { en: 'Review layout', 'zh-CN': '检查布局' },
  'author.command.reviewFailures': { en: 'Review failures', 'zh-CN': '检查失败项' },
  'author.command.reviewResult': { en: 'Review result', 'zh-CN': '检查结果' },
  'author.blocker.graphEmpty': {
    en: 'Add at least one operator before running a Scenario.',
    'zh-CN': '请先添加至少一个算子，再运行测试场景。',
  },
  'author.blocker.inputInvalid': {
    en: 'Resolve the highlighted input values before running.',
    'zh-CN': '请先修复已标出的输入值，再运行。',
  },
  'author.blocker.scenarioMissing': {
    en: 'Create a Scenario with business input and at least one expected outcome.',
    'zh-CN': '请创建包含业务输入和至少一个预期结果的测试场景。',
  },
  'author.blocker.coordinatePreparing': {
    en: 'The canonical Scenario coordinate is still being prepared.',
    'zh-CN': '系统仍在准备当前测试场景的标准坐标。',
  },
  'author.blocker.scenarioStale': {
    en: 'This Scenario targets an older Graph or Contract and cannot create current evidence.',
    'zh-CN': '此测试场景指向旧版编排图或契约，无法生成当前证据。',
  },
  'author.blocker.runInProgress': {
    en: 'The current Scenario run is still in progress.',
    'zh-CN': '当前测试场景仍在运行中。',
  },
  'author.blocker.layoutPending': {
    en: 'Accept or cancel the pending layout preview before running.',
    'zh-CN': '请先接受或取消待处理的布局预览，再运行。',
  },
  'author.command.savedRunDetail': {
    en: 'Runs the exact saved Scenario coordinate and records durable current evidence.',
    'zh-CN': '按已保存的精确场景坐标运行，并记录持久且当前的证据。',
  },
  'author.command.sandboxRunDetail': {
    en: 'Runs an immutable sandbox snapshot and records current exploratory evidence.',
    'zh-CN': '运行不可变的沙盒快照，并记录当前的探索性证据。',
  },
  'author.command.waitForActive': {
    en: 'Wait for the active authoring command to finish.',
    'zh-CN': '请等待当前编排操作完成。',
  },
  'status.saved': { en: 'SAVED', 'zh-CN': '已保存' },
  'status.ephemeral': { en: 'EPHEMERAL', 'zh-CN': '临时' },
  'status.notRun': { en: 'NOT RUN', 'zh-CN': '未运行' },
  'status.notConfigured': { en: 'NOT CONFIGURED', 'zh-CN': '未配置' },
  'status.notChecked': { en: 'NOT CHECKED', 'zh-CN': '未检查' },
  'status.notEvaluated': { en: 'NOT EVALUATED', 'zh-CN': '未评估' },
  'status.valid': { en: 'VALID', 'zh-CN': '有效' },
  'status.invalid': { en: 'INVALID', 'zh-CN': '无效' },
  'status.current': { en: 'CURRENT', 'zh-CN': '当前' },
  'status.stale': { en: 'STALE', 'zh-CN': '已过期' },
  'status.pass': { en: 'PASS', 'zh-CN': '通过' },
  'status.fail': { en: 'FAIL', 'zh-CN': '失败' },
  'status.runnable': { en: 'RUNNABLE', 'zh-CN': '可运行' },
  'status.ready': { en: 'READY', 'zh-CN': '就绪' },
  'status.running': { en: 'RUNNING', 'zh-CN': '运行中' },
  'status.durable': { en: 'DURABLE', 'zh-CN': '持久证据' },
  'status.governed': { en: 'GOVERNED', 'zh-CN': '治理证据' },
  'status.exploratory': { en: 'EXPLORATORY', 'zh-CN': '探索性证据' },
  'diagnostic.requestFailed.title': { en: 'Request failed', 'zh-CN': '请求失败' },
  'diagnostic.requestFailed.explanation': {
    en: 'The requested operation did not complete.',
    'zh-CN': '本次请求未能完成。',
  },
  'diagnostic.requestFailed.remediation': {
    en: 'Review the request details and retry.',
    'zh-CN': '检查请求详情后重试。',
  },
  'diagnostic.runFailed.title': { en: 'Runtime execution failed', 'zh-CN': '运行时执行失败' },
  'diagnostic.runFailed.explanation': {
    en: 'The graph did not complete successfully.',
    'zh-CN': '编排图未能成功完成执行。',
  },
  'diagnostic.runFailed.remediation': {
    en: 'Inspect the failed trace and rerun the same Scenario.',
    'zh-CN': '检查失败链路后重新运行同一测试场景。',
  },
  'diagnostic.assertionFailed.title': { en: 'Business assertion failed', 'zh-CN': '业务断言失败' },
  'diagnostic.assertionFailed.explanation': {
    en: 'The actual result differs from the authored expectation.',
    'zh-CN': '实际结果与已定义的业务预期不一致。',
  },
  'diagnostic.assertionFailed.remediation': {
    en: 'Open the Case and compare Expected with Actual.',
    'zh-CN': '打开用例并比较预期值与实际值。',
  },
  'diagnostic.contractMultipleSources.title': {
    en: 'Contract field has multiple sources',
    'zh-CN': '契约字段存在多个数据源',
  },
  'diagnostic.contractMultipleSources.explanation': {
    en: 'More than one authoritative binding writes the same target field.',
    'zh-CN': '多个权威绑定正在写入同一个目标字段。',
  },
  'diagnostic.contractMultipleSources.remediation': {
    en: 'Keep one authoritative source for this target field.',
    'zh-CN': '为该目标字段保留唯一权威数据源。',
  },
  'diagnostic.contractTypeConflict.title': {
    en: 'Contract field type conflicts',
    'zh-CN': '契约字段类型冲突',
  },
  'diagnostic.contractTypeConflict.explanation': {
    en: 'The bound source type does not match the declared target type.',
    'zh-CN': '绑定的数据源类型与目标声明类型不一致。',
  },
  'diagnostic.contractTypeConflict.remediation': {
    en: 'Align the source and target field types.',
    'zh-CN': '统一数据源与目标字段的类型。',
  },
  'diagnostic.generic.title': { en: 'System diagnostic', 'zh-CN': '系统诊断' },
  'diagnostic.generic.explanation': {
    en: 'The system reported a diagnostic that is not yet in the product catalog.',
    'zh-CN': '系统返回了一条尚未纳入产品目录的诊断。',
  },
  'diagnostic.generic.remediation': {
    en: 'Use the protocol code and technical details to continue investigation.',
    'zh-CN': '请结合协议代码和技术详情继续排查。',
  },
} as const satisfies Record<string, LocalizedMessage>;

export type MessageId = keyof typeof MESSAGE_CATALOG;

const STATUS_IDS: Record<string, MessageId> = {
  SAVED: 'status.saved',
  EPHEMERAL: 'status.ephemeral',
  'NOT RUN': 'status.notRun',
  'NOT CONFIGURED': 'status.notConfigured',
  'NOT CHECKED': 'status.notChecked',
  'NOT EVALUATED': 'status.notEvaluated',
  VALID: 'status.valid',
  INVALID: 'status.invalid',
  CURRENT: 'status.current',
  STALE: 'status.stale',
  PASS: 'status.pass',
  FAIL: 'status.fail',
  RUNNABLE: 'status.runnable',
  READY: 'status.ready',
  RUNNING: 'status.running',
  DURABLE: 'status.durable',
  GOVERNED: 'status.governed',
  EXPLORATORY: 'status.exploratory',
};

export function translateMessage(
  locale: Locale,
  id: MessageId,
  values: TranslationValues = {},
): string {
  return interpolate(MESSAGE_CATALOG[id][locale], values);
}

export function statusMessageId(value: string): MessageId | null {
  return STATUS_IDS[value] ?? null;
}

export function messageCatalogErrors(): string[] {
  return Object.entries(MESSAGE_CATALOG).flatMap(([id, localized]) => {
    const errors: string[] = [];
    if (!/^[a-z][A-Za-z0-9]*(\.[a-z][A-Za-z0-9]*)+$/.test(id)) {
      errors.push(`${id}: message id must use stable dotted lower-camel segments`);
    }
    const english = placeholders(localized.en);
    const chinese = placeholders(localized['zh-CN']);
    if (english.join('|') !== chinese.join('|')) {
      errors.push(`${id}: placeholder sets differ (${english.join(',')} vs ${chinese.join(',')})`);
    }
    if (!localized.en.trim() || !localized['zh-CN'].trim()) {
      errors.push(`${id}: both locales require non-empty text`);
    }
    return errors;
  });
}

function placeholders(value: string): string[] {
  return [...value.matchAll(/\{([A-Za-z][A-Za-z0-9]*)\}/g)].map((match) => match[1]).sort();
}

function interpolate(template: string, values: TranslationValues): string {
  return template.replace(/\{([A-Za-z][A-Za-z0-9]*)\}/g, (match, key: string) => (
    Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : match
  ));
}
