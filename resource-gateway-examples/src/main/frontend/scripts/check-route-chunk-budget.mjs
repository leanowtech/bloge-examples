import { readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';

const assetsDirectory = resolve('dist/assets');
const applicationChunkBudget = 350 * 1024;
const shellChunkBudget = 180 * 1024;
const routeStartupTransferBudget = 350 * 1024;
const vendorPrefixes = ['react-runtime-', 'react-flow-'];
const requiredApplicationChunks = [
  'CapabilityStudio-',
  'BusinessMirrorWorkspace-',
  'AuthorCanvas-',
  'CorrectnessStudio-',
  'LibraryWorkbench-',
  'RehearsalWorkbench-',
  'Showcase-',
  'author-domain-',
  'locale-catalog-',
];
const routePrefixes = [
  'CapabilityStudio-', 'BusinessMirrorWorkspace-', 'AuthorCanvas-', 'CorrectnessStudio-', 'LibraryWorkbench-',
  'RehearsalWorkbench-', 'Showcase-',
];

const chunks = readdirSync(assetsDirectory)
  .filter((name) => name.endsWith('.js'))
  .map((name) => ({ name, bytes: statSync(resolve(assetsDirectory, name)).size }));
const applicationChunks = chunks.filter(({ name }) => (
  !vendorPrefixes.some((prefix) => name.startsWith(prefix))
));
const shellChunk = applicationChunks.find(({ name }) => name.startsWith('index-'));
const violations = [];

if (!shellChunk) {
  violations.push('Missing application shell chunk (index-*.js).');
} else if (shellChunk.bytes > shellChunkBudget) {
  violations.push(`Shell ${shellChunk.name} is ${formatKiB(shellChunk.bytes)}; budget is ${formatKiB(shellChunkBudget)}.`);
}

for (const prefix of requiredApplicationChunks) {
  if (!applicationChunks.some(({ name }) => name.startsWith(prefix))) {
    violations.push(`Missing named application chunk ${prefix}*.js.`);
  }
}

for (const chunk of applicationChunks) {
  if (chunk.bytes > applicationChunkBudget) {
    violations.push(`${chunk.name} is ${formatKiB(chunk.bytes)}; application chunk budget is ${formatKiB(applicationChunkBudget)}.`);
  }
}

const manifest = JSON.parse(readFileSync(resolve('dist/.vite/manifest.json'), 'utf8'));
const routeTransfers = routePrefixes.map((prefix) => {
  const routeEntry = Object.values(manifest).find((entry) => (
    entry.isDynamicEntry === true
      && typeof entry.file === 'string'
      && entry.file.endsWith('.js')
      && basename(entry.file).startsWith(prefix)
  ));
  if (!routeEntry) {
    violations.push(`Missing route manifest entry ${prefix}*.js.`);
    return { name: prefix.slice(0, -1), gzipBytes: 0, files: [] };
  }
  const files = collectStartupFiles(manifest, manifest['index.html'], routeEntry);
  const gzipBytes = [...files].reduce((total, file) => (
    total + gzipSync(readFileSync(resolve('dist', file))).length
  ), 0);
  if (gzipBytes > routeStartupTransferBudget) {
    violations.push(`${prefix.slice(0, -1)} startup closure is ${formatKiB(gzipBytes)} gzip; budget is ${formatKiB(routeStartupTransferBudget)}.`);
  }
  return { name: prefix.slice(0, -1), gzipBytes, files: [...files] };
});

console.log('Route chunk budget');
for (const chunk of applicationChunks.sort((left, right) => right.bytes - left.bytes)) {
  console.log(`  ${chunk.name.padEnd(52)} ${formatKiB(chunk.bytes)}`);
}
console.log('\nRoute startup transfer (shell + static JS/CSS closure)');
for (const route of routeTransfers.sort((left, right) => right.gzipBytes - left.gzipBytes)) {
  console.log(`  ${route.name.padEnd(24)} ${formatKiB(route.gzipBytes).padStart(12)}  ${route.files.length} files`);
}

if (violations.length > 0) {
  console.error('\nBudget violations:');
  for (const violation of violations) console.error(`  - ${violation}`);
  process.exitCode = 1;
} else {
  console.log(`\nPASS: shell <= ${formatKiB(shellChunkBudget)}, every application chunk <= ${formatKiB(applicationChunkBudget)}, and every route startup closure <= ${formatKiB(routeStartupTransferBudget)} gzip.`);
}

function formatKiB(bytes) {
  return `${(bytes / 1024).toFixed(2)} KiB`;
}

function collectStartupFiles(manifest, ...roots) {
  const files = new Set();
  const visited = new Set();
  const visit = (entry) => {
    if (!entry || visited.has(entry.file)) return;
    visited.add(entry.file);
    files.add(entry.file);
    for (const css of entry.css ?? []) files.add(css);
    for (const key of entry.imports ?? []) visit(manifest[key]);
  };
  for (const root of roots) visit(root);
  return files;
}

function basename(file) {
  return file.slice(file.lastIndexOf('/') + 1);
}
