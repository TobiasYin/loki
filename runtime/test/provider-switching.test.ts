import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ThorRuntime, RuntimeSession, type ModelProfile } from '../src/index.js';
import { fixture, registry } from './fixtures.js';

function configured(provider: 'opencode-go' | 'deepseek', text: string, sessionId = 'session-test') {
  const protocol = provider === 'opencode-go' ? 'openai-responses' : 'openai-completions';
  const f = fixture(protocol, [{ text }]);
  f.lease.sessionId = f.snapshot.session.sessionId = sessionId;
  Object.assign(f.snapshot.session.model, {
    profileId: `${provider}-saved-config:${protocol}`, credentialRef: `${provider}-native-key`, provider,
    baseUrl: provider === 'opencode-go' ? 'https://opencode.ai/zen/go/v1' : 'https://api.deepseek.com',
    modelId: provider === 'opencode-go' ? 'gpt-6-luna' : 'deepseek-chat',
    providerMetadataKnown: true, input: ['text'], supportsTools: true, reasoning: false,
  } satisfies Partial<ModelProfile>);
  return f;
}

test('saved Go → DeepSeek → Go configurations reuse the Pi chat with current credentials and retained native Pi entries', async () => {
  const session = new RuntimeSession('session-test');
  const first = configured('opencode-go', 'remembered-go-answer');
  assert.equal((await new ThorRuntime(first.ports, registry(), {}, session).run(first.lease, [{ type: 'text', text: 'remember original task' }])).state, 'completed');
  const agent = session.agent!;
  const assistantEntry = session.piSnapshot!.entries.find(entry => entry.type === 'message' && entry.message.role === 'assistant');
  const assistant = assistantEntry?.type === 'message' ? assistantEntry.message : undefined;
  assert.equal(assistant?.role, 'assistant');
  if (assistant?.role === 'assistant') {
    assistant.content.push({ type: 'thinking', thinking: '', thinkingSignature: JSON.stringify({type:'reasoning',id:'rs_provider_fixture',encrypted_content:'opaque-old-provider-signature'}), redacted: true });
  }
  const originalEntries = structuredClone(session.piSnapshot!.entries);
  const second = configured('deepseek', 'remembered-deepseek-answer');
  assert.equal((await new ThorRuntime(second.ports, registry(), {}, session).run(second.lease, [{ type: 'text', text: 'continue with DeepSeek' }])).state, 'completed');
  assert.notEqual(session.agent, agent, 'Idle Agent instances can be released while the same logical Pi session resumes');
  assert.equal(session.agent!.state.model?.provider, 'deepseek');
  assert.equal(session.agent!.state.model?.api, 'openai-completions');
  assert.deepEqual(session.piSnapshot!.entries.slice(0,originalEntries.length),originalEntries,'Provider adaptation must not rewrite raw Pi entries or usage');
  assert.equal(second.requests[0]!.url, 'https://api.deepseek.com/chat/completions');
  assert.equal(second.requests[0]!.envelope.credentialRef, 'deepseek-native-key');
  assert.equal(second.requests[0]!.envelope.profileRef, 'deepseek-saved-config:openai-completions');
  const deepseekBody = JSON.stringify(second.requests[0]!.body);
  for (const text of ['remember original task', 'remembered-go-answer', 'continue with DeepSeek']) assert.ok(deepseekBody.includes(text));
  assert.ok(!deepseekBody.includes('opaque-old-provider-signature'));
  assert.ok(second.events.some(e => e.type === 'context' && e.data.piSessionReused === true));
  const deepseekEntries = structuredClone(session.piSnapshot!.entries);
  const third = configured('opencode-go', 'continued after switching back');
  assert.equal((await new ThorRuntime(third.ports, registry(), {}, session).run(third.lease, [{ type: 'text', text: 'continue with Go again' }])).state, 'completed');
  assert.notEqual(session.agent, agent);
  assert.equal(session.agent!.state.model?.provider, 'opencode-go');
  assert.deepEqual(session.piSnapshot!.entries.slice(0,deepseekEntries.length),deepseekEntries);
  assert.equal(third.requests[0]!.envelope.credentialRef, 'opencode-go-native-key');
  const goBody = JSON.stringify(third.requests[0]!.body);
  for (const text of ['remember original task', 'remembered-go-answer', 'remembered-deepseek-answer', 'continue with Go again']) assert.ok(goBody.includes(text));
  assert.ok(goBody.includes('opaque-old-provider-signature'),'Returning to the original provider restores its compatible opaque reasoning item');
  assert.equal(first.executions.length + second.executions.length + third.executions.length, 0);
  assert.equal(first.contexts[0]!.modelProfileRevision, second.contexts[0]!.modelProfileRevision);
  assert.equal(first.contexts[0]!.capabilityEpoch, second.contexts[0]!.capabilityEpoch);
});

test('parallel chats bind different providers independently without mixing credentials, prompts, or Pi Agents', { timeout: 3000 }, async () => {
  const go = configured('opencode-go', 'go-only-result', 'go-chat');
  const deepseek = configured('deepseek', 'deepseek-only-result', 'deepseek-chat');
  const goSession = new RuntimeSession('go-chat'); const deepseekSession = new RuntimeSession('deepseek-chat');
  let started = 0; let release!: () => void;
  const bothStarted = new Promise<void>(resolve => { release = resolve; });
  const onRequest = async () => { if (++started === 2) release(); await bothStarted; };
  go.setOnRequest(onRequest); deepseek.setOnRequest(onRequest);
  const results = await Promise.all([
    new ThorRuntime(go.ports, registry(), {}, goSession).run(go.lease, [{ type: 'text', text: 'go-only-prompt' }]),
    new ThorRuntime(deepseek.ports, registry(), {}, deepseekSession).run(deepseek.lease, [{ type: 'text', text: 'deepseek-only-prompt' }]),
  ]);
  assert.deepEqual(results.map(r => r.state), ['completed', 'completed']);
  assert.notEqual(goSession.agent, deepseekSession.agent);
  for (const [f, own, foreign, provider] of [[go, 'go-only-prompt', 'deepseek-only-prompt', 'opencode-go'], [deepseek, 'deepseek-only-prompt', 'go-only-prompt', 'deepseek']] as const) {
    assert.equal(f.requests.length, 1);
    assert.equal(f.requests[0]!.envelope.credentialRef, `${provider}-native-key`);
    assert.equal(f.requests[0]!.envelope.sessionId, f.lease.sessionId);
    assert.ok(JSON.stringify(f.requests[0]!.body).includes(own));
    assert.ok(!JSON.stringify(f.requests[0]!.body).includes(foreign));
  }
});
