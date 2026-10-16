import {test} from 'node:test';
import assert from 'node:assert/strict';
import {ThorRuntime, ToolRegistry, type ErrorCode, type ToolDescriptor} from '../src/index.js';
import {fixture,descriptor} from './fixtures.js';
function registry() {const r=new ToolRegistry();r.register({packId:'read-error-priority',packVersion:'1',tools:[
 {...descriptor('capture'),toolId:'perception.capture_screen',resources:['selected-screen/snapshot']},
 {...descriptor('tap',['screen_control']),toolId:'input.tap',resources:['selected-screen/snapshot'],effectClass:'input' as ToolDescriptor['effectClass']},
]});return r;}
const rejected=(error:ErrorCode)=>({content:[{type:'text' as const,text:'PRIVATE_REJECTED_BODY'},{type:'attachment' as const,attachmentId:'rejected-attachment'}],observedAt:Date.now(),source:'fixture-native',truncated:false,error,outcome:'unknown' as const,evidenceRef:'fixed-native-evidence'});
for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) {
 for(const code of ['PERMISSION_MISSING','SECURE_CONTENT'] as const)test(`${protocol}: Native ${code} screen-read refusal outranks source replacement without admitting body`,async()=>{
  const f=fixture(protocol,[{call:{name:'capture',id:'rejected_read'}},{text:'Keep the real refusal.'}]);let attachmentReads=0;
  const get=f.ports.attachments.get;f.ports.attachments.get=async id=>{if(id==='rejected-attachment')attachmentReads++;return get(id);};
  f.setTool(async()=>{f.snapshot.l0.snapshotId='new-source';return rejected(code);});
  assert.equal((await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Read screen.'}])).state,'completed');
  const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,code);assert.equal(result.data.recovery,undefined);assert.equal(result.data.outcome,'unknown');assert.equal(result.data.evidenceRef,'fixed-native-evidence');assert.equal(result.data.contentDiscarded,true);assert.equal(attachmentReads,0);
  assert.ok(!JSON.stringify([f.events,f.turns,f.requests]).includes('PRIVATE_REJECTED_BODY'));
  assert.ok(!JSON.stringify(f.requests).includes('rejected-attachment'));
  assert.ok(JSON.stringify(f.requests[1]!.body).includes(code));assert.equal(f.executions.length,1);
 });
 test(`${protocol}: Native TARGET_CHANGED screen read still requests new observation with unknown receipt`,async()=>{
  const f=fixture(protocol,[{call:{name:'capture',id:'changed_read'}},{text:'Observe again.'}]);f.setTool(async()=>{f.snapshot.l0.snapshotId='new-source';return rejected('TARGET_CHANGED');});
  await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Read screen.'}]);const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,'TARGET_CHANGED');assert.equal((result.data.recovery as any).kind,'observe_again');assert.equal(result.data.outcome,'unknown');assert.equal(result.data.evidenceRef,'fixed-native-evidence');assert.ok(!JSON.stringify([f.events,f.turns,f.requests]).includes('PRIVATE_REJECTED_BODY'));
 });
 test(`${protocol}: successful screen read from replaced source still discards stale body`,async()=>{
  const f=fixture(protocol,[{call:{name:'capture',id:'stale_success'}},{text:'Observe again.'}]);f.setTool(async()=>{f.snapshot.l0.snapshotId='new-source';const {error,...outcome}=rejected('SECURE_CONTENT');return outcome;});
  await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Read screen.'}]);const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,'TARGET_CHANGED');assert.equal((result.data.recovery as any).kind,'observe_again');assert.ok(!JSON.stringify([f.events,f.turns,f.requests]).includes('PRIVATE_REJECTED_BODY'));
 });
 test(`${protocol}: non-read COMMAND_BLOCKED keeps its structured feedback after target navigation`,async()=>{
  const f=fixture(protocol,[{call:{name:'tap',id:'blocked_input'}},{text:'Respect policy.'}]);f.snapshot.session.enabled.screen_control=true;
  const feedback=JSON.stringify({reason:'blocked_by_policy',allowedAlternative:'observe_current_screen'});
  f.setTool(async()=>{f.snapshot.l0.snapshotId='new-source';return {...rejected('COMMAND_BLOCKED'),content:[{type:'text',text:feedback}]};});
  await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Perform allowed action only.'}]);const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,'COMMAND_BLOCKED');assert.equal(result.data.recovery,undefined);assert.ok(JSON.stringify(result.data.content).includes('blocked_by_policy'));assert.ok(JSON.stringify(f.requests[1]!.body).includes('allowedAlternative'));
 });
 for(const guard of ['stop','epoch','capability'] as const)test(`${protocol}: ${guard} still outranks Native safety refusal and source replacement`,async()=>{
  const f=fixture(protocol,[{call:{name:'capture',id:'revoked_read'}},{text:'No private content.'}]);const runtime=new ThorRuntime(f.ports,registry());
  f.setTool(async()=>{f.snapshot.l0.snapshotId='new-source';if(guard==='stop')runtime.stop();else if(guard==='epoch'){f.snapshot.session.capabilityEpoch++;f.snapshot.lease.capabilityEpoch++;runtime.authorizationChanged(f.lease.sessionId,f.snapshot.session.capabilityEpoch);}else f.snapshot.session.enabled.screen_perception=false;return rejected('SECURE_CONTENT');});
  const run=await runtime.run(f.lease,[{type:'text',text:'Read screen.'}]);
  if(guard!=='capability')assert.equal(run.state,'cancelled');
  const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,guard==='stop'?'CANCELLED':guard==='epoch'?'STALE_AUTHORIZATION':'CAPABILITY_DISABLED');assert.equal(result.data.recovery,undefined);assert.ok(!JSON.stringify([f.events,f.turns,f.requests]).includes('PRIVATE_REJECTED_BODY'));assert.equal(f.executions.length,1);
 });
}
