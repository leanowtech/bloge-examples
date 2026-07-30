import { useEffect, useState } from 'react';

import type { VisualFunctionAuthoring } from '../types';
import { ReferenceEditor } from './OperatorBuilder';

interface FunctionBuilderProps {
  functionKey: string;
  fn: VisualFunctionAuthoring;
  onRename: (nextKey: string) => void;
  onChange: (fn: VisualFunctionAuthoring) => void;
  onRemove: () => void;
  onOpenTests: () => void;
}

export default function FunctionBuilder({
  functionKey,
  fn,
  onRename,
  onChange,
  onRemove,
  onOpenTests,
}: FunctionBuilderProps) {
  const [keyDraft, setKeyDraft] = useState(functionKey);
  useEffect(() => setKeyDraft(functionKey), [functionKey]);
  const signatures = fn.signatures?.length
    ? fn.signatures
    : fn.signature ? [fn.signature] : [];
  const patch = (value: Partial<VisualFunctionAuthoring>) => onChange({ ...fn, ...value });
  const updateSignatures = (values: string[]) => patch({ signature: undefined, signatures: values });

  return (
    <div className="library-task-builder" data-testid="function-builder">
      <header className="library-builder-heading">
        <div>
          <span>Built-in Function</span>
          <h2>{functionKey}</h2>
        </div>
        <button type="button" className="danger compact" onClick={onRemove}>Delete</button>
      </header>

      <section className="library-builder-section">
        <header><h3>Identity</h3><span>Expression callable</span></header>
        <div className="library-form-grid">
          <label>
            <span>Callable name</span>
            <input
              value={keyDraft}
              onChange={(event) => setKeyDraft(event.target.value)}
              onBlur={() => onRename(keyDraft)}
              data-authoring-path={`/functions/${pointer(functionKey)}`}
            />
          </label>
          <label>
            <span>Category</span>
            <input
              value={fn.category ?? ''}
              onChange={(event) => patch({ category: event.target.value })}
              data-authoring-path={`/functions/${pointer(functionKey)}/category`}
            />
          </label>
          <label className="library-form-wide">
            <span>Description</span>
            <textarea
              value={fn.description ?? ''}
              onChange={(event) => patch({ description: event.target.value })}
              data-authoring-path={`/functions/${pointer(functionKey)}/description`}
            />
          </label>
        </div>
      </section>

      <section className="library-builder-section">
        <header><h3>Signatures</h3><span>{signatures.length} overloads</span></header>
        <table className="function-signature-table">
          <thead><tr><th>Overload</th><th>Signature</th><th aria-label="Actions" /></tr></thead>
          <tbody>
            {signatures.map((signature, index) => (
              <tr key={`${index}:${signature}`}>
                <td>{index + 1}</td>
                <td>
                  <input
                    aria-label={`Function signature ${index + 1}`}
                    value={signature}
                    onChange={(event) => updateSignatures(
                      signatures.map((value, valueIndex) => (
                        valueIndex === index ? event.target.value : value
                      )),
                    )}
                    data-authoring-path={`/functions/${pointer(functionKey)}/signatures/${index}`}
                  />
                </td>
                <td>
                  <button
                    type="button"
                    aria-label={`Remove overload ${index + 1}`}
                    title="Remove overload"
                    onClick={() => updateSignatures(
                      signatures.filter((_, valueIndex) => valueIndex !== index),
                    )}
                  >
                    x
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <button
          type="button"
          className="secondary compact"
          onClick={() => updateSignatures([...signatures, '(value: string) -> string'])}
        >
          + Add overload
        </button>
      </section>

      <section className="library-builder-section">
        <header><h3>Expression Examples</h3><span>{fn.examples?.length ?? 0}</span></header>
        <StringListEditor
          label="Expression example"
          values={fn.examples ?? []}
          initialValue={`${functionKey}(ctx.value)`}
          onChange={(examples) => patch({ examples })}
        />
      </section>

      <section className="library-builder-section">
        <header>
          <h3>Tests</h3>
          <button
            type="button"
            className="primary compact"
            onClick={onOpenTests}
            data-testid="open-function-test-table"
          >
            Open test table
          </button>
        </header>
        <ReferenceEditor
          values={(fn.tests ?? []).map((test) => test.ref)}
          onChange={(values) => patch({ tests: values.map((ref) => ({ ref })) })}
        />
      </section>
    </div>
  );
}

interface StringListEditorProps {
  label: string;
  values: string[];
  initialValue: string;
  onChange: (values: string[]) => void;
}

function StringListEditor({
  label,
  values,
  initialValue,
  onChange,
}: StringListEditorProps) {
  return (
    <div className="reference-editor">
      {values.map((value, index) => (
        <div key={`${index}:${value}`}>
          <input
            aria-label={`${label} ${index + 1}`}
            value={value}
            onChange={(event) => onChange(
              values.map((item, itemIndex) => itemIndex === index ? event.target.value : item),
            )}
          />
          <button
            type="button"
            aria-label={`Remove ${label.toLowerCase()} ${index + 1}`}
            title={`Remove ${label.toLowerCase()}`}
            onClick={() => onChange(values.filter((_, itemIndex) => itemIndex !== index))}
          >
            x
          </button>
        </div>
      ))}
      <button
        type="button"
        className="secondary compact"
        onClick={() => onChange([...values, initialValue])}
      >
        + Add {label.toLowerCase()}
      </button>
    </div>
  );
}

function pointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}
