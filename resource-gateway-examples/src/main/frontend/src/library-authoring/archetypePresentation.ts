import type { ProductMessageDescriptor } from '../i18n/messageCatalog';

export const OPERATOR_ARCHETYPES = [
  'pure',
  'decision',
  'resource-read',
  'external-write',
  'remote-worker',
  'ai-tool',
  'event-source',
  'message-handler',
  'webhook',
] as const;

export type OperatorArchetype = typeof OPERATOR_ARCHETYPES[number];

export interface OperatorArchetypePresentation {
  value: OperatorArchetype | string;
  label: ProductMessageDescriptor;
  summary: ProductMessageDescriptor;
  external: boolean;
}

const PRESENTATIONS: Record<OperatorArchetype, Omit<OperatorArchetypePresentation, 'value'>> = {
  pure: descriptor('pure', false),
  decision: descriptor('decision', false),
  'resource-read': descriptor('resourceRead', true),
  'external-write': descriptor('externalWrite', true),
  'remote-worker': descriptor('remoteWorker', true),
  'ai-tool': descriptor('aiTool', true),
  'event-source': descriptor('eventSource', true),
  'message-handler': descriptor('messageHandler', true),
  webhook: descriptor('webhook', true),
};

export function presentOperatorArchetype(value: string | undefined): OperatorArchetypePresentation {
  const normalized = value?.trim() || 'pure';
  if (isOperatorArchetype(normalized)) return { value: normalized, ...PRESENTATIONS[normalized] };
  return {
    value: normalized,
    label: { messageId: 'library.archetype.unknown.label', rawCode: normalized },
    summary: { messageId: 'library.archetype.unknown.summary', rawCode: normalized },
    external: true,
  };
}

export function isOperatorArchetype(value: string): value is OperatorArchetype {
  return OPERATOR_ARCHETYPES.includes(value as OperatorArchetype);
}

function descriptor(
  key: 'pure' | 'decision' | 'resourceRead' | 'externalWrite' | 'remoteWorker'
    | 'aiTool' | 'eventSource' | 'messageHandler' | 'webhook',
  external: boolean,
): Omit<OperatorArchetypePresentation, 'value'> {
  return {
    label: { messageId: `library.archetype.${key}.label` },
    summary: { messageId: `library.archetype.${key}.summary` },
    external,
  };
}
