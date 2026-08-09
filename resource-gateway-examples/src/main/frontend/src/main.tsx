import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import App from './App';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container #root not found');
}

const render = () => createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

if (typeof globalThis.acquireVsCodeApi === 'function') {
  void import('./host/vscodeWebviewBridge').then(({ installVsCodeWebviewBridge }) => {
    installVsCodeWebviewBridge();
    render();
  });
} else {
  render();
}
