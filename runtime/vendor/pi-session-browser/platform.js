/** Browser/Native platform boundary for pinned upstream Pi 1.2.0.
 * No agent loop, message projection, token selection, summarization or retry logic lives here.
 * Files, shell tools and CLI export stay unavailable: callers inject Native tools and persist
 * SessionManager.inMemory() entries through their own storage boundary.
 */
const unsupported = () => { throw new Error('This CLI operation is unavailable in the Thor browser session adapter'); };
export const randomUUID = () => globalThis.crypto.randomUUID();
export const existsSync = () => false;
export const appendFileSync = unsupported, closeSync = unsupported, createReadStream = unsupported;
export const mkdirSync = unsupported, openSync = unsupported, readdirSync = unsupported;
export const readSync = unsupported, statSync = unsupported, writeFileSync = unsupported;
export const readFileSync = unsupported, readdir = unsupported, stat = unsupported;
export const createInterface = unsupported;
export default { lockSync: unsupported, lock: unsupported };

export function normalizePath(value) {
  if (typeof value !== 'string') throw new TypeError('Expected a virtual path');
  const absolute = value.startsWith('/');
  const parts = [];
  for (const part of value.split('/')) {
    if (!part || part === '.') continue;
    if (part === '..') { if (parts.length && parts.at(-1) !== '..') parts.pop(); else if (!absolute) parts.push(part); }
    else parts.push(part);
  }
  return `${absolute ? '/' : ''}${parts.join('/')}` || (absolute ? '/' : '.');
}
export const sep = '/';
export const join = (...parts) => normalizePath(parts.filter(Boolean).join('/'));
export function resolve(...parts) {
  let value = '/';
  for (const part of parts) value = part.startsWith('/') ? part : `${value}/${part}`;
  return normalizePath(value);
}
export const resolvePath = resolve;
export const basename = (value, suffix = '') => { const name = normalizePath(value).split('/').at(-1); return suffix && name.endsWith(suffix) ? name.slice(0, -suffix.length) : name; };
export const dirname = value => { const path = normalizePath(value); const at = path.lastIndexOf('/'); return at < 0 ? '.' : path.slice(0, at) || '/'; };
export class StringDecoder {
  constructor() { this.decoder = new TextDecoder(); }
  write(bytes) { return this.decoder.decode(bytes, { stream: true }); }
  end(bytes) { return this.decoder.decode(bytes); }
}
export class Buffer extends Uint8Array {
  static from(value, encoding) {
    if (typeof value !== 'string') return new Buffer(value);
    if (encoding === 'base64') return new Buffer(Uint8Array.from(atob(value), c => c.charCodeAt(0)));
    return new Buffer(new TextEncoder().encode(value));
  }
  toString(encoding) {
    if (encoding !== 'base64') return new TextDecoder().decode(this);
    let binary = '';
    for (let offset = 0; offset < this.length; offset += 8192) binary += String.fromCharCode(...this.subarray(offset, offset + 8192));
    return btoa(binary);
  }
}

export const APP_NAME = 'thor-agent', CONFIG_DIR_NAME = '.pi', VERSION = '1.2.0';
export const getAgentDir = () => '/thor/.pi';
export const getSessionsDir = () => '/thor/.pi/sessions';
export const getDocsPath = () => '/thor/pi/docs';
export const getExamplesPath = () => '/thor/pi/examples';
export const getReadmePath = () => '/thor/pi/README.md';
export const DEFAULT_HTTP_IDLE_TIMEOUT_MS = 300000;
export function parseHttpIdleTimeoutMs(value) {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (trimmed.toLowerCase() === 'disabled') return 0;
    if (trimmed.length === 0) return undefined;
    return parseHttpIdleTimeoutMs(Number(trimmed));
  }
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? Math.floor(value) : undefined;
}

// Native already normalizes and bounds screenshot attachments. Preserve their exact bytes;
// no Node image backend, global Buffer shim or transitive filesystem access is introduced.
export async function processImage(bytes, mimeType) {
  if (typeof mimeType !== 'string' || !mimeType.startsWith('image/')) return { ok: false, message: 'Unsupported image format' };
  return { ok: true, data: Buffer.from(bytes).toString('base64'), mimeType, hints: [] };
}
export async function normalizeToolResultImages(content) { return content; }

// Terminal renderers are not used by the web presentation layer.
export const theme = new Proxy({}, { get: () => (...args) => String(args.at(-1) ?? '') });
export const getThemeByName = () => undefined;
export const executeBashWithOperations = unsupported, createLocalBashOperations = unsupported;
export const generateBugReportSummary = unsupported, exportSessionToHtml = unsupported;
export const createToolHtmlRenderer = unsupported, createAllToolDefinitions = unsupported;
export const clearApiKeyCache = () => {};

// Upstream skills.js's presentation-only formatter, copied without loading CLI discovery.
export function formatSkillsForPrompt(skills, fileReadTool = 'read') {
  const visibleSkills = skills.filter(s => !s.disableModelInvocation);
  if (visibleSkills.length === 0) return '';
  const lines = [
    '\n\nThe following skills provide specialized instructions for specific tasks.',
    fileReadTool === 'read' ? "Use the read tool to load a skill's file when the task matches its description."
      : fileReadTool === 'bash' ? "Use bash to load a skill's file when the task matches its description."
        : "Load a skill's file when the task matches its description.",
    'When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.',
    '', '<available_skills>',
  ];
  const escapeXml = str => str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');
  for (const skill of visibleSkills) lines.push('  <skill>', `    <name>${escapeXml(skill.name)}</name>`, `    <description>${escapeXml(skill.description)}</description>`, `    <location>${escapeXml(skill.filePath)}</location>`, '  </skill>');
  lines.push('</available_skills>');
  return lines.join('\n');
}
