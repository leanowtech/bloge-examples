import { Redo2, Undo2, X } from 'lucide-react';
import { useI18n } from '../../i18n/I18nProvider';

interface MutationNoticeProps {
  message: string;
  action: 'undo' | 'redo';
  onAction: () => void;
  onDismiss: () => void;
}

/** Small always-available undo/redo notice; the heavier deletion dialog stays lazy-loaded. */
export default function MutationNotice({ message, action, onAction, onDismiss }: MutationNoticeProps) {
  const { t } = useI18n();
  const ActionIcon = action === 'undo' ? Undo2 : Redo2;
  return (
    <div className="author-mutation-notice" data-testid="author-mutation-notice">
      <span role="status" aria-live="polite">{message}</span>
      <button type="button" className="compact" onClick={onAction}>
        <ActionIcon size={15} aria-hidden="true" />
        {t(action === 'undo' ? 'Undo' : 'Redo')}
      </button>
      <button type="button" className="icon-button" aria-label={t('Dismiss')} title={t('Dismiss')} onClick={onDismiss}>
        <X size={15} aria-hidden="true" />
      </button>
    </div>
  );
}
