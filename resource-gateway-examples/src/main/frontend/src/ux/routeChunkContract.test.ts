import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const appSource = readFileSync(new URL('../App.tsx', import.meta.url), 'utf8');
const viteSource = readFileSync(new URL('../../vite.config.ts', import.meta.url), 'utf8');
const packageJson = JSON.parse(
  readFileSync(new URL('../../package.json', import.meta.url), 'utf8'),
) as { scripts: Record<string, string> };

describe('Route chunk contract', () => {
  it('loads each heavyweight workspace through a route boundary', () => {
    expect(appSource).not.toMatch(/^import AuthorCanvas/m);
    expect(appSource).not.toMatch(/^import RehearsalWorkbench/m);
    expect(appSource).not.toMatch(/^import Showcase/m);
    expect(appSource).not.toMatch(/^import LibraryWorkbench/m);
    expect(appSource).toContain("lazy(loadAuthorCanvas)");
    expect(appSource).toContain("lazy(loadLibraryWorkbench)");
    expect(appSource).toContain("lazy(loadRehearsalWorkbench)");
    expect(appSource).toContain("lazy(loadShowcase)");
  });

  it('prefetches only after explicit navigation intent', () => {
    expect(appSource).toContain('onPointerEnter={prefetch(');
    expect(appSource).toContain('onFocus={prefetch(');
    expect(appSource).not.toContain('requestIdleCallback');
  });

  it('keeps stable author domain code in its own named cache unit', () => {
    expect(viteSource).toContain('manifest: true');
    expect(viteSource).toContain("return 'author-domain'");
    expect(viteSource).toContain("id.endsWith('/src/draftModel.ts')");
    expect(viteSource).toContain("id.endsWith('/src/canvasExamples.ts')");
  });

  it('fails the production build when the route chunk budget drifts', () => {
    expect(packageJson.scripts['check:bundle']).toBe('node scripts/check-route-chunk-budget.mjs');
    expect(packageJson.scripts['check:host']).toContain('vscodeWebviewBridge.test.ts');
    expect(packageJson.scripts.build).toContain('vite build && npm run check:bundle');
  });
});
