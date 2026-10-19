import {test} from 'node:test';
import assert from 'node:assert/strict';
import {ThorRuntime,RuntimeSession,ToolRegistry,type ToolOutcome,RuntimeError,ERROR_CODES,isErrorCode,type ErrorCode} from '../src/index.js';
import {descriptor,fixture,PNG} from './fixtures.js';

function registry() {const r=new ToolRegistry();r.register({packId:'native-null-success-fixture',packVersion:'1',tools:[
 {...descriptor('select'),toolId:'perception.select_screen',resources:['this-run/target-snapshot']},
 {...descriptor('capture'),toolId:'perception.capture_screen',resources:['selected-screen/snapshot']},
 {...descriptor('tap',['screen_control']),toolId:'input.tap',resources:['selected-screen/snapshot'],effectClass:'input'},
]});return r;}
const success=(text='Native success'):ToolOutcome=>({content:[{type:'text',text}],observedAt:Date.now(),source:'fixture-native-shaped',truncated:false,error:null,outcome:'observed'});
for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) {
 test(`${protocol}: Native error:null select and capture succeed, transport image to next request, and clear only the current-screen observation gate`,async()=>{
  const f=fixture(protocol,[{call:{name:'select',id:'select_lower'}},{call:{name:'tap',id:'blocked_before_observation'}},{call:{name:'capture',id:'fresh_capture'}},{call:{name:'tap',id:'new_action'}},{text:'Observed the new screen and completed.'}]);f.snapshot.session.enabled.screen_control=true;
  const session=new RuntimeSession(f.lease.sessionId);const runtime=new ThorRuntime(f.ports,registry(),{},session);const agents:unknown[]=[];
  let selected=false;const read=f.ports.authority.read;
  f.ports.authority.read=async(...args)=>{if(selected&&args[2])f.snapshot.l0.snapshotId='native-lower-observation';return read(...args);};
  f.setOnRequest(()=>{agents.push(session.agent);});
  f.setTool(async e=>{
   if(e.toolId==='perception.select_screen'){selected=true;f.snapshot.l0.targetScreen='lower';return success(JSON.stringify({selectedScreen:'lower',targetSnapshotId:'native-lower-observation',readOnlySelection:true}));}
   if(e.toolId==='perception.capture_screen')return {...success('NULL-SUCCESS-IMAGE'),content:[{type:'text',text:'NULL-SUCCESS-IMAGE'},{type:'attachment',attachmentId:'tool-image'}]};
   assert.ok(e.toolCallId.startsWith('new_action'),'Selection cannot clear the screen observation gate by itself');return {...success('ONE-INPUT-SUBMITTED'),outcome:'submitted'};
  });
  assert.equal((await runtime.run(f.lease,[{type:'text',text:'Select the lower screen, observe it, then act.'}])).state,'completed');
  assert.deepEqual(f.executions.map(e=>e.toolId),['perception.select_screen','perception.capture_screen','input.tap']);
  const results=f.events.filter(e=>e.type==='tool_result');
  const ok=results.filter(e=>e.toolCallId?.startsWith('select_lower')||e.toolCallId?.startsWith('fresh_capture')||e.toolCallId?.startsWith('new_action'));
  assert.equal(ok.length,3);assert.ok(ok.every(e=>e.data.error===undefined&&e.data.contentDiscarded!==true));
  const blocked=results.find(e=>e.toolCallId?.startsWith('blocked_before_observation'))!;assert.equal(blocked.data.error,'TARGET_CHANGED');assert.equal((blocked.data.recovery as any).submission,'not_dispatched');
  assert.ok(!f.events.some(e=>e.type==='diagnostic'&&e.data.code==='TOOL_FAILED'));
  assert.equal(f.contexts[1]!.l0SnapshotId,'native-lower-observation');
  assert.ok(JSON.stringify(f.requests[3]!.body).includes(PNG));assert.ok(JSON.stringify(f.requests[3]!.body).includes('NULL-SUCCESS-IMAGE'));
  const history=f.turns.at(-1)!.messages.filter(m=>m.kind==='tool_result');
  assert.equal(history.filter(m=>m.kind==='tool_result'&&m.isError).length,1,'Only the deliberately blocked old input is a tool error');
  assert.ok(history.filter(m=>m.kind==='tool_result'&&!m.toolCallId.startsWith('blocked_before_observation')).every(m=>m.kind==='tool_result'&&!m.isError));
  assert.ok(agents.every(a=>a===session.agent));assert.ok(f.turns.at(-1)!.complete);
 });
 test(`${protocol}: submitted screen action admits its perception-sourced post-action screenshot`,async()=>{
  const f=fixture(protocol,[{call:{name:'tap',id:'tap_with_capture'}},{text:'The action completed; use its fresh screenshot.'}]);
  f.snapshot.session.enabled.screen_control=true;
  f.setTool(async()=>({...success('POST_ACTION_CAPTURE'),outcome:'submitted',submission:'submitted',content:[{type:'text',text:'POST_ACTION_CAPTURE'},{type:'attachment',attachmentId:'tool-image'}]}));
  await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Act once and inspect the resulting screen.'}]);
  const result=f.events.find(e=>e.type==='tool_result')!;
  assert.equal(f.executions.length,1);assert.equal(result.data.error,undefined);assert.equal(result.data.outcome,'submitted');
  assert.notEqual(result.data.contentDiscarded,true);assert.ok(JSON.stringify(f.requests[1]!.body).includes(PNG));
 });
 for(const invalid of ['unrelated-source','perception-revoked'] as const)test(`${protocol}: submitted screenshot still rejects ${invalid} and preserves its action receipt`,async()=>{
  const f=fixture(protocol,[{call:{name:'tap',id:'tap_capture_guard'}},{text:'Do not replay the submitted action.'}]);
  f.snapshot.session.enabled.screen_control=true;
  if(invalid==='unrelated-source')f.attachments.get('tool-image')!.source=['web_search'];
  f.setTool(async()=>{
   if(invalid==='perception-revoked')f.snapshot.session.enabled.screen_perception=false;
   return {...success('PRIVATE_POST_ACTION_PIXELS'),outcome:'submitted',submission:'submitted',content:[{type:'text',text:'PRIVATE_POST_ACTION_PIXELS'},{type:'attachment',attachmentId:'tool-image'}]};
  });
  await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Act once; respect image provenance and revocation.'}]);
  const result=f.events.find(e=>e.type==='tool_result')!;
  assert.equal(f.executions.length,1);assert.ok(result.data.error);assert.equal(result.data.outcome,'submitted');assert.equal(result.data.submission,'submitted');
  assert.equal(result.data.contentDiscarded,true);assert.ok(!JSON.stringify(f.requests).includes(PNG));
  assert.ok(!JSON.stringify([f.requests,f.events,f.turns]).includes('PRIVATE_POST_ACTION_PIXELS'));
 });
 test(`${protocol}: Native error:null success still rejects replaced-source text and pixels`,async()=>{
  const f=fixture(protocol,[{call:{name:'capture',id:'changed_capture'}},{text:'Observe the changed source again.'}]);
  let attachmentReads=0;const get=f.ports.attachments.get;f.ports.attachments.get=async id=>{attachmentReads++;return get(id);};
  f.setTool(async()=>{f.snapshot.l0.snapshotId='replaced-source';return {...success('PRIVATE_NULL_SUCCESS_BODY'),content:[{type:'text',text:'PRIVATE_NULL_SUCCESS_BODY'},{type:'attachment',attachmentId:'tool-image'}]};});
  await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Read only this source.'}]);
  const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,'TARGET_CHANGED');assert.equal(result.data.contentDiscarded,true);assert.equal(attachmentReads,0);
  assert.ok(!JSON.stringify([f.requests,f.events,f.turns]).includes('PRIVATE_NULL_SUCCESS_BODY'));assert.ok(!JSON.stringify(f.requests).includes(PNG));
 });
 for(const value of ['',false,0,'NEW_UNKNOWN_ERROR',{},[]])test(`${protocol}: unknown non-null error ${JSON.stringify(value)} fails protocol without delivering its body`,async()=>{
  const f=fixture(protocol,[{call:{name:'capture',id:'bad_error'}},{text:'Respect malformed Native feedback.'}]);
  f.setTool(async()=>({...success('PRIVATE_UNKNOWN_ERROR_BODY'),error:value} as unknown as ToolOutcome));
  await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Validate Native errors strictly.'}]);
  const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,'PROTOCOL_ERROR');assert.equal(result.data.contentDiscarded,true);assert.equal(result.data.recovery,undefined);
  assert.ok(!JSON.stringify([f.requests,f.events,f.turns]).includes('PRIVATE_UNKNOWN_ERROR_BODY'));
 });
 test(`${protocol}: unknown falsy input error withholds body but retains the Native submitted receipt`,async()=>{
  const f=fixture(protocol,[{call:{name:'tap',id:'submitted_bad_error'}},{text:'Inspect before any next action.'}]);f.snapshot.session.enabled.screen_control=true;
  f.setTool(async()=>({...success('PRIVATE_INVALID_INPUT_BODY'),error:false,outcome:'submitted',evidenceRef:'native-submitted-once'} as unknown as ToolOutcome));
  await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Never treat an unknown error as success.'}]);
  const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,'PROTOCOL_ERROR');assert.equal(result.data.outcome,'submitted');assert.equal(result.data.contentDiscarded,true);assert.equal(f.turns.at(-1)!.effects?.[0]?.outcome,'submitted');assert.equal(f.executions.length,1);assert.ok(!JSON.stringify([f.requests,f.events,f.turns]).includes('PRIVATE_INVALID_INPUT_BODY'));
 });
 test(`${protocol}: stop outranks an error:null success and withholds its body`,async()=>{
  const f=fixture(protocol,[{call:{name:'capture',id:'stopped_capture'}}]);const runtime=new ThorRuntime(f.ports,registry());
  f.setTool(async()=>{runtime.stop();return success('PRIVATE_CANCELLED_NULL_BODY');});
  assert.equal((await runtime.run(f.lease,[{type:'text',text:'Stop before admitting the body.'}])).state,'cancelled');
  const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,'CANCELLED');assert.equal(result.data.recovery,undefined);assert.ok(!JSON.stringify([f.requests,f.events,f.turns]).includes('PRIVATE_CANCELLED_NULL_BODY'));
 });
}

test('RuntimeError preserves fixed codes and normalizes unknown Native bridge codes without a null run.error',()=>{
 for(const code of ERROR_CODES){assert.ok(isErrorCode(code));assert.equal(new RuntimeError(code).code,code);}
 for(const code of [undefined,null,'','UNTRUSTED_BRIDGE','DUPLICATE_CALL','UNKNOWN_NATIVE_ERROR',false,0,{},[]]) {
  assert.equal(isErrorCode(code),false);const error=new RuntimeError(code as ErrorCode);assert.equal(error.code,'PROTOCOL_ERROR');assert.equal(error.message,'PROTOCOL_ERROR');
 }
 assert.equal(new RuntimeError('IO_ERROR','Fixed local diagnostic').message,'Fixed local diagnostic');
});
