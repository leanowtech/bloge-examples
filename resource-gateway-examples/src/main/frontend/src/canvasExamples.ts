import type { DraftNodeBinding } from './types';

export interface CanvasExampleNode {
  id: string;
  operatorRef: string;
  label: string;
  position: { x: number; y: number };
  inputs?: Record<string, DraftNodeBinding>;
  config?: Record<string, unknown>;
  fixtureOutput?: unknown;
  expectedInput?: unknown;
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
  label: string;
  domain: string;
  description: string;
  pattern: string;
  nodes: CanvasExampleNode[];
  edges: CanvasExampleEdge[];
  outputNodeId: string;
}

export interface CanvasExampleAvailability {
  template: CanvasExampleTemplate;
  missingOperatorRefs: string[];
}

function constantInput(value: unknown, targetPort: string): DraftNodeBinding {
  return { kind: 'constant', value, targetPort };
}

export const CANVAS_EXAMPLE_TEMPLATES: CanvasExampleTemplate[] = [
  {
    key: 'loan-policy-fallback',
    label: 'Loan policy fallback',
    domain: 'Risk',
    description: 'Applicant profile, dual credit providers, policy rules, and final response mapping.',
    pattern: 'Fan-out / decision / transform',
    outputNodeId: 'n5',
    nodes: [
      {
        id: 'n1',
        operatorRef: 'resource:loan-applicant-service.getProfile',
        label: 'Fetch applicant',
        position: { x: 72, y: 96 },
        inputs: { applicantId: constantInput('applicant-1001', 'params') },
        fixtureOutput: {
          applicantId: 'applicant-1001',
          score: 715,
          segment: 'prime',
          income: 92000,
          employmentYears: 4,
        },
      },
      {
        id: 'n2',
        operatorRef: 'resource:credit-provider.primary',
        label: 'Primary credit',
        position: { x: 360, y: 24 },
        fixtureOutput: { score: 728, provider: 'primary', band: 'A' },
      },
      {
        id: 'n3',
        operatorRef: 'resource:credit-provider.secondary',
        label: 'Secondary credit',
        position: { x: 360, y: 168 },
        fixtureOutput: { score: 701, provider: 'secondary', band: 'B' },
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
              conditions: { score: 'score >= 680', employmentYears: 'employmentYears >= 2' },
              output: { decision: 'manual_review', tier: 'standard', reason: 'borderline credit' },
            },
            {
              otherwise: true,
              output: { decision: 'decline', tier: 'risk', reason: 'policy threshold not met' },
            },
          ],
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
            segment: 'n1.output.payload.segment',
            primaryScore: 'n2.output.payload.score',
            secondaryScore: 'n3.output.payload.score',
            decision: 'n4.output.decision',
            tier: 'n4.output.tier',
            reason: 'n4.output.reason',
          },
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
  },
  {
    key: 'order-fulfillment-lane',
    label: 'Order fulfillment lane',
    domain: 'Commerce',
    description: 'Order list, item enrichment, shipping quote, SLA decision, and customer response.',
    pattern: 'List / enrichment / SLA',
    outputNodeId: 'n5',
    nodes: [
      {
        id: 'n1',
        operatorRef: 'resource:order-service.listOrders',
        label: 'Load orders',
        position: { x: 72, y: 96 },
        inputs: { userId: constantInput('user-42', 'params') },
        fixtureOutput: {
          items: [
            { orderId: 'order-1001', productId: 'prod-8', total: 128, region: 'west' },
            { orderId: 'order-1002', productId: 'prod-11', total: 42, region: 'west' },
          ],
          total: 2,
        },
      },
      {
        id: 'n2',
        operatorRef: '__foreach__:enrichOrders',
        label: 'Enrich order items',
        position: { x: 360, y: 24 },
        fixtureOutput: [
          { orderId: 'order-1001', productId: 'prod-8', priority: 'expedite' },
          { orderId: 'order-1002', productId: 'prod-11', priority: 'standard' },
        ],
      },
      {
        id: 'n3',
        operatorRef: 'resource:logistics-service.getShipping',
        label: 'Shipping quote',
        position: { x: 360, y: 168 },
        fixtureOutput: { carrier: 'DHL', etaDays: 2, cost: 16.5 },
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
              conditions: { etaDays: 'etaDays <= 5' },
              output: { lane: 'standard', promisedHours: 72, reason: 'normal delivery window' },
            },
            {
              otherwise: true,
              output: { lane: 'manual_review', promisedHours: 96, reason: 'shipping exception' },
            },
          ],
        },
      },
      {
        id: 'n5',
        operatorRef: 'bloge:transform',
        label: 'Fulfillment response',
        position: { x: 940, y: 96 },
        config: {
          assignments: {
            orderCount: 'n1.output.payload.total',
            enrichedOrders: 'n2.output',
            carrier: 'n3.output.payload.carrier',
            lane: 'n4.output.lane',
            promisedHours: 'n4.output.promisedHours',
            reason: 'n4.output.reason',
          },
        },
      },
    ],
    edges: [
      {
        id: 'n1:payload.items->n2:input',
        source: 'n1',
        target: 'n2',
        sourcePort: 'payload',
        sourcePath: 'items',
        targetPort: 'input',
        bindingKey: 'input',
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
        id: 'n2:output->n5:inputs.enrichedOrders',
        source: 'n2',
        target: 'n5',
        sourcePort: 'output',
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
  },
  {
    key: 'personalized-dashboard',
    label: 'Personalized dashboard',
    domain: 'Experience',
    description: 'Profile fan-out into wallet, recommendations, notifications, and a final dashboard view.',
    pattern: 'Aggregator / fan-out',
    outputNodeId: 'n5',
    nodes: [
      {
        id: 'n1',
        operatorRef: 'resource:user-service.getProfile',
        label: 'User profile',
        position: { x: 72, y: 96 },
        inputs: { userId: constantInput('user-42', 'params') },
        fixtureOutput: {
          userId: 'user-42',
          name: 'Ada Chen',
          tier: 'pro',
          segment: 'high-value',
          score: 742,
        },
      },
      {
        id: 'n2',
        operatorRef: 'resource:wallet-service.getBalance',
        label: 'Wallet balance',
        position: { x: 360, y: 0 },
        fixtureOutput: { amount: 128.45, currency: 'USD' },
      },
      {
        id: 'n3',
        operatorRef: 'resource:recommendation-service.forUser',
        label: 'Recommendations',
        position: { x: 360, y: 132 },
        fixtureOutput: {
          items: [
            { productId: 'prod-8', reason: 'segment match' },
            { productId: 'prod-11', reason: 'recent activity' },
          ],
        },
      },
      {
        id: 'n4',
        operatorRef: 'resource:notification-service.unread',
        label: 'Unread notifications',
        position: { x: 360, y: 264 },
        fixtureOutput: {
          count: 3,
          items: [
            { id: 'notif-1', title: 'Invoice ready' },
            { id: 'notif-2', title: 'New recommendation' },
          ],
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
            name: 'n1.output.payload.name',
            tier: 'n1.output.payload.tier',
            segment: 'n1.output.payload.segment',
            walletAmount: 'n2.output.payload.amount',
            walletCurrency: 'n2.output.payload.currency',
            recommendations: 'n3.output.payload.items',
            unreadCount: 'n4.output.payload.count',
          },
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
  },
];

export function exampleRequiredOperatorRefs(template: CanvasExampleTemplate): string[] {
  return Array.from(new Set(template.nodes.map((node) => node.operatorRef)));
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

