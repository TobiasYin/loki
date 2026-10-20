import test from 'node:test';
import assert from 'node:assert/strict';
import { convertToLlm, estimateContextTokens } from '@thor-agent/pi-session-browser';
import type { Message } from '@earendil-works/pi-ai';
import { nativePiState, piAssistant } from './pi-test-state.js';
import { checkProfile } from '../src/profile-validation.js';
import { ThorRuntime } from '../src/runtime.js';
import { RuntimeSession } from '../src/session.js';
import { fixture, registry, profile } from './fixtures.js';

function turns(f:ReturnType<typeof fixture>,...values:[id:string,text:string][]) {
  const messages:Message[]=values.flatMap(([id,text])=>[{role:'user' as const,content:text,timestamp:1},piAssistant(f,`answer-${id}`)]);
  return nativePiState(f,messages);
}
function automatic(f:ReturnType<typeof fixture>,maxOutput=32000,contextWindow=32000) {
  Object.assign(f.snapshot.session.model,{contextWindow,maxOutput,outputBudgetAutomatic:true,
    piSettings:{compaction:{enabled:false},retry:{enabled:false}}});
}
async function execute(f:ReturnType<typeof fixture>,text:string,piState?:RuntimeSession['piSnapshot']) {
  const session=new RuntimeSession(f.lease.sessionId);const estimates:number[]=[];const usageDetails:ReturnType<typeof estimateContextTokens>[]=[];
  f.setOnRequest(()=>{
    assert.ok(session.agent);
    const estimate=estimateContextTokens(convertToLlm(session.agent.state.messages));usageDetails.push(estimate);estimates.push(estimate.tokens);
  });
  const result=await new ThorRuntime(f.ports,registry(),{},session).run(f.lease,[{type:'text',text}],piState);
  return {result,estimates,session,usageDetails};
}
function assertAutomaticRequest(f:ReturnType<typeof fixture>,estimated:number,index=0) {
  const p=f.snapshot.session.model;const actual=f.requests[index]!.body.max_output_tokens;
  assert.equal(f.contexts[index]!.estimatedTokens,estimated,'context diagnostics use the official Pi estimate');
  assert.ok(actual<=Math.min(p.maxOutput,p.contextWindow-estimated-4096),'Pi may choose a smaller per-request ceiling, but never exceed the available budget');
  assert.ok(actual>=16);assert.ok(actual<=p.maxOutput);assert.ok(estimated+actual+4096<=p.contextWindow);
  assert.equal(f.events.filter(e=>e.type==='model_round')[index]!.data.requestedMaxOutputTokens,actual);
}

for(const [modelId,contextWindow] of [['grok-4.20-beta',500000],['kimi-k2.6',262144]] as const) {
  for(const gap of [0,1]) test(`${modelId} automatic catalog output=context-${gap} uses Pi context usage while retaining history`,async()=>{
    const f=fixture();automatic(f,contextWindow-gap,contextWindow);
    Object.assign(f.snapshot.session.model,{modelId,provider:'opencode-go',providerMetadataKnown:true,baseUrl:'https://opencode.ai/zen/go/v1',input:['text'],supportsTools:true,reasoning:false});
    const history=turns(f,['earlier','earlier task context'],['recent','recent game progress']);const saved=structuredClone(f.snapshot.session.model);
    const {result,estimates}=await execute(f,'continue from recent game progress',history);
    assert.equal(result.state,'completed');assert.equal(f.requests.length,1);
    const body=JSON.stringify(f.requests[0]!.body);for(const marker of ['earlier task context','recent game progress','answer-recent'])assert.ok(body.includes(marker));
    assert.deepEqual(f.contexts[0]!.droppedTurnIds,[]);assertAutomaticRequest(f,estimates[0]!);
    assert.deepEqual(f.snapshot.session.model,saved,'per-request output allocation never rewrites the saved model profile');
  });
}

test('automatic allocation reduces output using Pi estimates and retains both complete turns',async()=>{
  const f=fixture();automatic(f,30000);
  const history=turns(f,['old','old '.repeat(2400)],['recent','recent '.repeat(1600)]);
  const {result,estimates}=await execute(f,'continue the same task',history);
  assert.equal(result.state,'completed');assert.equal(f.requests.length,1);
  const body=JSON.stringify(f.requests[0]!.body);assert.ok(body.includes('answer-old'));assert.ok(body.includes('answer-recent'));
  assert.deepEqual(f.contexts[0]!.droppedTurnIds,[]);assertAutomaticRequest(f,estimates[0]!);assert.ok(f.requests[0]!.body.max_output_tokens<30000);
});

test('restored Pi assistant usage drives allocation without recounting the whole saved transcript',async()=>{
  const first=fixture();automatic(first);const initial=await execute(first,'retain this long material '.repeat(1000));
  assert.equal(initial.result.state,'completed');assert.ok(initial.session.piSnapshot);
  const next=fixture();automatic(next);const restored=await execute(next,'continue',structuredClone(initial.session.piSnapshot));
  assert.equal(restored.result.state,'completed');assert.equal(restored.usageDetails[0]!.usageTokens,14);
  assert.notEqual(restored.usageDetails[0]!.lastUsageIndex,null);
  assert.ok(restored.estimates[0]!<initial.estimates[0]!, 'the official Pi estimator trusts preserved provider usage plus trailing messages');
  assertAutomaticRequest(next,restored.estimates[0]!);
  assert.ok(JSON.stringify(next.requests[0]!.body).includes('retain this long material'));
});

test('automatic Luna preserves the full 128000 model ceiling when Pi estimates leave room',async()=>{
  const f=fixture();automatic(f,128000,400000);f.snapshot.session.model.modelId='gpt-6-luna';
  const {result,estimates}=await execute(f,'reason through this question',turns(f,['recent','preserve conversation']));
  assert.equal(result.state,'completed');assert.equal(f.requests.length,1);assertAutomaticRequest(f,estimates[0]!);
  assert.equal(f.requests[0]!.body.max_output_tokens,128000);assert.ok(JSON.stringify(f.requests[0]!.body).includes('preserve conversation'));
});

test('explicit output settings retain their selected ceiling without a second history eviction policy',async()=>{
  for(const automaticMarker of [undefined,false]) {
    const f=fixture();Object.assign(f.snapshot.session.model,{contextWindow:32000,maxOutput:24000,outputBudgetAutomatic:automaticMarker,piSettings:{compaction:{enabled:false}}});
    const {result}=await execute(f,'continue',turns(f,['old','older '.repeat(150)],['recent','recent '.repeat(150)]));
    assert.equal(result.state,'completed');assert.equal(f.requests[0]!.body.max_output_tokens,24000);
    const body=JSON.stringify(f.requests[0]!.body);assert.ok(body.includes('answer-old'));assert.ok(body.includes('answer-recent'));
    assert.deepEqual(f.contexts[0]!.droppedTurnIds,[]);
  }
});

test('official Pi estimates leave exactly sixteen output tokens at the context safety boundary',async()=>{
  const baseline=fixture();automatic(baseline);const initial=await execute(baseline,'x');assert.equal(initial.result.state,'completed');
  // Pi estimates user text in ceil(chars / 4). Other Native facts and tools are
  // identical, so replacing the one-token baseline makes this exact boundary.
  const targetInput=32000-4096-16;
  const text='x'.repeat((targetInput-initial.estimates[0]!+1)*4);
  const edge=fixture();automatic(edge);const {result,estimates}=await execute(edge,text);
  assert.equal(result.state,'completed');assert.equal(estimates[0],targetInput);assertAutomaticRequest(edge,estimates[0]!);
  assert.equal(edge.requests[0]!.body.max_output_tokens,16);
});

test('oversized current context cannot bypass the minimum-output safety margin or silently evict history',async()=>{
  const baseline=fixture();automatic(baseline);const initial=await execute(baseline,'x');
  const targetInput=32000-4096-16;
  const text='x'.repeat((targetInput-initial.estimates[0]!+1)*4+4);
  const f=fixture();automatic(f);const {result}=await execute(f,text);
  assert.equal(result.state,'failed');assert.equal(result.error,'LIMIT_EXCEEDED');assert.equal(f.requests.length,0);
});

test('equal context/output is valid only with a boolean automatic marker; larger ceilings remain invalid',()=>{
  const p=profile();p.maxOutput=p.contextWindow;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});
  p.outputBudgetAutomatic=false;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});
  p.outputBudgetAutomatic=true;assert.doesNotThrow(()=>checkProfile(p));p.maxOutput++;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});
  for(const marker of ['true',1,null]) assert.throws(()=>checkProfile({...profile(),outputBudgetAutomatic:marker as unknown as boolean}),{code:'UNSUPPORTED'});
});

test('product continuation after official Pi compaction discards pre-compaction usage when allocating the next request',async()=>{
  const f=fixture('openai-responses',[
    {text:'SUMMARY: preserve the destination and current progress.'},
    {text:'Continued correctly after compaction.'},
  ]);
  Object.assign(f.snapshot.session.model,{contextWindow:16000,maxOutput:1024,
    piSettings:{compaction:{enabled:true,reserveTokens:500,keepRecentTokens:800},retry:{enabled:false}}});
  const recent=piAssistant(f,'Recent checkpoint.');recent.usage={...recent.usage,input:15600,output:10,totalTokens:15610};
  const state=nativePiState(f,[
    {role:'user',content:'Old goal '+ 'older context '.repeat(1200),timestamp:1},piAssistant(f,'Earlier stage complete.'),
    {role:'user',content:'Recent task '+ 'recent context '.repeat(300),timestamp:1},recent,
  ]);
  const logical=new RuntimeSession(f.lease.sessionId);
  const result=await new ThorRuntime(f.ports,registry(),{},logical).run(f.lease,[{type:'text',text:'Current goal'}],state);
  assert.equal(result.state,'completed','retained raw usage from before the compaction must not reject the new smaller request');
  assert.equal(f.requests.length,2,'one real summary request and one continued response');
  assert.equal(logical.piSnapshot!.entries.filter(entry=>entry.type==='compaction').length,1);
  assert.ok(f.contexts.at(-1)!.estimatedTokens<8000);
  const continuation=JSON.stringify(f.requests[1]!.body);
  assert.ok(continuation.includes('SUMMARY: preserve the destination'));assert.ok(continuation.includes('Current goal'));
  assert.ok(!continuation.includes('older context older context'));
  assert.ok(f.events.some(event=>event.type==='diagnostic' && event.data.code==='CONTEXT_COMPACTION_FINISHED' && event.data.compacted===true));
});

test('independent Zen input limit prevents oversized prompts even when total context has room',async()=>{
  const f=fixture();Object.assign(f.snapshot.session.model,{contextWindow:200000,maxInput:16000,maxOutput:32000,outputBudgetAutomatic:true,piSettings:{compaction:{enabled:false}}});
  const result=await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Long input '.repeat(12000)}]);
  assert.equal(result.state,'failed');assert.equal(result.error,'LIMIT_EXCEEDED');assert.equal(f.requests.length,0);
});

test('Zen compacts at the independent input cap while total context still has room',async()=>{
  const f=fixture('openai-responses',[{text:'SUMMARY: keep the current goal.'},{text:'Continued under the input cap.'}]);
  Object.assign(f.snapshot.session.model,{contextWindow:200000,maxInput:16000,maxOutput:1024,
    piSettings:{compaction:{enabled:true,reserveTokens:5000,keepRecentTokens:800},retry:{enabled:false}}});
  const recent=piAssistant(f,'Recent checkpoint.');recent.usage={...recent.usage,input:12500,output:10,totalTokens:12510};
  const state=nativePiState(f,[
    {role:'user',content:'Old goal '+ 'older context '.repeat(1200),timestamp:1},piAssistant(f,'Earlier stage complete.'),
    {role:'user',content:'Recent task '+ 'recent context '.repeat(300),timestamp:1},recent,
  ]);
  const logical=new RuntimeSession(f.lease.sessionId);
  const result=await new ThorRuntime(f.ports,registry(),{},logical).run(f.lease,[{type:'text',text:'Current goal'}],state);
  assert.equal(result.state,'completed');assert.equal(f.requests.length,2);
  assert.equal(logical.piSnapshot!.entries.filter(entry=>entry.type==='compaction').length,1);
  assert.ok(f.events.some(event=>event.type==='diagnostic' && event.data.code==='CONTEXT_COMPACTION_FINISHED' && event.data.compacted===true));
  assert.ok(JSON.stringify(f.requests[1]!.body).includes('SUMMARY: keep the current goal.'));
});
