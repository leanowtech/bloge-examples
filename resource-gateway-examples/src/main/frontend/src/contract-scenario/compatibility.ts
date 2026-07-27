import type {
  ContractCompatibilityMigration,
  ContractCompatibilityReport,
  ContractDraft,
  ScenarioDraft,
  ScenarioDraftSet,
} from './domain';

export interface CompatibilityMigrationApplication {
  draftSet: ScenarioDraftSet;
  appliedActionIds: string[];
  blockedActionIds: string[];
}

/**
 * Applies only migration edits that do not invent values and deliberately leaves the draft stale.
 *
 * The author must inspect the result and explicitly resolve compatibility before the normal
 * server-side validation and save gates will accept it.
 */
export function applyAutomaticCompatibilityMigrations(
  draftSet: ScenarioDraftSet,
  report: ContractCompatibilityReport,
  contract: ContractDraft,
): CompatibilityMigrationApplication {
  let scenarios = draftSet.scenarios;
  const appliedActionIds: string[] = [];
  const blockedActionIds: string[] = [];
  for (const action of report.migrations.filter((candidate) => candidate.automatic)) {
    const result = applyAction(scenarios, action, contract);
    if (!result.applied) {
      blockedActionIds.push(action.actionId);
      continue;
    }
    scenarios = result.scenarios;
    appliedActionIds.push(action.actionId);
  }
  if (appliedActionIds.length === 0) {
    return { draftSet, appliedActionIds, blockedActionIds };
  }
  return {
    draftSet: {
      ...draftSet,
      scenarios,
      metadata: {
        ...draftSet.metadata,
        updatedAt: new Date().toISOString(),
        provenance: {
          ...draftSet.metadata.provenance,
          stagedCompatibilityMigration: {
            reportFingerprint: report.reportFingerprint,
            sourceRevision: report.scenarioRevision,
            actionIds: appliedActionIds,
          },
        },
      },
    },
    appliedActionIds,
    blockedActionIds,
  };
}

/**
 * Records an explicit compatibility review and moves the mutable draft to the current coordinate.
 *
 * This does not claim a test pass. Saving the returned draft still invokes authoritative current
 * Contract validation, and prior Scenario revisions plus their baselines remain immutable.
 */
export function rebaseAfterCompatibilityReview(
  draftSet: ScenarioDraftSet,
  report: ContractCompatibilityReport,
  currentTarget: ContractDraft['target'],
  currentContractFingerprint: string,
): ScenarioDraftSet {
  return {
    ...draftSet,
    target: { ...currentTarget },
    contractFingerprint: currentContractFingerprint,
    metadata: {
      ...draftSet.metadata,
      updatedAt: new Date().toISOString(),
      provenance: {
        ...draftSet.metadata.provenance,
        compatibilityResolution: {
          reportFingerprint: report.reportFingerprint,
          sourceRevision: report.scenarioRevision,
          baselineContractFingerprint: report.baselineContractFingerprint,
          currentContractFingerprint: report.currentContractFingerprint,
          classification: report.classification,
          findingIds: report.findings.map((finding) => finding.findingId),
          reviewedAt: new Date().toISOString(),
        },
      },
    },
  };
}

function applyAction(
  scenarios: ScenarioDraft[],
  action: ContractCompatibilityMigration,
  contract: ContractDraft,
): { applied: boolean; scenarios: ScenarioDraft[] } {
  if (action.fromPath.includes('/*') || action.toPath.includes('/*')) {
    return { applied: false, scenarios };
  }
  const targeted = new Set(action.scenarioIds);
  if (action.kind === 'ADD_DEFAULT') {
    const fieldSchema = schemaAtPath(contract.inputSchema.schema, action.toPath);
    if (!fieldSchema || !Object.prototype.hasOwnProperty.call(fieldSchema, 'default')) {
      return { applied: false, scenarios };
    }
    return applyGivenChange(scenarios, targeted, (input) => (
      hasPointer(input, action.toPath)
        ? { ok: true, value: input }
        : setPointer(input, action.toPath, fieldSchema.default)
    ));
  }
  if (action.kind === 'REMOVE_INPUT') {
    return applyGivenChange(scenarios, targeted, (input) => (
      hasPointer(input, action.fromPath || action.toPath)
        ? removePointer(input, action.fromPath || action.toPath)
        : { ok: true, value: input }
    ));
  }
  if (action.kind === 'RENAME_INPUT') {
    return applyGivenChange(scenarios, targeted, (input) => {
      const source = valueAtPointer(input, action.fromPath);
      if (!source.found) return { ok: true, value: input };
      const destination = valueAtPointer(input, action.toPath);
      if (destination.found && !sameJsonValue(destination.value, source.value)) {
        return { ok: false, value: input };
      }
      const added = setPointer(input, action.toPath, source.value);
      return added.ok ? removePointer(added.value, action.fromPath) : added;
    });
  }
  if (action.kind === 'REBIND_OUTPUT_ASSERTION') {
    return {
      applied: true,
      scenarios: scenarios.map((scenario) => (
        targeted.has(scenario.scenarioId)
          ? {
              ...scenario,
              then: {
                assertions: scenario.then.assertions.map((assertion) => (
                  assertion.scope === 'OUTPUT_PATH'
                    ? { ...assertion, path: replacePathPrefix(assertion.path, action.fromPath, action.toPath) }
                    : assertion
                )),
              },
            }
          : scenario
      )),
    };
  }
  return { applied: false, scenarios };
}

function applyGivenChange(
  scenarios: ScenarioDraft[],
  targeted: Set<string>,
  change: (input: unknown) => { ok: boolean; value: unknown },
): { applied: boolean; scenarios: ScenarioDraft[] } {
  const changed = new Map<string, unknown>();
  for (const scenario of scenarios) {
    if (!targeted.has(scenario.scenarioId)) continue;
    const result = change(scenario.given.input);
    if (!result.ok) return { applied: false, scenarios };
    changed.set(scenario.scenarioId, result.value);
  }
  return {
    applied: true,
    scenarios: scenarios.map((scenario) => (
      changed.has(scenario.scenarioId)
        ? {
            ...scenario,
            given: {
              input: changed.get(scenario.scenarioId),
              provenance: 'MIGRATED',
            },
          }
        : scenario
    )),
  };
}

function schemaAtPath(
  root: Record<string, unknown>,
  pointer: string,
): Record<string, unknown> | null {
  let schema: Record<string, unknown> = root;
  for (const token of pointerTokens(pointer)) {
    const properties = isRecord(schema.properties) ? schema.properties : null;
    const child = properties?.[token];
    if (!isRecord(child)) return null;
    schema = child;
  }
  return schema;
}

function setPointer(
  root: unknown,
  pointer: string,
  value: unknown,
): { ok: boolean; value: unknown } {
  const tokens = pointerTokens(pointer);
  if (tokens.length === 0) return { ok: true, value };
  if (!isRecord(root)) return { ok: false, value: root };
  const copy: Record<string, unknown> = { ...root };
  let cursor = copy;
  for (const token of tokens.slice(0, -1)) {
    const current = cursor[token];
    if (current !== undefined && !isRecord(current)) {
      return { ok: false, value: root };
    }
    const next = isRecord(current) ? { ...current } : {};
    cursor[token] = next;
    cursor = next;
  }
  cursor[tokens[tokens.length - 1] ?? ''] = cloneJson(value);
  return { ok: true, value: copy };
}

function removePointer(
  root: unknown,
  pointer: string,
): { ok: boolean; value: unknown } {
  const tokens = pointerTokens(pointer);
  if (tokens.length === 0 || !isRecord(root)) return { ok: false, value: root };
  const copy: Record<string, unknown> = { ...root };
  let cursor = copy;
  for (const token of tokens.slice(0, -1)) {
    const current = cursor[token];
    if (!isRecord(current)) return { ok: false, value: root };
    const next = { ...current };
    cursor[token] = next;
    cursor = next;
  }
  delete cursor[tokens[tokens.length - 1] ?? ''];
  return { ok: true, value: copy };
}

function valueAtPointer(root: unknown, pointer: string): { found: boolean; value: unknown } {
  let current = root;
  for (const token of pointerTokens(pointer)) {
    if (!isRecord(current) || !Object.prototype.hasOwnProperty.call(current, token)) {
      return { found: false, value: undefined };
    }
    current = current[token];
  }
  return { found: true, value: current };
}

function hasPointer(root: unknown, pointer: string): boolean {
  return valueAtPointer(root, pointer).found;
}

function pointerTokens(pointer: string): string[] {
  if (!pointer || pointer === '/') return pointer === '/' ? [''] : [];
  if (!pointer.startsWith('/')) return [];
  return pointer.slice(1).split('/').map((token) => token
    .replace(/~1/g, '/')
    .replace(/~0/g, '~'));
}

function replacePathPrefix(path: string, from: string, to: string): string {
  if (path === from) return to;
  return path.startsWith(`${from}/`) ? `${to}${path.slice(from.length)}` : path;
}

function cloneJson(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(cloneJson);
  if (isRecord(value)) {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, cloneJson(item)]));
  }
  return value;
}

function sameJsonValue(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
