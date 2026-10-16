import { RuntimeError, type ModelProfile } from './contracts.js';

/** Metadata enables official presets; an actual connection probe remains a separate
 * observation. A provider name alone must never bypass custom-profile validation. */
export function hasOfficialMetadata(p: ModelProfile): boolean {
  if (p.providerMetadataKnown !== true) return false;
  try {
    const u = new URL(p.baseUrl);
    if (u.protocol !== 'https:' || u.username || u.password || u.port || u.search || u.hash) return false;
    const path = u.pathname.replace(/\/+$/, '');
    const goPath=p.protocol==='anthropic-messages'
      ? path==='/zen/go' || path==='/inference/go/anthropic'
      : path==='/zen/go/v1' || path==='/inference/go/openai/v1';
    return p.provider === 'opencode-go' && u.hostname === 'opencode.ai' && goPath
      || p.provider === 'deepseek' && u.hostname === 'api.deepseek.com' && (path === '' || path === '/v1');
  } catch { return false; }
}

export function checkProfile(p: ModelProfile): void {
  const reject = (): never => { throw new RuntimeError('UNSUPPORTED', 'Model profile metadata or custom connection test is incomplete'); };
  const u = (() => { try { return new URL(p.baseUrl); } catch { return reject(); } })();
  if (!['https:', 'http:'].includes(u.protocol) || u.username || u.password || u.search || u.hash) reject();
  if (!['openai-responses', 'anthropic-messages', 'openai-completions'].includes(p.protocol) || !p.modelId || !p.credentialRef || p.contextWindow <= p.maxOutput || p.maxOutput < 16 || !Number.isSafeInteger(p.contextWindow) || !Number.isSafeInteger(p.maxOutput)) reject();
  if (p.configurationProbe === true) {
    // Native only sets this on its dedicated explicit configuration-test lease.
    // The probe must run before it has a successful lastProbe observation.
  } else if (hasOfficialMetadata(p) && p.probeRequired !== true) {
    if (!p.input?.includes('text') || p.input.some(x => !['text', 'image'].includes(x)) || typeof p.supportsTools !== 'boolean' || typeof p.reasoning !== 'boolean') reject();
  } else {
    const probe = p.lastProbe;
    if (!probe || probe.revision !== p.revision || !probe.textStream || !probe.toolCalling || (p.input ?? ['text', 'image']).includes('image') && (!probe.imageInput || !probe.imageToolRoundTrip)) reject();
  }
  for (const [h, v] of Object.entries(p.customHeaders ?? {})) if (!/^[a-z0-9-]+$/i.test(h) || /^(host|authorization|x-api-key|cookie|proxy-authorization|content-length|connection)$/i.test(h) || /[\r\n]/.test(v)) reject();
}
