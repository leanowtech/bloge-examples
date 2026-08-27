import { useI18n } from '../i18n/I18nProvider';
import type { ToolCoordinate } from './authorSpine';

export interface ObjectBreadcrumbProps {
  coordinate: ToolCoordinate | null;
  selectedNodeId?: string;
}

/** Renders the current Tool > DAG > Node context without entering a protocol payload. */
export default function ObjectBreadcrumb({ coordinate, selectedNodeId }: ObjectBreadcrumbProps) {
  const { t } = useI18n();
  const graphLabel = coordinate?.graphDraftId
    ? `${t('DAG')} · ${coordinate.graphDraftId}`
    : null;

  return (
    <nav
      className="spine-object-breadcrumb"
      data-testid="object-breadcrumb"
      data-tool-id={coordinate?.toolId}
      aria-label={t('Object context')}
    >
      {coordinate ? (
        <ol>
          <li><span>{t('Tool')}</span><strong title={coordinate.toolName}>{coordinate.toolName}</strong></li>
          {graphLabel && <li><span>{t('DAG')}</span><strong title={graphLabel}>{graphLabel}</strong></li>}
          {selectedNodeId && <li><span>{t('Node')}</span><strong title={selectedNodeId}>{selectedNodeId}</strong></li>}
        </ol>
      ) : (
        <p>{t('No tool selected')}</p>
      )}
    </nav>
  );
}
