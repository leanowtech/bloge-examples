import { spawnSync } from 'node:child_process';
import { cp, mkdir, readFile, readdir, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const extensionDirectory = path.resolve(scriptDirectory, '..');
const frontendDirectory = path.resolve(extensionDirectory, '..', 'src', 'main', 'frontend');
const sourceDirectory = path.join(frontendDirectory, 'dist');
const targetDirectory = path.join(extensionDirectory, 'media', 'webview');
const checkOnly = process.argv.includes('--check');

if (!checkOnly) {
  const build = spawnSync('npm', ['run', 'build'], {
    cwd: frontendDirectory,
    encoding: 'utf8',
    stdio: 'inherit',
  });
  if (build.status !== 0) process.exit(build.status || 1);
  await rm(targetDirectory, { recursive: true, force: true });
  await mkdir(targetDirectory, { recursive: true });
  await cp(sourceDirectory, targetDirectory, { recursive: true });
}

await verifyWebviewBundle(targetDirectory);
process.stdout.write(`PASS: VS Code WebView bundle ${checkOnly ? 'verified' : 'prepared'} at ${targetDirectory}\n`);

async function verifyWebviewBundle(directory) {
  const indexPath = path.join(directory, 'index.html');
  const manifestPath = path.join(directory, '.vite', 'manifest.json');
  const [index, manifest] = await Promise.all([
    readFile(indexPath, 'utf8'),
    readFile(manifestPath, 'utf8').then(JSON.parse),
  ]).catch(() => {
    throw new Error('RG.HOST.WEBVIEW.BUNDLE_MISSING: run npm run prepare:webview');
  });
  if (/\b(?:src|href)="\/assets\//.test(index)) {
    throw new Error('RG.HOST.WEBVIEW.ABSOLUTE_ASSET_PATH');
  }
  if (!manifest['index.html'] || typeof manifest['index.html'].file !== 'string') {
    throw new Error('RG.HOST.WEBVIEW.MANIFEST_INVALID');
  }
  const files = await collectFiles(directory);
  const referenced = [...index.matchAll(/(?:src|href)="\.\/(assets\/[^"?#]+)/g)]
    .map((match) => match[1]);
  for (const asset of referenced) {
    if (!files.has(asset)) throw new Error(`RG.HOST.WEBVIEW.ASSET_MISSING: ${asset}`);
  }
  const totalBytes = await Promise.all(
    [...files].map((file) => stat(path.join(directory, file)).then((entry) => entry.size)),
  ).then((sizes) => sizes.reduce((sum, size) => sum + size, 0));
  if (totalBytes > 4 * 1024 * 1024) {
    throw new Error(`RG.HOST.WEBVIEW.BUNDLE_TOO_LARGE: ${totalBytes}`);
  }
}

async function collectFiles(root, current = root, result = new Set()) {
  for (const entry of await readdir(current, { withFileTypes: true })) {
    const absolute = path.join(current, entry.name);
    if (entry.isDirectory()) await collectFiles(root, absolute, result);
    else result.add(path.relative(root, absolute).split(path.sep).join('/'));
  }
  return result;
}
