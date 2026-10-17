import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RuntimeSession } from '../src/session.js';
import { ThorRuntime } from '../src/runtime.js';
import { SessionManager } from '@thor-agent/pi-session-browser';
import { piAssistant, nativePiState } from './pi-test-state.js';
import { fixture, registry } from './fixtures.js';
import type { DomainMessage } from '../src/contracts.js';

test('official Pi records retain signed blocks, their exact order, usage and original model identity across disk serialization',()=>{
  const f=fixture();const message=piAssistant(f,'');message.model='original-model';
  message.usage={...message.usage,input:120,output:34,cacheRead:90,totalTokens:244};
  message.content=[
    {type:'thinking',thinking:'synthetic reason',thinkingSignature:JSON.stringify({type:'reasoning',id:'rs_fixture',encrypted_content:'opaque-fixture'})},
    {type:'text',text:'first',textSignature:'msg_original_first'},
    {type:'text',text:'second',textSignature:'msg_original_second'},
    {type:'toolCall',id:'call_fixture|fc_fixture',name:'echo',arguments:{text:'exact'},namespace:'fixture'},
  ];message.stopReason='toolUse';
  const original=structuredClone(message);
  const state=nativePiState(f,[message,{role:'toolResult',toolCallId:'call_fixture|fc_fixture',toolName:'echo',content:[{type:'text',text:'confirmed'}],isError:false,timestamp:2}]);
  const restored=SessionManager.inMemory('/thor',undefined,JSON.parse(JSON.stringify([state.header,...state.entries])));
  assert.deepEqual(restored.buildSessionProjection().messages[0],original);
  assert.deepEqual(message,original,'serializing native records never rewrites the original signed content');
});
test('native Pi persistence stores exact SDK text signatures and restores the same provider response items',async()=>{
  const f=fixture('openai-responses',[{call:{name:'echo'}},{text:'signed-final'}]);const logical=new RuntimeSession(f.lease.sessionId);
  assert.equal((await new ThorRuntime(f.ports,registry(),{},logical).run(f.lease,[{type:'text',text:'synthetic task'}])).state,'completed');
  const turn=JSON.parse(JSON.stringify(f.turns.at(-1)!));
  const assistant=turn.messages.filter((m:DomainMessage)=>m.kind==='assistant');
  assert.ok(assistant.every((m:Extract<DomainMessage,{kind:'assistant'}>)=>m.replay?.api==='openai-responses'));
  assert.ok(assistant.at(-1).replay.content[0].textSignature,'original SDK message ID is retained');
  const second=fixture('openai-responses',[{text:'followup'}]);
  assert.equal((await new ThorRuntime(second.ports,registry()).run(second.lease,[{type:'text',text:'continue'}],JSON.parse(JSON.stringify(logical.piSnapshot)))).state,'completed');
  const last=assistant.at(-1).replay.content[0]; const signature=JSON.parse(last.textSignature);
  assert.ok(second.requests[0]!.body.input.some((item:any)=>item.id===signature.id && item.content?.[0]?.text==='signed-final'));
  assert.equal(second.requests[0]!.body.prompt_cache_key,f.lease.sessionId,'cache affinity is stable across Run reconstruction');
});
test('real Responses SSE reasoning blocks survive disk replay without moving or dropping them',async()=>{
  const f=fixture('openai-responses',[{call:{name:'echo'}},{text:'finished'}]);
  const fetch=f.ports.network.fetch;
  let first=true;
  f.ports.network.fetch=async(...args)=>{
    const response=await fetch(...args);if(!first)return response;first=false;
    const text=await response.text();
    const events=text.split('\n\n').map(chunk=>chunk.split('\n').find(line=>line.startsWith('data: '))).filter(Boolean).map(line=>JSON.parse(line!.slice(6)));
    const reasoning={type:'reasoning',id:'rs_durable',summary:[],content:[{type:'reasoning_text',text:'synthetic persisted reasoning'}],status:'completed'};
    const output:any[]=[];
    for(const e of events){
      if(e.output_index!==undefined)e.output_index++;
      if(e.response?.output)e.response.output.unshift(reasoning);
      output.push(e);
      if(e.type==='response.created')output.push(
        {type:'response.output_item.added',output_index:0,item:{...reasoning,status:'in_progress'}},
        {type:'response.reasoning_text.delta',output_index:0,item_id:reasoning.id,delta:'synthetic persisted reasoning'},
        {type:'response.output_item.done',output_index:0,item:reasoning});
    }
    return new Response(output.map(e=>`event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join(''),{headers:{'content-type':'text/event-stream'}});
  };
  const logical=new RuntimeSession('session-test');
  assert.equal((await new ThorRuntime(f.ports,registry(),{},logical).run(f.lease,[{type:'text',text:'test reasoning'}])).state,'completed');
  const turn=JSON.parse(JSON.stringify(f.turns.at(-1)!));
  const assistant=turn.messages.find((m:DomainMessage)=>m.kind==='assistant');
  assert.equal(assistant.replay.content[0].type,'thinking');
  assert.equal(JSON.parse(assistant.replay.content[0].thinkingSignature).id,'rs_durable');
  const next=fixture('openai-responses',[{text:'continued'}]);
  assert.equal((await new ThorRuntime(next.ports,registry()).run(next.lease,[{type:'text',text:'next'}],structuredClone(logical.piSnapshot))).state,'completed');
  const previous=f.requests.at(-1)!.body.input;
  assert.deepEqual(next.requests[0]!.body.input.slice(0,previous.length),previous);
});
