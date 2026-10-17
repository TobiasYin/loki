/** Reproduce the pinned Pi session browser port from an unpacked npm 1.2.0 archive.
 * Usage: node scripts/vendor-pi-session.mjs ../build/pi-session/private/package
 * Core algorithm bodies are copied unchanged; only platform import specifiers change.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const source = path.resolve(process.argv[2] ?? '../build/pi-session/private/package');
const target = path.resolve('vendor/pi-session-browser');
const metadata = JSON.parse(fs.readFileSync(path.join(source, 'package.json'), 'utf8'));
if (metadata.name !== '@earendil-works/pi-coding-agent' || metadata.version !== '1.2.0') throw new Error('Unexpected Pi package');
const modules = [
  'core/agent-session', 'core/session-manager', 'core/messages', 'core/settings-manager',
  'core/settings-defaults', 'core/nested-tool-calls', 'core/system-prompt', 'core/usage-totals',
  'core/virtual-models', 'core/defaults', 'core/source-info', 'core/prompt-templates',
  'core/auth-guidance', 'core/model-registry', 'core/session-export',
  'core/extensions/runner', 'core/extensions/wrapper', 'core/tools/tool-definition-wrapper',
  'core/compaction/index', 'core/compaction/compaction', 'core/compaction/branch-summarization',
  'core/compaction/utils', 'core/mcp-servers', 'utils/sleep', 'utils/text', 'utils/frontmatter',
];
const externalPlatform = new Set(['crypto', 'fs', 'fs/promises', 'path', 'readline', 'string_decoder', 'proper-lockfile']);
const internalPlatform = new Set([
  'config.js', 'utils/paths.js', 'utils/image-process.js',
  'utils/tool-result-images.js', 'modes/interactive/theme/theme.js', 'core/bash-executor.js',
  'core/bug-report.js', 'core/export-html/index.js', 'core/export-html/tool-renderer.js',
  'core/tools/bash.js', 'core/tools/index.js', 'core/http-dispatcher.js',
  'core/skills.js', 'core/provider-composer.js',
]);
const hashes = {};
const sha = (data) => crypto.createHash('sha256').update(data).digest('hex');
for (const name of modules) {
  const relative = `${name}.js`;
  const original = fs.readFileSync(path.join(source, 'dist', relative), 'utf8');
  const rewrites = [];
  let port = original.replace(/(from\s+["'])([^"']+)(["'])/g, (full, prefix, specifier, suffix) => {
    const clean = specifier.replace(/^node:/, '');
    const resolved = path.posix.normalize(path.posix.join(path.posix.dirname(relative), specifier));
    let replacement;
    if (externalPlatform.has(clean) || internalPlatform.has(resolved)) replacement = path.posix.relative(path.posix.dirname(relative), 'platform.js');
    if (resolved === 'core/extensions/index.js') replacement = './extensions/browser-index.js';
    if (!replacement) return full;
    if (!replacement.startsWith('.')) replacement = `./${replacement}`;
    rewrites.push([specifier, replacement]);
    return `${prefix}${replacement}${suffix}`;
  });
  // Pi's Node entry converts prompt base64 images through Buffer before its image backend.
  // The browser byte adapter is intentionally local, never a global Node polyfill.
  if (name === 'core/agent-session') port = `import { Buffer } from "../platform.js";\n${port}`;
  const destination = path.join(target, relative);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, port);
  const declaration = fs.readFileSync(path.join(source, 'dist', `${name}.d.ts`));
  fs.writeFileSync(path.join(target, `${name}.d.ts`), declaration);
  hashes[relative] = { upstreamSha256: sha(original), browserSha256: sha(port), importRewrites: rewrites };
}
// Keep only the transitive type declarations of these modules (never CLI entrypoints).
const declarations = new Set();
function copyDeclaration(relative) {
  if (declarations.has(relative)) return;
  const sourceFile = path.join(source, 'dist', relative);
  if (!fs.existsSync(sourceFile)) return;
  declarations.add(relative);
  const content = fs.readFileSync(sourceFile, 'utf8');
  const destination = path.join(target, relative);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, content);
  for (const match of content.matchAll(/\b(?:from\s+|import\s*\(\s*)["']([^"']+)/g)) {
    const specifier = match[1];
    if (specifier.startsWith('.')) copyDeclaration(path.posix.normalize(path.posix.join(path.posix.dirname(relative), specifier.replace(/\.(?:js|ts)$/, '.d.ts'))));
  }
}
for (const name of modules) copyDeclaration(`${name}.d.ts`);
const loader = fs.readFileSync(path.join(source, 'dist/core/extensions/loader.js'), 'utf8');
const runtimeFunction = 'export function createExtensionRuntime() {' + loader.split('export function createExtensionRuntime() {')[1].split('\n/**\n * Create the ExtensionAPI')[0];
const runtimePort = `import { McpServerRegistry } from "../mcp-servers.js";\n${runtimeFunction}`;
fs.writeFileSync(path.join(target, 'core/extensions/browser-runtime.js'), runtimePort);
hashes['core/extensions/browser-runtime.js'] = { upstreamModule: 'core/extensions/loader.js', upstreamModuleSha256: sha(loader), extractedFunctionSha256: sha(runtimeFunction), browserSha256: sha(runtimePort) };
fs.writeFileSync(path.join(target, 'UPSTREAM.json'), `${JSON.stringify({
  package: metadata.name, version: metadata.version, repository: metadata.repository,
  npmArchive: 'https://registry.npmjs.org/@earendil-works/pi-coding-agent/-/pi-coding-agent-1.2.0.tgz',
  npmIntegrity: 'sha512-h/4WaVWv1VlCaY8x8qEN5U+7jbF/e7+Ck6moPeEOFn5juMTWwNAXjwgYM7gRgbBYh8Zppwt6qWvJDzNTf4GNEQ==',
  transformations: 'Import specifiers only, plus a local Buffer byte adapter import in agent-session.js. Algorithm bodies unchanged.',
  modules: hashes,
}, null, 2)}\n`);
