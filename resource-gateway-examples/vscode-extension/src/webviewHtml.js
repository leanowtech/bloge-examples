'use strict';

const crypto = require('node:crypto');

function createNonce() {
  return crypto.randomBytes(24).toString('base64url');
}

function buildWebviewHtml({ indexHtml, webview, extensionUri, vscode, nonce = createNonce() }) {
  if (typeof indexHtml !== 'string' || !indexHtml.includes('<div id="root"></div>')) {
    throw new Error('RG.HOST.WEBVIEW.INDEX_INVALID');
  }
  const assetRoot = vscode.Uri.joinPath(extensionUri, 'media', 'webview');
  let html = indexHtml.replace(
    /(src|href)="\.\/(assets\/[^"?#]+)([^"]*)"/g,
    (_match, attribute, assetPath, suffix) => {
      const uri = webview.asWebviewUri(vscode.Uri.joinPath(assetRoot, ...assetPath.split('/')));
      return `${attribute}="${escapeAttribute(String(uri))}${suffix}"`;
    },
  );
  html = html.replace(/<script\b(?![^>]*\bnonce=)/g, `<script nonce="${nonce}"`);
  const csp = [
    "default-src 'none'",
    `img-src ${webview.cspSource} data: https:`,
    `font-src ${webview.cspSource}`,
    `style-src ${webview.cspSource} 'unsafe-inline'`,
    `script-src ${webview.cspSource} 'nonce-${nonce}'`,
    "connect-src 'none'",
    "frame-src 'none'",
  ].join('; ');
  html = html.replace('<head>', `<head>\n    <meta http-equiv="Content-Security-Policy" content="${csp}">`);
  return html;
}

function escapeAttribute(value) {
  return value.replace(/&/g, '&amp;').replace(/"/g, '&quot;');
}

module.exports = { buildWebviewHtml, createNonce };
