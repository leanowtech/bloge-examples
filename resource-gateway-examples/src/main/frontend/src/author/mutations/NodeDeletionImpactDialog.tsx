import { useRef } from 'react';
import { Redo2, Trash2, Undo2, X } from 'lucide-react';

import useDialogFocusTrap from '../accessibility/useDialogFocusTrap';
import { useI18n } from '../../i18n/I18nProvider';
import type {
  AssetImpact,
  AssetImpactKind,
  NodeDeletionImpact,
} from './reversibleMutationJournal';

interface NodeDeletionImpactDialogProps {
  open: boolean;
  nodeLabels: string[];
  impact: NodeDeletionImpact;
  onCancel: () => void;
  onConfirm: () => void;
}

interface MutationNoticeProps {
  message: string;
  action: 'undo' | 'redo';
  onAction: () => void;
  onDismiss: () => void;
}

const IMPACT_LABELS: Record<AssetImpactKind, { singular: string; plural: string }> = {
  NODE: { singular: 'node', plural: 'nodes' },
  EDGE: { singular: 'connected edge', plural: 'connected edges' },
  FIXTURE_OUTPUT: { singular: 'fixture output', plural: 'fixture outputs' },
  FIXTURE_INPUT: { singular: 'fixture input', plural: 'fixture inputs' },
  TEST_CASE: { singular: 'operator test case', plural: 'operator test cases' },
  TEST_RESULT: { singular: 'operator test result', plural: 'operator test results' },
  TEST_PUBLICATION: { singular: 'governed test publication', plural: 'governed test publications' },
  OUTPUT_BINDING: { singular: 'Graph output binding', plural: 'Graph output bindings' },
};

export default function NodeDeletionImpactDialog({
  open,
  nodeLabels,
  impact,
  onCancel,
  onConfirm,
}: NodeDeletionImpactDialogProps) {
  const { t } = useI18n();
  const dialogRef = useRef<HTMLElement>(null);
  useDialogFocusTrap({ open, dialogRef, onDismiss: onCancel, initialFocusKey: nodeLabels.join(':') });
  if (!open) return null;

  return (
    <div className="rule-editor-backdrop" role="presentation" data-testid="node-delete-impact-backdrop">
      <section
        ref={dialogRef}
        className="node-delete-impact-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="node-delete-impact-title"
        tabIndex={-1}
      >
        <div className="node-delete-impact-heading">
          <span className="node-delete-impact-icon" aria-hidden="true"><Trash2 size={18} /></span>
          <div>
            <h2 id="node-delete-impact-title">{t('Review impact before deleting')}</h2>
            <p>{t('This is one reversible transaction. Undo restores every item listed below.')}</p>
          </div>
        </div>
        <div className="node-delete-targets">
          <span>{t('Deleting')}</span>
          <strong>{nodeLabels.join(', ')}</strong>
        </div>
        <ul className="node-delete-impact-list" aria-label={t('Affected assets')}>
          {impact.items.map((item) => (
            <ImpactRow key={item.kind} item={item} />
          ))}
        </ul>
        <div className="node-delete-impact-actions">
          <button type="button" className="secondary compact" onClick={onCancel} data-dialog-initial-focus>
            {t('Keep node')}
          </button>
          <button type="button" className="danger compact" onClick={onConfirm}>
            <Trash2 size={15} aria-hidden="true" />
            {t('Delete node and assets')}
          </button>
        </div>
      </section>
    </div>
  );
}

export function MutationNotice({ message, action, onAction, onDismiss }: MutationNoticeProps) {
  const { t } = useI18n();
  const ActionIcon = action === 'undo' ? Undo2 : Redo2;
  return (
    <div className="author-mutation-notice" data-testid="author-mutation-notice">
      <span role="status" aria-live="polite">{message}</span>
      <button type="button" className="compact" onClick={onAction}>
        <ActionIcon size={15} aria-hidden="true" />
        {t(action === 'undo' ? 'Undo' : 'Redo')}
      </button>
      <button
        type="button"
        className="icon-button"
        aria-label={t('Dismiss')}
        title={t('Dismiss')}
        onClick={onDismiss}
      >
        <X size={15} aria-hidden="true" />
      </button>
    </div>
  );
}

function ImpactRow({ item }: { item: AssetImpact }) {
  const { t } = useI18n();
  const labels = IMPACT_LABELS[item.kind];
  const label = item.count === 1 ? labels.singular : labels.plural;
  return (
    <li data-severity={item.severity.toLowerCase()}>
      <strong>{item.count}</strong>
      {' '}
      <span>{t(label)}</span>
    </li>
  );
}
