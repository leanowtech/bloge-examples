import type { RemediationAction } from './remediationAction';

export default function RemediationActionList({
  actions,
  onInvoke,
}: {
  actions: RemediationAction[];
  onInvoke: (action: RemediationAction) => void;
}) {
  if (actions.length === 0) {
    return null;
  }
  return (
    <section className="remediation-action-list" aria-label="Recommended next actions">
      <header>
        <span>Next actions</span>
        <strong>{actions.length} path{actions.length === 1 ? '' : 's'} to a trusted result</strong>
      </header>
      <div>
        {actions.map((action, index) => (
          <article key={action.id} data-severity={action.severity.toLowerCase()}>
            <div className="remediation-action-order">{index + 1}</div>
            <div className="remediation-action-copy">
              <strong>{action.actionLabel}</strong>
              <p>{action.rootCause}</p>
              <small><b>Business impact</b> {action.businessImpact}</small>
              <small><b>Responsible</b> {action.owner} · {action.requiredRole}</small>
              {!action.available && (
                <small className="remediation-action-unavailable">
                  {action.unavailableReason}
                </small>
              )}
              <details>
                <summary>Audit and technical details</summary>
                <span>{action.auditRequirement}</span>
                {action.expiresAt && <span>Expires {action.expiresAt}</span>}
                <code>{action.technicalCode}</code>
                {action.technicalCoordinate && <code>{action.technicalCoordinate}</code>}
              </details>
            </div>
            {action.available && action.navigation === 'EXTERNAL' && (
              <a
                className={index === 0 ? 'primary compact' : 'secondary compact'}
                href={action.deepLink}
              >
                {action.actionLabel}
              </a>
            )}
            {action.available && action.navigation !== 'EXTERNAL' && (
              <button
                type="button"
                className={index === 0 ? 'primary compact' : 'secondary compact'}
                onClick={() => onInvoke(action)}
              >
                {action.actionLabel}
              </button>
            )}
          </article>
        ))}
      </div>
    </section>
  );
}
