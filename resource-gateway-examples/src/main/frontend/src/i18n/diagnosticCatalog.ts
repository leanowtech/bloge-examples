import type { Locale } from './i18n';
import { translateMessage, type MessageId } from './messageCatalog';

interface DiagnosticDescriptor {
  title: MessageId;
  explanation: MessageId;
  remediation: MessageId;
}

const DIAGNOSTIC_CATALOG: Record<string, DiagnosticDescriptor> = {
  REQUEST_FAILED: descriptor('requestFailed'),
  RUN_FAILED: descriptor('runFailed'),
  ASSERTION_FAILED: descriptor('assertionFailed'),
  'RG.TABLE_RUN.ASSERTION_MISMATCH': descriptor('assertionFailed'),
  EFFECTIVE_CONTRACT_MULTIPLE_SOURCES: descriptor('contractMultipleSources'),
  EFFECTIVE_CONTRACT_TYPE_CONFLICT: descriptor('contractTypeConflict'),
};

export interface DiagnosticPresentation {
  cataloged: boolean;
  title: string;
  explanation: string;
  remediation: string;
  technicalDetail: string;
}

export function presentDiagnostic(
  locale: Locale,
  code: string,
  technicalDetail: string,
  fallbackRemediation: string,
): DiagnosticPresentation {
  const entry = DIAGNOSTIC_CATALOG[code];
  if (!entry) {
    return {
      cataloged: false,
      title: translateMessage(locale, 'diagnostic.generic.title'),
      explanation: translateMessage(locale, 'diagnostic.generic.explanation'),
      remediation: locale === 'en' && fallbackRemediation
        ? fallbackRemediation
        : translateMessage(locale, 'diagnostic.generic.remediation'),
      technicalDetail,
    };
  }
  return {
    cataloged: true,
    title: translateMessage(locale, entry.title),
    explanation: translateMessage(locale, entry.explanation),
    remediation: translateMessage(locale, entry.remediation),
    technicalDetail,
  };
}

export function catalogedDiagnosticCodes(): string[] {
  return Object.keys(DIAGNOSTIC_CATALOG).sort();
}

function descriptor(
  key: 'requestFailed' | 'runFailed' | 'assertionFailed' | 'contractMultipleSources' | 'contractTypeConflict',
): DiagnosticDescriptor {
  return {
    title: `diagnostic.${key}.title`,
    explanation: `diagnostic.${key}.explanation`,
    remediation: `diagnostic.${key}.remediation`,
  } as DiagnosticDescriptor;
}
