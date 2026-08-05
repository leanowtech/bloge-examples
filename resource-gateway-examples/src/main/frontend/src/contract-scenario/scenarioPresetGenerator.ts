import type { GraphDraft, SchemaEnvelope } from '../types';
import type { ContractDraft, ScenarioCaseType, ScenarioDraft } from './domain';
import {
  controllableDependencies,
  type ScenarioNodeOption,
} from './scenarioAuthoring';

export interface ScenarioPresetInput {
  sequence: number;
  caseType: ScenarioCaseType;
  graphDraft: GraphDraft;
  contract: ContractDraft;
  nodes: ScenarioNodeOption[];
}

/** Generates reviewable business-shaped data while keeping inferred oracles visibly provisional. */
export function generateScenarioPreset(input: ScenarioPresetInput): ScenarioDraft {
  const intent = presetIntent(input.caseType);
  const expected = meaningfulValue(input.contract.outputSchema.schema, 'result', intent);
  const needsOracle = input.caseType === 'NEGATIVE' || input.caseType === 'PROPERTY';
  return {
    scenarioId: `scenario-${input.sequence}`,
    name: `${presetLabel(input.caseType)} ${input.sequence}`,
    description: presetDescription(input.caseType),
    caseType: input.caseType,
    tags: ['generated-preset', input.caseType.toLocaleLowerCase(), needsOracle ? 'needs-oracle' : 'review-oracle'],
    given: {
      input: meaningfulValue(input.contract.inputSchema.schema, 'input', intent),
      provenance: 'GENERATED',
    },
    dependencies: controllableDependencies(input.nodes, input.graphDraft.nodeFixtures ?? {}),
    then: {
      assertions: needsOracle ? [] : [{
        assertionId: `scenario-${input.sequence}-expected-output`,
        scope: 'OUTPUT_PATH',
        nodeId: '',
        fromNodeId: '',
        toNodeId: '',
        path: '',
        operator: 'EQUALS',
        expected,
      }],
    },
  };
}

export function generateScenarioPresetSuite(
  base: Omit<ScenarioPresetInput, 'sequence' | 'caseType'>,
  firstSequence = 1,
): ScenarioDraft[] {
  return (['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION'] as const).map((caseType, index) => (
    generateScenarioPreset({ ...base, caseType, sequence: firstSequence + index })
  ));
}

type PresetIntent = 'TYPICAL' | 'ADVERSE' | 'BOUNDARY';

function meaningfulValue(schema: unknown, fieldName: string, intent: PresetIntent): unknown {
  const shape = record(schema);
  const examples = Array.isArray(shape.examples) ? shape.examples : [];
  if (shape.const !== undefined) return clone(shape.const);
  if (shape.default !== undefined) return clone(shape.default);
  if (examples.length > 0) return clone(examples[intent === 'BOUNDARY' ? examples.length - 1 : 0]);
  if (Array.isArray(shape.enum) && shape.enum.length > 0) {
    return clone(shape.enum[intent === 'ADVERSE' ? shape.enum.length - 1 : 0]);
  }
  const type = schemaType(shape);
  switch (type) {
    case 'object': return Object.fromEntries(Object.entries(record(shape.properties)).map(([name, child]) => (
      [name, meaningfulValue(child, name, intent)]
    )));
    case 'array': {
      const count = Math.max(1, numberValue(shape.minItems, 1));
      return Array.from({ length: Math.min(count, 3) }, (_, index) => (
        meaningfulValue(shape.items, `${fieldName}${index + 1}`, intent)
      ));
    }
    case 'integer': return Math.round(numberForSchema(shape, fieldName, intent));
    case 'number': return numberForSchema(shape, fieldName, intent);
    case 'boolean': return intent !== 'ADVERSE';
    case 'null': return null;
    default: return stringForSchema(shape, fieldName, intent);
  }
}

function numberForSchema(
  schema: Record<string, unknown>,
  fieldName: string,
  intent: PresetIntent,
): number {
  const minimum = numberValue(schema.minimum, numberValue(schema.exclusiveMinimum, NaN));
  const maximum = numberValue(schema.maximum, numberValue(schema.exclusiveMaximum, NaN));
  if (intent === 'BOUNDARY') {
    if (Number.isFinite(maximum)) return maximum;
    if (Number.isFinite(minimum)) return minimum;
  }
  if (intent === 'ADVERSE' && Number.isFinite(minimum)) return minimum;
  const normalized = fieldName.toLocaleLowerCase();
  if (normalized.includes('age')) return intent === 'ADVERSE' ? 18 : 35;
  if (/(amount|price|balance|income|salary|limit)/.test(normalized)) return intent === 'ADVERSE' ? 0 : 1200;
  if (/(score|rating)/.test(normalized)) return intent === 'ADVERSE' ? 300 : 720;
  if (/(count|quantity|size)/.test(normalized)) return intent === 'ADVERSE' ? 0 : 3;
  if (Number.isFinite(minimum) && Number.isFinite(maximum)) return (minimum + maximum) / 2;
  if (Number.isFinite(minimum)) return minimum + 1;
  return intent === 'ADVERSE' ? 0 : 1;
}

function stringForSchema(
  schema: Record<string, unknown>,
  fieldName: string,
  intent: PresetIntent,
): string {
  const normalized = fieldName.toLocaleLowerCase();
  const format = String(schema.format ?? '').toLocaleLowerCase();
  if (format === 'email' || normalized.includes('email')) {
    return intent === 'ADVERSE' ? 'blocked@example.test' : 'alex.chen@example.test';
  }
  if (format === 'date') return intent === 'BOUNDARY' ? '2000-01-01' : '2026-08-05';
  if (format === 'date-time') return intent === 'BOUNDARY'
    ? '2000-01-01T00:00:00Z'
    : '2026-08-05T12:00:00Z';
  if (format === 'uuid') return '00000000-0000-4000-8000-000000001001';
  if (/(^|_)(id|key)$/.test(normalized) || normalized.endsWith('id')) {
    const entity = normalized.replace(/[_-]?id$/, '') || 'item';
    return `${entity}-${intent === 'ADVERSE' ? 'blocked' : '1001'}`;
  }
  if (normalized.includes('country')) return intent === 'ADVERSE' ? 'ZZ' : 'SG';
  if (normalized.includes('currency')) return 'SGD';
  if (normalized.includes('name')) return intent === 'ADVERSE' ? 'Review required' : 'Alex Chen';
  if (normalized.includes('status')) return intent === 'ADVERSE' ? 'REJECTED' : 'ACTIVE';
  const minLength = Math.max(1, numberValue(schema.minLength, 1));
  const maxLength = Math.max(minLength, numberValue(schema.maxLength, 64));
  if (intent === 'BOUNDARY') return 'x'.repeat(Math.min(maxLength, 32));
  const value = intent === 'ADVERSE' ? `blocked-${fieldName}` : `sample-${fieldName}`;
  return value.slice(0, maxLength).padEnd(Math.min(minLength, maxLength), 'x');
}

function schemaType(schema: Record<string, unknown>): string {
  if (typeof schema.type === 'string') return schema.type;
  if (Array.isArray(schema.type)) {
    return String(schema.type.find((type) => type !== 'null') ?? schema.type[0] ?? 'string');
  }
  if (schema.properties) return 'object';
  if (schema.items) return 'array';
  return 'string';
}

function presetIntent(caseType: ScenarioCaseType): PresetIntent {
  if (caseType === 'NEGATIVE') return 'ADVERSE';
  if (caseType === 'BOUNDARY') return 'BOUNDARY';
  return 'TYPICAL';
}

function presetLabel(caseType: ScenarioCaseType): string {
  switch (caseType) {
    case 'GOLDEN': return 'Happy path';
    case 'NEGATIVE': return 'Negative path';
    case 'BOUNDARY': return 'Boundary value';
    case 'REGRESSION': return 'Regression guard';
    case 'PROPERTY': return 'Property check';
  }
}

function presetDescription(caseType: ScenarioCaseType): string {
  switch (caseType) {
    case 'GOLDEN': return 'Typical contract-shaped business input with a provisional expected outcome.';
    case 'NEGATIVE': return 'Adverse but contract-shaped input; author the expected error oracle before promotion.';
    case 'BOUNDARY': return 'Values generated from declared schema boundaries for review.';
    case 'REGRESSION': return 'Typical input and provisional output intended to become a retained regression guard.';
    case 'PROPERTY': return 'Generated input awaiting a property oracle.';
  }
}

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function numberValue(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function clone<T>(value: T): T {
  return value === undefined ? value : structuredClone(value);
}

export function generateMeaningfulFixture(
  envelope: SchemaEnvelope | undefined,
  caseType: ScenarioCaseType = 'GOLDEN',
): unknown {
  return meaningfulValue(envelope?.schema, 'input', presetIntent(caseType));
}
