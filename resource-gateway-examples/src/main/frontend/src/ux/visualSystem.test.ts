import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const legacyCss = readFileSync(new URL('../styles.css', import.meta.url), 'utf8');
const tokensCss = readFileSync(new URL('../styles/tokens.css', import.meta.url), 'utf8');
const responsiveCss = readFileSync(new URL('../styles/responsive.css', import.meta.url), 'utf8');

describe('Stage 5 visual-system contract', () => {
  it('keeps semantic hidden state authoritative across responsive overrides', () => {
    expect(responsiveCss).toMatch(/\.app \[hidden\][\s\S]*display: none !important/);
  });

  it('eliminates unreadable literal text sizes from the product stylesheet', () => {
    expect(legacyCss).not.toMatch(/font-size:\s*(?:8|9|10|11)px/);
    expect(responsiveCss).not.toMatch(/font-size:\s*(?:8|9|10|11)px/);
  });

  it('defines the promised body, auxiliary, mobile, and touch-target floors', () => {
    expect(tokensCss).toContain('--rg-font-body: 13px');
    expect(tokensCss).toContain('--rg-font-aux: 12px');
    expect(tokensCss).toContain('--rg-font-mobile-body: 14px');
    expect(tokensCss).toContain('--rg-touch-target: 40px');
  });

  it('keeps comfortable as the default and scopes compact changes to spacing', () => {
    expect(tokensCss).toContain('--rg-control-min: 36px');
    expect(tokensCss).toMatch(/html\[data-density='compact'\][\s\S]*--rg-control-min: 32px/);
    expect(tokensCss).not.toMatch(/html\[data-density='compact'\][\s\S]*--rg-font-(?:body|aux)/);
  });

  it('replaces mobile topbar scrolling with a bounded disclosed navigation', () => {
    const mobileTopbar = responsiveCss.match(/@media \(max-width: 840px\) \{([\s\S]*?)\n\}/)?.[1] ?? '';
    expect(responsiveCss).toContain(".topbar-nav[data-open='true']");
    expect(responsiveCss).toContain('grid-template-columns: repeat(2, minmax(0, 1fr))');
    expect(mobileTopbar).not.toContain('overflow-x: auto');
  });

  it('enforces 40px controls for compact viewports and coarse pointers', () => {
    expect(responsiveCss).toMatch(/@media \(max-width: 840px\)[\s\S]*min-height: var\(--rg-touch-target\)/);
    expect(responsiveCss).toMatch(/button\.icon-button[\s\S]*min-width: var\(--rg-touch-target\)/);
    expect(responsiveCss).toMatch(/@media \(pointer: coarse\)[\s\S]*min-height: var\(--rg-touch-target\)/);
    expect(responsiveCss).toMatch(/\.react-flow__controls-button[\s\S]*width: var\(--rg-touch-target\)/);
  });

  it('reserves the responsive diagnostics touch target below author work', () => {
    expect(responsiveCss).toMatch(/workspace-v2:not\(\[data-author-mode='compose'\]\) > \.canvas[\s\S]*padding-bottom: calc\(var\(--rg-touch-target\) \+ 1px\)/);
    expect(responsiveCss).toMatch(/workspace-v2 > \.inspector[\s\S]*scroll-padding-bottom: calc\(var\(--rg-touch-target\) \+ 1px\)/);
  });

  it('adds continuation shadows to the real horizontally scrollable work surfaces', () => {
    expect(responsiveCss).toContain('.scenario-matrix-scroll');
    expect(responsiveCss).toContain('.library-home-table');
    expect(responsiveCss).toContain('no-repeat local');
    expect(legacyCss).toMatch(/\.scenario-matrix tbody td:nth-child\(2\)[\s\S]*position: sticky/);
  });

  it('uses a task-first mobile shell outside graph composition', () => {
    expect(responsiveCss).toContain(".workspace-v2:not([data-author-mode='compose']) > .author-command-bar");
    expect(responsiveCss).toContain(':is(.author-draft-identity, .author-mobile-truth, .author-secondary-command-group)');
    expect(responsiveCss).toMatch(/not\(\[data-author-mode='compose'\]\)[\s\S]*\.author-primary-command[\s\S]*display: none/);
    expect(responsiveCss).toMatch(/contract-workspace-header-actions[\s\S]*:disabled/);
  });

  it('gives mobile Scenario work an explicit intent, picker, step scope, and run summary', () => {
    expect(responsiveCss).toContain('.scenario-mobile-taskbar');
    expect(responsiveCss).toContain('.scenario-mobile-intent-switch');
    expect(responsiveCss).toContain('.scenario-mobile-case-picker');
    expect(responsiveCss).toContain('.scenario-mobile-step-nav');
    expect(responsiveCss).toContain('.scenario-mobile-run-summary');
    expect(responsiveCss).toMatch(/scenario-mobile-step-nav button[\s\S]*min-height: 48px/);
  });

  it('keeps mobile Matrix execution controls reachable after a command receipt appears', () => {
    expect(responsiveCss).toMatch(/data-command-density='compact'[\s\S]*scenario-matrix-bulkbar[\s\S]*min-height: 52px/);
    expect(responsiveCss).toMatch(/scenario-matrix-bulkbar > div:first-child[\s\S]*display: none/);
    expect(responsiveCss).toMatch(/scenario-matrix-run-stack > \.scenario-command-receipt[\s\S]*min-height: 34px/);
    expect(responsiveCss).toMatch(/scenario-command-receipt > dl[\s\S]*display: none/);
  });

  it('keeps canvas card geometry stable when an operator is selected', () => {
    expect(legacyCss).toMatch(/data-canvas-task-mode="focus"[\s\S]*\.operator-node \.operator-node-port-grid[\s\S]*display: none/);
    expect(legacyCss).toMatch(/data-canvas-task-mode="inspect"[\s\S]*\.operator-node \.operator-node-port-grid[\s\S]*display: none/);
  });

  it('keeps desktop task controls and readiness conclusions fully legible', () => {
    expect(legacyCss).toMatch(/\.canvas-task-modes[\s\S]*min-width: 204px/);
    expect(legacyCss).toMatch(/\.author-truth-status strong[\s\S]*overflow-wrap: normal[\s\S]*white-space: normal/);
    expect(responsiveCss).toMatch(/data-command-density='compact'[\s\S]*scenario-matrix-context code[\s\S]*display: none/);
  });

  it('opens formal mobile context as an overlay without shrinking the task surface', () => {
    expect(responsiveCss).toMatch(/workspace\.workspace-v2\.compact-workspace:not[\s\S]*grid-template-columns: minmax\(0, 1fr\)/);
    expect(responsiveCss).toMatch(/compact-workspace:not[\s\S]*> \.inspector[\s\S]*grid-column: 1[\s\S]*width: min\(304px, calc\(100% - 48px\)\)/);
  });

  it('projects mobile Library review and light edit as one bounded task', () => {
    expect(responsiveCss).toContain(".library-workbench[data-responsive-layout='MOBILE_TASK']");
    expect(responsiveCss).toContain('.mobile-library-taskbar');
    expect(responsiveCss).toContain('.mobile-library-asset-picker');
    expect(responsiveCss).toContain('.mobile-library-review');
    expect(responsiveCss).toContain('.mobile-library-light-editor');
    expect(responsiveCss).toMatch(/mobile-library-review-actions[\s\S]*\.primary[\s\S]*grid-column: 1 \/ -1/);
  });
});
