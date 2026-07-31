import { useEffect, useState } from 'react';

import type { SchemaEnvelope } from '../../types';
import SchemaValueForm from '../../contract-scenario/SchemaValueForm';

interface SchemaValueEditorProps {
  envelope?: SchemaEnvelope;
  schema?: Record<string, unknown>;
  value: unknown;
  onChange: (value: unknown) => void;
  label?: string;
  path?: string;
  compact?: boolean;
  advancedOnly?: boolean;
}

/**
 * Shared schema/value authoring surface for Graph, Operator, and Function Scenarios.
 *
 * The visual editor owns the default path. Advanced JSON is an explicit, lossless escape hatch
 * which keeps the last valid canonical value while the user is fixing malformed text.
 */
export default function SchemaValueEditor({
  envelope,
  schema,
  value,
  onChange,
  label = 'Value',
  path = '$',
  compact = false,
  advancedOnly = false,
}: SchemaValueEditorProps) {
  const canonicalText = pretty(value);
  const [advancedText, setAdvancedText] = useState(canonicalText);
  const [advancedError, setAdvancedError] = useState('');

  useEffect(() => {
    setAdvancedText(canonicalText);
    setAdvancedError('');
  }, [canonicalText]);

  const applyAdvanced = () => {
    try {
      onChange(JSON.parse(advancedText) as unknown);
      setAdvancedError('');
    } catch {
      setAdvancedError('Enter valid JSON before applying this advanced value.');
    }
  };

  return (
    <div className="shared-schema-value-editor" data-testid="schema-value-editor">
      {!advancedOnly && (
        <SchemaValueForm
          envelope={envelope}
          schema={schema}
          value={value}
          onChange={onChange}
          label={label}
          path={path}
          compact={compact}
        />
      )}
      <details className="shared-schema-value-advanced">
        <summary>{advancedOnly ? `${label} JSON` : 'Advanced JSON'}</summary>
        <textarea
          aria-label={`${label} advanced JSON`}
          value={advancedText}
          onChange={(event) => {
            setAdvancedText(event.target.value);
            setAdvancedError('');
          }}
          rows={Math.min(12, Math.max(4, advancedText.split('\n').length))}
          spellCheck={false}
        />
        {advancedError && <p role="alert">{advancedError}</p>}
        <button type="button" className="secondary compact" onClick={applyAdvanced}>
          Apply valid JSON
        </button>
      </details>
    </div>
  );
}

function pretty(value: unknown): string {
  return JSON.stringify(value ?? null, null, 2) ?? 'null';
}
