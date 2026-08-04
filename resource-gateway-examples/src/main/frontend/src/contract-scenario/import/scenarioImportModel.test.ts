import { describe, expect, it } from 'vitest';

import { tableDrivenScenarioBaseline } from '../tableDrivenTestingBaseline';
import {
  createScenarioMaterializationPlan,
  deriveScenarioImportTargets,
  diffScenarioImport,
  materializeScenarioImport,
  parseScenarioImport,
  ScenarioImportError,
  suggestScenarioImportBindings,
  type ScenarioColumnBinding,
} from './scenarioImportModel';

describe('scenario import model', () => {
  it('keeps the cross-runtime source fingerprint golden', async () => {
    const preview = await parseScenarioImport('id,name\nA,Case A', 'CSV');
    expect(preview.source.fingerprint).toBe(
      'sha256:1bd05be8e4c511fd47b1d52c21f4d689e96e1fc413ca36d8e07a0f4e55a70997',
    );
  });

  it('parses quoted CSV, embedded newlines and BOM through Papa Parse', async () => {
    const preview = await parseScenarioImport(
      '\uFEFFid,name,note\n1,"Prime, approved","line one\nline two"\n2,Declined,plain',
      'CSV',
    );

    expect(preview.rowCount).toBe(2);
    expect(preview.columns.map((column) => column.sourcePath)).toEqual(['/id', '/name', '/note']);
    expect(preview.rows[0].values).toEqual({
      '/id': '1',
      '/name': 'Prime, approved',
      '/note': 'line one\nline two',
    });
    expect(preview.source.fingerprint).toMatch(/^sha256:[0-9a-f]{64}$/);
    expect(preview.source.parser).toBe('papaparse-v5');
  });

  it('masks sensitive preview fields and reports formula risk without echoing payloads', async () => {
    const secret = 'never-echo-this-token';
    const preview = await parseScenarioImport(
      `name,api_token,amount\n=cmd,${secret},12`,
      'CSV',
    );

    expect(preview.sampleRows[0].values['/api_token']).toBe('[masked]');
    expect(preview.rows[0].values['/api_token']).toBe(secret);
    expect(preview.warnings.map((entry) => entry.code)).toEqual(expect.arrayContaining([
      'RG.SCENARIO_IMPORT.SENSITIVE_PATH',
      'RG.SCENARIO_IMPORT.FORMULA_PREFIX',
    ]));
    expect(JSON.stringify(preview.warnings)).not.toContain(secret);
  });

  it.each([
    ['duplicate headers', 'a,a\n1,2', 'CSV', 'RG.SCENARIO_IMPORT.HEADER_DUPLICATE'],
    ['row budget', 'a\n1\n2', 'CSV', 'RG.SCENARIO_IMPORT.ROWS_EXCEEDED'],
    ['column budget', 'a,b\n1,2', 'CSV', 'RG.SCENARIO_IMPORT.COLUMNS_EXCEEDED'],
    ['malformed JSON', '[{"a":', 'JSON', 'RG.SCENARIO_IMPORT.JSON_INVALID'],
    ['invalid JSON shape', '{"a":1}', 'JSON', 'RG.SCENARIO_IMPORT.JSON_SHAPE_INVALID'],
  ] as const)('fails closed for %s', async (_label, source, kind, code) => {
    const budget = code === 'RG.SCENARIO_IMPORT.ROWS_EXCEEDED'
      ? { maxRows: 1 }
      : code === 'RG.SCENARIO_IMPORT.COLUMNS_EXCEEDED'
        ? { maxColumns: 1 }
        : {};
    await expect(parseScenarioImport(source, kind, budget)).rejects.toMatchObject({ code });
  });

  it('enforces byte, cell, depth and item budgets', async () => {
    await expect(parseScenarioImport('a\n12345', 'CSV', { maxCellBytes: 3 })).rejects.toMatchObject({
      code: 'RG.SCENARIO_IMPORT.CELL_BYTES_EXCEEDED',
    });
    await expect(parseScenarioImport('[{"a":{"b":{"c":1}}}]', 'JSON', { maxDepth: 2 })).rejects.toMatchObject({
      code: 'RG.SCENARIO_IMPORT.DEPTH_EXCEEDED',
    });
    await expect(parseScenarioImport('[{"a":[1,2,3]}]', 'JSON', { maxItems: 3 })).rejects.toMatchObject({
      code: 'RG.SCENARIO_IMPORT.ITEMS_EXCEEDED',
    });
    await expect(parseScenarioImport('a\n1', 'CSV', { maxBytes: 2 })).rejects.toMatchObject({
      code: 'RG.SCENARIO_IMPORT.BYTES_EXCEEDED',
    });
  });

  it('flattens nested JSON and preserves null, missing, empty and literal null independently', async () => {
    const preview = await parseScenarioImport(JSON.stringify([
      { id: 'A', input: { score: 42 }, explicitNull: null, empty: '', literalNull: 'null' },
      { id: 'B', input: { score: 7 }, empty: '' },
    ]), 'JSON');

    expect(preview.columns.map((column) => column.sourcePath)).toEqual([
      '/empty', '/explicitNull', '/id', '/input/score', '/literalNull',
    ]);
    expect(preview.columns.find((column) => column.sourcePath === '/explicitNull')).toMatchObject({
      missingCount: 1,
      nullCount: 1,
    });
    expect(preview.columns.find((column) => column.sourcePath === '/empty')?.emptyCount).toBe(2);
    expect(preview.rows[0].values['/literalNull']).toBe('null');
  });

  it('auto-maps exact paths and requires confirmation for normalized guesses', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const preview = await parseScenarioImport(
      'name,caseType,field01,Field-02,unknown\nImported,GOLDEN,value,2,x',
      'CSV',
    );
    const bindings = suggestScenarioImportBindings(preview, deriveScenarioImportTargets(draftSet));

    expect(bindings.find((binding) => binding.sourcePath === '/name')).toMatchObject({
      target: { kind: 'NAME' },
      reason: 'EXACT_PATH',
      confidence: 1,
      confirmed: true,
    });
    expect(bindings.find((binding) => binding.sourcePath === '/field01')).toMatchObject({
      target: { kind: 'GIVEN', valuePath: ['field01'] },
      reason: 'EXACT_PATH',
    });
    expect(bindings.find((binding) => binding.sourcePath === '/Field-02')).toMatchObject({
      target: { kind: 'GIVEN', valuePath: ['field02'] },
      reason: 'NORMALIZED_NAME',
      confirmed: false,
    });
    expect(bindings.some((binding) => binding.sourcePath === '/unknown')).toBe(false);

    await expect(createScenarioMaterializationPlan({ preview, draftSet, bindings }))
      .rejects.toMatchObject({ code: 'RG.SCENARIO_IMPORT.BINDING_CONFIRMATION_REQUIRED' });
  });

  it('materializes all five value semantics and finite converters into canonical Scenarios', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const preview = await parseScenarioImport(JSON.stringify([{
      id: 'case-A',
      name: 'Imported boundary',
      caseType: 'boundary',
      tags: 'imported,priority',
      number: '42.5',
      emptyNull: '',
      emptyMissing: '',
      emptyDefault: '',
      literalNull: 'null',
      json: '{"approved":true}',
    }]), 'JSON');
    const targets = deriveScenarioImportTargets(draftSet);
    const bindings: ScenarioColumnBinding[] = [
      manual(preview, targets, '/name', 'case:name'),
      manual(preview, targets, '/caseType', 'case:type'),
      manual(preview, targets, '/tags', 'case:tags'),
      { ...manual(preview, targets, '/number', 'given:/field01'), converter: 'NUMBER' },
      { ...manual(preview, targets, '/emptyNull', 'given:/field02'), valueSemantics: 'NULL' },
      { ...manual(preview, targets, '/emptyMissing', 'given:/field03'), valueSemantics: 'MISSING' },
      { ...manual(preview, targets, '/emptyDefault', 'given:/field04'), valueSemantics: 'DEFAULT', defaultValue: 99 },
      manual(preview, targets, '/literalNull', 'given:/field05'),
      { ...manual(preview, targets, '/json', 'given:/field06'), converter: 'JSON' },
    ];
    const plan = await createScenarioMaterializationPlan({
      preview,
      draftSet,
      bindings,
      identitySourcePath: '/id',
      classification: 'CONFIDENTIAL',
    });
    const result = await materializeScenarioImport({
      preview,
      plan,
      draftSet,
      actor: 'author-1',
      materializedAt: '2026-08-04T12:00:00Z',
    });
    const imported = result.draftSet.scenarios[result.draftSet.scenarios.length - 1];

    expect(imported).toMatchObject({
      name: 'Imported boundary',
      caseType: 'BOUNDARY',
      tags: ['imported', 'priority'],
      given: { provenance: 'IMPORTED' },
    });
    expect(imported.given.input).toMatchObject({
      field01: 42.5,
      field02: null,
      field04: 99,
      field05: 'null',
      field06: { approved: true },
    });
    expect((imported.given.input as Record<string, unknown>)).not.toHaveProperty('field03');
    expect(result.receipt).toMatchObject({
      acceptedRowCount: 1,
      rejectedRowCount: 0,
      actor: 'author-1',
      classification: 'CONFIDENTIAL',
      rowIdentityPolicy: { kind: 'SOURCE_COLUMN', sourcePath: '/id' },
    });
    expect(result.receipt.receiptFingerprint).toMatch(/^sha256:[0-9a-f]{64}$/);
  });

  it('is idempotent for the same exact plan and returns the same receipt', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const preview = await parseScenarioImport('id,name,field01\nA,Imported A,11\nB,Imported B,22', 'CSV');
    const targets = deriveScenarioImportTargets(draftSet);
    const bindings = suggestScenarioImportBindings(preview, targets);
    const plan = await createScenarioMaterializationPlan({
      preview,
      draftSet,
      bindings,
      identitySourcePath: '/id',
    });
    const command = {
      preview,
      plan,
      draftSet,
      actor: 'author-1',
      materializedAt: '2026-08-04T12:00:00Z',
    };
    const first = await materializeScenarioImport(command);
    const retried = await materializeScenarioImport({ ...command, draftSet: first.draftSet });

    expect(retried.receipt).toEqual(first.receipt);
    expect(retried.draftSet).toEqual(first.draftSet);
    expect(new Set(first.receipt.materializedScenarioIds).size).toBe(2);
  });

  it('rejects fingerprint drift and reports row converter failures without leaking cell data', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const preview = await parseScenarioImport('name,field01\nImported,not-a-number', 'CSV');
    const targets = deriveScenarioImportTargets(draftSet);
    const bindings = suggestScenarioImportBindings(preview, targets).map((binding) => (
      binding.sourcePath === '/field01' ? { ...binding, converter: 'NUMBER' as const } : binding
    ));
    const plan = await createScenarioMaterializationPlan({ preview, draftSet, bindings });
    const result = await materializeScenarioImport({
      preview,
      plan,
      draftSet,
      actor: 'author-1',
      materializedAt: '2026-08-04T12:00:00Z',
    });

    expect(result.receipt.rows[0]).toMatchObject({
      status: 'REJECTED',
      diagnosticCode: 'RG.SCENARIO_IMPORT.NUMBER_INVALID',
    });
    expect(JSON.stringify(result.receipt)).not.toContain('not-a-number');
    await expect(materializeScenarioImport({
      preview,
      plan: { ...plan, source: { ...plan.source, fingerprint: 'sha256:drift' } },
      draftSet,
      actor: 'author-1',
      materializedAt: '2026-08-04T12:00:00Z',
    })).rejects.toMatchObject({ code: 'RG.SCENARIO_IMPORT.SOURCE_DRIFT' });
  });

  it('rejects duplicate explicit identities and empty numeric cells per row', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const preview = await parseScenarioImport('id,name,field01\nA,First,\nA,Second,7', 'CSV');
    const targets = deriveScenarioImportTargets(draftSet);
    const bindings = suggestScenarioImportBindings(preview, targets).map((binding) => (
      binding.sourcePath === '/field01' ? { ...binding, converter: 'NUMBER' as const } : binding
    ));
    const plan = await createScenarioMaterializationPlan({
      preview,
      draftSet,
      bindings,
      identitySourcePath: '/id',
    });
    const result = await materializeScenarioImport({
      preview,
      plan,
      draftSet,
      actor: 'author-1',
      materializedAt: '2026-08-04T12:00:00Z',
    });

    expect(result.receipt.rows).toEqual(expect.arrayContaining([
      expect.objectContaining({ diagnosticCode: 'RG.SCENARIO_IMPORT.NUMBER_INVALID' }),
      expect.objectContaining({ diagnosticCode: 'RG.SCENARIO_IMPORT.IDENTITY_DUPLICATE' }),
    ]));
    expect(result.receipt.acceptedRowCount).toBe(0);
    expect(result.receipt.rejectedRowCount).toBe(2);
  });

  it('diffs added, changed, removed and unchanged rows by explicit identity', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const original = await parseScenarioImport(JSON.stringify([
      { id: 'A', name: 'A', field01: 1 },
      { id: 'B', name: 'B', field01: 2 },
    ]), 'JSON');
    const targets = deriveScenarioImportTargets(draftSet);
    const bindings = suggestScenarioImportBindings(original, targets);
    const plan = await createScenarioMaterializationPlan({
      preview: original,
      draftSet,
      bindings,
      identitySourcePath: '/id',
    });
    const result = await materializeScenarioImport({
      preview: original,
      plan,
      draftSet,
      actor: 'author-1',
      materializedAt: '2026-08-04T12:00:00Z',
    });
    const updated = await parseScenarioImport(JSON.stringify([
      { id: 'A', name: 'A changed', field01: 1 },
      { id: 'C', name: 'C', field01: 3 },
    ]), 'JSON');

    const diff = await diffScenarioImport(updated, result.receipt);

    expect(diff).toEqual({
      added: [expect.stringMatching(/^sha256:[0-9a-f]{64}$/)],
      changed: [expect.stringMatching(/^sha256:[0-9a-f]{64}$/)],
      removed: [expect.stringMatching(/^sha256:[0-9a-f]{64}$/)],
      unchanged: [],
    });
    expect(JSON.stringify(result.receipt)).not.toContain('"A"');
    expect(JSON.stringify(result.receipt)).not.toContain('"B"');
  });

  it('uses payload-free typed errors', () => {
    const error = new ScenarioImportError('RG.SCENARIO_IMPORT.TEST', 'Safe summary');
    expect(error).toMatchObject({ code: 'RG.SCENARIO_IMPORT.TEST', message: 'Safe summary' });
  });
});

function manual(
  preview: Awaited<ReturnType<typeof parseScenarioImport>>,
  targets: ReturnType<typeof deriveScenarioImportTargets>,
  sourcePath: string,
  targetId: string,
): ScenarioColumnBinding {
  expect(preview.columns.some((column) => column.sourcePath === sourcePath)).toBe(true);
  const target = targets.find((candidate) => candidate.targetId === targetId);
  expect(target).toBeDefined();
  return {
    bindingId: `${sourcePath}->${targetId}`,
    sourcePath,
    target: target!,
    confidence: 1,
    reason: 'MANUAL',
    confirmed: true,
    converter: 'IDENTITY',
    valueSemantics: 'VALUE',
  };
}
