import type { ToolSignature } from './toolModel';
import { useI18n } from '../i18n/I18nProvider';
import './toolAuthoring.css';

export interface ToolSignatureBadgeProps {
  /** The author-facing tool projection. Invalid publication metadata is already removed upstream. */
  signature: ToolSignature;
}

/**
 * Labels the first-class object as a draft or published tool and exposes the exact revision.
 *
 * @param props signature projection owned by the tool module
 * @returns a compact lifecycle badge
 */
export default function ToolSignatureBadge({ signature }: ToolSignatureBadgeProps) {
  const { t } = useI18n();
  return (
    <span
      className="tool-signature-badge"
      data-testid="tool-signature-badge"
      data-tool-state={signature.state}
      data-tool-schema-state={signature.schemaState}
    >
      <strong>{signature.toolName}</strong>
      <span>
        {signature.state === 'published' ? t('Published') : signature.state === 'draft' ? t('Draft') : t('Unknown')}
      </span>
      <span>{signature.schemaState === 'typed' ? t('Typed I/O') : signature.schemaState === 'opaque' ? t('Opaque I/O') : t('I/O unknown')}</span>
      {signature.publicationId && (
        <code>
          {`#${signature.publicationId} · r${String(signature.publicationRevision ?? '?')}`}
        </code>
      )}
    </span>
  );
}
