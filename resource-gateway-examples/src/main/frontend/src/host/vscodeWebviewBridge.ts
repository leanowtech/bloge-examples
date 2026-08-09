import {
  resetBlogeApiTransport,
  setBlogeApiTransport,
  type BlogeApiTransport,
} from '../api';
import {
  resetWorkspaceRecoveryStore,
  setWorkspaceRecoveryStore,
  type RecoveryCoordinate,
  type WorkspaceRecoveryStore,
} from '../author/continuity/workspaceContinuity';
import { prepareHostDisposal } from './hostLifecycle';

const REQUEST_SCHEMA = 'bloge.vscodeWebviewRequest.v1';
const RESPONSE_SCHEMA = 'bloge.vscodeWebviewResponse.v1';
const DISPOSE_SCHEMA = 'bloge.vscodeHostWillDispose.v1';
const DISPOSE_RECEIPT_SCHEMA = 'bloge.vscodeHostDisposalReceipt.v1';

type HostOperation = 'FETCH' | 'RECOVERY_LOAD' | 'RECOVERY_SAVE' | 'RECOVERY_REMOVE';

interface VsCodeApi {
  postMessage(message: unknown): void;
}

interface HostRequest {
  schemaVersion: typeof REQUEST_SCHEMA;
  requestId: string;
  operation: HostOperation;
  payload: unknown;
}

interface HostResponse {
  schemaVersion: typeof RESPONSE_SCHEMA;
  requestId: string;
  ok: boolean;
  payload?: unknown;
  errorCode?: string;
}

interface HostDisposeRequest {
  schemaVersion: typeof DISPOSE_SCHEMA;
  requestId: string;
}

interface FetchResponsePayload {
  status: number;
  statusText?: string;
  headers?: Record<string, string>;
  body?: string;
}

interface PendingRequest {
  resolve(value: unknown): void;
  reject(cause: Error): void;
  timeout: number;
}

declare global {
  var acquireVsCodeApi: undefined | (() => VsCodeApi);
}

export class VsCodeWebviewBridge {
  readonly recoveryStore: WorkspaceRecoveryStore;
  readonly transport: BlogeApiTransport;
  private readonly pending = new Map<string, PendingRequest>();
  private readonly onMessage: (event: MessageEvent<unknown>) => void;

  constructor(
    private readonly vscode: VsCodeApi,
    private readonly target: Window = window,
    private readonly timeoutMs = 10_000,
  ) {
    this.transport = (input, init) => this.fetch(input, init);
    this.recoveryStore = {
      security: 'HOST_ENCRYPTED',
      load: async (coordinate) => {
        const payload = await this.request('RECOVERY_LOAD', { coordinate });
        return readNullableString(payload, 'serializedEnvelope');
      },
      save: async (coordinate, serializedEnvelope) => {
        await this.request('RECOVERY_SAVE', { coordinate, serializedEnvelope });
      },
      remove: async (coordinate) => {
        await this.request('RECOVERY_REMOVE', { coordinate });
      },
    };
    this.onMessage = (event) => this.receive(event.data);
    this.target.addEventListener('message', this.onMessage);
  }

  dispose(): void {
    this.target.removeEventListener('message', this.onMessage);
    for (const pending of this.pending.values()) {
      this.target.clearTimeout(pending.timeout);
      pending.reject(new Error('RG.HOST.BRIDGE.DISPOSED'));
    }
    this.pending.clear();
  }

  private async fetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
    const url = input instanceof Request
      ? input.url
      : new URL(String(input), this.target.location.href).toString();
    const request = new Request(url, input instanceof Request ? { ...requestInitFrom(input), ...init } : init);
    const body = request.method === 'GET' || request.method === 'HEAD'
      ? null
      : await request.clone().text();
    const payload = await this.request('FETCH', {
      url: request.url,
      method: request.method,
      headers: Object.fromEntries(request.headers.entries()),
      body,
    });
    const response = readFetchResponse(payload);
    return new Response(response.body ?? null, {
      status: response.status,
      statusText: response.statusText,
      headers: response.headers,
    });
  }

  private request(operation: HostOperation, payload: unknown): Promise<unknown> {
    const requestId = createRequestId();
    const message: HostRequest = {
      schemaVersion: REQUEST_SCHEMA,
      requestId,
      operation,
      payload,
    };
    return new Promise((resolve, reject) => {
      const timeout = this.target.setTimeout(() => {
        this.pending.delete(requestId);
        reject(new Error('RG.HOST.REQUEST.TIMEOUT'));
      }, this.timeoutMs);
      this.pending.set(requestId, { resolve, reject, timeout });
      this.vscode.postMessage(message);
    });
  }

  private receive(message: unknown): void {
    if (isHostResponse(message)) {
      const pending = this.pending.get(message.requestId);
      if (!pending) return;
      this.pending.delete(message.requestId);
      this.target.clearTimeout(pending.timeout);
      if (message.ok) pending.resolve(message.payload);
      else pending.reject(new Error(message.errorCode || 'RG.HOST.REQUEST.FAILED'));
      return;
    }
    if (isHostDisposeRequest(message)) {
      void prepareHostDisposal(this.target, this.timeoutMs).then((preparation) => {
        this.vscode.postMessage({
          schemaVersion: DISPOSE_RECEIPT_SCHEMA,
          requestId: message.requestId,
          ...preparation,
        });
      });
    }
  }
}

/** Installs host transport and encrypted recovery only inside an actual VS Code WebView. */
export function installVsCodeWebviewBridge(): VsCodeWebviewBridge | null {
  if (typeof globalThis.acquireVsCodeApi !== 'function') return null;
  const bridge = new VsCodeWebviewBridge(globalThis.acquireVsCodeApi());
  setBlogeApiTransport(bridge.transport);
  setWorkspaceRecoveryStore(bridge.recoveryStore);
  return bridge;
}

export function uninstallVsCodeWebviewBridge(bridge: VsCodeWebviewBridge): void {
  bridge.dispose();
  resetBlogeApiTransport();
  resetWorkspaceRecoveryStore();
}

function requestInitFrom(request: Request): RequestInit {
  return {
    method: request.method,
    headers: request.headers,
    body: request.body,
    cache: request.cache,
    credentials: request.credentials,
    integrity: request.integrity,
    keepalive: request.keepalive,
    mode: request.mode,
    redirect: request.redirect,
    referrer: request.referrer,
    referrerPolicy: request.referrerPolicy,
    signal: request.signal,
  };
}

function isHostResponse(value: unknown): value is HostResponse {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<HostResponse>;
  return candidate.schemaVersion === RESPONSE_SCHEMA
    && typeof candidate.requestId === 'string'
    && typeof candidate.ok === 'boolean';
}

function isHostDisposeRequest(value: unknown): value is HostDisposeRequest {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<HostDisposeRequest>;
  return candidate.schemaVersion === DISPOSE_SCHEMA && typeof candidate.requestId === 'string';
}

function readFetchResponse(value: unknown): FetchResponsePayload {
  if (!value || typeof value !== 'object') throw new Error('RG.HOST.RESPONSE.INVALID');
  const response = value as Partial<FetchResponsePayload>;
  if (!Number.isInteger(response.status) || (response.status ?? 0) < 200 || (response.status ?? 0) > 599) {
    throw new Error('RG.HOST.RESPONSE.INVALID_STATUS');
  }
  return response as FetchResponsePayload;
}

function readNullableString(value: unknown, key: string): string | null {
  if (!value || typeof value !== 'object') throw new Error('RG.HOST.RESPONSE.INVALID');
  const candidate = (value as Record<string, unknown>)[key];
  if (candidate === null || typeof candidate === 'string') return candidate;
  throw new Error('RG.HOST.RESPONSE.INVALID');
}

function createRequestId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `host-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export type { HostRequest, HostResponse, RecoveryCoordinate };
