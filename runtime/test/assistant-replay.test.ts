import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ThorRuntime } from '../src/runtime.js';
import { projectHistory } from '../src/context.js';
import { fixture, registry } from './fixtures.js';
import type { DomainMessage } from '../src/contracts.js';
import type { Model } from '@earendil-works/pi-ai';

function model(f: ReturnType<typeof fixture>): Model<any> {
  const p=f.snapshot.session.model;
  return { id:p.modelId,name:p.label,api:p.protocol,provider:'openai',baseUrl:p.baseUrl,reasoning:true,input:['text','image'],contextWindow:p.contextWindow,maxTokens:p.maxOutput,cost:{input:0,output:0,cacheRead:0,cacheWrite:0} };
}
test('durable assistant replay preserves signed blocks, order, and original model identity',async()=>{
  const f=fixture(); const content:NonNullable<Extract<DomainMessage,{kind:'assistant'}>['replay']>['content']=[
    {type:'thinking',thinking:'synthetic reason',thinkingSignature:JSON.stringify({type:'reasoning',id:'rs_fixture',encrypted_content:'opaque-fixture'})},
    {type:'text',text:'first',textSignature:'msg_original_first'},
    {type:'text',text:'second',textSignature:'msg_original_second'},
    {type:'toolCall',id:'call_fixture|fc_fixture',name:'echo',arguments:{text:'exact'},namespace:'fixture'},
  ];
  const messages:DomainMessage[]=[{kind:'assistant',text:'firstsecond',calls:[{id:'call_fixture|fc_fixture',name:'echo',arguments:{text:'exact'}}],replay:{api:'openai-responses',provider:'openai',model:'original-model',baseUrl:f.snapshot.session.model.baseUrl,content}}];
  const projected=await projectHistory(JSON.parse(JSON.stringify(messages)),model(f),f.ports.attachments,f.snapshot);
  assert.equal(projected[0]!.role,'assistant');if(projected[0]!.role!=='assistant')return;
  assert.deepEqual(projected[0]!.content,content);assert.equal(projected[0]!.model,'original-model');
  projected[0]!.content.pop();assert.equal(messages[0]!.kind==='assistant' && messages[0]!.replay!.content.length,4);
  const foreign=await projectHistory(messages,{...model(f),baseUrl:'https://another.invalid'},f.ports.attachments,f.snapshot);
  assert.equal(foreign[0]!.role,'assistant');if(foreign[0]!.role!=='assistant')return;
  assert.equal(foreign[0]!.content.some(c=>c.type==='thinking'),false);
  assert.equal(JSON.stringify(foreign).includes('opaque-fixture'),false);
});
test('saveTurn stores exact SDK text signatures on disk and restores the same response items',async()=>{
  const f=fixture('openai-responses',[{call:{name:'echo'}},{text:'signed-final'}]);
  assert.equal((await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'synthetic task'}])).state,'completed');
  const turn=JSON.parse(JSON.stringify(f.turns.at(-1)!));
  const assistant=turn.messages.filter((m:DomainMessage)=>m.kind==='assistant');
  assert.ok(assistant.every((m:Extract<DomainMessage,{kind:'assistant'}>)=>m.replay?.api==='openai-responses'));
  assert.ok(assistant.at(-1).replay.content[0].textSignature,'original SDK message ID is retained');
  const second=fixture('openai-responses',[{text:'followup'}]);
  assert.equal((await new ThorRuntime(second.ports,registry()).run(second.lease,[{type:'text',text:'continue'}],[turn])).state,'completed');
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
  assert.equal((await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'test reasoning'}])).state,'completed');
  const turn=JSON.parse(JSON.stringify(f.turns.at(-1)!));
  const assistant=turn.messages.find((m:DomainMessage)=>m.kind==='assistant');
  assert.equal(assistant.replay.content[0].type,'thinking');
  assert.equal(JSON.parse(assistant.replay.content[0].thinkingSignature).id,'rs_durable');
  const next=fixture('openai-responses',[{text:'continued'}]);
  assert.equal((await new ThorRuntime(next.ports,registry()).run(next.lease,[{type:'text',text:'next'}],[turn])).state,'completed');
  const previous=f.requests.at(-1)!.body.input;
  assert.deepEqual(next.requests[0]!.body.input.slice(0,previous.length),previous);
});
