import { describe, expect, it } from 'vitest';

import { businessMirrorAuthorHref } from './businessMirrorAuthorLink';

const subject = {
  graphName: 'loanDecisionPolicy',
  graphRef: {
    id: 'built-in:loanDecisionPolicy',
    revision: 3,
    fingerprint: `sha256:${'a'.repeat(64)}`,
  },
  packageId: 'legacy:loanDecisionPolicy',
};

describe('businessMirrorAuthorHref', () => {
  it('opens the exact Graph in Author Compose and never falls back to Showcase', () => {
    const href = businessMirrorAuthorHref(subject, {
      vscode: false,
      search: '?lang=zh-CN&unsafe=drop-me',
    });
    const url = new URL(href, 'http://localhost');

    expect(url.pathname).toBe('/author/');
    expect(url.searchParams.get('authorMode')).toBe('compose');
    expect(url.searchParams.get('sourceGraphName')).toBe('loanDecisionPolicy');
    expect(url.searchParams.get('sourceId')).toBe('built-in:loanDecisionPolicy');
    expect(url.searchParams.get('sourceRevision')).toBe('3');
    expect(url.searchParams.get('sourceFingerprint')).toBe(subject.graphRef.fingerprint);
    expect(url.searchParams.get('returnRoute')).toBe('business-mirror');
    expect(url.searchParams.get('returnPackageId')).toBe('legacy:loanDecisionPolicy');
    expect(url.searchParams.get('returnTask')).toBe('capabilities');
    expect(url.searchParams.get('lang')).toBe('zh-CN');
    expect(url.searchParams.has('unsafe')).toBe(false);
    expect(href).not.toContain('showcase');
  });

  it('uses the VS Code workspace route without changing the exact subject', () => {
    const href = businessMirrorAuthorHref(subject, { vscode: true });
    const params = new URLSearchParams(href.slice(1));

    expect(params.get('workspaceRoute')).toBe('author');
    expect(params.get('sourceId')).toBe(subject.graphRef.id);
    expect(params.get('sourceFingerprint')).toBe(subject.graphRef.fingerprint);
  });

  it('rejects incomplete and non-exact coordinates', () => {
    expect(() => businessMirrorAuthorHref({
      ...subject,
      graphRef: { ...subject.graphRef, revision: 0 },
    }, { vscode: false })).toThrow(/positive integer/);
    expect(() => businessMirrorAuthorHref({
      ...subject,
      graphRef: { ...subject.graphRef, fingerprint: 'latest' },
    }, { vscode: false })).toThrow(/SHA-256/);
  });
});
