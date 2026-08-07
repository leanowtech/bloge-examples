import type {
  DraftNodeBinding,
  NodeFixture,
  OperatorDefinition,
  OperatorPort,
  SchemaEnvelope,
} from './types';

export interface CanvasExampleNode {
  id: string;
  operatorRef: string;
  label: string;
  position: { x: number; y: number };
  inputs?: Record<string, DraftNodeBinding>;
  config?: Record<string, unknown>;
  fixtureOutput?: unknown;
  expectedInput?: unknown;
  operatorTestInput?: unknown;
  operatorTestExpectedOutput?: unknown;
}

export interface CanvasExampleEdge {
  id: string;
  source: string;
  target: string;
  sourcePort: string;
  targetPort: string;
  sourcePath?: string;
  targetPath?: string;
  bindingKey?: string;
}

export interface CanvasExampleTemplate {
  key: string;
  graphName: string;
  label: string;
  domain: string;
  description: string;
  pattern: string;
  inputSchema: SchemaEnvelope;
  outputSchema: SchemaEnvelope;
  nodes: CanvasExampleNode[];
  edges: CanvasExampleEdge[];
  outputNodeId: string;
  testCases?: CanvasExampleTestCase[];
}

export interface CanvasExampleAvailability {
  template: CanvasExampleTemplate;
  missingOperatorRefs: string[];
  incompatibleContractPaths: string[];
}

export interface CanvasExampleTestCase {
  id: string;
  name: string;
  caseType: 'GOLDEN' | 'NEGATIVE' | 'BOUNDARY' | 'REGRESSION';
  context: Record<string, unknown>;
  fixtureOverrides?: Record<string, NodeFixture>;
  expectedOutput: unknown;
}

function constantInput(value: unknown, targetPort: string): DraftNodeBinding {
  return { kind: 'constant', value, targetPort };
}

function jsonSchema(schema: Record<string, unknown>): SchemaEnvelope {
  return {
    format: 'json-schema',
    version: '2020-12',
    schema,
  };
}

function objectSchema(
  properties: Record<string, Record<string, unknown>>,
  required: string[] = Object.keys(properties),
  additionalProperties = false,
): Record<string, unknown> {
  return {
    type: 'object',
    properties,
    required,
    additionalProperties,
  };
}

function arrayOf(items: Record<string, unknown>): Record<string, unknown> {
  return { type: 'array', items };
}

const stringSchema = { type: 'string' };
const numberSchema = { type: 'number' };
const integerSchema = { type: 'integer' };

export const CANVAS_EXAMPLE_TEMPLATES: CanvasExampleTemplate[] = [
  {
    key: 'loan-policy-fallback',
    graphName: 'loanPolicyFallbackExample',
    label: 'Loan policy fallback',
    domain: 'Risk',
    description: 'Applicant profile, dual credit providers, policy rules, and final response mapping.',
    pattern: 'Fan-out / decision / transform',
    inputSchema: jsonSchema(objectSchema({
      applicantId: stringSchema,
    })),
    outputSchema: jsonSchema(objectSchema({
      applicantId: stringSchema,
      segment: stringSchema,
      primaryScore: integerSchema,
      secondaryScore: integerSchema,
      decision: stringSchema,
      tier: stringSchema,
      reason: stringSchema,
    })),
    outputNodeId: 'n5',
    nodes: [
      {
        id: 'n1',
        operatorRef: 'resource:loan-applicant-service.getProfile',
        label: 'Fetch applicant',
        position: { x: 72, y: 96 },
        inputs: { applicantId: constantInput('applicant-1001', 'params') },
        expectedInput: { params: { applicantId: 'applicant-1001' } },
        fixtureOutput: {
          payload: {
            applicantId: 'applicant-1001',
            score: 715,
            segment: 'prime',
            income: 92000,
            employmentYears: 4,
          },
        },
      },
      {
        id: 'n2',
        operatorRef: 'resource:credit-provider.primary',
        label: 'Primary credit',
        position: { x: 360, y: 24 },
        expectedInput: { params: { userId: 'applicant-1001' } },
        fixtureOutput: { payload: { score: 728, provider: 'primary', band: 'A' } },
      },
      {
        id: 'n3',
        operatorRef: 'resource:credit-provider.secondary',
        label: 'Secondary credit',
        position: { x: 360, y: 168 },
        expectedInput: { params: { userId: 'applicant-1001' } },
        fixtureOutput: { payload: { score: 701, provider: 'secondary', band: 'B' } },
      },
      {
        id: 'n4',
        operatorRef: 'bloge:decisionTable',
        label: 'Policy decision',
        position: { x: 650, y: 96 },
        config: {
          hitPolicy: 'unique',
          outputType: '{ decision: String, tier: String, reason: String }',
          conditionColumns: ['score', 'income', 'employmentYears'],
          outputColumns: ['decision', 'tier', 'reason'],
          rules: [
            {
              conditions: { score: 'score >= 720', income: 'income >= 80000' },
              output: { decision: 'approve', tier: 'prime', reason: 'strong primary credit' },
            },
            {
              conditions: { score: 'score >= 680 && score < 720', employmentYears: 'employmentYears >= 2' },
              output: { decision: 'manual_review', tier: 'standard', reason: 'borderline credit' },
            },
            {
              otherwise: true,
              output: { decision: 'decline', tier: 'risk', reason: 'policy threshold not met' },
            },
          ],
        },
        operatorTestInput: {
          inputs: { score: 728, income: 92000, employmentYears: 4 },
        },
        operatorTestExpectedOutput: {
          decision: 'approve',
          tier: 'prime',
          reason: 'strong primary credit',
        },
      },
      {
        id: 'n5',
        operatorRef: 'bloge:transform',
        label: 'Decision response',
        position: { x: 940, y: 96 },
        config: {
          assignments: {
            applicantId: 'n1.output.payload.applicantId',
            segment: 'coalesce(n1.output.payload.segment, "unknown")',
            primaryScore: 'toNumber(coalesce(n2.output.payload.score, 0))',
            secondaryScore: 'toNumber(coalesce(n3.output.payload.score, 0))',
            decision: 'n4.output.decision',
            tier: 'n4.output.tier',
            reason: 'coalesce(n4.output.reason, "policy fallback")',
          },
        },
        operatorTestInput: {
          inputs: {
            applicantId: 'applicant-1001',
            segment: 'prime',
            primaryScore: 728,
            secondaryScore: 701,
            decision: 'approve',
            tier: 'prime',
            reason: 'strong primary credit',
          },
        },
        operatorTestExpectedOutput: {
          applicantId: 'applicant-1001',
          segment: 'prime',
          primaryScore: 728,
          secondaryScore: 701,
          decision: 'approve',
          tier: 'prime',
          reason: 'strong primary credit',
        },
      },
    ],
    edges: [
      {
        id: 'n1:payload.applicantId->n2:params.userId',
        source: 'n1',
        target: 'n2',
        sourcePort: 'payload',
        sourcePath: 'applicantId',
        targetPort: 'params',
        targetPath: 'userId',
        bindingKey: 'userId',
      },
      {
        id: 'n1:payload.applicantId->n3:params.userId',
        source: 'n1',
        target: 'n3',
        sourcePort: 'payload',
        sourcePath: 'applicantId',
        targetPort: 'params',
        targetPath: 'userId',
        bindingKey: 'userId',
      },
      {
        id: 'n2:payload.score->n4:inputs.score',
        source: 'n2',
        target: 'n4',
        sourcePort: 'payload',
        sourcePath: 'score',
        targetPort: 'inputs',
        targetPath: 'score',
        bindingKey: 'score',
      },
      {
        id: 'n1:payload.income->n4:inputs.income',
        source: 'n1',
        target: 'n4',
        sourcePort: 'payload',
        sourcePath: 'income',
        targetPort: 'inputs',
        targetPath: 'income',
        bindingKey: 'income',
      },
      {
        id: 'n1:payload.employmentYears->n4:inputs.employmentYears',
        source: 'n1',
        target: 'n4',
        sourcePort: 'payload',
        sourcePath: 'employmentYears',
        targetPort: 'inputs',
        targetPath: 'employmentYears',
        bindingKey: 'employmentYears',
      },
      {
        id: 'n1:payload.applicantId->n5:inputs.applicantId',
        source: 'n1',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'applicantId',
        targetPort: 'inputs',
        targetPath: 'applicantId',
        bindingKey: 'applicantId',
      },
      {
        id: 'n1:payload.segment->n5:inputs.segment',
        source: 'n1',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'segment',
        targetPort: 'inputs',
        targetPath: 'segment',
        bindingKey: 'segment',
      },
      {
        id: 'n2:payload.score->n5:inputs.primaryScore',
        source: 'n2',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'score',
        targetPort: 'inputs',
        targetPath: 'primaryScore',
        bindingKey: 'primaryScore',
      },
      {
        id: 'n3:payload.score->n5:inputs.secondaryScore',
        source: 'n3',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'score',
        targetPort: 'inputs',
        targetPath: 'secondaryScore',
        bindingKey: 'secondaryScore',
      },
      {
        id: 'n4:output.decision->n5:inputs.decision',
        source: 'n4',
        target: 'n5',
        sourcePort: 'output',
        sourcePath: 'decision',
        targetPort: 'inputs',
        targetPath: 'decision',
        bindingKey: 'decision',
      },
      {
        id: 'n4:output.tier->n5:inputs.tier',
        source: 'n4',
        target: 'n5',
        sourcePort: 'output',
        sourcePath: 'tier',
        targetPort: 'inputs',
        targetPath: 'tier',
        bindingKey: 'tier',
      },
      {
        id: 'n4:output.reason->n5:inputs.reason',
        source: 'n4',
        target: 'n5',
        sourcePort: 'output',
        sourcePath: 'reason',
        targetPort: 'inputs',
        targetPath: 'reason',
        bindingKey: 'reason',
      },
    ],
    testCases: [
      {
        id: 'loan-prime-approval',
        name: 'Prime approval path',
        caseType: 'GOLDEN',
        context: { applicantId: 'applicant-1001' },
        expectedOutput: {
          applicantId: 'applicant-1001',
          segment: 'prime',
          primaryScore: 728,
          secondaryScore: 701,
          decision: 'approve',
          tier: 'prime',
          reason: 'strong primary credit',
        },
      },
      {
        id: 'loan-policy-decline',
        name: 'Policy decline path',
        caseType: 'NEGATIVE',
        context: { applicantId: 'applicant-2002' },
        fixtureOverrides: {
          n1: {
            output: {
              payload: {
                applicantId: 'applicant-2002',
                score: 662,
                segment: 'watchlist',
                income: 54000,
                employmentYears: 1,
              },
            },
          },
          n2: { output: { payload: { score: 650, provider: 'primary', band: 'C' } } },
          n3: { output: { payload: { score: 688, provider: 'secondary', band: 'B' } } },
        },
        expectedOutput: {
          applicantId: 'applicant-2002',
          segment: 'watchlist',
          primaryScore: 650,
          secondaryScore: 688,
          decision: 'decline',
          tier: 'risk',
          reason: 'policy threshold not met',
        },
      },
      {
        id: 'loan-manual-review-boundary',
        name: 'Score 680 manual-review boundary',
        caseType: 'BOUNDARY',
        context: { applicantId: 'applicant-6800' },
        fixtureOverrides: {
          n1: {
            output: {
              payload: {
                applicantId: 'applicant-6800',
                score: 680,
                segment: 'standard',
                income: 64000,
                employmentYears: 2,
              },
            },
          },
          n2: { output: { payload: { score: 680, provider: 'primary', band: 'B' } } },
          n3: { output: { payload: { score: 679, provider: 'secondary', band: 'C' } } },
        },
        expectedOutput: {
          applicantId: 'applicant-6800',
          segment: 'standard',
          primaryScore: 680,
          secondaryScore: 679,
          decision: 'manual_review',
          tier: 'standard',
          reason: 'borderline credit',
        },
      },
    ],
  },
  {
    key: 'order-fulfillment-lane',
    graphName: 'orderFulfillmentLaneExample',
    label: 'Order fulfillment lane',
    domain: 'Commerce',
    description: 'Order list, item enrichment, shipping quote, SLA decision, and customer response.',
    pattern: 'List / enrichment / SLA',
    inputSchema: jsonSchema(objectSchema({
      userId: stringSchema,
    })),
    outputSchema: jsonSchema(objectSchema({
      orderCount: integerSchema,
      enrichedOrders: arrayOf(objectSchema({
        orderId: stringSchema,
        productId: stringSchema,
        priority: stringSchema,
      }, ['orderId', 'productId', 'priority'], true)),
      carrier: stringSchema,
      lane: stringSchema,
      promisedHours: integerSchema,
      reason: stringSchema,
    })),
    outputNodeId: 'n5',
    nodes: [
      {
        id: 'n1',
        operatorRef: 'resource:order-service.listOrders',
        label: 'Load orders',
        position: { x: 72, y: 96 },
        inputs: { userId: constantInput('user-42', 'params') },
        expectedInput: { params: { userId: 'user-42' } },
        fixtureOutput: {
          payload: {
            items: [
              { orderId: 'order-1001', productId: 'prod-8', total: 128, region: 'west' },
              { orderId: 'order-1002', productId: 'prod-11', total: 42, region: 'west' },
            ],
            total: 2,
          },
        },
      },
      {
        id: 'n2',
        operatorRef: 'bloge:transform',
        label: 'Enrich order items',
        position: { x: 360, y: 24 },
        config: {
          assignments: {
            items: 'coalesce(inputs.items, [])',
          },
        },
        fixtureOutput: {
          items: [
            { orderId: 'order-1001', productId: 'prod-8', priority: 'expedite' },
            { orderId: 'order-1002', productId: 'prod-11', priority: 'standard' },
          ],
        },
        operatorTestInput: {
          inputs: {
            items: [
              { orderId: 'order-1001', productId: 'prod-8', priority: 'expedite' },
              { orderId: 'order-1002', productId: 'prod-11', priority: 'standard' },
            ],
          },
        },
        operatorTestExpectedOutput: {
          items: [
            { orderId: 'order-1001', productId: 'prod-8', priority: 'expedite' },
            { orderId: 'order-1002', productId: 'prod-11', priority: 'standard' },
          ],
        },
      },
      {
        id: 'n3',
        operatorRef: 'resource:logistics-service.getShipping',
        label: 'Shipping quote',
        position: { x: 360, y: 168 },
        expectedInput: { params: { orderId: 'order-1001' } },
        fixtureOutput: { payload: { carrier: 'DHL', etaDays: 2, cost: 16.5 } },
      },
      {
        id: 'n4',
        operatorRef: 'bloge:decisionTable',
        label: 'SLA lane',
        position: { x: 650, y: 96 },
        config: {
          hitPolicy: 'unique',
          outputType: '{ lane: String, promisedHours: Integer, reason: String }',
          conditionColumns: ['total', 'etaDays'],
          outputColumns: ['lane', 'promisedHours', 'reason'],
          rules: [
            {
              conditions: { total: 'total >= 2', etaDays: 'etaDays <= 2' },
              output: { lane: 'expedite', promisedHours: 24, reason: 'multi-order fast lane' },
            },
            {
              conditions: { total: 'total < 2', etaDays: 'etaDays <= 5' },
              output: { lane: 'standard', promisedHours: 72, reason: 'normal delivery window' },
            },
            {
              otherwise: true,
              output: { lane: 'manual_review', promisedHours: 96, reason: 'shipping exception' },
            },
          ],
        },
        operatorTestInput: {
          inputs: { total: 2, etaDays: 2 },
        },
        operatorTestExpectedOutput: {
          lane: 'expedite',
          promisedHours: 24,
          reason: 'multi-order fast lane',
        },
      },
      {
        id: 'n5',
        operatorRef: 'bloge:transform',
        label: 'Fulfillment response',
        position: { x: 940, y: 96 },
        config: {
          assignments: {
            orderCount: 'toNumber(coalesce(n1.output.payload.total, 0))',
            enrichedOrders: 'coalesce(n2.output.items, [])',
            carrier: 'coalesce(n3.output.payload.carrier, "manual")',
            lane: 'n4.output.lane',
            promisedHours: 'toNumber(coalesce(n4.output.promisedHours, 96))',
            reason: 'coalesce(n4.output.reason, "sla default")',
          },
        },
        operatorTestInput: {
          inputs: {
            orderCount: 2,
            enrichedOrders: [
              { orderId: 'order-1001', productId: 'prod-8', priority: 'expedite' },
              { orderId: 'order-1002', productId: 'prod-11', priority: 'standard' },
            ],
            carrier: 'DHL',
            lane: 'expedite',
            promisedHours: 24,
            reason: 'multi-order fast lane',
          },
        },
        operatorTestExpectedOutput: {
          orderCount: 2,
          enrichedOrders: [
            { orderId: 'order-1001', productId: 'prod-8', priority: 'expedite' },
            { orderId: 'order-1002', productId: 'prod-11', priority: 'standard' },
          ],
          carrier: 'DHL',
          lane: 'expedite',
          promisedHours: 24,
          reason: 'multi-order fast lane',
        },
      },
    ],
    edges: [
      {
        id: 'n1:payload.items->n2:inputs.items',
        source: 'n1',
        target: 'n2',
        sourcePort: 'payload',
        sourcePath: 'items',
        targetPort: 'inputs',
        targetPath: 'items',
        bindingKey: 'items',
      },
      {
        id: 'n1:payload.items.0.orderId->n3:params.orderId',
        source: 'n1',
        target: 'n3',
        sourcePort: 'payload',
        sourcePath: 'items.0.orderId',
        targetPort: 'params',
        targetPath: 'orderId',
        bindingKey: 'orderId',
      },
      {
        id: 'n1:payload.total->n4:inputs.total',
        source: 'n1',
        target: 'n4',
        sourcePort: 'payload',
        sourcePath: 'total',
        targetPort: 'inputs',
        targetPath: 'total',
        bindingKey: 'total',
      },
      {
        id: 'n3:payload.etaDays->n4:inputs.etaDays',
        source: 'n3',
        target: 'n4',
        sourcePort: 'payload',
        sourcePath: 'etaDays',
        targetPort: 'inputs',
        targetPath: 'etaDays',
        bindingKey: 'etaDays',
      },
      {
        id: 'n1:payload.total->n5:inputs.orderCount',
        source: 'n1',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'total',
        targetPort: 'inputs',
        targetPath: 'orderCount',
        bindingKey: 'orderCount',
      },
      {
        id: 'n2:output.items->n5:inputs.enrichedOrders',
        source: 'n2',
        target: 'n5',
        sourcePort: 'output',
        sourcePath: 'items',
        targetPort: 'inputs',
        targetPath: 'enrichedOrders',
        bindingKey: 'enrichedOrders',
      },
      {
        id: 'n3:payload.carrier->n5:inputs.carrier',
        source: 'n3',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'carrier',
        targetPort: 'inputs',
        targetPath: 'carrier',
        bindingKey: 'carrier',
      },
      {
        id: 'n4:output.lane->n5:inputs.lane',
        source: 'n4',
        target: 'n5',
        sourcePort: 'output',
        sourcePath: 'lane',
        targetPort: 'inputs',
        targetPath: 'lane',
        bindingKey: 'lane',
      },
      {
        id: 'n4:output.promisedHours->n5:inputs.promisedHours',
        source: 'n4',
        target: 'n5',
        sourcePort: 'output',
        sourcePath: 'promisedHours',
        targetPort: 'inputs',
        targetPath: 'promisedHours',
        bindingKey: 'promisedHours',
      },
      {
        id: 'n4:output.reason->n5:inputs.reason',
        source: 'n4',
        target: 'n5',
        sourcePort: 'output',
        sourcePath: 'reason',
        targetPort: 'inputs',
        targetPath: 'reason',
        bindingKey: 'reason',
      },
    ],
    testCases: [
      {
        id: 'order-expedite-lane',
        name: 'Multi-order fast lane',
        caseType: 'GOLDEN',
        context: { userId: 'user-42' },
        expectedOutput: {
          orderCount: 2,
          enrichedOrders: [
            { orderId: 'order-1001', productId: 'prod-8', priority: 'expedite' },
            { orderId: 'order-1002', productId: 'prod-11', priority: 'standard' },
          ],
          carrier: 'DHL',
          lane: 'expedite',
          promisedHours: 24,
          reason: 'multi-order fast lane',
        },
      },
      {
        id: 'order-standard-lane',
        name: 'Single-order standard lane',
        caseType: 'NEGATIVE',
        context: { userId: 'user-77' },
        fixtureOverrides: {
          n1: {
            output: {
              payload: {
                items: [
                  { orderId: 'order-2001', productId: 'prod-21', total: 88, region: 'east' },
                ],
                total: 1,
              },
            },
          },
          n2: {
            output: {
              items: [
                { orderId: 'order-2001', productId: 'prod-21', priority: 'standard' },
              ],
            },
          },
          n3: { output: { payload: { carrier: 'UPS', etaDays: 4, cost: 12.2 } } },
        },
        expectedOutput: {
          orderCount: 1,
          enrichedOrders: [
            { orderId: 'order-2001', productId: 'prod-21', priority: 'standard' },
          ],
          carrier: 'UPS',
          lane: 'standard',
          promisedHours: 72,
          reason: 'normal delivery window',
        },
      },
      {
        id: 'order-expedite-boundary',
        name: 'Two orders at two-day SLA boundary',
        caseType: 'BOUNDARY',
        context: { userId: 'user-boundary-2d' },
        expectedOutput: {
          orderCount: 2,
          enrichedOrders: [
            { orderId: 'order-1001', productId: 'prod-8', priority: 'expedite' },
            { orderId: 'order-1002', productId: 'prod-11', priority: 'standard' },
          ],
          carrier: 'DHL',
          lane: 'expedite',
          promisedHours: 24,
          reason: 'multi-order fast lane',
        },
      },
    ],
  },
  {
    key: 'personalized-dashboard',
    graphName: 'personalizedDashboardExample',
    label: 'Personalized dashboard',
    domain: 'Experience',
    description: 'Profile fan-out into wallet, recommendations, notifications, and a final dashboard view.',
    pattern: 'Aggregator / fan-out',
    inputSchema: jsonSchema(objectSchema({
      userId: stringSchema,
    })),
    outputSchema: jsonSchema(objectSchema({
      userId: stringSchema,
      name: stringSchema,
      tier: stringSchema,
      segment: stringSchema,
      walletAmount: numberSchema,
      walletCurrency: stringSchema,
      recommendations: arrayOf(objectSchema({
        productId: stringSchema,
        reason: stringSchema,
      }, ['productId', 'reason'], true)),
      unreadCount: integerSchema,
    })),
    outputNodeId: 'n5',
    nodes: [
      {
        id: 'n1',
        operatorRef: 'resource:user-service.getProfile',
        label: 'User profile',
        position: { x: 72, y: 96 },
        inputs: { userId: constantInput('user-42', 'params') },
        expectedInput: { params: { userId: 'user-42' } },
        fixtureOutput: {
          payload: {
            userId: 'user-42',
            name: 'Ada Chen',
            tier: 'pro',
            segment: 'high-value',
            score: 742,
          },
        },
      },
      {
        id: 'n2',
        operatorRef: 'resource:wallet-service.getBalance',
        label: 'Wallet balance',
        position: { x: 360, y: 0 },
        expectedInput: { params: { userId: 'user-42' } },
        fixtureOutput: { payload: { amount: 128.45, currency: 'USD' } },
      },
      {
        id: 'n3',
        operatorRef: 'resource:recommendation-service.forUser',
        label: 'Recommendations',
        position: { x: 360, y: 132 },
        expectedInput: { params: { userId: 'user-42' } },
        fixtureOutput: {
          payload: {
            items: [
              { productId: 'prod-8', reason: 'segment match' },
              { productId: 'prod-11', reason: 'recent activity' },
            ],
          },
        },
      },
      {
        id: 'n4',
        operatorRef: 'resource:notification-service.unread',
        label: 'Unread notifications',
        position: { x: 360, y: 264 },
        expectedInput: { params: { userId: 'user-42' } },
        fixtureOutput: {
          payload: {
            count: 3,
            items: [
              { id: 'notif-1', title: 'Invoice ready' },
              { id: 'notif-2', title: 'New recommendation' },
            ],
          },
        },
      },
      {
        id: 'n5',
        operatorRef: 'bloge:transform',
        label: 'Dashboard response',
        position: { x: 680, y: 132 },
        config: {
          assignments: {
            userId: 'n1.output.payload.userId',
            name: 'coalesce(n1.output.payload.name, "Customer")',
            tier: 'coalesce(n1.output.payload.tier, "standard")',
            segment: 'coalesce(n1.output.payload.segment, "unknown")',
            walletAmount: 'round(toNumber(coalesce(n2.output.payload.amount, 0)))',
            walletCurrency: 'coalesce(n2.output.payload.currency, "USD")',
            recommendations: 'coalesce(n3.output.payload.items, [])',
            unreadCount: 'toNumber(coalesce(n4.output.payload.count, 0))',
          },
        },
        operatorTestInput: {
          inputs: {
            userId: 'user-42',
            name: 'Ada Chen',
            tier: 'pro',
            segment: 'high-value',
            walletAmount: 128.45,
            walletCurrency: 'USD',
            recommendations: [
              { productId: 'prod-8', reason: 'segment match' },
              { productId: 'prod-11', reason: 'recent activity' },
            ],
            unreadCount: 3,
          },
        },
        operatorTestExpectedOutput: {
          userId: 'user-42',
          name: 'Ada Chen',
          tier: 'pro',
          segment: 'high-value',
          walletAmount: 128,
          walletCurrency: 'USD',
          recommendations: [
            { productId: 'prod-8', reason: 'segment match' },
            { productId: 'prod-11', reason: 'recent activity' },
          ],
          unreadCount: 3,
        },
      },
    ],
    edges: [
      {
        id: 'n1:payload.userId->n2:params.userId',
        source: 'n1',
        target: 'n2',
        sourcePort: 'payload',
        sourcePath: 'userId',
        targetPort: 'params',
        targetPath: 'userId',
        bindingKey: 'userId',
      },
      {
        id: 'n1:payload.userId->n3:params.userId',
        source: 'n1',
        target: 'n3',
        sourcePort: 'payload',
        sourcePath: 'userId',
        targetPort: 'params',
        targetPath: 'userId',
        bindingKey: 'userId',
      },
      {
        id: 'n1:payload.userId->n4:params.userId',
        source: 'n1',
        target: 'n4',
        sourcePort: 'payload',
        sourcePath: 'userId',
        targetPort: 'params',
        targetPath: 'userId',
        bindingKey: 'userId',
      },
      {
        id: 'n1:payload.userId->n5:inputs.userId',
        source: 'n1',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'userId',
        targetPort: 'inputs',
        targetPath: 'userId',
        bindingKey: 'userId',
      },
      {
        id: 'n1:payload.name->n5:inputs.name',
        source: 'n1',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'name',
        targetPort: 'inputs',
        targetPath: 'name',
        bindingKey: 'name',
      },
      {
        id: 'n1:payload.tier->n5:inputs.tier',
        source: 'n1',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'tier',
        targetPort: 'inputs',
        targetPath: 'tier',
        bindingKey: 'tier',
      },
      {
        id: 'n1:payload.segment->n5:inputs.segment',
        source: 'n1',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'segment',
        targetPort: 'inputs',
        targetPath: 'segment',
        bindingKey: 'segment',
      },
      {
        id: 'n2:payload.amount->n5:inputs.walletAmount',
        source: 'n2',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'amount',
        targetPort: 'inputs',
        targetPath: 'walletAmount',
        bindingKey: 'walletAmount',
      },
      {
        id: 'n2:payload.currency->n5:inputs.walletCurrency',
        source: 'n2',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'currency',
        targetPort: 'inputs',
        targetPath: 'walletCurrency',
        bindingKey: 'walletCurrency',
      },
      {
        id: 'n3:payload.items->n5:inputs.recommendations',
        source: 'n3',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'items',
        targetPort: 'inputs',
        targetPath: 'recommendations',
        bindingKey: 'recommendations',
      },
      {
        id: 'n4:payload.count->n5:inputs.unreadCount',
        source: 'n4',
        target: 'n5',
        sourcePort: 'payload',
        sourcePath: 'count',
        targetPort: 'inputs',
        targetPath: 'unreadCount',
        bindingKey: 'unreadCount',
      },
    ],
    testCases: [
      {
        id: 'dashboard-high-value',
        name: 'High-value dashboard',
        caseType: 'GOLDEN',
        context: { userId: 'user-42' },
        expectedOutput: {
          userId: 'user-42',
          name: 'Ada Chen',
          tier: 'pro',
          segment: 'high-value',
          walletAmount: 128,
          walletCurrency: 'USD',
          recommendations: [
            { productId: 'prod-8', reason: 'segment match' },
            { productId: 'prod-11', reason: 'recent activity' },
          ],
          unreadCount: 3,
        },
      },
      {
        id: 'dashboard-fallbacks',
        name: 'Fallback defaults',
        caseType: 'NEGATIVE',
        context: { userId: 'user-99' },
        fixtureOverrides: {
          n1: {
            output: {
              payload: {
                userId: 'user-99',
                name: null,
                tier: null,
                segment: null,
                score: 512,
              },
            },
          },
          n2: { output: { payload: { amount: '99', currency: null } } },
          n3: { output: { payload: { items: [] } } },
          n4: { output: { payload: { count: '0', items: [] } } },
        },
        expectedOutput: {
          userId: 'user-99',
          name: 'Customer',
          tier: 'standard',
          segment: 'unknown',
          walletAmount: 99,
          walletCurrency: 'USD',
          recommendations: [],
          unreadCount: 0,
        },
      },
      {
        id: 'dashboard-zero-state-boundary',
        name: 'Zero balance and zero notification boundary',
        caseType: 'BOUNDARY',
        context: { userId: 'user-zero' },
        fixtureOverrides: {
          n1: {
            output: {
              payload: {
                userId: 'user-zero',
                name: 'New Customer',
                tier: 'starter',
                segment: 'new',
                score: 500,
              },
            },
          },
          n2: { output: { payload: { amount: 0, currency: 'USD' } } },
          n3: { output: { payload: { items: [] } } },
          n4: { output: { payload: { count: 0, items: [] } } },
        },
        expectedOutput: {
          userId: 'user-zero',
          name: 'New Customer',
          tier: 'starter',
          segment: 'new',
          walletAmount: 0,
          walletCurrency: 'USD',
          recommendations: [],
          unreadCount: 0,
        },
      },
    ],
  },
];

export function exampleRequiredOperatorRefs(template: CanvasExampleTemplate): string[] {
  return Array.from(new Set(template.nodes.map((node) => node.operatorRef)));
}

function pathSegments(path: string): string[] {
  return path
    .replace(/^\$\.?/, '')
    .replace(/\[(\d+)]/g, '.$1')
    .split(/[./]/)
    .map((segment) => segment.trim())
    .filter(Boolean);
}

function schemaBranches(schema: Record<string, unknown>): Record<string, unknown>[] {
  return ['oneOf', 'anyOf', 'allOf'].flatMap((key) => {
    const value = schema[key];
    return Array.isArray(value)
      ? value.filter((candidate): candidate is Record<string, unknown> => (
        typeof candidate === 'object' && candidate !== null && !Array.isArray(candidate)
      ))
      : [];
  });
}

function schemaExposesSegments(schema: Record<string, unknown>, segments: string[]): boolean {
  if (segments.length === 0) {
    return true;
  }

  const branches = schemaBranches(schema);
  if (branches.length > 0 && branches.some((branch) => schemaExposesSegments(branch, segments))) {
    return true;
  }

  const [segment, ...rest] = segments;
  if (schema.type === 'array' || schema.items) {
    const items = schema.items;
    if (typeof items !== 'object' || items === null || Array.isArray(items)) {
      return true;
    }
    return schemaExposesSegments(
      items as Record<string, unknown>,
      /^\d+$/.test(segment) ? rest : segments,
    );
  }

  const properties = schema.properties;
  if (typeof properties === 'object' && properties !== null && !Array.isArray(properties)) {
    const propertySchema = (properties as Record<string, unknown>)[segment];
    if (typeof propertySchema === 'object' && propertySchema !== null && !Array.isArray(propertySchema)) {
      return schemaExposesSegments(propertySchema as Record<string, unknown>, rest);
    }
    const additionalProperties = schema.additionalProperties;
    if (additionalProperties === true) {
      return true;
    }
    if (
      typeof additionalProperties === 'object'
      && additionalProperties !== null
      && !Array.isArray(additionalProperties)
    ) {
      return schemaExposesSegments(additionalProperties as Record<string, unknown>, rest);
    }
    return false;
  }

  if (schema.additionalProperties === false) {
    return false;
  }
  return true;
}

function portExposesPath(port: OperatorPort | undefined, path: string | undefined): boolean {
  if (!port || !path?.trim()) {
    return Boolean(port);
  }
  return schemaExposesSegments(port.schema.schema, pathSegments(path));
}

function contractPathLabel(
  node: CanvasExampleNode,
  port: string,
  path: string | undefined,
): string {
  return `${node.label}.${port}${path?.trim() ? `.${path.trim()}` : ''}`;
}

/**
 * Finds example endpoints that the currently loaded operator contracts no longer expose.
 * Missing operators are reported separately so an incomplete catalog does not create duplicate noise.
 */
export function exampleIncompatibleContractPaths(
  template: CanvasExampleTemplate,
  operators: ReadonlyMap<string, OperatorDefinition>,
): string[] {
  const nodes = new Map(template.nodes.map((node) => [node.id, node]));
  const incompatible = new Set<string>();

  for (const edge of template.edges) {
    const sourceNode = nodes.get(edge.source);
    const targetNode = nodes.get(edge.target);
    const sourceOperator = sourceNode ? operators.get(sourceNode.operatorRef) : undefined;
    const targetOperator = targetNode ? operators.get(targetNode.operatorRef) : undefined;

    if (sourceNode && sourceOperator) {
      const sourcePort = sourceOperator.ports?.outputs.find((port) => port.name === edge.sourcePort);
      if (!portExposesPath(sourcePort, edge.sourcePath)) {
        incompatible.add(contractPathLabel(sourceNode, edge.sourcePort, edge.sourcePath));
      }
    }
    if (targetNode && targetOperator) {
      const targetPort = targetOperator.ports?.inputs.find((port) => port.name === edge.targetPort);
      if (!portExposesPath(targetPort, edge.targetPath)) {
        incompatible.add(contractPathLabel(targetNode, edge.targetPort, edge.targetPath));
      }
    }
  }

  return Array.from(incompatible);
}

export function maxNumericNodeId(nodes: CanvasExampleNode[]): number {
  return nodes.reduce((max, node) => {
    const match = /^n(\d+)$/.exec(node.id);
    return match ? Math.max(max, Number(match[1])) : max;
  }, 0);
}

function edgeEndpointLabel(port: string, path: string | undefined, fallback: string): string {
  const base = port || fallback;
  return path ? `${base}.${path}` : base;
}

export function exampleEdgeLabel(edge: CanvasExampleEdge): string {
  return `${edgeEndpointLabel(edge.sourcePort, edge.sourcePath, 'value')} -> ${
    edgeEndpointLabel(edge.targetPort, edge.targetPath, 'input')
  }`;
}

export function hasOwnValue(object: object, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(object, key);
}
