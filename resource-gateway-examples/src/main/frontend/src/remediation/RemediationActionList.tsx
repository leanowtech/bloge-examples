import type { RemediationAction } from './remediationAction';
import { localizeRehearsalText } from '../i18n/generatedProductText';
import { useI18n } from '../i18n/I18nProvider';

export default function RemediationActionList({
  actions,
  onInvoke,
}: {
  actions: RemediationAction[];
  onInvoke: (action: RemediationAction) => void;
}) {
  const { t, d, m } = useI18n();
  if (actions.length === 0) {
    return null;
  }
  const actionableCount = actions.filter((action) => action.available).length;
  return (
    <section className="remediation-action-list" aria-label={t('Recommended next actions')}>
      <header>
        <span>{t(actionableCount > 0 ? 'Next actions' : 'Recommendations')}</span>
        <strong>{actionableCount > 0
          ? actionableCount === 1
            ? t('1 path to a trusted result')
            : t('{count} paths to a trusted result', { count: actionableCount })
          : t('No direct action is available in this deployment.')}</strong>
      </header>
      <div>
        {actions.map((action, index) => (
          <article
            key={action.id}
            data-severity={action.severity.toLowerCase()}
            data-capability={action.available
              ? action.navigation === 'EXTERNAL' || action.navigation === 'AUTHOR' ? 'handoff' : 'execute'
              : 'explain'}
          >
            <div className="remediation-action-order">{index + 1}</div>
            <div className="remediation-action-copy">
              <strong>{d(action.actionLabel)}</strong>
              <p>{localizeRehearsalText(d, m, action.rootCause)}</p>
              <small><b>{t('Business impact')}</b> {d(action.businessImpact)}</small>
              <small>
                <b>{t('Responsible')}</b> {localizeRehearsalText(d, m, action.owner)} · {d(action.requiredRole)}
              </small>
              {!action.available && (
                <small className="remediation-action-unavailable">
                  {localizeRehearsalText(d, m, action.unavailableReason)}
                </small>
              )}
              <details>
                <summary>{t('Audit and technical details')}</summary>
                <span>{d(action.auditRequirement)}</span>
                {action.expiresAt && <span>{t('Expires {date}', { date: action.expiresAt })}</span>}
                <code>{action.technicalCode}</code>
                {action.technicalCoordinate && <code>{action.technicalCoordinate}</code>}
              </details>
            </div>
            {action.available && (action.navigation === 'EXTERNAL' || action.navigation === 'AUTHOR') && (
              <a
                className={index === 0 ? 'primary compact' : 'secondary compact'}
                href={action.deepLink}
              >
                {d(action.actionLabel)}
              </a>
            )}
            {action.available && action.navigation !== 'EXTERNAL' && action.navigation !== 'AUTHOR' && (
              <button
                type="button"
                className={index === 0 ? 'primary compact' : 'secondary compact'}
                onClick={() => onInvoke(action)}
              >
                {d(action.actionLabel)}
              </button>
            )}
          </article>
        ))}
      </div>
    </section>
  );
}
