import test from 'node:test';
import assert from 'node:assert/strict';
import { ModelRoundDiagnostics } from '../src/model-round-diagnostics.js';
import { ThorRuntime } from '../src/runtime.js';
import { RuntimeSession } from '../src/session.js';
import { fixture, registry, type Reply, protocolSse } from './fixtures.js';

const event = (type:string,response:unknown) => `event: ${type}\ndata: ${JSON.stringify({type,response})}\n\n`;
function replaceStream(f:ReturnType<typeof fixture>,body:string):void {
  const original=f.ports.network.fetch;
  f.ports.network.fetch=async(...args)=>{
    // Still pass through the fixture's request capture and the real Pi payload builder.
    const response=await original(...args);await response.body?.cancel();
    return new Response(body,{headers:{'content-type':'text/event-stream'}});
  };
}

test('reasoning-only output exhaustion records actual requested budget and reported usage without losing the Pi session',async()=>{
  const f=fixture();const session=new RuntimeSession('session-test');
  f.snapshot.session.model.maxOutput=2048;f.snapshot.session.model.reasoning=true;f.snapshot.session.model.thinkingEffort='low';
  replaceStream(f,event('response.created',{id:'synthetic-private-id',status:'in_progress'})+event('response.incomplete',{
    id:'synthetic-private-id',status:'incomplete',incomplete_details:{reason:'max_output_tokens',private:'must-not-persist'},output:[],
    usage:{input_tokens:987,output_tokens:2048,output_tokens_details:{reasoning_tokens:2048},total_tokens:3035},
  }));
  const result=await new ThorRuntime(f.ports,registry(),{},session).run(f.lease,[{type:'text',text:'保留任务继续'}]);
  assert.equal(result.state,'failed');assert.equal(result.error,'LIMIT_EXCEEDED');assert.equal(f.executions.length,0);assert.equal(f.requests.length,1);
  assert.equal(f.requests[0]!.body.max_output_tokens,2048);assert.equal(f.requests[0]!.body.reasoning.effort,'low');
  const safe={modelRound:1,requestedMaxOutputTokens:2048,incompleteReason:'max_output_tokens',inputTokens:987,totalInputTokens:987,outputTokens:2048,reasoningTokens:2048,stopReason:'length'};
  assert.deepEqual(f.events.find(e=>e.type==='model_round')!.data,safe);
  assert.deepEqual(f.events.find(e=>e.type==='diagnostic')!.data,{code:'MODEL_OUTPUT_LIMIT',...safe});
  assert.ok(!JSON.stringify(f.events).includes('synthetic-private-id'));assert.ok(!JSON.stringify(f.events).includes('must-not-persist'));
  const agent=session.agent;assert.ok(agent);assert.equal(f.turns.at(-1)!.complete,false);
  const priorEntries=structuredClone(session.piSnapshot!.entries);
  const next=fixture();assert.equal((await new ThorRuntime(next.ports,registry(),{},session).run(next.lease,[{type:'text',text:'继续'}])).state,'completed');assert.notEqual(session.agent,agent);
  assert.deepEqual(session.piSnapshot!.entries.slice(0,priorEntries.length),priorEntries);
  assert.ok(JSON.stringify(next.requests[0]!.body).includes('保留任务继续'));
});

for(const reason of ['content_filter','private-provider-reason',''] as const) test(`Responses incomplete ${reason || 'missing reason'} never becomes output-limit or success`,async()=>{
  const f=fixture();replaceStream(f,event('response.created',{id:'synthetic',status:'in_progress'})+event('response.incomplete',{
    id:'synthetic',status:'incomplete',...(reason?{incomplete_details:{reason}}:{}),output:[],usage:{input_tokens:10,output_tokens:0,output_tokens_details:{reasoning_tokens:0}},
  }));
  const result=await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'test'}]);
  assert.equal(result.state,'failed');assert.equal(result.error,'IO_ERROR');assert.equal(f.executions.length,0);assert.equal(f.requests.length,1);
  assert.ok(!f.events.some(e=>e.type==='diagnostic'&&e.data.code==='MODEL_OUTPUT_LIMIT'));
  assert.equal(f.events.find(e=>e.type==='model_round')!.data.incompleteReason,reason==='content_filter'?reason:'unknown');
  assert.ok(!JSON.stringify(f.events).includes('private-provider-reason'));
});

test('Completions content filtering remains an error and retains reported usage without raw details',async()=>{
  const f=fixture('openai-completions');
  const chunk={id:'private-id',object:'chat.completion.chunk',created:0,model:'private-model',choices:[{index:0,delta:{role:'assistant'},finish_reason:'content_filter'}],usage:{prompt_tokens:42,completion_tokens:7,completion_tokens_details:{reasoning_tokens:3}}};
  replaceStream(f,`data: ${JSON.stringify(chunk)}\n\ndata: [DONE]\n\n`);
  const result=await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'test'}]);
  assert.equal(result.state,'failed');assert.equal(result.error,'IO_ERROR');assert.equal(f.requests.length,1);assert.equal(f.executions.length,0);
  assert.deepEqual(f.events.find(e=>e.type==='model_round')!.data,{modelRound:1,requestedMaxOutputTokens:1024,inputTokens:42,totalInputTokens:42,outputTokens:7,reasoningTokens:3,incompleteReason:'content_filter',stopReason:'error'});
  assert.ok(!JSON.stringify(f.events).includes('private-id'));assert.ok(!JSON.stringify(f.events).includes('private-model'));
});

for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) test(`${protocol} bounded Pi continuation never executes truncated partial tool arguments`,async()=>{
  const reply:Reply={call:{name:'echo',args:'{"text":"unfinished'},stopReason:'length'};const f=fixture(protocol,[reply,reply]);
  f.snapshot.session.model.piSettings={compaction:{enabled:false},retry:{enabled:false}};
  if(protocol==='openai-responses') {
    const raw=protocolSse(protocol,reply).split('\n\n').filter(frame=>!frame.startsWith('event: response.output_item.done')).join('\n\n');replaceStream(f,raw+'\n\n');
  }
  // Pi owns incomplete-tool continuation. Bound this deliberately repeated provider
  // fixture to two requests; neither salvaged partial may reach Native.
  const result=await new ThorRuntime(f.ports,registry(),{maxRequests:2}).run(f.lease,[{type:'text',text:'one incomplete action'}]);
  assert.equal(result.state,'failed');assert.equal(result.error,'LIMIT_EXCEEDED');assert.equal(f.executions.length,0);assert.equal(f.requests.length,2);
  assert.ok(!f.events.some(e=>e.type==='tool_started'));assert.ok(!f.turns.at(-1)!.messages.some(m=>m.kind==='assistant'&&m.calls.length));
});

for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) test(`${protocol} successful rounds report the actual payload budget outside visible diagnostics`,async()=>{
  const f=fixture(protocol,[{call:{name:'echo'}},{text:'done'}]);f.snapshot.session.model.maxOutput=4096;
  assert.equal((await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'test'}])).state,'completed');
  const rounds=f.events.filter(e=>e.type==='model_round');assert.equal(rounds.length,2);assert.deepEqual(rounds.map(e=>e.data.modelRound),[1,2]);
  for(let i=0;i<rounds.length;i++) {
    const body=f.requests[i]!.body;const requested=protocol==='openai-responses'?body.max_output_tokens:body.max_completion_tokens??body.max_tokens;
    assert.equal(requested,4096);assert.equal(rounds[i]!.data.requestedMaxOutputTokens,requested);
    // This Completions fixture does not report usage: missing is not zero.
    assert.equal(rounds[i]!.data.inputTokens,protocol==='openai-completions'?undefined:10);assert.equal(rounds[i]!.data.outputTokens,protocol==='openai-completions'?undefined:4);
  }
  assert.ok(!f.events.some(e=>e.type==='diagnostic'));
});

for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) test(`${protocol} restored Pi session retains bounded truncated rounds and explicit follow-up never replays Native work`,async()=>{
  const session=new RuntimeSession('session-test');const first=fixture(protocol,[{call:{name:'echo'}},{text:'first task completed'}]);
  assert.equal((await new ThorRuntime(first.ports,registry(),{},session).run(first.lease,[{type:'text',text:'first task'}])).state,'completed');assert.equal(first.executions.length,1);
  const agent=session.agent; const firstEntries=structuredClone(session.piSnapshot!.entries);
  const second=fixture(protocol,[{call:{name:'echo',args:'{"text":"partial'},stopReason:'length'},{call:{name:'echo',args:'{"text":"must not retry"}'}}]);
  second.snapshot.session.model.piSettings={compaction:{enabled:false},retry:{enabled:false}};
  const failed=await new ThorRuntime(second.ports,registry(),{maxRequests:1},session).run(second.lease,[{type:'text',text:'second task'}]);
  assert.equal(failed.state,'failed');assert.equal(failed.error,'LIMIT_EXCEEDED');assert.equal(second.requests.length,1);assert.equal(second.executions.length,0);assert.notEqual(session.agent,agent);
  assert.deepEqual(session.piSnapshot!.entries.slice(0,firstEntries.length),firstEntries);
  const truncatedEntries=structuredClone(session.piSnapshot!.entries);
  assert.ok(truncatedEntries.some(entry=>entry.type==='message' && entry.message.role==='assistant' && entry.message.stopReason==='length'));
  assert.ok(!second.events.some(event=>event.type==='tool_started' || event.type==='tool_intent'));
  const followup=fixture(protocol,[{text:'explicitly continued'}]);
  assert.equal((await new ThorRuntime(followup.ports,registry(),{},session).run(followup.lease,[{type:'text',text:'continue manually'}])).state,'completed');
  assert.notEqual(session.agent,agent);assert.deepEqual(session.piSnapshot!.entries.slice(0,truncatedEntries.length),truncatedEntries);assert.equal(followup.executions.length,0);assert.equal(followup.requests.length,1);
  assert.ok(JSON.stringify(followup.requests[0]!.body).includes('first task'));assert.ok(JSON.stringify(followup.requests[0]!.body).includes('second task'));
});

test('diagnostic observer drops unsafe counters, raw metadata and unrecognized reasons',()=>{
  const d=new ModelRoundDiagnostics(1);d.payload('openai-responses',{max_output_tokens:Infinity,headers:'private',model:'private'});
  d.observe('openai-responses',{type:'response.incomplete',response:{id:'private',incomplete_details:{reason:'private'},usage:{input_tokens:NaN,output_tokens:-1,output_tokens_details:{reasoning_tokens:'2048'}}}});
  d.finish('private');assert.deepEqual(d.snapshot(),{modelRound:1,incompleteReason:'unknown',stopReason:'unknown'});
  d.payload('openai-completions',{max_tokens:0,max_completion_tokens:4096});
  d.observe('openai-completions',{usage:{prompt_tokens:0,completion_tokens:Number.MAX_SAFE_INTEGER+1,completion_tokens_details:{reasoning_tokens:0}}});
  assert.deepEqual(d.snapshot(),{modelRound:1,incompleteReason:'unknown',stopReason:'unknown',requestedMaxOutputTokens:4096,inputTokens:0,totalInputTokens:0,reasoningTokens:0});
});

test('Responses persists cache accounting at the real streaming boundary without double-counting reasoning',async()=>{
  const f=fixture();replaceStream(f,event('response.created',{id:'private',status:'in_progress'})+event('response.completed',{
    id:'private',status:'completed',output:[{id:'m',type:'message',role:'assistant',status:'completed',content:[{type:'output_text',text:'done',annotations:[]}]}],
    usage:{input_tokens:1000,input_tokens_details:{cached_tokens:800,cache_write_tokens:40},output_tokens:100,output_tokens_details:{reasoning_tokens:60},total_tokens:1100},
  }));
  assert.equal((await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'test'}])).state,'completed');
  const round=f.events.find(e=>e.type==='model_round')!.data;
  assert.deepEqual(round,{modelRound:1,requestedMaxOutputTokens:1024,inputTokens:1000,totalInputTokens:1000,outputTokens:100,reasoningTokens:60,cacheReadTokens:800,cacheWriteTokens:40,uncachedInputTokens:160,stopReason:'stop'});
  assert.equal(Number(round.totalInputTokens)+Number(round.outputTokens),1100);
});

test('Completions accepts DeepSeek cache hit/miss and prefers standard detail counters',()=>{
  const d=new ModelRoundDiagnostics(1);
  d.observe('openai-completions',{usage:{prompt_tokens:1000,completion_tokens:90,prompt_cache_hit_tokens:750,prompt_cache_miss_tokens:250,completion_tokens_details:{reasoning_tokens:30}}});
  assert.deepEqual(d.snapshot(),{modelRound:1,inputTokens:1000,totalInputTokens:1000,outputTokens:90,cacheReadTokens:750,uncachedInputTokens:250,reasoningTokens:30});
  d.observe('openai-completions',{usage:{prompt_tokens:1000,prompt_tokens_details:{cached_tokens:800,cache_creation_tokens:10},prompt_cache_hit_tokens:750,prompt_cache_miss_tokens:190}});
  assert.equal(d.snapshot().cacheReadTokens,800);assert.equal(d.snapshot().cacheWriteTokens,10);assert.equal(d.snapshot().uncachedInputTokens,190);
});

test('Anthropic start and cumulative delta counters merge without losing cache input or summing repeated totals',()=>{
  const d=new ModelRoundDiagnostics(1);
  d.observe('anthropic-messages',{type:'message_start',message:{usage:{input_tokens:100,output_tokens:1,cache_read_input_tokens:800,cache_creation_input_tokens:200}}});
  d.observe('anthropic-messages',{type:'message_delta',usage:{output_tokens:40},delta:{stop_reason:'end_turn'}});
  d.observe('anthropic-messages',{type:'message_delta',usage:{output_tokens:40}});
  assert.deepEqual(d.snapshot(),{modelRound:1,inputTokens:100,totalInputTokens:1100,uncachedInputTokens:100,outputTokens:40,cacheReadTokens:800,cacheWriteTokens:200});
});

test('missing cache counters remain absent while an explicitly reported zero remains measurable',()=>{
  const missing=new ModelRoundDiagnostics(1);missing.observe('openai-completions',{usage:{prompt_tokens:42,completion_tokens:7}});
  assert.equal(missing.snapshot().cacheReadTokens,undefined);assert.equal(missing.snapshot().cacheWriteTokens,undefined);assert.equal(missing.snapshot().uncachedInputTokens,undefined);
  const zero=new ModelRoundDiagnostics(1);zero.observe('openai-responses',{type:'response.completed',response:{usage:{input_tokens:42,input_tokens_details:{cached_tokens:0},output_tokens:7}}});
  assert.equal(zero.snapshot().cacheReadTokens,0);assert.equal(zero.snapshot().uncachedInputTokens,42);
});

test('cache counters reject unsafe values, fall back to valid aliases, and omit impossible derived counts',()=>{
  const d=new ModelRoundDiagnostics(1);
  d.observe('openai-completions',{usage:{prompt_tokens:10,prompt_tokens_details:{cached_tokens:-1,cache_write_tokens:'2'},prompt_cache_hit_tokens:20,prompt_cache_miss_tokens:Infinity,private:'not-recorded'}});
  assert.deepEqual(d.snapshot(),{modelRound:1,inputTokens:10,totalInputTokens:10,cacheReadTokens:20});
  const overflow=new ModelRoundDiagnostics(1);overflow.observe('anthropic-messages',{type:'message_start',message:{usage:{input_tokens:Number.MAX_SAFE_INTEGER,cache_read_input_tokens:1}}});
  assert.equal(overflow.snapshot().totalInputTokens,undefined);
});
