import type { ExactTargetRef, EnterpriseScope, ScenarioDraft, ScenarioDraftSet } from '../contract-scenario/domain';
import { canonicalJson, sha256FingerprintSync } from '../contract-scenario/fingerprint';

/** Supported output projections for a decision table. */
export type DecisionOutputKind = 'scalar' | 'object' | 'plan' | 'dispatch';

/** A decision-table column and the values that are safe to use as authoring representatives. */
export interface DecisionColumn {
  name: string;
  type: 'integer' | 'number' | 'string' | 'enum' | 'boolean';
  values?: unknown[];
  authorSamples?: unknown[];
}

/** The supported, deliberately small predicate grammar. */
export type DecisionPredicate =
  | { kind: 'comparison'; column: string; operator: '<' | '<=' | '>' | '>=' | '==' | '!='; value: number }
  | { kind: 'range'; column: string; lower: number; lowerInclusive: boolean; upper: number; upperInclusive: boolean }
  | { kind: 'in'; column: string; values: unknown[] }
  | { kind: 'otherwise' }
  | { kind: 'opaque'; column: string; expression: string };

/** A parsed predicate with threshold values retained for representative generation. */
export type ParsedPredicate = DecisionPredicate & { values: unknown[] };

/** One rule in a decision table. Conditions in a rule are ANDed. */
export interface DecisionRule {
  id: string;
  conditions?: Record<string, string | DecisionPredicate>;
  otherwise?: boolean;
  output: unknown;
}

/** Minimal decision-table shape accepted by the enumerator. */
export interface DecisionTable {
  tableId?: string;
  hitPolicy: 'first' | 'unique';
  columns: DecisionColumn[];
  rules: DecisionRule[];
  outputKind?: DecisionOutputKind;
}

/** Result of evaluating a table against one concrete input. */
export interface DecisionEvaluation {
  status: 'MATCHED' | 'NO_MATCH' | 'AMBIGUOUS' | 'OPAQUE';
  ruleId?: string;
  output?: unknown;
  matches: string[];
}

/** Deterministic bounded product and its honesty metadata. */
export interface CartesianResult {
  combinations: Array<Record<string, unknown>>;
  truncated: boolean;
  strategy: 'EXHAUSTIVE' | 'STRATIFIED';
  totalCombinations: number;
  emittedCombinations: number;
}

/** Options controlling the generated ScenarioDraft set. */
export interface EnumerationOptions {
  mode: 'per-rule' | 'combinatorial';
  cap: number;
  colToInputPath?: Record<string, string>;
  target?: ExactTargetRef;
  scope?: EnterpriseScope;
  contractFingerprint?: string;
  owner?: string;
  classification?: ScenarioDraftSet['metadata']['classification'];
}

/** A generated set plus explicit boundedness/provenance information. */
export interface EnumerationResult {
  scenarios: ScenarioDraft[];
  draftSet: ScenarioDraftSet;
  metadata: {
    mode: EnumerationOptions['mode'];
    cap: number;
    truncated: boolean;
    exhaustive: boolean;
    strategy: CartesianResult['strategy'];
    provenance: 'DECISION_TABLE_ENUMERATION';
    sourceFingerprint: string;
    opaqueColumns: string[];
    diagnostics: string[];
  };
}

/** Raised when an opaque predicate has no author samples from which to make a bounded case. */
export class OpaquePredicateRequiresAuthorSamplesError extends Error {
  /** Creates a stable, actionable authoring error. */
  public constructor(column: string) {
    super(`Opaque decision predicate for '${column}' requires author samples.`);
    this.name = 'OpaquePredicateRequiresAuthorSamplesError';
  }
}

/** Parses one supported predicate; unsupported expressions remain explicitly opaque. */
export function parsePredicate(expression: string, column: string): ParsedPredicate {
  const text = expression.trim();
  if (text.toLowerCase() === 'otherwise') return { kind: 'otherwise', values: [] };
  const escaped = escapeRegExp(column);
  const number = '(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))';
  const comparison = new RegExp(`^${escaped}\\s*(<=|>=|==|!=|<|>)\\s*${number}$`).exec(text)
    ?? new RegExp(`^${number}\\s*(<=|>=|==|!=|<|>)\\s*${escaped}$`).exec(text);
  if (comparison) {
    const leftColumn = text.trimStart().startsWith(column);
    const operator = (leftColumn ? comparison[1] : invertOperator(comparison[2] ?? comparison[1])) as '<' | '<=' | '>' | '>=' | '==' | '!=';
    const value = Number(leftColumn ? comparison[2] : comparison[1]);
    return { kind: 'comparison', column, operator, value, values: [value] };
  }
  const range = new RegExp(`^${number}\\s*(<=|<)\\s*${escaped}\\s*(<=|<)\\s*${number}$`).exec(text);
  if (range) {
    const lower = Number(range[1]);
    const upper = Number(range[4]);
    return { kind: 'range', column, lower, lowerInclusive: range[2] === '<=', upper, upperInclusive: range[3] === '<=', values: [lower, upper] };
  }
  const inMatch = new RegExp(`^${escaped}\\s+in\\s+\\[(.*)\\]$`, 'i').exec(text);
  if (inMatch) {
    const values = parseList(inMatch[1] ?? '');
    return { kind: 'in', column, values };
  }
  return { kind: 'opaque', column, expression: text, values: [] };
}

/** Builds a stable representative set, using integer epsilon 1 around numeric thresholds. */
export function representativeValues(column: DecisionColumn, predicates: ParsedPredicate[]): unknown[] {
  const relevant = predicates.filter((predicate) => predicate.kind !== 'otherwise' && predicate.column === column.name);
  const supplied = [...(column.values ?? []), ...(column.authorSamples ?? [])];
  if (relevant.some((predicate) => predicate.kind === 'opaque') && supplied.length === 0) {
    throw new OpaquePredicateRequiresAuthorSamplesError(column.name);
  }
  const candidates: unknown[] = [...supplied];
  for (const predicate of relevant) {
    if (predicate.kind === 'comparison') candidates.push(predicate.value);
    if (predicate.kind === 'range') candidates.push(predicate.lower, predicate.upper);
    if (predicate.kind === 'in') candidates.push(...predicate.values);
  }
  if (column.type === 'integer') {
    for (const value of [...candidates]) {
      if (typeof value === 'number' && Number.isFinite(value)) candidates.push(value - 1, value + 1);
    }
  }
  const unique = dedupe(candidates);
  if (unique.length === 0 && (column.type === 'integer' || column.type === 'number')) return [0];
  if (unique.length === 0 && column.type === 'string') return [''];
  if (column.type === 'number' || column.type === 'integer') {
    return unique.filter((value): value is number => typeof value === 'number' && Number.isFinite(value)).sort((a, b) => a - b);
  }
  if (column.type === 'boolean') return dedupe([false, true, ...unique.filter((value): value is boolean => typeof value === 'boolean')]);
  return unique;
}

/** Evaluates the table without executing any operator or dispatch target. */
export function evalDecisionTable(table: DecisionTable, input: Record<string, unknown>): DecisionEvaluation {
  const matches: DecisionRule[] = [];
  let opaque = false;
  for (const rule of table.rules) {
    if (rule.otherwise) continue;
    const result = ruleMatches(rule, input, false);
    opaque ||= result.opaque;
    if (result.matches) matches.push(rule);
  }
  if (table.hitPolicy === 'unique' && matches.length > 1) return { status: 'AMBIGUOUS', matches: matches.map((rule) => rule.id) };
  const selected = table.hitPolicy === 'first' ? matches[0] : matches.length === 1 ? matches[0] : undefined;
  if (selected) return { status: 'MATCHED', ruleId: selected.id, output: selected.output, matches: matches.map((rule) => rule.id) };
  const otherwise = table.rules.find((rule) => rule.otherwise);
  if (otherwise && !opaque) return { status: 'MATCHED', ruleId: otherwise.id, output: otherwise.output, matches: [otherwise.id] };
  return { status: opaque ? 'OPAQUE' : 'NO_MATCH', matches: [] };
}

/** Picks one deterministic representative input that reaches a rule. */
export function pickCombo(table: DecisionTable, ruleId: string, cap = 500): Record<string, unknown> | null {
  const rule = table.rules.find((candidate) => candidate.id === ruleId);
  if (!rule) return null;
  const domains = domainsFor(table);
  const product = boundedCartesian(domains, Math.max(1, cap));
  for (const combo of product.combinations) {
    if (rule.otherwise) {
      const evalResult = evalDecisionTable(table, combo);
      if (evalResult.status === 'MATCHED' && evalResult.ruleId === rule.id) return combo;
    } else if (ruleMatches(rule, combo, true).matches) {
      const preceding = table.rules.slice(0, table.rules.indexOf(rule));
      const intercepted = preceding.some((higher) => !higher.otherwise && ruleMatches(higher, combo, true).matches);
      if (!intercepted) return combo;
    }
  }
  return null;
}

/** Produces a deterministic, bounded cartesian product with explicit truncation metadata. */
export function boundedCartesian(valuesByColumn: Record<string, unknown[]>, cap: number): CartesianResult {
  const boundedCap = Math.max(1, Math.floor(cap));
  const keys = Object.keys(valuesByColumn).sort();
  const values = keys.map((key) => dedupe(valuesByColumn[key] ?? []));
  if (values.some((domain) => domain.length === 0)) return { combinations: [], truncated: false, strategy: 'EXHAUSTIVE', totalCombinations: 0, emittedCombinations: 0 };
  const total = values.reduce((count, domain) => Math.min(Number.MAX_SAFE_INTEGER, count * domain.length), 1);
  const count = Math.min(total, boundedCap);
  const combinations: Array<Record<string, unknown>> = [];
  for (let ordinal = 0; ordinal < count; ordinal += 1) {
    const index = total <= boundedCap || count === 1 ? ordinal : Math.floor((ordinal * (total - 1)) / (count - 1));
    let remainder = index;
    const combination: Record<string, unknown> = {};
    for (let dimension = values.length - 1; dimension >= 0; dimension -= 1) {
      const domain = values[dimension] ?? [];
      const valueIndex = remainder % domain.length;
      remainder = Math.floor(remainder / domain.length);
      const key = keys[dimension];
      if (key !== undefined) combination[key] = domain[valueIndex];
    }
    combinations.push(combination);
  }
  return { combinations, truncated: total > boundedCap, strategy: total > boundedCap ? 'STRATIFIED' : 'EXHAUSTIVE', totalCombinations: total, emittedCombinations: combinations.length };
}

/** Enumerates per-rule or combinatorial scenarios and preserves the existing ScenarioDraft wire shape. */
export function enumerateDecisionTableScenarios(table: DecisionTable, options: EnumerationOptions): EnumerationResult {
  const cap = Math.max(1, Math.floor(options.cap));
  const sourceFingerprint = sha256FingerprintSync(table);
  const parsed = allPredicates(table);
  const opaqueColumns = [...new Set(parsed.filter((entry) => entry.predicate.kind === 'opaque').map((entry) => entry.column))].sort();
  const domains = domainsFor(table);
  const product = boundedCartesian(domains, cap);
  const candidates: Array<{ input: Record<string, unknown>; rule: DecisionRule }> = [];
  if (options.mode === 'per-rule') {
    for (const rule of table.rules) {
      const input = pickCombo(table, rule.id, cap);
      if (input) candidates.push({ input, rule });
    }
  } else {
    for (const input of product.combinations) {
      const evaluation = evalDecisionTable(table, input);
      const rule = table.rules.find((candidate) => candidate.id === evaluation.ruleId);
      if (rule) candidates.push({ input, rule });
    }
  }
  const scenarios = dedupeScenarios(candidates.map(({ input, rule }, index) => scenarioFrom(table, sourceFingerprint, input, rule, options, index)));
  const target = options.target ?? { kind: 'GRAPH', id: table.tableId ?? 'decision-table', revision: 1, fingerprint: sourceFingerprint };
  const draftSet: ScenarioDraftSet = {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: `${table.tableId ?? 'decision-table'}:${sourceFingerprint.slice(-16)}`,
    revision: 0,
    scope: options.scope ?? { tenantId: '', organizationId: '', projectId: '', environment: '', region: '' },
    target: { ...target },
    contractFingerprint: options.contractFingerprint ?? sourceFingerprint,
    scenarios,
    metadata: { owner: options.owner ?? '', classification: options.classification ?? 'INTERNAL', createdAt: null, updatedAt: null, provenance: { sourceFingerprint, provenance: 'DECISION_TABLE_ENUMERATION', mode: options.mode, cap, exhaustive: opaqueColumns.length === 0 && !product.truncated } },
  };
  return { scenarios, draftSet, metadata: { mode: options.mode, cap, truncated: product.truncated, strategy: product.strategy, exhaustive: opaqueColumns.length === 0 && !product.truncated, provenance: 'DECISION_TABLE_ENUMERATION', sourceFingerprint, opaqueColumns, diagnostics: opaqueColumns.length ? ['Opaque predicates use author samples and are not exhaustive.'] : [] } };
}

function domainsFor(table: DecisionTable): Record<string, unknown[]> {
  const parsed = allPredicates(table);
  return Object.fromEntries(table.columns.map((column) => [column.name, representativeValues(column, parsed.filter((entry) => entry.column === column.name).map((entry) => entry.predicate))]));
}

function allPredicates(table: DecisionTable): Array<{ column: string; predicate: ParsedPredicate }> {
  const result: Array<{ column: string; predicate: ParsedPredicate }> = [];
  for (const rule of table.rules) {
    for (const [column, condition] of Object.entries(rule.conditions ?? {})) {
      if (typeof condition !== 'string') result.push({ column, predicate: condition.kind === 'opaque' || condition.kind === 'otherwise' ? { ...condition, values: condition.kind === 'otherwise' ? [] : [] } : { ...condition, values: condition.kind === 'range' ? [condition.lower, condition.upper] : condition.kind === 'comparison' ? [condition.value] : condition.values } });
      else for (const expression of splitConjunction(condition)) result.push({ column, predicate: parsePredicate(expression, column) });
    }
  }
  return result;
}

function ruleMatches(rule: DecisionRule, input: Record<string, unknown>, allowOpaque: boolean): { matches: boolean; opaque: boolean } {
  let opaque = false;
  for (const [column, condition] of Object.entries(rule.conditions ?? {})) {
    const predicates = typeof condition === 'string' ? splitConjunction(condition).map((part) => parsePredicate(part, column)) : [condition as ParsedPredicate];
    for (const predicate of predicates) {
      if (predicate.kind === 'opaque') { opaque = true; if (!allowOpaque) return { matches: false, opaque: true }; continue; }
      if (predicate.kind !== 'otherwise' && !evaluatePredicate(predicate, input[column])) return { matches: false, opaque };
    }
  }
  return { matches: true, opaque };
}

function evaluatePredicate(predicate: DecisionPredicate, value: unknown): boolean {
  if (predicate.kind === 'comparison' && typeof value === 'number') return ({ '<': value < predicate.value, '<=': value <= predicate.value, '>': value > predicate.value, '>=': value >= predicate.value, '==': value === predicate.value, '!=': value !== predicate.value })[predicate.operator];
  if (predicate.kind === 'range' && typeof value === 'number') return (predicate.lowerInclusive ? value >= predicate.lower : value > predicate.lower) && (predicate.upperInclusive ? value <= predicate.upper : value < predicate.upper);
  if (predicate.kind === 'in') return predicate.values.some((candidate) => canonicalJson(candidate) === canonicalJson(value));
  if (predicate.kind === 'otherwise') return true;
  return false;
}

function scenarioFrom(table: DecisionTable, sourceFingerprint: string, input: Record<string, unknown>, rule: DecisionRule, options: EnumerationOptions, index: number): ScenarioDraft {
  const mappedInput = mapInput(input, options.colToInputPath ?? {});
  const output = normalizeOutput(rule.output, table.outputKind ?? 'object');
  const valueFingerprint = sha256FingerprintSync({ input: mappedInput, output, rule: rule.id });
  return { scenarioId: `decision:${sourceFingerprint.slice(-12)}:${valueFingerprint.slice(-12)}`, name: `Decision ${rule.id}`, description: `Generated from decision-table rule '${rule.id}'.`, caseType: 'PROPERTY', tags: ['DECISION_TABLE_ENUMERATION', `rule:${rule.id}`, `output:${table.outputKind ?? 'object'}`], given: { input: mappedInput, provenance: 'GENERATED' }, dependencies: [], then: { assertions: [{ assertionId: `decision-output:${index}:${rule.id}`, scope: 'OUTPUT_PATH', nodeId: '', fromNodeId: '', toNodeId: '', path: '', operator: 'EQUALS', expected: output }] } };
}

function normalizeOutput(output: unknown, kind: DecisionOutputKind): unknown {
  if (kind === 'plan') return isRecord(output) && Array.isArray(output.steps) ? { ...output } : { action: 'return', steps: [], reason: String(output ?? '') };
  if (kind === 'dispatch') return isRecord(output) ? { ...output, dispatchMode: 'MODELED_ONLY' } : { targetRef: output, dispatchMode: 'MODELED_ONLY' };
  return output;
}

function mapInput(input: Record<string, unknown>, paths: Record<string, string>): Record<string, unknown> {
  const mapped: Record<string, unknown> = {};
  for (const [column, value] of Object.entries(input)) setPath(mapped, paths[column] ?? column, value);
  return mapped;
}

function setPath(target: Record<string, unknown>, path: string, value: unknown): void {
  const parts = path.replace(/^\//, '').split(/[./]/).filter(Boolean);
  let cursor = target;
  for (const part of parts.slice(0, -1)) cursor = (cursor[part] ??= {}) as Record<string, unknown>;
  if (parts.length) cursor[parts[parts.length - 1] as string] = value;
}

function splitConjunction(value: string): string[] { return value.split(/\s*&&\s*/).map((part) => part.trim()).filter(Boolean); }
function invertOperator(operator: string): string { return ({ '<': '>', '<=': '>=', '>': '<', '>=': '<=', '==': '==', '!=': '!=' })[operator] ?? operator; }
function escapeRegExp(value: string): string { return value.replace(/[.*+?^${}()|[\[\]\\]/g, '\\$&'); }
function parseList(value: string): unknown[] { try { return JSON.parse(`[${value.replace(/'/g, '\"')}]`) as unknown[]; } catch { return value.split(',').map((entry) => entry.trim()).filter(Boolean); } }
function dedupe(values: unknown[]): unknown[] { const seen = new Set<string>(); return values.filter((value) => { const key = canonicalJson(value); if (seen.has(key)) return false; seen.add(key); return true; }); }
function dedupeScenarios(scenarios: ScenarioDraft[]): ScenarioDraft[] { const unique = new Map<string, ScenarioDraft>(); for (const scenario of scenarios) unique.set(canonicalJson({ input: scenario.given.input, expected: scenario.then.assertions[0]?.expected }), scenario); return [...unique.values()].sort((left, right) => left.scenarioId.localeCompare(right.scenarioId)); }
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value); }
