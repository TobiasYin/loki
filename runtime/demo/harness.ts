import { ThorRuntime, RuntimeSession } from '../src/index.js';
import { nativePiState, piAssistant } from '../test/pi-test-state.js';
import { fixture, registry } from '../test/fixtures.js';

declare global { interface Window { harnessResult?: { ok: boolean; results: unknown[]; error?: string } } }
const output = document.querySelector('pre')!;
const results: unknown[] = [];
try {
  // This is a protocol/port harness, not the product's chat UI.
  globalThis.fetch = async () => { throw new Error('Direct JS networking is forbidden in this harness'); };
  for (const protocol of ['openai-responses', 'anthropic-messages'] as const) {
    const f = fixture(protocol, [{ call: { name: 'picture' }, chunkSize: 1 }, { text: '浏览器图片工具回传完成中文', chunkSize: 3 }]);
    const run = await new ThorRuntime(f.ports, registry()).run(f.lease, [{ type: 'text', text: 'Synthetic user image' }, { type: 'attachment', attachmentId: 'user-image' }]);
    const text = f.events.filter(e => e.type === 'text_delta').map(e => e.data.text).join('');
    if (run.state !== 'completed' || f.requests.length !== 2 || f.executions.length !== 1 || text !== '浏览器图片工具回传完成中文') throw new Error(`${protocol} failed: ${run.state} / ${text}`);
    results.push({ protocol, state: run.state, requests: f.requests.length, tools: f.executions.length, text, epoch: run.capabilityEpoch, snapshots: f.contexts.length });
  }
  const revoked = fixture('openai-responses', [{ call: { name: 'echo' } }]); const guarded = new ThorRuntime(revoked.ports, registry());
  guarded.subscribe(event => {
    if (event.type === 'state' && event.data.state === 'executing_tools') {
      revoked.snapshot.session.enabled.screen_perception = false; revoked.snapshot.session.capabilityEpoch++;
      guarded.authorizationChanged(revoked.lease.sessionId, revoked.snapshot.session.capabilityEpoch);
      revoked.snapshot.session.enabled.screen_perception = true; revoked.snapshot.session.capabilityEpoch++; revoked.snapshot.lease.capabilityEpoch += 2;
    }
  });
  const revokedRun = await guarded.run(revoked.lease, [{ type: 'text', text: 'Revoke and re-enable' }]);
  if (revokedRun.state !== 'cancelled' || revoked.executions.length) throw new Error('Revocation failed');
  results.push({ scenario: 'revoke/off/on', state: revokedRun.state, oldEpochExecutions: revoked.executions.length });
  const cancelled = fixture('anthropic-messages', [{ wait: true }]); const cancellable = new ThorRuntime(cancelled.ports, registry()); cancelled.setOnRequest(() => cancellable.stop());
  if ((await cancellable.run(cancelled.lease, [{ type: 'text', text: 'Cancel' }])).state !== 'cancelled') throw new Error('Cancellation failed');
  results.push({ scenario: 'cancel', state: 'cancelled' });
  const failure = fixture('openai-responses', [{ status: 500 }]);
  if ((await new ThorRuntime(failure.ports, registry()).run(failure.lease, [{ type: 'text', text: 'HTTP failure' }])).state !== 'failed') throw new Error('Failure handling failed');
  results.push({ scenario: 'HTTP 500', state: 'failed', requests: failure.requests.length });
  const compressed=fixture('openai-responses',[
    {text:'SUMMARY: Earlier work is complete; preserve the destination and current progress.'},
    {text:'Current goal completed from the saved progress.'},
  ]);
  Object.assign(compressed.snapshot.session.model,{contextWindow:16000,maxOutput:1024,
    piSettings:{compaction:{enabled:true,reserveTokens:500,keepRecentTokens:800},retry:{enabled:false}}});
  const recent=piAssistant(compressed,'Recent confirmed checkpoint.');
  recent.usage={...recent.usage,input:15600,output:10,totalTokens:15610};
  const piState=nativePiState(compressed,[
    {role:'user',content:'Old goal '+ 'older context '.repeat(1200),timestamp:1},
    piAssistant(compressed,'Earlier stage complete.'),
    {role:'user',content:'Recent task '+ 'recent context '.repeat(300),timestamp:1},recent,
  ]);
  const logical=new RuntimeSession(compressed.lease.sessionId);
  const compressedRun=await new ThorRuntime(compressed.ports,registry(),{},logical).run(compressed.lease,[{type:'text',text:'Current goal'}],piState);
  if(compressedRun.state!=='completed')throw new Error(`Pi compaction continuation failed: ${compressedRun.error}; HTTP=${compressed.requests.length}; estimate=${compressed.contexts.at(-1)?.estimatedTokens}; checkpoints=${logical.piSnapshot?.entries.filter(entry=>entry.type==='compaction').length}`);
  const compaction=logical.piSnapshot!.entries.find(entry=>entry.type==='compaction');
  if(compaction?.type!=='compaction' || compressed.requests.length!==2 || !JSON.stringify(compressed.requests[1]!.body).includes('SUMMARY:'))throw new Error('Pi compaction checkpoint failed');
  results.push({scenario:'official Pi automatic compaction',requests:compressed.requests.length,tokensBefore:compaction.tokensBefore,summarySaved:true});
  window.harnessResult = { ok: true, results }; output.textContent = JSON.stringify(window.harnessResult, null, 2);
} catch (error) {
  window.harnessResult = { ok: false, results, error: error instanceof Error ? error.message : String(error) };
  output.textContent = JSON.stringify(window.harnessResult, null, 2);
}
