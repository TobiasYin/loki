import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Agent } from '@earendil-works/pi-agent-core';
import type { AssistantMessage, Model } from '@earendil-works/pi-ai';
import { AssistantMessageEventStream } from '@earendil-works/pi-ai/utils/event-stream';
import { providerStopBoundary } from '../src/provider-stop.js';
import { ThorRuntime, RuntimeSession } from '../src/index.js';
import { fixture, registry, protocolSse } from './fixtures.js';
const model:Model<any>={id:'fixture',name:'Fixture',api:'openai-responses',provider:'openai',baseUrl:'https://fixture.invalid',reasoning:false,input:['text'],contextWindow:32000,maxTokens:1024,cost:{input:0,output:0,cacheRead:0,cacheWrite:0}};
function message(text:string):AssistantMessage {return {role:'assistant',content:[{type:'text',text}],api:model.api,provider:model.provider,model:model.id,usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}},stopReason:'stop',timestamp:Date.now()};}
const deferred=<T=void>()=>{let resolve!:(v:T)=>void;const promise=new Promise<T>(r=>resolve=r);return {promise,resolve};};
for(const stuck of ['next','result'] as const) test(`actual Pi cancellation terminates a provider stuck in ${stuck}, preserves partial and accepts follow-up`,{timeout:5000},async()=>{
  const controller=new AbortController(),source=new AssistantMessageEventStream(),entered=deferred();
  const partial=message('partial retained');partial.stopReason='aborted';let calls=0;
  const agent=new Agent({initialState:{model},streamFn:()=>{
    calls++;
    if(calls===1)return providerStopBoundary(source,controller.signal,model,async(phase,work)=>{if(phase===`provider_${stuck}`)entered.resolve();return work();});
    const complete=new AssistantMessageEventStream();complete.push({type:'done',reason:'stop',message:message('follow-up')});return complete;
  }});
  const seen=deferred();agent.subscribe(e=>{if(e.type==='message_update' && e.assistantMessageEvent.type==='text_delta')seen.resolve();});
  const prompt=agent.prompt('remember user context');
  source.push({type:'start',partial});source.push({type:'text_delta',contentIndex:0,delta:'partial retained',partial});
  if(stuck==='result')source.end();
  await entered.promise;await seen.promise;
  controller.abort();agent.abort();await prompt;
  assert.equal(agent.state.isStreaming,false);
  assert.equal(agent.state.messages.at(-1)?.role,'assistant');
  assert.equal((agent.state.messages.at(-1) as AssistantMessage).stopReason,'aborted');
  assert.ok(JSON.stringify(agent.state.messages).includes('partial retained'));
  const stopped=JSON.stringify(agent.state.messages);
  partial.content=[{type:'text',text:'late raw provider sentinel'}];
  source.push({type:'done',reason:'stop',message:message('late raw provider sentinel')});source.end();
  await new Promise(r=>setImmediate(r));assert.equal(JSON.stringify(agent.state.messages),stopped);
  await agent.prompt('continue');assert.equal(agent.state.isStreaming,false);assert.equal(calls,2);
  assert.ok(JSON.stringify(agent.state.messages).includes('remember user context'));
  assert.ok(JSON.stringify(agent.state.messages).includes('follow-up'));
});

for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) {
 test(`${protocol}: UI stop terminates real SDK partial SSE with uncooperative reader and preserves the same Pi session`,{timeout:5000},async()=>{
  const f=fixture(protocol,[{call:{name:'echo',id:'completed_before_stop'}}]),session=new RuntimeSession(f.lease.sessionId),runtime=new ThorRuntime(f.ports,registry(),{},session);
  const originalFetch=f.ports.network.fetch;let requests=0;
  let stream!:ReadableStreamDefaultController<Uint8Array>;let cancelled=0;const entered=deferred();
  f.ports.network.fetch=async(...args)=>{if(requests++===0)return originalFetch(...args);return new Response(new ReadableStream<Uint8Array>({start(c){stream=c;c.enqueue(new TextEncoder().encode(protocolSse(protocol,{text:'partial screen evidence',disconnect:true})));},pull(){return new Promise(()=>{});},cancel(){cancelled++;return new Promise(()=>{});}}),{headers:{'content-type':'text/event-stream'}});};
  runtime.subscribe(e=>{if(e.type==='text_delta')entered.resolve();});
  const running=runtime.run(f.lease,[{type:'text',text:'remember stop context'}]);await entered.promise;
  const diagnostic=runtime.diagnostics();assert.ok(diagnostic.waits?.some(w=>w.phase==='agent_prompt'));assert.ok(diagnostic.waits?.some(w=>w.phase==='provider_next'));assert.equal(diagnostic.piStreaming,true);
  assert.ok(!JSON.stringify(diagnostic).includes('partial screen evidence'));
  runtime.stop();const stopped=await running;
  assert.equal(stopped.state,'cancelled');assert.equal(session.agent!.state.isStreaming,false);assert.equal(f.executions.length,1);
  assert.ok(JSON.stringify(f.turns.at(-1)).includes('echo-result'));
  assert.ok(JSON.stringify(f.turns.at(-1)).includes('partial screen evidence'));assert.equal(f.runs.filter(r=>r.state==='cancelled').length,1);
  const events=f.events.length;try{stream.enqueue(new TextEncoder().encode(protocolSse(protocol,{text:'late sentinel'})));}catch{}
  await new Promise(r=>setImmediate(r));assert.equal(f.events.length,events);
  const prior=session.agent,second=fixture(protocol,[{text:'resumed'}]);
  assert.equal((await new ThorRuntime(second.ports,registry(),{},session).run(second.lease,[{type:'text',text:'continue'}],[f.turns.at(-1)!])).state,'completed');
  assert.equal(session.agent,prior);assert.equal(second.executions.length,0);
  assert.ok(JSON.stringify(second.requests[0]!.body).includes('remember stop context'));
  assert.ok(JSON.stringify(second.requests[0]!.body).includes('partial screen evidence'));
  assert.equal(cancelled,1);assert.equal(runtime.diagnostics().reserved,false);
 });
}

test('stop during request body serialization cancels actual SDK before Native transport and late serialization cannot resume it',{timeout:5000},async()=>{
 const f=fixture(),session=new RuntimeSession(f.lease.sessionId),runtime=new ThorRuntime(f.ports,registry(),{},session);
 const entered=deferred(),release=deferred<ArrayBuffer>(),clone=Request.prototype.clone;
 Request.prototype.clone=function(){const value=clone.call(this);if(this.url.includes('fixture.invalid'))Object.defineProperty(value,'arrayBuffer',{value:()=>{entered.resolve();return release.promise;}});return value;};
 try {
  const running=runtime.run(f.lease,[{type:'text',text:'serialization context'}]);await entered.promise;
  assert.ok(runtime.diagnostics().waits?.some(w=>w.phase==='request_body'));
  runtime.stop();assert.equal((await running).state,'cancelled');assert.equal(session.agent!.state.isStreaming,false);assert.equal(f.requests.length,0);
  const count=f.events.length;release.resolve(new ArrayBuffer(0));await new Promise(r=>setImmediate(r));assert.equal(f.events.length,count);assert.equal(f.requests.length,0);
 } finally {Request.prototype.clone=clone;release.resolve(new ArrayBuffer(0));}
 const agent=session.agent,second=fixture();assert.equal((await new ThorRuntime(second.ports,registry(),{},session).run(second.lease,[{type:'text',text:'continue'}],[f.turns.at(-1)!])).state,'completed');assert.equal(session.agent,agent);assert.equal(second.executions.length,0);
});
