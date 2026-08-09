import { useRef, useState } from 'react';
import { ShieldAlert } from 'lucide-react';

import useDialogFocusTrap from '../accessibility/useDialogFocusTrap';
import { useI18n } from '../../i18n/I18nProvider';

interface ProductionCommandDialogProps {
  open: boolean;
  commandLabel: string;
  targetLabel: string;
  onCancel: () => void;
  onConfirm: () => void;
}

const CONFIRMATION_PHRASE = 'PRODUCTION';

/** Explicit production safeguard shared by destructive Author commands. */
export default function ProductionCommandDialog({
  open,
  commandLabel,
  targetLabel,
  onCancel,
  onConfirm,
}: ProductionCommandDialogProps) {
  const { t } = useI18n();
  const dialogRef = useRef<HTMLElement>(null);
  const [confirmation, setConfirmation] = useState('');
  useDialogFocusTrap({ open, dialogRef, onDismiss: onCancel, initialFocusKey: commandLabel });
  if (!open) return null;
  const confirmed = confirmation.trim().toUpperCase() === CONFIRMATION_PHRASE;

  return (
    <div
      className="rule-editor-backdrop production-command-backdrop"
      role="presentation"
      data-testid="production-command-backdrop"
    >
      <section
        ref={dialogRef}
        className="production-command-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="production-command-title"
        tabIndex={-1}
      >
        <header>
          <ShieldAlert aria-hidden="true" size={20} />
          <div>
            <span>{t('Production safeguard')}</span>
            <h2 id="production-command-title">{commandLabel}</h2>
          </div>
        </header>
        <dl>
          <div><dt>{t('Environment')}</dt><dd>PRODUCTION</dd></div>
          <div><dt>{t('Target')}</dt><dd>{targetLabel}</dd></div>
        </dl>
        <label>
          <span>{t('Type PRODUCTION to confirm this destructive command.')}</span>
          <input
            data-dialog-initial-focus
            aria-label={t('Production confirmation')}
            autoComplete="off"
            spellCheck={false}
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
          />
        </label>
        <footer>
          <button type="button" className="secondary compact" onClick={onCancel}>{t('Cancel')}</button>
          <button
            type="button"
            className="danger compact"
            disabled={!confirmed}
            onClick={onConfirm}
          >
            {t('Confirm command')}
          </button>
        </footer>
      </section>
    </div>
  );
}
