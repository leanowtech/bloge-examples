import type {
  DraftNode,
  DraftNodeBinding,
  GraphDraft,
  OperatorDefinition,
  OperatorPort,
  SchemaEnvelope,
  SimulationResponse,
} from '../../types';

export type EffectiveFieldSource = 'DECLARED' | 'INFERRED' | 'OBSERVED';
export type EffectiveConfidence = 'EXACT' | 'INFERRED' | 'OBSERVED' | 'OPAQUE';
export type EffectiveProjectionConfidence = 'EXACT' | 'INFERRED' | 'OPAQUE' | 'CONFLICTED';

export interface EffectiveFieldTrace {
  kind: 'SCHEMA' | 'ASSIGNMENT' | 'DECISION_OUTPUT' | 'RUN_RESULT';
  nodeId: string;
  coordinate: string;
  detail: string;
}

export interface EffectiveContractField {
  path: string;
  type: string;
  required: boolean;
  source: EffectiveFieldSource;
  confidence: EffectiveConfidence;
  trace: EffectiveFieldTrace;
}

export interface EffectiveInputBinding {
  id: string;
  targetPath: string;
  sourcePath: string;
  kind: 'EDGE' | 'CONTEXT' | 'CONSTANT' | 'NODE' | 'EXPRESSION' | 'OPAQUE';
  type: string;
  confidence: EffectiveConfidence;
  status: 'CONNECTED' | 'UNBOUND' | 'CONFLICT';
  edgeId: string;
  sourceNodeId: string;
}

export interface EffectiveContractConflict {
  path: string;
  code: 'TYPE_MISMATCH' | 'MULTIPLE_SOURCES';
  message: string;
  types: string[];
}

export interface EffectiveContractProjection {
  target: {
    nodeId: string;
    operatorRef: string;
  };
  declaredInputs: EffectiveContractField[];
  declaredOutputs: EffectiveContractField[];
  inferredOutputs: EffectiveContractField[];
  observedOutputs: EffectiveContractField[];
  activeBindings: EffectiveInputBinding[];
  conflicts: EffectiveContractConflict[];
  confidence: EffectiveProjectionConfidence;
  provenance: {
    declared: string;
    inferred: string[];
    bound: string[];
    observed: string;
  };
}

export interface EffectiveContractProjectionRequest {
  graphDraft: GraphDraft;
  nodeId: string;
  operator?: OperatorDefinition;
  operators?: OperatorDefinition[];
  run?: SimulationResponse | null;
}

/**
 * Projects the effective node contract without mutating declared schema or observed payloads.
 *
 * Declared, inferred, bound, and observed sources intentionally remain separate. Consumers can
 * compare them or explicitly accept an inference, but a runtime observation can never silently
 * become an authored Contract through this projection.
 */
export function projectEffectiveContract(
  request: EffectiveContractProjectionRequest,
): EffectiveContractProjection {
  const node = request.graphDraft.nodes.find((candidate) => candidate.id === request.nodeId);
  if (!node) {
    return emptyProjection(request.nodeId);
  }
  const operators = operatorIndex(request.graphDraft, request.operators ?? []);
  const operator = request.operator ?? operators.get(node.operatorRef);
  const declaredInputs = fieldsFromPorts(node.id, operator?.ports?.inputs ?? [], 'input');
  const declaredOutputs = fieldsFromPorts(node.id, operator?.ports?.outputs ?? [], 'output');
  const inferredOutputs = inferredFields(node);
  const observedOutputs = observedFields(node, request.run);
  const activeBindings = bindingsForNode(
    request.graphDraft,
    node,
    declaredInputs,
    operators,
  );
  const conflicts = [
    ...bindingConflicts(activeBindings, declaredInputs),
    ...outputConflicts(declaredOutputs, inferredOutputs, observedOutputs),
  ];
  return {
    target: { nodeId: node.id, operatorRef: node.operatorRef },
    declaredInputs,
    declaredOutputs,
    inferredOutputs,
    observedOutputs,
    activeBindings: withRequiredGaps(activeBindings, declaredInputs),
    conflicts,
    confidence: projectionConfidence(declaredOutputs, inferredOutputs, conflicts),
    provenance: {
      declared: operator
        ? `Operator catalog ${operator.source?.libraryId || operator.source?.kind || 'definition'}`
        : 'No operator declaration',
      inferred: inferredOutputs.map((field) => field.trace.coordinate),
      bound: activeBindings.map((binding) => binding.edgeId || binding.id),
      observed: request.run ? `Latest exploratory run for ${request.run.graphName}` : 'No run observation',
    },
  };
}

/**
 * Builds an open authored schema from inferred output fields for an explicit user acceptance step.
 *
 * Required fields are deliberately not invented. Accepting inference records field names and types
 * while retaining an open object until the author tightens requiredness and compatibility policy.
 */
export function schemaFromAcceptedInference(
  projection: EffectiveContractProjection,
): SchemaEnvelope | null {
  const properties = Object.fromEntries(
    projection.inferredOutputs
      .map((field) => [topLevelOutputField(field.path), schemaForType(field.type)])
      .filter(([name]) => Boolean(name)),
  );
  if (Object.keys(properties).length === 0) {
    return null;
  }
  return {
    format: 'json-schema',
    version: '2020-12',
    schema: {
      type: 'object',
      properties,
      required: [],
      additionalProperties: true,
    },
  };
}

function fieldsFromPorts(
  nodeId: string,
  ports: OperatorPort[],
  direction: 'input' | 'output',
): EffectiveContractField[] {
  return ports.flatMap((port, index) => {
    const portName = port.name || direction;
    const fields = flattenSchemaFields(port.schema.schema, portName, Boolean(port.required));
    if (fields.length > 0) {
      return fields.map((field) => ({
        ...field,
        source: 'DECLARED' as const,
        confidence: 'EXACT' as const,
        trace: {
          kind: 'SCHEMA' as const,
          nodeId,
          coordinate: `/ports/${direction}s/${index}/schema/${field.path}`,
          detail: `${portName} is declared by the operator schema.`,
        },
      }));
    }
    return [{
      path: portName,
      type: schemaType(port.schema.schema),
      required: Boolean(port.required),
      source: 'DECLARED' as const,
      confidence: 'EXACT' as const,
      trace: {
        kind: 'SCHEMA' as const,
        nodeId,
        coordinate: `/ports/${direction}s/${index}/schema`,
        detail: `${portName} is an open declared port.`,
      },
    }];
  });
}

function flattenSchemaFields(
  schema: Record<string, unknown>,
  prefix: string,
  parentRequired: boolean,
  depth = 0,
): Array<Pick<EffectiveContractField, 'path' | 'type' | 'required'>> {
  const properties = recordValue(schema.properties);
  if (depth >= 4 || Object.keys(properties).length === 0) {
    return [];
  }
  const required = new Set(stringArray(schema.required));
  return Object.entries(properties).flatMap(([name, rawField]) => {
    const field = recordValue(rawField);
    const path = `${prefix}.${name}`;
    const children = flattenSchemaFields(
      field,
      path,
      parentRequired && required.has(name),
      depth + 1,
    );
    return children.length > 0
      ? children
      : [{
          path,
          type: schemaType(field),
          required: parentRequired && required.has(name),
        }];
  });
}

function inferredFields(node: DraftNode): EffectiveContractField[] {
  const assignments = recordValue(node.config?.assignments);
  if (Object.keys(assignments).length > 0) {
    return Object.entries(assignments).map(([field, rawExpression]) => {
      const expression = String(rawExpression);
      return {
        path: `output.${field}`,
        type: expressionType(expression),
        required: false,
        source: 'INFERRED',
        confidence: expressionType(expression) === 'any' ? 'OPAQUE' : 'INFERRED',
        trace: {
          kind: 'ASSIGNMENT',
          nodeId: node.id,
          coordinate: `/nodes/${node.id}/config/assignments/${field}`,
          detail: expression,
        },
      };
    });
  }
  const columns = decisionOutputColumns(node.config);
  const rules = Array.isArray(node.config?.rules) ? node.config.rules : [];
  return columns.map((field) => {
    const samples = rules
      .map((rule) => recordValue(recordValue(rule).output)[field])
      .filter((value) => value !== undefined);
    const types = [...new Set(samples.map(valueType))];
    return {
      path: `output.${field}`,
      type: types.length === 1 ? types[0] : typeFromOutputSignature(node.config?.outputType, field),
      required: false,
      source: 'INFERRED',
      confidence: types.length === 1 ? 'INFERRED' : 'OPAQUE',
      trace: {
        kind: 'DECISION_OUTPUT',
        nodeId: node.id,
        coordinate: `/nodes/${node.id}/config/outputColumns/${field}`,
        detail: `${samples.length} decision rule sample${samples.length === 1 ? '' : 's'}`,
      },
    };
  });
}

function observedFields(
  node: DraftNode,
  run: SimulationResponse | null | undefined,
): EffectiveContractField[] {
  if (!run || !(node.id in run.results)) {
    return [];
  }
  return flattenObservedValue(run.results[node.id], 'output').map((field) => ({
    ...field,
    required: false,
    source: 'OBSERVED',
    confidence: 'OBSERVED',
    trace: {
      kind: 'RUN_RESULT',
      nodeId: node.id,
      coordinate: `/results/${node.id}/${field.path}`,
      detail: 'Observed in the latest exploratory run; not part of the authored Contract.',
    },
  }));
}

function flattenObservedValue(
  value: unknown,
  prefix: string,
  depth = 0,
): Array<Pick<EffectiveContractField, 'path' | 'type'>> {
  if (depth >= 4 || !isRecord(value) || Object.keys(value).length === 0) {
    return [{ path: prefix, type: valueType(value) }];
  }
  return Object.entries(value).flatMap(([name, child]) => (
    Array.isArray(child) || !isRecord(child)
      ? [{ path: `${prefix}.${name}`, type: valueType(child) }]
      : flattenObservedValue(child, `${prefix}.${name}`, depth + 1)
  ));
}

function bindingsForNode(
  graph: GraphDraft,
  node: DraftNode,
  declaredInputs: EffectiveContractField[],
  operators: Map<string, OperatorDefinition>,
): EffectiveInputBinding[] {
  const edges = graph.edges
    .filter((edge) => edge.target.nodeId === node.id && (!edge.kind || edge.kind === 'data'))
    .map((edge) => {
      const sourceNode = graph.nodes.find((candidate) => candidate.id === edge.source.nodeId);
      const sourceOperator = sourceNode ? operators.get(sourceNode.operatorRef) : undefined;
      const sourceDeclared = sourceNode
        ? fieldsFromPorts(sourceNode.id, sourceOperator?.ports?.outputs ?? [], 'output')
        : [];
      const sourceInferred = sourceNode ? inferredFields(sourceNode) : [];
      const sourcePath = endpointPath(edge.source.port || 'output', edge.source.path);
      const targetPath = endpointPath(
        edge.target.port || defaultInputPort(declaredInputs),
        edge.target.path,
      );
      const sourceField = findField(sourceDeclared, sourcePath) ?? findField(sourceInferred, sourcePath);
      return {
        id: edge.id,
        targetPath,
        sourcePath: `${sourceNode?.label || edge.source.nodeId}.${sourcePath}`,
        kind: 'EDGE' as const,
        type: sourceField?.type ?? 'any',
        confidence: sourceField?.confidence ?? 'OPAQUE',
        status: 'CONNECTED' as const,
        edgeId: edge.id,
        sourceNodeId: edge.source.nodeId,
      };
    });
  const direct = Object.entries(node.inputs ?? {}).map(([bindingKey, binding]) => (
    directBinding(graph, bindingKey, binding, declaredInputs, operators)
  ));
  const projectedEdgeKeys = new Set(edges.map(bindingIdentity));
  return [
    ...edges,
    ...direct.filter((binding) => (
      binding.kind !== 'NODE' || !projectedEdgeKeys.has(bindingIdentity(binding))
    )),
  ];
}

function bindingIdentity(binding: EffectiveInputBinding): string {
  return [
    normalizedPath(binding.targetPath),
    binding.sourceNodeId,
    normalizedPath(binding.sourcePath),
  ].join('\u001f');
}

function directBinding(
  graph: GraphDraft,
  bindingKey: string,
  binding: DraftNodeBinding,
  declaredInputs: EffectiveContractField[],
  operators: Map<string, OperatorDefinition>,
): EffectiveInputBinding {
  const targetPath = endpointPath(
    binding.targetPort || defaultInputPort(declaredInputs),
    binding.targetPath || bindingKey,
  );
  if (binding.kind === 'contextPath') {
    const type = schemaTypeAtPath(graph.inputSchema?.schema, binding.path || '');
    return bound(bindingKey, targetPath, `ctx.${binding.path || bindingKey}`, 'CONTEXT', type, 'EXACT');
  }
  if (binding.kind === 'constant') {
    return bound(bindingKey, targetPath, displayConstant(binding.value), 'CONSTANT', valueType(binding.value), 'EXACT');
  }
  if (binding.kind === 'nodePath' || binding.nodeId) {
    const sourceNode = graph.nodes.find((candidate) => candidate.id === binding.nodeId);
    const sourceOperator = sourceNode ? operators.get(sourceNode.operatorRef) : undefined;
    const sourceFields = sourceNode
      ? [
          ...fieldsFromPorts(sourceNode.id, sourceOperator?.ports?.outputs ?? [], 'output'),
          ...inferredFields(sourceNode),
        ]
      : [];
    const sourcePath = endpointPath(binding.sourcePort || 'output', binding.path);
    const sourceField = findField(sourceFields, sourcePath);
    return {
      ...bound(
        bindingKey,
        targetPath,
        `${sourceNode?.label || binding.nodeId}.${sourcePath}`,
        'NODE',
        sourceField?.type ?? 'any',
        sourceField?.confidence ?? 'OPAQUE',
      ),
      sourceNodeId: binding.nodeId || '',
    };
  }
  if (binding.kind === 'expression' || binding.expr) {
    return bound(
      bindingKey,
      targetPath,
      binding.expr || binding.path || 'expression',
      'EXPRESSION',
      expressionType(binding.expr || ''),
      'INFERRED',
    );
  }
  return bound(bindingKey, targetPath, binding.path || binding.kind, 'OPAQUE', 'any', 'OPAQUE');
}

function bound(
  id: string,
  targetPath: string,
  sourcePath: string,
  kind: EffectiveInputBinding['kind'],
  type: string,
  confidence: EffectiveConfidence,
): EffectiveInputBinding {
  return {
    id,
    targetPath,
    sourcePath,
    kind,
    type,
    confidence,
    status: 'CONNECTED',
    edgeId: '',
    sourceNodeId: '',
  };
}

function withRequiredGaps(
  bindings: EffectiveInputBinding[],
  declaredInputs: EffectiveContractField[],
): EffectiveInputBinding[] {
  const targets = new Set(bindings.map((binding) => normalizedPath(binding.targetPath)));
  const gaps = declaredInputs
    .filter((field) => field.required && !targets.has(normalizedPath(field.path)))
    .map((field) => ({
      id: `unbound:${field.path}`,
      targetPath: field.path,
      sourcePath: 'No source',
      kind: 'OPAQUE' as const,
      type: field.type,
      confidence: 'EXACT' as const,
      status: 'UNBOUND' as const,
      edgeId: '',
      sourceNodeId: '',
    }));
  return [...bindings, ...gaps];
}

function bindingConflicts(
  bindings: EffectiveInputBinding[],
  declaredInputs: EffectiveContractField[],
): EffectiveContractConflict[] {
  const conflicts: EffectiveContractConflict[] = [];
  const byTarget = new Map<string, EffectiveInputBinding[]>();
  bindings.forEach((binding) => {
    const path = normalizedPath(binding.targetPath);
    byTarget.set(path, [...(byTarget.get(path) ?? []), binding]);
  });
  for (const [path, candidates] of byTarget) {
    if (candidates.length > 1) {
      conflicts.push({
        path,
        code: 'MULTIPLE_SOURCES',
        message: `${path} has ${candidates.length} active sources.`,
        types: [...new Set(candidates.map((candidate) => candidate.type))],
      });
      candidates.forEach((candidate) => {
        candidate.status = 'CONFLICT';
      });
    }
    const declared = findField(declaredInputs, path);
    for (const candidate of candidates) {
      if (declared && incompatibleTypes(declared.type, candidate.type)) {
        conflicts.push({
          path,
          code: 'TYPE_MISMATCH',
          message: `${candidate.type} source is incompatible with declared ${declared.type}.`,
          types: [declared.type, candidate.type],
        });
        candidate.status = 'CONFLICT';
      }
    }
  }
  return conflicts;
}

function outputConflicts(
  declared: EffectiveContractField[],
  inferred: EffectiveContractField[],
  observed: EffectiveContractField[],
): EffectiveContractConflict[] {
  const paths = new Set([...declared, ...inferred, ...observed].map((field) => normalizedPath(field.path)));
  return [...paths].flatMap((path) => {
    const types = [...new Set(
      [findField(declared, path), findField(inferred, path), findField(observed, path)]
        .map((field) => field?.type)
        .filter((type): type is string => Boolean(type) && type !== 'any'),
    )];
    return types.length > 1
      ? [{
          path,
          code: 'TYPE_MISMATCH' as const,
          message: `${path} has conflicting declared, inferred, or observed types.`,
          types,
        }]
      : [];
  });
}

function projectionConfidence(
  declared: EffectiveContractField[],
  inferred: EffectiveContractField[],
  conflicts: EffectiveContractConflict[],
): EffectiveProjectionConfidence {
  if (conflicts.length > 0) return 'CONFLICTED';
  const explicitDeclared = declared.some((field) => !field.path.endsWith('output') || field.type !== 'object');
  if (explicitDeclared && inferred.length === 0) return 'EXACT';
  if (inferred.length > 0) return 'INFERRED';
  return 'OPAQUE';
}

function operatorIndex(
  graph: GraphDraft,
  operators: OperatorDefinition[],
): Map<string, OperatorDefinition> {
  return new Map([
    ...Object.values(graph.operatorSnapshots ?? {}),
    ...operators,
  ].map((operator) => [operator.operatorRef, operator]));
}

function findField(
  fields: EffectiveContractField[],
  path: string,
): EffectiveContractField | undefined {
  const normalized = normalizedPath(path);
  return fields.find((field) => normalizedPath(field.path) === normalized);
}

function normalizedPath(path: string): string {
  return path.replace(/^\$\.?/, '').replace(/^\.+|\.+$/g, '');
}

function endpointPath(port: string, path: string | undefined): string {
  const normalizedPort = normalizedPath(port) || 'value';
  const normalizedChild = normalizedPath(path || '');
  return normalizedChild ? `${normalizedPort}.${normalizedChild}` : normalizedPort;
}

function defaultInputPort(fields: EffectiveContractField[]): string {
  return fields[0]?.path.split('.')[0] || 'inputs';
}

function schemaTypeAtPath(schema: Record<string, unknown> | undefined, path: string): string {
  let current = recordValue(schema);
  for (const segment of normalizedPath(path).split('.').filter(Boolean)) {
    current = recordValue(recordValue(current.properties)[segment]);
  }
  return schemaType(current);
}

function schemaType(schema: Record<string, unknown>): string {
  const type = schema.type;
  if (typeof type === 'string' && type) return type;
  if (Array.isArray(type)) {
    return String(type.find((candidate) => candidate !== 'null') ?? 'any');
  }
  if (schema.properties || schema.additionalProperties) return 'object';
  if (schema.items) return 'array';
  return 'any';
}

function expressionType(expression: string): string {
  const normalized = expression.trim();
  if (!normalized) return 'any';
  if (/^(toNumber|round)\s*\(/.test(normalized)) return 'number';
  if (/^(true|false)$/.test(normalized) || /(?:===?|!==?|>=?|<=?)\s*/.test(normalized)) return 'boolean';
  if (/^\[/.test(normalized) || /,\s*\[\]\s*\)/.test(normalized)) return 'array';
  if (/^["']/.test(normalized) || /,\s*["']/.test(normalized)) return 'string';
  return 'any';
}

function decisionOutputColumns(config: Record<string, unknown> | undefined): string[] {
  const rawColumns = Array.isArray(config?.outputColumns) ? config.outputColumns : [];
  return rawColumns.map((column, index) => {
    if (typeof column === 'string') return column;
    const record = recordValue(column);
    return String(record.name || record.key || record.id || `output${index + 1}`);
  });
}

function typeFromOutputSignature(signature: unknown, field: string): string {
  if (typeof signature !== 'string') return 'any';
  const match = new RegExp(`${escapeRegex(field)}\\s*:\\s*([A-Za-z]+)`, 'i').exec(signature);
  const type = match?.[1]?.toLowerCase();
  if (type === 'integer' || type === 'long' || type === 'double' || type === 'float') return 'number';
  if (type === 'string' || type === 'boolean' || type === 'array' || type === 'object') return type;
  return 'any';
}

function topLevelOutputField(path: string): string {
  return normalizedPath(path).replace(/^output\./, '').split('.')[0] || '';
}

function schemaForType(type: string): Record<string, unknown> {
  return type === 'any' ? {} : { type };
}

function valueType(value: unknown): string {
  if (Array.isArray(value)) return 'array';
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'string') return 'string';
  if (typeof value === 'object') return 'object';
  return 'any';
}

function incompatibleTypes(left: string, right: string): boolean {
  return left !== 'any' && right !== 'any' && left !== 'null' && right !== 'null' && left !== right;
}

function displayConstant(value: unknown): string {
  const text = JSON.stringify(value);
  return text && text.length > 48 ? `${text.slice(0, 45)}...` : text ?? String(value);
}

function recordValue(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {};
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String) : [];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function emptyProjection(nodeId: string): EffectiveContractProjection {
  return {
    target: { nodeId, operatorRef: '' },
    declaredInputs: [],
    declaredOutputs: [],
    inferredOutputs: [],
    observedOutputs: [],
    activeBindings: [],
    conflicts: [],
    confidence: 'OPAQUE',
    provenance: {
      declared: 'Target node not found',
      inferred: [],
      bound: [],
      observed: 'No run observation',
    },
  };
}
