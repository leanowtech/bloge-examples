'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const { EncryptedRecoveryStore } = require('./encryptedRecoveryStore');
const { createGatewayFetchHandler, createHostMessageRouter } = require('./hostRouter');
const {
  DISPOSE_SCHEMA,
  parseDisposalReceipt,
  protocolError,
} = require('./protocol');
const { buildWebviewHtml } = require('./webviewHtml');

const PANEL_TYPE = 'resourceGateway.authoring';
const TOKEN_SECRET = 'resourceGatewayAuthoring.remoteToken.v1';

class ResourceGatewayPanelController {
  constructor(vscode, context, outputChannel) {
    this.vscode = vscode;
    this.context = context;
    this.outputChannel = outputChannel;
    this.current = null;
    this.pendingDisposal = null;
  }

  open() {
    if (this.current) {
      this.current.reveal(this.vscode.ViewColumn.Active);
      return this.current;
    }
    const webviewRoot = this.vscode.Uri.joinPath(this.context.extensionUri, 'media', 'webview');
    const panel = this.vscode.window.createWebviewPanel(
      PANEL_TYPE,
      'Resource Gateway Author',
      this.vscode.ViewColumn.Active,
      {
        enableScripts: true,
        enableFindWidget: true,
        retainContextWhenHidden: true,
        localResourceRoots: [webviewRoot],
      },
    );
    this.attach(panel);
    return panel;
  }

  deserialize(panel) {
    this.attach(panel);
  }

  adopt(panel) {
    const superseded = this.current;
    this.current = panel;
    if (superseded && superseded !== panel) {
      superseded.dispose();
    }
  }

  attach(panel) {
    this.adopt(panel);
    const startedAt = Date.now();
    const indexPath = path.join(this.context.extensionPath, 'media', 'webview', 'index.html');
    if (!fs.existsSync(indexPath)) {
      panel.webview.html = missingBuildHtml();
      this.vscode.window.showErrorMessage(
        'Resource Gateway WebView assets are missing. Run npm run prepare:webview in vscode-extension.',
      );
      return;
    }
    panel.webview.html = buildWebviewHtml({
      indexHtml: fs.readFileSync(indexPath, 'utf8'),
      webview: panel.webview,
      extensionUri: this.context.extensionUri,
      vscode: this.vscode,
    });
    const recoveryRoot = this.vscode.Uri.joinPath(this.context.globalStorageUri, 'recovery').fsPath;
    const recoveryStore = new EncryptedRecoveryStore({
      secretStorage: this.context.secrets,
      storagePath: recoveryRoot,
    });
    const configuration = this.vscode.workspace.getConfiguration('resourceGatewayAuthoring');
    let fetchHandler;
    try {
      fetchHandler = createGatewayFetchHandler({
        remoteBaseUrl: configuration.get('remoteBaseUrl', ''),
        workspaceTrusted: this.vscode.workspace.isTrusted,
        allowAdminProxy: configuration.get('allowAdminProxy', false),
        requestTimeoutMs: configuration.get('requestTimeoutMs', 10_000),
        tokenProvider: () => this.context.secrets.get(TOKEN_SECRET),
      });
    } catch (cause) {
      const errorCode = cause && cause.code ? cause.code : 'RG.HOST.REMOTE_BASE.INVALID';
      fetchHandler = async () => ({
        status: 503,
        statusText: errorCode,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: errorCode }),
      });
      this.vscode.window.showErrorMessage(`Resource Gateway host configuration rejected: ${errorCode}`);
    }
    const router = createHostMessageRouter({
      postMessage: async (message) => {
        const delivered = await panel.webview.postMessage(message);
        if (!delivered) throw protocolError('RG.HOST.WEBVIEW.UNAVAILABLE');
      },
      recoveryStore,
      fetchHandler,
      onReady: ({ route }) => {
        const coldStartMs = Math.max(0, Date.now() - startedAt);
        this.outputChannel.appendLine(`workspace-ready route=${route} coldStartMs=${coldStartMs}`);
        this.vscode.window.setStatusBarMessage(
          `Resource Gateway ready in ${coldStartMs} ms`,
          3_000,
        );
      },
    });
    panel.webview.onDidReceiveMessage((message) => {
      if (this.receiveDisposalReceipt(message)) return;
      void router(message);
    });
    panel.onDidDispose(() => {
      if (this.current === panel) this.current = null;
      if (this.pendingDisposal) {
        this.pendingDisposal.reject(protocolError('RG.HOST.PANEL.DISPOSED'));
        this.clearPendingDisposal();
      }
    });
  }

  async closeSafely(timeoutMs = 10_000) {
    const panel = this.current;
    if (!panel) return true;
    if (this.pendingDisposal) return false;
    const requestId = `dispose-${crypto.randomUUID()}`;
    const receipt = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(protocolError('RG.HOST.DISPOSAL.TIMEOUT'));
        this.clearPendingDisposal();
      }, Math.max(1_000, Math.min(60_000, timeoutMs)));
      this.pendingDisposal = { requestId, resolve, reject, timer };
    });
    let delivered = false;
    try {
      delivered = await panel.webview.postMessage({ schemaVersion: DISPOSE_SCHEMA, requestId });
    } catch {
      delivered = false;
    }
    if (!delivered) {
      this.pendingDisposal.resolve({ ready: false, failureCount: 1, timedOut: false });
      this.clearPendingDisposal();
      await receipt;
      return false;
    }
    try {
      const result = await receipt;
      if (!result.ready) {
        this.vscode.window.showWarningMessage(
          `Resource Gateway remained open because recovery failed (${result.failureCount} failure).`,
        );
        return false;
      }
      panel.dispose();
      return true;
    } catch (cause) {
      this.vscode.window.showWarningMessage(
        `Resource Gateway remained open: ${cause && cause.code ? cause.code : 'RG.HOST.DISPOSAL.FAILED'}`,
      );
      return false;
    } finally {
      this.clearPendingDisposal();
    }
  }

  receiveDisposalReceipt(message) {
    if (!this.pendingDisposal) return false;
    const receipt = parseDisposalReceipt(message, this.pendingDisposal.requestId);
    if (!receipt) return false;
    this.pendingDisposal.resolve(receipt);
    return true;
  }

  clearPendingDisposal() {
    if (!this.pendingDisposal) return;
    clearTimeout(this.pendingDisposal.timer);
    this.pendingDisposal = null;
  }
}

function missingBuildHtml() {
  return '<!doctype html><html><body><h1>Resource Gateway assets are missing</h1>'
    + '<p>Run <code>npm run prepare:webview</code> in the extension directory.</p></body></html>';
}

module.exports = { PANEL_TYPE, ResourceGatewayPanelController, TOKEN_SECRET };
