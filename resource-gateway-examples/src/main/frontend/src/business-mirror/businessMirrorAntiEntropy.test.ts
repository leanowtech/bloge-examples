import { describe, expect, it } from 'vitest';

import { MESSAGE_CATALOG } from '../i18n/messageCatalog';
import { businessMirrorAuthorHref } from '../shared/workspace-routing/businessMirrorAuthorLink';
import {
  BUSINESS_MIRROR_REMEDIATION_DESCRIPTORS,
  KNOWN_BUSINESS_MIRROR_BLOCKER_CODES,
} from './guidance';

describe('Business Mirror Stage 4 anti-entropy contracts', () => {
  it('keeps every known blocker descriptor localized in both supported catalogs', () => {
    for (const code of KNOWN_BUSINESS_MIRROR_BLOCKER_CODES) {
      const descriptor = BUSINESS_MIRROR_REMEDIATION_DESCRIPTORS[code];
      expect(descriptor, code).toBeDefined();
      for (const messageId of [
        descriptor.titleMessageId,
        descriptor.impactMessageId,
        descriptor.instructionMessageId,
      ]) {
        const localized = MESSAGE_CATALOG[messageId as keyof typeof MESSAGE_CATALOG];
        expect(localized, `${code} -> ${messageId}`).toBeDefined();
        expect(localized?.en, `${code} -> ${messageId} en`).toBeTruthy();
        expect(localized?.['zh-CN'], `${code} -> ${messageId} zh-CN`).toBeTruthy();
      }
    }
  });

  it('keeps the exact author link on the Author Compose surface', () => {
    const href = businessMirrorAuthorHref({
      graphName: 'loanDecisionPolicy',
      graphRef: {
        id: 'built-in:loanDecisionPolicy',
        revision: 3,
        fingerprint: `sha256:${'a'.repeat(64)}`,
      },
      packageId: 'legacy:loanDecisionPolicy',
    }, { vscode: false, search: '?lang=zh-CN' });
    const url = new URL(href, 'http://localhost');

    expect(url.pathname).toBe('/author/');
    expect(url.searchParams.get('authorWorkspace')).toBe('v2');
    expect(url.searchParams.get('authorMode')).toBe('compose');
    expect(url.searchParams.get('sourceGraphName')).toBe('loanDecisionPolicy');
    expect(url.searchParams.get('sourceId')).toBe('built-in:loanDecisionPolicy');
    expect(url.searchParams.get('returnRoute')).toBe('business-mirror');
    expect(href).not.toContain('/showcase');
  });
});
