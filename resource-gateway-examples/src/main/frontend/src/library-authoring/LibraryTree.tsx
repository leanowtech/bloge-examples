import { Plus } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import type { VisualLibraryAuthoringDocument } from '../types';
import type { LibraryAssetKind, LibraryAssetSelection } from './model';

interface LibraryTreeProps {
  document: VisualLibraryAuthoringDocument;
  selection: LibraryAssetSelection;
  onSelect: (selection: LibraryAssetSelection) => void;
  onAdd: (kind: Exclude<LibraryAssetKind, 'library'>) => void;
}

export default function LibraryTree({
  document,
  selection,
  onSelect,
  onAdd,
}: LibraryTreeProps) {
  const { t , d } = useI18n();
  const groups: Array<{
    kind: Exclude<LibraryAssetKind, 'library'>;
    label: string;
    values: Record<string, unknown>;
  }> = [
    { kind: 'type', label: 'Types', values: document.types ?? {} },
    { kind: 'operator', label: 'Operators', values: document.operators ?? {} },
    { kind: 'function', label: 'Functions', values: document.functions ?? {} },
  ];
  const testCount = [
    ...Object.values(document.operators ?? {}),
    ...Object.values(document.functions ?? {}),
  ].reduce((count, asset) => (
    count + (asset.tests?.length ?? 0)
  ), 0);

  return (
    <aside className="library-tree" aria-label={t('Library assets')}>
      <button
        type="button"
        className={`library-tree-root ${selection.kind === 'library' ? 'selected' : ''}`}
        onClick={() => onSelect({ kind: 'library', key: '' })}
        data-testid="library-tree:library"
      >
        <span>{t('Library')}</span>
        <strong>{document.library.name || document.library.id}</strong>
      </button>
      {groups.map((group) => (
        <section key={group.kind}>
          <header>
            <span>{d(group.label)}</span>
            <span>{Object.keys(group.values).length}</span>
            <button
              type="button"
              aria-label={t('Add {kind}', { kind: d(group.kind) })}
              title={t('Add {kind}', { kind: d(group.kind) })}
              onClick={() => onAdd(group.kind)}
            >
              <Plus size={14} aria-hidden="true" />
            </button>
          </header>
          <ul>
            {Object.keys(group.values).map((key) => (
              <li key={key}>
                <button
                  type="button"
                  className={selection.kind === group.kind && selection.key === key ? 'selected' : ''}
                  onClick={() => onSelect({ kind: group.kind, key })}
                  data-testid={`library-tree:${group.kind}:${key}`}
                  title={key}
                >
                  <span aria-hidden="true">{group.kind === 'operator' ? 'OP' : group.kind === 'function' ? 'FN' : 'T'}</span>
                  <strong>{key}</strong>
                </button>
              </li>
            ))}
            {Object.keys(group.values).length === 0 && <li className="library-tree-empty">{t('None yet')}</li>}
          </ul>
        </section>
      ))}
      <footer>
        <span>{t('Test references')}</span>
        <strong>{testCount}</strong>
      </footer>
    </aside>
  );
}
