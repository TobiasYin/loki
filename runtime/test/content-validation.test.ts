import {test} from 'node:test';
import assert from 'node:assert/strict';
import {ThorRuntime,type Content,type ToolOutcome} from '../src/index.js';
import {fixture,registry,descriptor} from './fixtures.js';
import {resolveContent} from '../src/context.js';

for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) {
 test(`${protocol}: current user null/missing/non-string Content is rejected without an HTTP request`,async()=>{
  for(const text of [null,undefined,false,0,{},[]]) {
   const f=fixture(protocol);const bad=[text===undefined?{type:'text'}:{type:'text',text}] as Content[];
   const result=await new ThorRuntime(f.ports,registry()).run(f.lease,bad);
   assert.equal(result.state,'failed');assert.equal(result.error,'PROTOCOL_ERROR');assert.equal(f.requests.length,0);
  }
 });
 test(`${protocol}: malformed current tool text fails protocol and retains its submitted receipt rather than coercing text`,async()=>{
  for(const text of [null,undefined,false,0,{},[]]) {
   const f=fixture(protocol,[{call:{name:'tap',id:'bad_current_text'}},{text:'Inspect actual state before another action.'}]);f.snapshot.session.enabled.screen_control=true;
   const r=new (await import('../src/registry.js')).ToolRegistry();r.register({packId:'current-tool-strict',packVersion:'1',tools:[{...descriptor('tap',['screen_control']),toolId:'input.tap',effectClass:'input',resources:['selected-screen/snapshot']}]});f.setTool(async()=>({content:[text===undefined?{type:'text'}:{type:'text',text}],observedAt:Date.now(),source:'current-native',truncated:false,error:null,outcome:'submitted',evidenceRef:'submitted-invalid-text'} as unknown as ToolOutcome));
   await new ThorRuntime(f.ports,r).run(f.lease,[{type:'text',text:'Do not silently coerce malformed current tool output.'}]);
   const e=f.events.find(e=>e.type==='tool_result')!;assert.equal(e.data.error,'PROTOCOL_ERROR');assert.equal(e.data.outcome,'submitted');assert.equal(e.data.contentDiscarded,true);assert.equal(e.data.recovery,undefined);assert.equal(f.executions.length,1);assert.equal(f.turns.at(-1)!.effects?.[0]?.outcome,'submitted');
  }
 });
}

test('current content references and malformed typed values fail strict protocol validation',async()=>{
 const f=fixture();
 for(const content of [null,[null],[{type:'text',text:null}],[{type:'text'}],[{type:'unknown',text:'not supported'}],[{type:'attachment',attachmentId:null}],[{type:'attachment',attachmentId:''}]])await assert.rejects(resolveContent(content as unknown as Content[],f.ports.attachments,f.snapshot.session.enabled),(e:any)=>e.code==='PROTOCOL_ERROR');
});
