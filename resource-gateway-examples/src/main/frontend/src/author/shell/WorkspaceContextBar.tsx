import type { ReactNode } from 'react';
import { Building2, ShieldAlert, Target } from 'lucide-react';

import { useI18n } from '../../i18n/I18nProvider';
import type { AuthorCommandScope } from '../task/taskStateProjection';
import type { TaskCommandPolicy } from '../task/commandAuthority';
import type { TaskCoordinate } from '../task/taskCoordinate';

interface WorkspaceContextBarProps {
  coordinate: TaskCoordinate;
  objectLabel: string;
  objectMeta?: string;
  owner?: string;
  lifecycle?: { label: string; state: string; title?: string };
  lifecycleTestId?: string;
  commandScope?: AuthorCommandScope | {
    kind: string;
    count: number;
    targetIds?: string[];
    fingerprint?: string;
  };
  commandPolicy?: TaskCommandPolicy;
  actions?: ReactNode;
  className?: string;
}

/** Shared enterprise coordinate shown by Author, Library, and Rehearsal task surfaces. */
export default function WorkspaceContextBar({
  coordinate,
  objectLabel,
  objectMeta = '',
  owner = '',
  lifecycle,
  lifecycleTestId,
  commandScope,
  commandPolicy,
  actions,
  className = '',
}: WorkspaceContextBarProps) {
  const { d, t } = useI18n();
  const environmentTone = commandPolicy?.environmentTone
    ?? (isProduction(coordinate.environment) ? 'DANGER' : 'NEUTRAL');
  const scopeKind = commandScope?.kind ?? coordinate.subjectKind;
  const scopeCount = commandScope?.count ?? (coordinate.subjectRef ? 1 : 0);
  const revision = coordinate.revision > 0
    ? t('revision {revision}', { revision: coordinate.revision })
    : t('unsaved');
  const policyMessage = commandPolicy?.decision === 'DENY'
    ? policyReason(commandPolicy.reasonCode, t)
    : commandPolicy?.decision === 'REQUIRE_CONFIRMATION'
      ? t('Production safeguard: destructive commands require confirmation.')
      : '';

  return (
    <section
      className={`workspace-context-bar ${className}`.trim()}
      data-testid="workspace-context-bar"
      data-environment-tone={environmentTone.toLowerCase()}
      data-command-policy={commandPolicy?.decision.toLowerCase() ?? 'allow'}
      data-role={coordinate.role.toLowerCase()}
    >
      <div className="workspace-context-identity">
        <Building2 size={15} aria-hidden="true" />
        <div>
          <strong title={objectLabel}>{objectLabel || coordinate.subjectRef || t('Untitled')}</strong>
          <span>{d(coordinate.subjectKind)} · {revision}{objectMeta ? ` · ${objectMeta}` : ''}</span>
        </div>
        {lifecycle && (
          <span
            className="workspace-context-lifecycle"
            data-state={lifecycle.state.toLowerCase()}
            data-testid={lifecycleTestId}
            title={lifecycle.title}
          >
            {lifecycle.label}
          </span>
        )}
      </div>
      <dl className="workspace-context-facts">
        <div title={`${t('Tenant')}: ${coordinate.tenantId}`}>
          <dt>{t('Tenant')}</dt>
          <dd>{coordinate.tenantId}</dd>
        </div>
        <div title={`${t('Namespace')}: ${coordinate.namespace}`}>
          <dt>{t('Namespace')}</dt>
          <dd>{coordinate.namespace}</dd>
        </div>
        <div
          className="workspace-context-environment"
          data-tone={environmentTone.toLowerCase()}
          title={`${t('Environment')}: ${coordinate.environment}`}
        >
          {environmentTone === 'DANGER' && <ShieldAlert size={13} aria-hidden="true" />}
          <dt>{t('Environment')}</dt>
          <dd>{coordinate.environment.toUpperCase()}</dd>
        </div>
        <div className="workspace-context-role" title={`${t('Role')}: ${d(coordinate.role)}`}>
          <dt>{t('Role')}</dt>
          <dd>{d(coordinate.role)}</dd>
        </div>
        <div
          className="workspace-context-scope"
          title={t('{kind} scope, {count} target(s)', { kind: d(scopeKind), count: scopeCount })}
          data-testid="workspace-command-scope"
          data-scope-kind={scopeKind.toLowerCase()}
          data-scope-count={scopeCount}
        >
          <Target size={13} aria-hidden="true" />
          <dt>{t('Scope')}</dt>
          <dd>{d(scopeKind)} · {scopeCount}</dd>
        </div>
        {owner && (
          <div title={`${t('Owner')}: ${owner}`}>
            <dt>{t('Owner')}</dt>
            <dd>{owner}</dd>
          </div>
        )}
      </dl>
      {policyMessage && (
        <span className="workspace-context-policy" role="status" title={policyMessage}>
          {policyMessage}
        </span>
      )}
      {actions && <div className="workspace-context-actions">{actions}</div>}
    </section>
  );
}

function isProduction(environment: string): boolean {
  const normalized = environment.trim().toLowerCase();
  return normalized === 'prod' || normalized === 'production';
}

function policyReason(
  reasonCode: string,
  t: (message: string, params?: Record<string, string | number>) => string,
): string {
  switch (reasonCode) {
    case 'RG.AUTHOR.COMMAND.CROSS_TENANT':
      return t('Cross-tenant command blocked. Return to the owning tenant.');
    case 'RG.AUTHOR.COMMAND.CAPABILITY_MISSING':
      return t('Command unavailable in this deployment.');
    case 'RG.AUTHOR.COMMAND.ROLE_VIEWER':
    case 'RG.AUTHOR.COMMAND.ROLE_REVIEWER':
      return t('Read-only role: editing commands are unavailable.');
    default:
      return t('Command blocked by workspace policy.');
  }
}
