# BLOGE Resource Gateway Authoring for VS Code

This reference extension runs the Resource Gateway product workspaces inside a VS Code WebView. The default mode is
serverless. Business Mirror opens first with three embedded Legacy Graph projections and a complete
catalog-preview-import-save-compile task. The Author workspace also loads an embedded operator catalog and stores its
crash-recovery snapshots as AES-256-GCM ciphertext. The encryption key lives only in VS Code `SecretStorage`.

## Run the extension

```bash
cd resource-gateway-examples/vscode-extension
npm run prepare:webview
code --new-window --extensionDevelopmentPath="$PWD"
```

In the Extension Development Host, run **Resource Gateway: Open Authoring Workspace**. Business Mirror is the default
page. Open `loanDecisionPolicy`, import it, fill the guided business definition, save revision `2`, then compile its
expected `BLOCKED` readiness report. No server is required. Select **Author** to load any of the three complete canvas
examples, edit it, wait for the `宿主加密存储 / Host encrypted storage` state, then run **Resource Gateway: Save Recovery
and Close**. Reopen the workspace to verify exact Author recovery.

The offline Business Mirror adapter never accesses the network, Secret, or production business payload. It binds
idempotency keys to canonical request material, exactly replays an ambiguous save, and rejects key reuse with changed
material. Package heads are extension-process state; they are not presented as durable production storage. See the
[Business Mirror Workspace guide](../../docs/resource-gateway-business-mirror-workspace-guide.md) for the complete fixed
task and the server/remote-runtime boundary.

Author Workspace v2 keeps the standard canvas free of overlay controls. Use the diagonal-arrow button to expand
Inspect: the selected node and its nearest structural context are centered at a readable zoom while the minimap keeps
the full topology visible. Returning to the standard canvas restores the previous map state.

For repeatable host automation, open `vscode://bloge.bloge-resource-gateway-authoring/open` while the extension is
installed or running in an Extension Development Host. Development hosts also open the panel after startup; packaged
extensions do not auto-open.

`prepare:webview` builds the frontend, copies the production Vite output under `media/webview`, and verifies the
manifest, relative asset references, missing files, and a 4 MiB packaged-resource budget. Generated assets are not
committed.

## Optional remote runtime

Set `resourceGatewayAuthoring.remoteBaseUrl` to an HTTPS Resource Gateway URL, or a loopback HTTP URL for local
development. A remote runtime is used only in a trusted VS Code workspace. `/admin` remains blocked unless
`resourceGatewayAuthoring.allowAdminProxy` is explicitly enabled.

Use **Resource Gateway: Set Remote Token** to place a bearer token in VS Code `SecretStorage`. The WebView cannot read
the token and any authorization, cookie, host, origin, or referer header supplied by WebView code is discarded.

## Lifecycle boundary

VS Code does not expose a cancellable "panel is about to close" event for the tab close button. Data safety therefore
does not depend on a disposal handshake:

1. the frontend continuously checkpoints changed work after a 350 ms debounce and at least every 5 seconds;
2. hidden/page lifecycle signals request another checkpoint;
3. extension-controlled close and deactivation use the versioned disposal request/receipt and close only when
   `ready=true`;
4. a direct tab close can lose only work newer than the last encrypted checkpoint and is covered by the field-study
   fault matrix rather than being presented as an impossible atomic-close guarantee;
5. serializer recovery and development auto-open converge on one authoritative panel, so a restart cannot create two
   competing Author tabs.

## Verify

```bash
npm test
npm run verify
```

The tests cover protocol validation, secret-key encryption, tamper detection, tenant/environment partitioning, offline
catalog behavior, remote SSRF boundaries, credential ownership, CSP generation, and fail-closed safe disposal.

The complete security boundary, actual VS Code measurements, screenshots, discovered defects, and E3/E4 gap are in
[`docs/resource-gateway-ux-round3-s5-vscode-host-integration.md`](../../docs/resource-gateway-ux-round3-s5-vscode-host-integration.md).
