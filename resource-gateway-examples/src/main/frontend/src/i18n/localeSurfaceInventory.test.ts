// @vitest-environment node
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import {
  BUILT_IN_NODE_EDITOR_KINDS,
  resolveNodeEditor,
} from '../author/node-editor/nodeEditorRegistry';
import { hasChineseTranslation } from './i18n';

const DEEP_SURFACES = [
  '../AuthorCanvas.tsx',
  '../author/canvas/CanvasTaskNavigator.tsx',
  '../author/shell/AuthorCommandBar.tsx',
  '../author/review/AuthorDiagnosticsDrawer.tsx',
  '../contract-scenario/ContractScenarioWorkspace.tsx',
  '../contract-scenario/ContractSemanticsEditor.tsx',
  '../contract-scenario/SchemaFieldTree.tsx',
  '../contract-scenario/AssertionBuilder.tsx',
  '../contract-scenario/DependencyBehaviorEditor.tsx',
  '../contract-scenario/table/ScenarioMatrixSurface.tsx',
  '../contract-scenario/mobile/MobileScenarioTaskSurface.tsx',
  '../library-authoring/mobile/MobileLibraryTaskSurface.tsx',
  '../remediation/RemediationActionList.tsx',
  '../library-authoring/AssetTestTable.tsx',
  '../library-authoring/LibraryWorkbench.tsx',
  '../library-authoring/LibraryStartChoices.tsx',
  '../library-authoring/LibraryTree.tsx',
  '../library-authoring/OperatorBuilder.tsx',
  '../library-authoring/FunctionBuilder.tsx',
  '../library-authoring/SchemaTreeEditor.tsx',
  '../library-authoring/CanonicalContractPreview.tsx',
  '../library-authoring/ExistingAssetDiscovery.tsx',
  '../library-authoring/SampleInferenceReview.tsx',
  '../library-authoring/GovernedFixtureSavePanel.tsx',
  '../author/contract/EffectiveContractPanel.tsx',
  '../RehearsalWorkbench.tsx',
  '../Showcase.tsx',
] as const;

describe('deep-surface locale inventory', () => {
  it('requires every literal legacy t() key on audited surfaces to have Chinese text', () => {
    const missing = DEEP_SURFACES.flatMap((relativePath) => {
      const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8');
      const keys = [...source.matchAll(/\bt\(\s*'((?:\\'|[^'])+)'/g)]
        .map((match) => match[1].replace(/\\'/g, "'"));
      return [...new Set(keys)]
        .filter((key) => !hasChineseTranslation(key))
        .map((key) => `${relativePath}: ${key}`);
    });

    expect(missing).toEqual([]);
  });

  it('rejects visible English JSX text and accessibility attributes that bypass localization', () => {
    const bypasses = DEEP_SURFACES.flatMap((relativePath) => {
      const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8');
      const file = ts.createSourceFile(relativePath, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
      const findings: string[] = [];
      const visit = (node: ts.Node) => {
        if (ts.isJsxText(node)) {
          const value = node.getText(file).replace(/\s+/g, ' ').trim();
          if (/[A-Za-z]{2,}/.test(value)) findings.push(`${relativePath}: ${value}`);
        }
        if (ts.isJsxAttribute(node)
          && ['aria-label', 'placeholder', 'title'].includes(node.name.getText(file))
          && node.initializer
          && ts.isStringLiteral(node.initializer)
          && /[A-Za-z]{2,}/.test(node.initializer.text)) {
          findings.push(`${relativePath}: ${node.name.getText(file)}="${node.initializer.text}"`);
        }
        ts.forEachChild(node, visit);
      };
      visit(file);
      return findings;
    });

    expect(bypasses).toEqual([]);
  });

  it('requires evidence-model templates to have Chinese translations', () => {
    const relativePath = '../contract-scenario/evidenceModel.ts';
    const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8');
    const file = ts.createSourceFile(relativePath, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    const templates = new Set<string>();
    const collectStrings = (node: ts.Node) => {
      if (ts.isStringLiteral(node) && /[A-Za-z]{2,}\s+[A-Za-z]{2,}/.test(node.text)) {
        templates.add(node.text);
      }
      ts.forEachChild(node, collectStrings);
    };
    const visit = (node: ts.Node) => {
      if (ts.isPropertyAssignment(node)
        && ['headline', 'summary', 'detail', 'message'].includes(node.name.getText(file))) {
        collectStrings(node.initializer);
      }
      if (ts.isVariableDeclaration(node)
        && node.name.getText(file) === 'detail'
        && node.initializer) {
        collectStrings(node.initializer);
      }
      if (ts.isCallExpression(node)
        && node.expression.getText(file) === 'dimension'
        && node.arguments[4]) {
        collectStrings(node.arguments[4]);
      }
      ts.forEachChild(node, visit);
    };
    visit(file);

    expect([...templates].filter((template) => !hasChineseTranslation(template))).toEqual([]);
  });

  it('covers dynamic node-editor tasks and tab labels that are not literal t() calls', () => {
    const messages = BUILT_IN_NODE_EDITOR_KINDS.flatMap((kind) => {
      const definition = resolveNodeEditor(kind);
      return [definition.primaryTask, ...definition.tabs.map((tab) => tab.label)];
    });

    expect([...new Set(messages)].filter((message) => !hasChineseTranslation(message))).toEqual([]);
  });

  it('covers human-facing readiness copy assembled before rendering', () => {
    const source = readFileSync(new URL('../author/readiness/authorReadiness.ts', import.meta.url), 'utf8');
    const file = ts.createSourceFile('authorReadiness.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    const messages = new Set<string>();
    const visit = (node: ts.Node) => {
      if (ts.isStringLiteral(node) && /[A-Za-z]{2,}\s+[A-Za-z]{2,}/.test(node.text)) {
        messages.add(node.text);
      }
      ts.forEachChild(node, visit);
    };
    visit(file);

    expect([...messages].filter((message) => !hasChineseTranslation(message))).toEqual([]);
  });

  it('covers dynamic canvas labels and notices projected from operator metadata', () => {
    const targets = [
      ['../AuthorCanvas.tsx', ['operatorFocusRows', 'operatorFocusTitle', 'operatorPropertyRows']],
      ['../draftModel.ts', ['operatorSideEffect', 'operatorReadiness', 'operatorVisualContract']],
    ] as const;
    const messages = new Set<string>();
    for (const [relativePath, functionNames] of targets) {
      const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8');
      const file = ts.createSourceFile(relativePath, source, ts.ScriptTarget.Latest, true,
        relativePath.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
      const visit = (node: ts.Node) => {
        if (ts.isFunctionDeclaration(node)
          && node.name
          && functionNames.includes(node.name.text as never)) {
          const collectVisibleStrings = (child: ts.Node) => {
            if (ts.isPropertyAssignment(child)) {
              const propertyName = child.name.getText(file);
              if (['label', 'visualLabel', 'contractHint', 'inputContractLabel',
                'outputContractLabel', 'badgeLabel', 'nodeNotice', 'notice'].includes(propertyName)) {
                const collectInitializer = (value: ts.Node) => {
                  if (ts.isStringLiteral(value) && /[A-Za-z]{2,}/.test(value.text)) {
                    messages.add(value.text);
                  }
                  ts.forEachChild(value, collectInitializer);
                };
                collectInitializer(child.initializer);
              }
            }
            if (ts.isReturnStatement(child) && child.expression && ts.isStringLiteral(child.expression)) {
              messages.add(child.expression.text);
            }
            ts.forEachChild(child, collectVisibleStrings);
          };
          if (node.body) collectVisibleStrings(node.body);
          return;
        }
        ts.forEachChild(node, visit);
      };
      visit(file);
    }

    expect([...messages].filter((message) => !hasChineseTranslation(message))).toEqual([]);
  });

  it('covers effective-contract protocol tokens shown as product status', () => {
    const tokens = [
      'DECLARED', 'INFERRED', 'OBSERVED', 'EXACT', 'OPAQUE', 'CONFLICTED',
      'SCHEMA', 'ASSIGNMENT', 'DECISION_OUTPUT', 'RUN_RESULT',
      'EDGE', 'CONTEXT', 'CONSTANT', 'NODE', 'EXPRESSION',
      'CONNECTED', 'UNBOUND', 'CONFLICT', 'No source',
    ];

    expect(tokens.filter((token) => !hasChineseTranslation(token))).toEqual([]);
  });
});
