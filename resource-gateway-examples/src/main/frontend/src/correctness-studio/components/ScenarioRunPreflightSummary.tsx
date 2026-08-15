import { AlertTriangle, ShieldCheck, ShieldX } from 'lucide-react';

import { useI18n } from '../../i18n/I18nProvider';
import type { ProductMessageId } from '../../i18n/messageCatalog';
import type {
  PreflightInvocationMode,
  ScenarioRunPreflightProjection,
} from '../model/preflightRiskProjection';

interface ScenarioRunPreflightSummaryProps {
  projection: ScenarioRunPreflightProjection;
  compact?: boolean;
}

export default function ScenarioRunPreflightSummary({
  projection,
  compact = false,
}: ScenarioRunPreflightSummaryProps) {
  const { m } = useI18n();
  const StatusIcon = projection.status === 'SAFE'
    ? ShieldCheck
    : projection.status === 'BLOCKED' ? ShieldX : AlertTriangle;
  return (
    <section
      className="correctness-preflight"
      data-status={projection.status.toLocaleLowerCase()}
      data-testid="correctness-run-preflight"
      aria-label={m('correctness.preflight.title')}
    >
      <header>
        <StatusIcon aria-hidden="true" size={18} />
        <div>
          <span>{m('correctness.preflight.title')}</span>
          <strong>{m(statusMessageId(projection.status))}</strong>
          <small>{m('correctness.preflight.summary', {
            cases: projection.selectedCaseCount,
            subject: projection.counts.subjectReal,
            real: projection.counts.real + projection.counts.observe,
            mocked: projection.counts.mocked,
            fault: projection.counts.fault,
            fallback: projection.counts.fallbackToReal,
          })}</small>
        </div>
      </header>
      <dl>
        <div><dt>{m('correctness.preflight.environment')}</dt><dd><code>{projection.environment || '—'}</code></dd></div>
        <div><dt>{m('correctness.preflight.targetEffect')}</dt><dd><code>{projection.targetEffect}</code></dd></div>
        <div><dt>{m('correctness.preflight.subject')}</dt><dd>{projection.counts.subjectReal}</dd></div>
        {(['REAL', 'MOCKED', 'FAULT', 'REPLAY', 'OBSERVE', 'DENIED'] as const).map((mode) => (
          <div key={mode} data-mode={mode.toLocaleLowerCase()}>
            <dt>{m(modeMessageId(mode))}</dt>
            <dd>{modeCount(projection, mode)}</dd>
          </div>
        ))}
      </dl>
      {projection.reasons.length > 0 && (
        <ul>
          {projection.reasons.map((reason) => (
            <li key={reason.code} data-severity={reason.severity.toLocaleLowerCase()}>
              {reason.severity === 'BLOCKING'
                ? <ShieldX aria-hidden="true" size={14} />
                : <AlertTriangle aria-hidden="true" size={14} />}
              <span>{m(reason.message.messageId, reason.message.params)}</span>
            </li>
          ))}
        </ul>
      )}
      {!compact && projection.invocationGroups.length > 0 && (
        <details>
          <summary>{m('correctness.preflight.invocations')}</summary>
          <div className="correctness-preflight-invocations">
            {projection.invocationGroups.map((group) => (
              <div
                key={[
                  group.mode,
                  group.nodeId,
                  group.operatorRef,
                  group.source,
                  group.behaviorKind,
                  group.fallbackToReal,
                ].join(':')}
                data-mode={group.mode.toLocaleLowerCase()}
              >
                <strong>{m(modeMessageId(group.mode))}</strong>
                <code>{group.nodeId}</code>
                <span>{group.operatorRef || '—'}</span>
                <small>{m('correctness.preflight.caseCount', { count: group.caseCount })}</small>
              </div>
            ))}
          </div>
          {projection.truncatedGroupCount > 0 && (
            <p>{m('correctness.preflight.groupsTruncated', {
              count: projection.truncatedGroupCount,
            })}</p>
          )}
        </details>
      )}
    </section>
  );
}

function statusMessageId(status: ScenarioRunPreflightProjection['status']): ProductMessageId {
  switch (status) {
    case 'SAFE': return 'correctness.preflight.status.safe';
    case 'REVIEW': return 'correctness.preflight.status.review';
    case 'BLOCKED': return 'correctness.preflight.status.blocked';
  }
}

function modeMessageId(mode: PreflightInvocationMode): ProductMessageId {
  switch (mode) {
    case 'REAL': return 'correctness.preflight.mode.real';
    case 'MOCKED': return 'correctness.preflight.mode.mocked';
    case 'FAULT': return 'correctness.preflight.mode.fault';
    case 'REPLAY': return 'correctness.preflight.mode.replay';
    case 'OBSERVE': return 'correctness.preflight.mode.observe';
    case 'DENIED': return 'correctness.preflight.mode.denied';
  }
}

function modeCount(
  projection: ScenarioRunPreflightProjection,
  mode: PreflightInvocationMode,
): number {
  switch (mode) {
    case 'REAL': return projection.counts.real;
    case 'MOCKED': return projection.counts.mocked;
    case 'FAULT': return projection.counts.fault;
    case 'REPLAY': return projection.counts.replay;
    case 'OBSERVE': return projection.counts.observe;
    case 'DENIED': return projection.counts.denied;
  }
}
