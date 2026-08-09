'use strict';

const vscode = require('vscode');

const {
  PANEL_TYPE,
  ResourceGatewayPanelController,
  TOKEN_SECRET,
} = require('./panelController');

let controller;

function activate(context) {
  const output = vscode.window.createOutputChannel('Resource Gateway Authoring', { log: true });
  controller = new ResourceGatewayPanelController(vscode, context, output);
  context.subscriptions.push(
    output,
    vscode.commands.registerCommand('resourceGatewayAuthoring.open', () => controller.open()),
    vscode.commands.registerCommand('resourceGatewayAuthoring.closeSafely', () => controller.closeSafely()),
    vscode.commands.registerCommand('resourceGatewayAuthoring.setRemoteToken', async () => {
      const token = await vscode.window.showInputBox({
        title: 'Resource Gateway remote token',
        prompt: 'Stored in VS Code SecretStorage. The token is never sent through the WebView.',
        password: true,
        ignoreFocusOut: true,
      });
      if (typeof token === 'string' && token.trim()) {
        await context.secrets.store(TOKEN_SECRET, token.trim());
        vscode.window.showInformationMessage('Resource Gateway remote token stored securely.');
      }
    }),
    vscode.commands.registerCommand('resourceGatewayAuthoring.clearRemoteToken', async () => {
      await context.secrets.delete(TOKEN_SECRET);
      vscode.window.showInformationMessage('Resource Gateway remote token removed.');
    }),
    vscode.window.registerUriHandler({
      handleUri(uri) {
        if (uri.path === '/open') controller.open();
      },
    }),
    vscode.window.registerWebviewPanelSerializer(PANEL_TYPE, {
      async deserializeWebviewPanel(panel) {
        controller.deserialize(panel);
      },
    }),
  );
  if (context.extensionMode === vscode.ExtensionMode.Development) {
    setTimeout(() => controller.open(), 500);
  }
}

async function deactivate() {
  if (controller) await controller.closeSafely();
}

module.exports = { activate, deactivate };
