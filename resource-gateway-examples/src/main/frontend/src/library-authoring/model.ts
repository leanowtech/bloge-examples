import type {
  VisualFunctionAuthoring,
  VisualLibraryAuthoringDocument,
  VisualOperatorAuthoring,
} from '../types';

export type LibraryAssetKind = 'library' | 'type' | 'operator' | 'function';

export interface LibraryAssetSelection {
  kind: LibraryAssetKind;
  key: string;
}

export interface CompactFieldRow {
  id: string;
  name: string;
  type: string;
  required: boolean;
  sourceValue?: unknown;
}

export function createQuickLibraryDocument(
  libraryId: string,
  owner: string,
): VisualLibraryAuthoringDocument {
  const normalizedId = safeId(libraryId, 'new-operator-library');
  const namespace = normalizedId.split(/[-.:]/)[0] || 'app';
  return {
    schemaVersion: 'bloge.visualLibraryAuthoring.v1',
    library: {
      id: normalizedId,
      name: titleCase(normalizedId),
      version: '1.0.0',
      owner: owner.trim(),
      status: 'ACTIVE',
    },
    defaults: {
      operatorVersion: '1.0.0',
      namespace,
    },
    types: {},
    operators: {
      [`${namespace}:transform`]: {
        name: 'Transform',
        description: 'Transforms validated business input into a stable output.',
        archetype: 'pure',
        input: { value: 'string' },
        output: { result: 'string' },
        tests: [],
      },
    },
    functions: {},
    imports: [],
    examples: {},
  };
}

export function assetSelectionFromPath(authoringPath: string): LibraryAssetSelection {
  const segments = authoringPath.split('/').filter(Boolean).map(decodePointer);
  if (segments[0] === 'operators' && segments[1]) {
    return { kind: 'operator', key: segments[1] };
  }
  if (segments[0] === 'functions' && segments[1]) {
    return { kind: 'function', key: segments[1] };
  }
  if (segments[0] === 'types' && segments[1]) {
    return { kind: 'type', key: segments[1] };
  }
  return { kind: 'library', key: '' };
}

export function addAsset(
  document: VisualLibraryAuthoringDocument,
  kind: Exclude<LibraryAssetKind, 'library'>,
): { document: VisualLibraryAuthoringDocument; selection: LibraryAssetSelection } {
  if (kind === 'type') {
    const key = uniqueKey('BusinessRecord', document.types ?? {});
    return {
      document: { ...document, types: { ...(document.types ?? {}), [key]: { fields: {} } } },
      selection: { kind, key },
    };
  }
  if (kind === 'operator') {
    const namespace = document.defaults?.namespace || 'app';
    const key = uniqueKey(`${namespace}:new-operator`, document.operators ?? {});
    const operator: VisualOperatorAuthoring = {
      name: 'New Operator',
      description: '',
      archetype: 'pure',
      input: {},
      output: {},
      tests: [],
    };
    return {
      document: { ...document, operators: { ...(document.operators ?? {}), [key]: operator } },
      selection: { kind, key },
    };
  }
  const namespace = document.defaults?.namespace || 'app';
  const key = uniqueKey(`${namespace}.newFunction`, document.functions ?? {});
  const fn: VisualFunctionAuthoring = {
    name: key,
    description: '',
    category: 'business',
    signatures: ['(value: string) -> string'],
    examples: [],
    tests: [],
  };
  return {
    document: { ...document, functions: { ...(document.functions ?? {}), [key]: fn } },
    selection: { kind, key },
  };
}

export function renameAsset(
  document: VisualLibraryAuthoringDocument,
  selection: LibraryAssetSelection,
  nextKey: string,
): { document: VisualLibraryAuthoringDocument; selection: LibraryAssetSelection } {
  const normalized = nextKey.trim();
  if (!normalized || selection.kind === 'library' || normalized === selection.key) {
    return { document, selection };
  }
  const collectionName = `${selection.kind}s` as 'types' | 'operators' | 'functions';
  const collection = document[collectionName] ?? {};
  if (Object.prototype.hasOwnProperty.call(collection, normalized)) {
    return { document, selection };
  }
  const renamed = Object.fromEntries(Object.entries(collection).map(([key, value]) => {
    if (key !== selection.key) {
      return [key, value];
    }
    if (selection.kind === 'function' && value && typeof value === 'object') {
      return [normalized, { ...value as VisualFunctionAuthoring, name: normalized }];
    }
    return [normalized, value];
  }));
  return {
    document: { ...document, [collectionName]: renamed },
    selection: { ...selection, key: normalized },
  };
}

export function removeAsset(
  document: VisualLibraryAuthoringDocument,
  selection: LibraryAssetSelection,
): VisualLibraryAuthoringDocument {
  if (selection.kind === 'library') {
    return document;
  }
  const collectionName = `${selection.kind}s` as 'types' | 'operators' | 'functions';
  return {
    ...document,
    [collectionName]: Object.fromEntries(
      Object.entries(document[collectionName] ?? {})
        .filter(([key]) => key !== selection.key),
    ),
  };
}

export function compactFieldRows(fields: Record<string, unknown>): CompactFieldRow[] {
  return Object.entries(fields).map(([rawName, value], index) => {
    const required = !rawName.endsWith('?');
    const name = required ? rawName : rawName.slice(0, -1);
    return {
      id: `${index}:${rawName}`,
      name,
      type: compactTypeLabel(value),
      required,
      sourceValue: value,
    };
  });
}

export function compactFieldsFromRows(rows: CompactFieldRow[]): Record<string, unknown> {
  return Object.fromEntries(rows
    .map((row) => ({
      ...row,
      name: row.name.trim(),
      type: row.type.trim() || 'any',
    }))
    .filter((row) => row.name)
    .map((row) => [
      row.required ? row.name : `${row.name}?`,
      row.sourceValue !== undefined && compactTypeLabel(row.sourceValue) === row.type
        ? row.sourceValue
        : row.type,
    ]));
}

export interface NestedCompactField {
  path: string;
  name: string;
  type: string;
  required: boolean;
  depth: number;
}

export function nestedCompactFields(value: unknown): NestedCompactField[] {
  const result: NestedCompactField[] = [];
  collectNestedFields(value, '', 0, result);
  return result;
}

export function typeFields(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return {};
  }
  const fields = (value as { fields?: unknown }).fields;
  return fields && typeof fields === 'object' && !Array.isArray(fields)
    ? fields as Record<string, unknown>
    : {};
}

export function replaceTypeFields(value: unknown, fields: Record<string, unknown>): unknown {
  const source = value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
  return { ...source, fields };
}

export function encodeAuthoringPath(...segments: string[]): string {
  return `/${segments.map(encodePointer).join('/')}`;
}

function compactTypeLabel(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const node = value as { type?: unknown; enum?: unknown; fields?: unknown };
    if (typeof node.type === 'string') {
      return node.type;
    }
    if (Array.isArray(node.enum)) {
      return `enum(${node.enum.map(String).join('|')})`;
    }
    if (node.fields && typeof node.fields === 'object' && !Array.isArray(node.fields)) {
      return 'object';
    }
  }
  return 'any';
}

function collectNestedFields(
  value: unknown,
  parentPath: string,
  depth: number,
  result: NestedCompactField[],
): void {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return;
  }
  const fields = (value as { fields?: unknown }).fields;
  if (!fields || typeof fields !== 'object' || Array.isArray(fields)) {
    return;
  }
  Object.entries(fields as Record<string, unknown>).forEach(([rawName, child]) => {
    const required = !rawName.endsWith('?');
    const name = required ? rawName : rawName.slice(0, -1);
    const path = parentPath ? `${parentPath}.${name}` : name;
    result.push({
      path,
      name,
      type: compactTypeLabel(child),
      required,
      depth,
    });
    collectNestedFields(child, path, depth + 1, result);
  });
}

function uniqueKey(candidate: string, collection: Record<string, unknown>): string {
  if (!Object.prototype.hasOwnProperty.call(collection, candidate)) {
    return candidate;
  }
  let sequence = 2;
  while (Object.prototype.hasOwnProperty.call(collection, `${candidate}-${sequence}`)) {
    sequence += 1;
  }
  return `${candidate}-${sequence}`;
}

function safeId(value: string, fallback: string): string {
  const normalized = value.trim().replace(/[^A-Za-z0-9._:-]+/g, '-').replace(/^-+|-+$/g, '');
  return normalized || fallback;
}

function titleCase(value: string): string {
  return value
    .split(/[-_.:]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function encodePointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}

function decodePointer(value: string): string {
  return value.replace(/~1/g, '/').replace(/~0/g, '~');
}
