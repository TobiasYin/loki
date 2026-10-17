import test from 'node:test';
import assert from 'node:assert/strict';
import type { Model } from '@earendil-works/pi-ai';
import type { HistoryTurn, ModelProfile } from '../src/contracts.js';
import { compileContext } from '../src/context.js';
import { checkProfile } from '../src/profile-validation.js';
import { ThorRuntime } from '../src/runtime.js';
import { fixture, registry, profile } from './fixtures.js';

function turn(id:string,text:string):HistoryTurn {
  return {turnId:id,runId:`run-${id}`,source:[],observedAt:1,complete:true,messages:[{kind:'user',content:[{type:'text',text}]},{kind:'assistant',text:`answer-${id}`,calls:[]}]};
}
function model(p:ModelProfile):Model<any> {
  return {id:p.modelId,name:p.label,api:p.protocol,provider:'openai',baseUrl:p.baseUrl,reasoning:false,input:['text'],contextWindow:p.contextWindow,maxTokens:p.maxOutput,cost:{input:0,output:0,cacheRead:0,cacheWrite:0}};
}
function options(f:ReturnType<typeof fixture>,history:HistoryTurn[]=[],text='current task') {
  return {snapshot:f.snapshot,tools:registry().effective(f.snapshot),registryVersion:'1',model:model(f.snapshot.session.model),history,current:[{role:'user' as const,content:text,timestamp:1}],attachments:f.ports.attachments,imageTokens:0,maxHistoryTurns:32};
}

for(const [modelId,contextWindow] of [['grok-4.20-beta',500000],['kimi-k2.6',262144]] as const) {
  for(const gap of [0,1]) test(`${modelId} automatic catalog output=context-${gap} sends while retaining recent history`,async()=>{
    const f=fixture();Object.assign(f.snapshot.session.model,{modelId,provider:'opencode-go',providerMetadataKnown:true,baseUrl:'https://opencode.ai/zen/go/v1',input:['text'],supportsTools:true,reasoning:false,contextWindow,maxOutput:contextWindow-gap,outputBudgetAutomatic:true});
    const history=[turn('earlier','earlier task context'),turn('recent','recent game progress')];const saved=structuredClone(f.snapshot.session.model);
    const result=await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'continue from recent game progress'}],history);
    assert.equal(result.state,'completed');assert.equal(f.requests.length,1);
    assert.deepEqual(f.contexts[0]!.droppedTurnIds,[]);assert.ok(f.contexts[0]!.includedMessageIds.includes('recent'));
    assert.ok(JSON.stringify(f.requests[0]!.body).includes('recent game progress'));
    const actual=f.requests[0]!.body.max_output_tokens;assert.equal(actual,contextWindow-f.contexts[0]!.estimatedTokens-4096);assert.ok(actual>=16);assert.ok(actual<contextWindow-gap);
    assert.equal(f.events.find(e=>e.type==='model_round')!.data.requestedMaxOutputTokens,actual);
    assert.deepEqual(f.snapshot.session.model,saved,'effective per-round output never overwrites the saved profile');
  });
}

test('automatic allocation shrinks output before discarding history and retains all bounded turns that fit',async()=>{
  const f=fixture();Object.assign(f.snapshot.session.model,{contextWindow:32000,maxOutput:30000,outputBudgetAutomatic:true});
  const history=[turn('old','old '.repeat(800)),turn('recent','recent '.repeat(600))];const c=await compileContext(options(f,history));
  assert.deepEqual(c.manifest.droppedTurnIds,[]);assert.deepEqual(c.manifest.includedMessageIds.slice(0,2),['old','recent']);
  assert.equal(c.maxOutput,32000-c.manifest.estimatedTokens-4096);assert.ok(c.maxOutput<30000);assert.ok(c.maxOutput>=16);
});

test('automatic input overflow drops only the oldest whole turn before allocating output',async()=>{
  const f=fixture();Object.assign(f.snapshot.session.model,{contextWindow:32000,maxOutput:32000,outputBudgetAutomatic:true});
  const history=[turn('old','old'.repeat(7000)),turn('recent','recent'.repeat(300))];const c=await compileContext(options(f,history));
  assert.deepEqual(c.manifest.droppedTurnIds,['old']);assert.ok(c.manifest.includedMessageIds.includes('recent'));
  assert.ok(JSON.stringify(c.messages).includes('answer-recent'));assert.ok(!JSON.stringify(c.messages).includes('answer-old'));
  assert.equal(c.maxOutput,32000-c.manifest.estimatedTokens-4096);assert.ok(c.maxOutput>=16);
});

test('automatic Luna retains its complete 128000 ceiling whenever the input leaves room',async()=>{
  const f=fixture();Object.assign(f.snapshot.session.model,{modelId:'gpt-6-luna',contextWindow:400000,maxOutput:128000,outputBudgetAutomatic:true});
  const c=await compileContext(options(f,[turn('recent','preserve conversation')],'reason through this question'));
  assert.equal(c.maxOutput,128000);assert.deepEqual(c.manifest.droppedTurnIds,[]);
  const result=await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'short request'}]);
  assert.equal(result.state,'completed');assert.equal(f.requests[0]!.body.max_output_tokens,128000);
});

test('explicit and custom output budgets keep the previous reservation and compression behavior',async()=>{
  const f=fixture();Object.assign(f.snapshot.session.model,{contextWindow:32000,maxOutput:24000});
  const history=[turn('old','old'.repeat(7000)),turn('recent','recent'.repeat(100))];
  const explicit=await compileContext(options(f,history));f.snapshot.session.model.outputBudgetAutomatic=false;
  const explicitlyFalse=await compileContext(options(f,history));
  assert.equal(explicit.maxOutput,24000);assert.equal(explicitlyFalse.maxOutput,24000);
  assert.deepEqual(explicit.manifest.droppedTurnIds,explicitlyFalse.manifest.droppedTurnIds);assert.ok(explicit.manifest.droppedTurnIds.includes('old'));
  assert.deepEqual(explicit.messages.map(m=>({...m,timestamp:0})),explicitlyFalse.messages.map(m=>({...m,timestamp:0})));assert.ok(explicit.manifest.estimatedTokens<=8000);
});

test('automatic output leaves exactly the minimum sixteen tokens and rejects oversized current input without network',async()=>{
  const f=fixture();Object.assign(f.snapshot.session.model,{contextWindow:32000,maxOutput:32000,outputBudgetAutomatic:true});
  const initial=await compileContext(options(f,[],''));const filler='x'.repeat(32000-initial.manifest.estimatedTokens-4096-16);
  const edge=await compileContext(options(f,[],filler));assert.equal(edge.maxOutput,16);assert.equal(edge.manifest.estimatedTokens+4096+16,32000);
  await assert.rejects(compileContext(options(f,[],filler+'x')),{code:'LIMIT_EXCEEDED'});
  const result=await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'x'.repeat(32000)}]);
  assert.equal(result.state,'failed');assert.equal(result.error,'LIMIT_EXCEEDED');assert.equal(f.requests.length,0);
});

test('equal context/output is valid only with a boolean automatic marker; larger ceilings remain invalid',()=>{
  const p=profile();p.maxOutput=p.contextWindow;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});
  p.outputBudgetAutomatic=false;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});
  p.outputBudgetAutomatic=true;assert.doesNotThrow(()=>checkProfile(p));p.maxOutput++;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});
  for(const marker of ['true',1,null]) assert.throws(()=>checkProfile({...profile(),outputBudgetAutomatic:marker as unknown as boolean}),{code:'UNSUPPORTED'});
});
