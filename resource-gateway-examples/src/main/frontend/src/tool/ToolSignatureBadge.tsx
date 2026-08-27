import type { ToolSignature } from './toolModel';
import './toolAuthoring.css';

interface ToolSignatureBadgeProps {
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
  return (
    <span
      className="tool-signature-badge"
      data-testid="tool-signature-badge"
      data-tool-state={signature.state}
    >
      <strong>{signature.toolName}</strong>
      <span>{signature.state === 'published' ? 'Published' : 'Draft'}</span>
      {signature.publicationId && (
        <code>
          {`#${signature.publicationId} · r${String(signature.publicationRevision ?? '?')}`}
        </code>
      )}
    </span>
  );
}
