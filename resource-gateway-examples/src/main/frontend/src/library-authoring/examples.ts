import type { VisualLibraryAuthoringDocument } from '../types';

export interface LibraryAuthoringExample {
  key: string;
  label: string;
  domain: string;
  description: string;
  document: VisualLibraryAuthoringDocument;
}

export const LIBRARY_AUTHORING_EXAMPLES: LibraryAuthoringExample[] = [
  {
    key: 'customer-support',
    label: 'Customer Support Triage',
    domain: 'Customer service',
    description: 'Named types, a resource read, a pure classifier, and overloaded support functions.',
    document: {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: {
        id: 'customer-support-authoring',
        name: 'Customer Support Authoring',
        version: '1.0.0',
        owner: 'customer-operations',
        status: 'ACTIVE',
      },
      defaults: { operatorVersion: '1.0.0', namespace: 'support' },
      types: {
        Ticket: {
          fields: {
            id: 'string',
            subject: 'string',
            'body?': 'string',
            'customerTier?': { enum: ['free', 'pro', 'enterprise'] },
          },
        },
        TriageResult: {
          fields: {
            priority: { enum: ['p0', 'p1', 'p2', 'p3'] },
            topic: 'string',
            confidence: 'number',
          },
        },
      },
      operators: {
        'support:load-profile': {
          name: 'Load Customer Profile',
          description: 'Reads the customer profile used during triage.',
          archetype: 'resource-read',
          requiresSecrets: false,
          input: { customerId: 'string' },
          output: { profile: 'any' },
          runtime: { bindingRef: 'customer-profile-service' },
          tests: [{ ref: 'fixtures/load-enterprise-profile' }],
        },
        'support:classify-ticket': {
          name: 'Classify Ticket',
          description: 'Classifies a support ticket without external side effects.',
          archetype: 'pure',
          input: { ticket: 'Ticket', 'profile?': 'any' },
          output: { triage: 'TriageResult' },
          tests: [{ ref: 'fixtures/classify-enterprise-ticket' }],
        },
      },
      functions: {
        'support.normalizeText': {
          name: 'support.normalizeText',
          description: 'Normalizes customer-provided text.',
          category: 'string',
          signatures: ['(text: string) -> string'],
          examples: ['support.normalizeText(ctx.ticket.subject)'],
          tests: [{ ref: 'fixtures/normalize-spaces' }],
        },
        'support.firstPresent': {
          name: 'support.firstPresent',
          description: 'Returns the first available value.',
          category: 'null-handling',
          signatures: [
            '(value: any, fallback?: any) -> any',
            '(values: any[]) -> any',
          ],
          examples: [],
          tests: [{ ref: 'fixtures/first-present' }],
        },
      },
      imports: [],
      examples: {},
    },
  },
  {
    key: 'order-fulfillment',
    label: 'Order Fulfillment',
    domain: 'Commerce',
    description: 'Read/write operators with explicit idempotency and a reusable total function.',
    document: {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: {
        id: 'order-fulfillment-authoring',
        name: 'Order Fulfillment Authoring',
        version: '1.0.0',
        owner: 'fulfillment-platform',
        status: 'ACTIVE',
      },
      defaults: { operatorVersion: '1.0.0', namespace: 'orders' },
      types: {
        FulfillmentRequest: {
          fields: {
            orderId: 'string',
            warehouseId: 'string',
            items: 'any[]',
          },
        },
      },
      operators: {
        'orders:load-order': {
          name: 'Load Order',
          archetype: 'resource-read',
          requiresSecrets: false,
          input: { orderId: 'string' },
          output: { order: 'any' },
          runtime: { bindingRef: 'order-service' },
          tests: [{ ref: 'fixtures/load-order' }],
        },
        'orders:reserve-stock': {
          name: 'Reserve Stock',
          archetype: 'external-write',
          effect: 'WRITE_EXTERNAL',
          idempotency: 'REQUIRED',
          requiresSecrets: false,
          input: { request: 'FulfillmentRequest' },
          output: { reservationId: 'string' },
          runtime: { bindingRef: 'inventory-service' },
          tests: [{ ref: 'fixtures/reserve-stock' }],
        },
      },
      functions: {
        'orders.totalUnits': {
          name: 'orders.totalUnits',
          description: 'Counts requested units.',
          category: 'commerce',
          signatures: ['(items: any[]) -> number'],
          examples: ['orders.totalUnits(ctx.items)'],
          tests: [{ ref: 'fixtures/total-units' }],
        },
      },
      imports: [],
      examples: {},
    },
  },
  {
    key: 'risk-policy',
    label: 'Risk Decision Policy',
    domain: 'Risk',
    description: 'Pure decision operators, constrained records, and overload-friendly policy helpers.',
    document: {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: {
        id: 'risk-policy-authoring',
        name: 'Risk Policy Authoring',
        version: '1.0.0',
        owner: 'risk-platform',
        status: 'ACTIVE',
      },
      defaults: { operatorVersion: '1.0.0', namespace: 'risk' },
      types: {
        RiskFacts: {
          fields: {
            applicantId: 'string',
            score: 'number',
            'country?': 'string',
          },
        },
        RiskDecision: {
          fields: {
            approved: 'boolean',
            reason: 'string',
          },
        },
      },
      operators: {
        'risk:evaluate-policy': {
          name: 'Evaluate Risk Policy',
          description: 'Evaluates declared facts without external effects.',
          archetype: 'decision',
          input: { facts: 'RiskFacts' },
          output: { decision: 'RiskDecision' },
          tests: [
            { ref: 'fixtures/approve-low-risk' },
            { ref: 'fixtures/reject-high-risk' },
          ],
        },
      },
      functions: {
        'risk.coerceScore': {
          name: 'risk.coerceScore',
          description: 'Normalizes optional score inputs.',
          category: 'risk',
          signatures: [
            '(score: number) -> number',
            '(score?: string) -> number',
          ],
          examples: ['risk.coerceScore(ctx.score)'],
          tests: [{ ref: 'fixtures/coerce-score' }],
        },
      },
      imports: [],
      examples: {},
    },
  },
];
