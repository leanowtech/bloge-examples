import type { OperatorSummary } from '../../draftModel';

export type NodeEditorVisualKind = OperatorSummary['visualKind'];

export type NodeEditorTab =
  | 'rules'
  | 'mapping'
  | 'config'
  | 'data'
  | 'test'
  | 'contract'
  | 'advanced';

export interface NodeEditorTabDefinition {
  id: NodeEditorTab;
  label: string;
}

export interface NodeEditorDefinition {
  visualKind: NodeEditorVisualKind;
  defaultTab: NodeEditorTab;
  primaryTask: string;
  tabs: NodeEditorTabDefinition[];
}

const COMMON_TABS: NodeEditorTabDefinition[] = [
  { id: 'config', label: 'Config' },
  { id: 'data', label: 'Data' },
  { id: 'test', label: 'Test' },
  { id: 'contract', label: 'Contract' },
  { id: 'advanced', label: 'Advanced' },
];

const DEFINITIONS: Record<NodeEditorVisualKind, NodeEditorDefinition> = {
  'decision-table': definition(
    'decision-table',
    'rules',
    'Edit decision rules',
    { id: 'rules', label: 'Rules' },
  ),
  transform: definition(
    'transform',
    'mapping',
    'Map output fields',
    { id: 'mapping', label: 'Mapping' },
  ),
  foreach: definition('foreach', 'config', 'Configure iteration'),
  resource: definition('resource', 'config', 'Configure resource call'),
  http: definition('http', 'config', 'Configure HTTP call'),
  streaming: definition('streaming', 'config', 'Configure event stream'),
  design: definition('design', 'contract', 'Review design contract'),
  generic: definition('generic', 'config', 'Configure operator'),
};

function definition(
  visualKind: NodeEditorVisualKind,
  defaultTab: NodeEditorTab,
  primaryTask: string,
  specializedTab?: NodeEditorTabDefinition,
): NodeEditorDefinition {
  return {
    visualKind,
    defaultTab,
    primaryTask,
    tabs: specializedTab ? [specializedTab, ...COMMON_TABS] : [...COMMON_TABS],
  };
}

/** Resolves a predictable editor contract for every catalog visual kind. */
export function resolveNodeEditor(
  visualKind: NodeEditorVisualKind | string | null | undefined,
): NodeEditorDefinition {
  if (visualKind && visualKind in DEFINITIONS) {
    return DEFINITIONS[visualKind as NodeEditorVisualKind];
  }
  return DEFINITIONS.generic;
}

export const BUILT_IN_NODE_EDITOR_KINDS = Object.freeze(
  Object.keys(DEFINITIONS) as NodeEditorVisualKind[],
);
