import { useMemo, useState } from 'react';

import type { OperatorDefinition } from '../types';
import './toolAuthoring.css';

type OperatorKind = 'all' | 'publication' | 'resource';

interface ToolPaletteFacetsProps {
  operators: readonly OperatorDefinition[];
  onAddOperator?: (operatorRef: string) => void;
}

function kindOf(operatorRef: string): Exclude<OperatorKind, 'all'> | null {
  if (operatorRef.startsWith('resource:')) return 'resource';
  if (operatorRef.startsWith('publication:')) return 'publication';
  return null;
}

/**
 * Projects a flat operator catalog into explicit external-API and published-tool facets.
 *
 * @param props current catalog and the canvas insertion callback supplied by the host surface
 * @returns deterministic facet controls plus a stable operator list
 */
export default function ToolPaletteFacets({ operators, onAddOperator }: ToolPaletteFacetsProps) {
  const [facet, setFacet] = useState<OperatorKind>('all');
  const visible = useMemo(
    () => (facet === 'all' ? operators : operators.filter((operator) => kindOf(operator.operatorRef) === facet)),
    [facet, operators],
  );
  return (
    <div className="tool-palette-facets" data-testid="tool-palette-facets">
      {(['all', 'resource', 'publication'] as const).map((candidate) => (
        <button
          key={candidate}
          type="button"
          className={candidate === facet ? 'active compact' : 'compact'}
          data-testid={`tool-palette-${candidate}`}
          onClick={() => setFacet(candidate)}
        >
          {candidate === 'all' ? 'All' : candidate === 'publication' ? 'Published tools' : 'External APIs'}
        </button>
      ))}
      <ul>
        {visible.map((operator) => (
          <li key={operator.operatorRef}>
            <code>{operator.operatorRef}</code>
            <span>{kindOf(operator.operatorRef) ?? 'built-in'}</span>
            {(kindOf(operator.operatorRef) && onAddOperator) && (
              <button type="button" onClick={() => onAddOperator(operator.operatorRef)}>Add</button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
