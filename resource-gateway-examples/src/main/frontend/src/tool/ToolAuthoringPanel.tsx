import { useState } from 'react';

import type { ToolCoordinate } from '../spine/authorSpine';
import { useI18n } from '../i18n/I18nProvider';
import type { ToolDraftLike, ToolPublicationMetadata } from './toolModel';
import { toolSignatureFromDraft } from './toolModel';
import ToolSignatureBadge from './ToolSignatureBadge';
import { publishToolDraft, type ToolAuthoringRequester } from './toolTransport';
import './toolAuthoring.css';

export interface ToolAuthoringPanelProps {
  /** Current server-shaped graph draft projection, not a separate tool protocol. */
  draft?: ToolDraftLike;
  /** Navigation identity used for display and publication context. */
  coordinate: ToolCoordinate | null;
  /** Immutable publication receipt for the current draft revision, if any. */
  publication?: ToolPublicationMetadata;
  /** Receives the real publication identity after a successful publish. */
  onPublished: (publication: ToolPublicationMetadata) => void | Promise<void>;
  /** Honest notice when the publication succeeded but catalog refresh did not. */
  catalogError?: string;
  /** Retries catalog refresh without repeating or undoing the successful publication. */
  onRefreshCatalog?: () => void | Promise<void>;
  /** Injectable transport seam used by focused tests and host adapters. */
  request?: ToolAuthoringRequester;
}

/**
 * Renders the spine-aware tool signature and publish action for the current graph draft.
 *
 * @param props current draft, navigation coordinate, receipt callback, and request seam
 * @returns compact authoring status panel
 */
export default function ToolAuthoringPanel({
  draft,
  coordinate,
  publication,
  onPublished,
  catalogError,
  onRefreshCatalog,
  request,
}: ToolAuthoringPanelProps) {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  if (!coordinate) return null;
  const signature = toolSignatureFromDraft(draft, coordinate, publication);
  const canPublish = Boolean(draft?.draftId && draft.revision && signature.state !== 'published');
  const publish = async () => {
    if (!draft?.draftId || !draft.revision) return;
    setBusy(true);
    setError('');
    try {
      await onPublished(await publishToolDraft(draft.draftId, draft.revision, request));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Tool publish failed.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="tool-authoring-panel" data-testid="tool-authoring-panel">
      <ToolSignatureBadge signature={signature} />
      <button
        type="button"
        className="primary compact"
        data-testid="tool-publish"
        disabled={!canPublish || busy}
        onClick={() => void publish()}
      >
        {busy ? t('Publishing…') : t('Publish tool')}
      </button>
      {catalogError && (
        <p className="tool-authoring-warning" role="status">
          {catalogError}
          {onRefreshCatalog && (
            <button type="button" className="secondary compact" data-testid="tool-catalog-refresh" onClick={() => void onRefreshCatalog()}>
              {t('Refresh catalog')}
            </button>
          )}
        </p>
      )}
      {error && <p className="tool-authoring-error" role="alert">{error}</p>}
    </section>
  );
}
