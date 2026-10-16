import {test} from 'node:test';
import assert from 'node:assert/strict';
import {ThorRuntime,RuntimeSession,assertPaired,type HistoryTurn,type Content,type ToolOutcome} from '../src/index.js';
import {fixture,registry,descriptor} from './fixtures.js';
import {project,projectHistory,resolveContent} from '../src/context.js';
import type {Model} from '@earendil-works/pi-ai';

function legacyHistory(textField:unknown=null,missing=false):HistoryTurn[] {
 const text=missing?{type:'text'}:{type:'text',text:textField};
 return [{turnId:'legacy-bug-turn',runId:'legacy-failed-run',source:['screen_perception','screen_control'],observedAt:1,complete:false,
  constraints:['Do not replay previous device actions'],effects:[{toolCallId:'old_background',toolId:'agent.background',effectClass:'input',outcome:'submitted',evidenceRef:'old-submitted-evidence'}],
  messages:[{kind:'user',content:[{type:'text',text:'Previous dual-screen goal'}]},
   {kind:'assistant',text:'',calls:[{id:'old_background',name:'agent_background',arguments:{}}]},
   {kind:'tool_result',toolCallId:'old_background',toolName:'agent_background',content:[{type:'text',text:'ORIGINAL_BACKGROUND_RESULT'}],isError:false},
   {kind:'assistant',text:'',calls:[{id:'old_select',name:'perception_select_screen',arguments:{screen:'lower'}}]},
   {kind:'tool_result',toolCallId:'old_select',toolName:'perception_select_screen',content:[text],isError:true}]},
  {turnId:'unrelated-good-turn',runId:'good-run',source:[],observedAt:2,complete:true,messages:[{kind:'user',content:[{type:'text',text:'UNRELATED_USER_HISTORY'}]},{kind:'assistant',text:'UNRELATED_ASSISTANT_HISTORY',calls:[]}]}] as HistoryTurn[];
}
for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) {
 for(const missing of [false,true])test(`${protocol}: cold saved history with ${missing?'missing':'null'} old tool text resumes through real Pi HTTP without rewriting or replay`,async(t)=>{
  const f=fixture(protocol,[{text:'Continued from preserved history.'}]);f.snapshot.session.enabled.screen_control=true;
  const history=JSON.parse(JSON.stringify(legacyHistory(null,missing))) as HistoryTurn[];
  const original=JSON.stringify(history);const session=new RuntimeSession(f.lease.sessionId);assert.equal(Boolean(session.agent),false);
  const result=await new ThorRuntime(f.ports,registry(),{},session).run(f.lease,[{type:'text',text:'Continue the existing goal; inspect state before any new action.'}],history);
  t.diagnostic(JSON.stringify({protocol,missing,state:result.state,error:result.error,httpRequests:f.requests.length,nativeExecutions:f.executions.length,
   diagnostics:f.events.filter(e=>e.type==='diagnostic').map(e=>e.data),sdkErrors:session.agent?.state.messages.filter(m=>m.role==='assistant').map(m=>m.role==='assistant'?m.errorMessage:undefined).filter(Boolean)}));
  assert.equal(result.state,'completed');assert.equal(f.requests.length,1,'Cold history must reach the actual protocol serializer and HTTP port');assert.equal(f.executions.length,0,'History recovery must never replay either old tool call');
  assert.equal(JSON.stringify(history),original,'Original persisted DomainTurns must be preserved byte-for-byte');assert.equal(history[0]!.effects![0]!.outcome,'submitted');assert.equal(history[0]!.messages.length,5);assert.equal(history[0]!.complete,false);
  const body=JSON.stringify(f.requests[0]!.body);assert.ok(body.includes('此前结果正文不可用'));assert.ok(body.includes('不要重放旧操作'));assert.ok(body.includes('ORIGINAL_BACKGROUND_RESULT'));assert.ok(body.includes('UNRELATED_USER_HISTORY'));assert.ok(body.includes('UNRELATED_ASSISTANT_HISTORY'));assert.ok(body.includes('old-submitted-evidence'));
  assert.ok(session.agent);assertPaired(session.agent.state.messages);
  assert.ok(f.contexts[0]!.includedMessageIds.includes('legacy-bug-turn'));assert.ok(f.contexts[0]!.includedMessageIds.includes('unrelated-good-turn'));assert.equal(f.turns.at(-1)!.complete,true);
 });
}

for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) {
 test(`${protocol}: current user null/missing/non-string Content is rejected without an HTTP request`,async()=>{
  for(const text of [null,undefined,false,0,{},[]]) {
   const f=fixture(protocol);const bad=[text===undefined?{type:'text'}:{type:'text',text}] as Content[];
   const result=await new ThorRuntime(f.ports,registry()).run(f.lease,bad);
   assert.equal(result.state,'failed');assert.equal(result.error,'PROTOCOL_ERROR');assert.equal(f.requests.length,0);
  }
 });
 test(`${protocol}: historical user text is never eligible for legacy tool repair`,async()=>{
  const f=fixture(protocol);f.snapshot.session.enabled.screen_control=true;const history=legacyHistory();
  (history[0]!.messages[0] as any).content[0].text=null;const before=JSON.stringify(history);
  const result=await new ThorRuntime(f.ports,registry(),{},new RuntimeSession(f.lease.sessionId)).run(f.lease,[{type:'text',text:'Continue strictly.'}],history);
  assert.equal(result.state,'failed');assert.equal(result.error,'PROTOCOL_ERROR');assert.equal(f.requests.length,0);assert.equal(JSON.stringify(history),before);
 });
 test(`${protocol}: historical non-null malformed tool text is not silently repaired`,async()=>{
  for(const value of [false,0,{},[]]) {
   const f=fixture(protocol);f.snapshot.session.enabled.screen_control=true;const history=legacyHistory(value);const before=JSON.stringify(history);
   const result=await new ThorRuntime(f.ports,registry(),{},new RuntimeSession(f.lease.sessionId)).run(f.lease,[{type:'text',text:'Continue strictly.'}],history);
   assert.equal(result.state,'failed');assert.equal(result.error,'PROTOCOL_ERROR');assert.equal(f.requests.length,0);assert.equal(JSON.stringify(history),before);
  }
 });
 test(`${protocol}: revoked derived history does not reappear through the compatibility projection`,async()=>{
  const f=fixture(protocol);const history=legacyHistory();const before=JSON.stringify(history);
  const result=await new ThorRuntime(f.ports,registry(),{},new RuntimeSession(f.lease.sessionId)).run(f.lease,[{type:'text',text:'Only authorized context.'}],history);
  assert.equal(result.state,'completed');assert.equal(f.requests.length,1);const body=JSON.stringify(f.requests[0]!.body);
  assert.ok(body.includes('Previous dual-screen goal'));assert.ok(!body.includes('ORIGINAL_BACKGROUND_RESULT'));assert.ok(!body.includes('此前结果正文不可用'));assert.ok(!body.includes('old-submitted-evidence'));assert.equal(JSON.stringify(history),before);
 });
 test(`${protocol}: malformed current tool text fails protocol and retains its submitted receipt rather than coercing text`,async()=>{
  for(const text of [null,undefined,false,0,{},[]]) {
   const f=fixture(protocol,[{call:{name:'tap',id:'bad_current_text'}},{text:'Inspect actual state before another action.'}]);f.snapshot.session.enabled.screen_control=true;
   const r=new (await import('../src/registry.js')).ToolRegistry();r.register({packId:'current-tool-strict',packVersion:'1',tools:[{...descriptor('tap',['screen_control']),toolId:'input.tap',effectClass:'input',resources:['selected-screen/snapshot']}]});f.setTool(async()=>({content:[text===undefined?{type:'text'}:{type:'text',text}],observedAt:Date.now(),source:'current-native',truncated:false,error:null,outcome:'submitted',evidenceRef:'submitted-invalid-text'} as unknown as ToolOutcome));
   await new ThorRuntime(f.ports,r).run(f.lease,[{type:'text',text:'Do not repair new tool output as old history.'}]);
   const e=f.events.find(e=>e.type==='tool_result')!;assert.equal(e.data.error,'PROTOCOL_ERROR');assert.equal(e.data.outcome,'submitted');assert.equal(e.data.contentDiscarded,true);assert.equal(e.data.recovery,undefined);assert.equal(f.executions.length,1);assert.equal(f.turns.at(-1)!.effects?.[0]?.outcome,'submitted');
   assert.ok(!JSON.stringify(f.requests).includes('此前结果正文不可用'));
  }
 });
}

test('historical projection preserves failure flags/pairs while ordinary project remains strict and content references are validated',async()=>{
 const f=fixture();f.snapshot.session.enabled.screen_control=true;
 const p=f.snapshot.session.model;const model:Model<any>={id:p.modelId,name:'History model',provider:'openai',api:p.protocol,reasoning:false,input:['text','image'],baseUrl:p.baseUrl,contextWindow:32000,maxTokens:1024,cost:{input:0,output:0,cacheRead:0,cacheWrite:0}};
 const history=legacyHistory();const original=JSON.stringify(history);
 const projected=await projectHistory(history[0]!.messages,model,f.ports.attachments,f.snapshot);assertPaired(projected);
 const results=projected.filter(m=>m.role==='toolResult');assert.equal(results.length,2);assert.equal(results[0]!.isError,false);assert.equal(results[1]!.isError,true);assert.equal(results[1]!.toolCallId,'old_select');assert.equal(results[1]!.toolName,'perception_select_screen');assert.equal(JSON.stringify(history),original);
 await assert.rejects(project(history[0]!.messages,model,f.ports.attachments,f.snapshot),(e:any)=>e.code==='PROTOCOL_ERROR');
 for(const content of [null,[null],[{type:'text',text:null}],[{type:'text'}],[{type:'unknown',text:'not supported'}],[{type:'attachment',attachmentId:null}],[{type:'attachment',attachmentId:''}]])await assert.rejects(resolveContent(content as unknown as Content[],f.ports.attachments,f.snapshot.session.enabled),(e:any)=>e.code==='PROTOCOL_ERROR');
});
