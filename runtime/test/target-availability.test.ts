import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ThorRuntime, RuntimeSession, ToolRegistry, type Availability, type ToolDescriptor } from '../src/index.js';
import { descriptor, fixture, PNG } from './fixtures.js';
import { unavailableToolReason } from '../src/tool-availability.js';

function input(overrides:Partial<ToolDescriptor>={}):ToolDescriptor {
  return {...descriptor('tap',['screen_control']),toolId:'input.tap',effectClass:'input',executor:'native-device',provenance:'builtin',platformPrerequisites:['input.tap'],resources:['selected-screen/snapshot'],...overrides};
}
function registry(t=input()) {const r=new ToolRegistry();r.register({packId:'native-availability-fixture',packVersion:'1',tools:[t,
  {...descriptor('capture'),toolId:'perception.capture_screen',executor:'native-device',platformPrerequisites:['perception.capture_screen'],resources:['selected-screen/snapshot']},
]});return r;}
function ready(f:ReturnType<typeof fixture>){f.snapshot.session.enabled.screen_control=true;f.snapshot.availability['input.tap']={status:'ready'};f.snapshot.availability['perception.capture_screen']={status:'ready'};}
const observed=()=>({content:[{type:'text' as const,text:'Current fixture screen'}],source:'fixture-native',observedAt:Date.now(),truncated:false,outcome:'observed' as const});
const typedTarget:Availability={status:'temporarily_unavailable',reasonCode:'TARGET_CHANGED',reason:'Fixed Native observation no longer valid'};

for(const protocol of ['openai-responses','openai-completions','anthropic-messages'] as const) {
  test(`${protocol}: typed Native target unavailability before intent re-observes and continues the same Pi without replay`,async()=>{
    const f=fixture(protocol,[{call:{name:'tap',id:'stale'}},{call:{name:'capture',id:'fresh'}},{call:{name:'tap',id:'new_action'}},{text:'Current observation used.'}]);ready(f);
    const session=new RuntimeSession(f.lease.sessionId),runtime=new ThorRuntime(f.ports,registry(),{},session);const agents:unknown[]=[];
    f.setOnRequest(i=>{agents.push(session.agent);if(i===0)f.snapshot.availability['input.tap']=structuredClone(typedTarget);if(i===1)f.snapshot.availability['input.tap']={status:'ready'};});
    f.setTool(async e=>e.toolId==='perception.capture_screen'?{...observed(),content:[{type:'text',text:'New fixture screenshot'},{type:'attachment',attachmentId:'tool-image'}]}:{...observed(),outcome:'submitted'});
    assert.equal((await runtime.run(f.lease,[{type:'text',text:'Observe the selected screen again when its identity becomes stale.'}])).state,'completed');
    assert.deepEqual(f.executions.map(e=>e.toolId),['perception.capture_screen','input.tap']);
    assert.equal(f.events.filter(e=>e.type==='tool_intent'&&e.toolCallId?.startsWith('stale')).length,0);
    const rejection=f.events.find(e=>e.type==='tool_result'&&e.data.error==='TARGET_CHANGED')!;
    assert.ok(rejection);assert.equal((rejection.data.recovery as any).submission,'not_dispatched');assert.equal((rejection.data.recovery as any).retryPreviousAction,false);
    assert.ok(JSON.stringify(f.requests[1]!.body).includes('observe_again'));assert.ok(JSON.stringify(f.requests[2]!.body).includes(PNG));
    assert.ok(agents.every(a=>a===session.agent));assert.ok(f.requests.every(r=>r.envelope.runId===f.lease.runId));assert.equal(f.snapshot.l0.snapshotId,'l0-1','Recovery must not depend on a changed snapshot ID');
  });
  for(const [name,state,expected] of [
    ['untyped pending/busy',{status:'temporarily_unavailable',reason:'TARGET_CHANGED text is not an authoritative code'},'BACKEND_UNAVAILABLE'],
    ['backend missing',{status:'backend_missing',reasonCode:'TARGET_CHANGED'},'BACKEND_UNAVAILABLE'],
    ['unsupported backend',{status:'unsupported',reasonCode:'TARGET_CHANGED'},'BACKEND_UNAVAILABLE'],
    ['permission missing',{status:'permission_missing',reasonCode:'TARGET_CHANGED'},'PERMISSION_MISSING'],
    ['secure target',{status:'temporarily_unavailable',reasonCode:'SECURE_CONTENT'},'SECURE_CONTENT'],
    ['display gone',{status:'temporarily_unavailable',reasonCode:'DISPLAY_GONE'},'DISPLAY_GONE'],
    ['unknown typed value',{status:'temporarily_unavailable',reasonCode:'CANCELLED'},'BACKEND_UNAVAILABLE'],
  ] as const) test(`${protocol}: ${name} is never relabelled as observation recovery`,async()=>{
    const f=fixture(protocol,[{call:{name:'tap',id:'rejected'}},{text:'Respect the refusal.'}]);ready(f);
    f.setOnRequest(i=>{if(i===0)f.snapshot.availability['input.tap']=state as Availability;});
    await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Use only an available authorized input.'}]);
    const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,expected);assert.equal(result.data.recovery,undefined);assert.equal(f.executions.length,0);assert.ok(!f.events.some(e=>e.type==='tool_intent'));
  });
  test(`${protocol}: capability revocation outranks a typed stale observation`,async()=>{
    const f=fixture(protocol,[{call:{name:'tap',id:'revoked'}},{text:'No input.'}]);ready(f);
    f.setOnRequest(i=>{if(i===0){f.snapshot.session.enabled.screen_control=false;f.snapshot.availability['input.tap']=structuredClone(typedTarget);}});
    await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Do not act after revocation.'}]);
    const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,'CAPABILITY_DISABLED');assert.equal(result.data.recovery,undefined);assert.equal(f.executions.length,0);
  });
  for(const guard of ['stop','epoch'] as const) test(`${protocol}: ${guard} wins before any stale-target Native dispatch`,async()=>{
    const f=fixture(protocol,[{call:{name:'tap',id:'cancelled'}}]);ready(f);const runtime=new ThorRuntime(f.ports,registry());
    f.setOnRequest(i=>{if(i===0){f.snapshot.availability['input.tap']=structuredClone(typedTarget);if(guard==='stop')runtime.stop();else {f.snapshot.session.capabilityEpoch++;f.snapshot.lease.capabilityEpoch++;runtime.authorizationChanged(f.lease.sessionId,f.snapshot.session.capabilityEpoch);}}});
    assert.equal((await runtime.run(f.lease,[{type:'text',text:'Only act while authorized.'}])).state,'cancelled');assert.equal(f.executions.length,0);assert.ok(!f.events.some(e=>e.type==='tool_result'&&e.data.recovery));
  });
  for(const code of ['PERMISSION_MISSING','SECURE_CONTENT','DISPLAY_GONE'] as const)test(`${protocol}: Native ${code} after dispatch is not replaced by later typed target availability`,async()=>{
    const f=fixture(protocol,[{call:{name:'tap',id:'native_refusal'}},{text:'Preserve the actual refusal.'}]);ready(f);
    f.setTool(async()=>{f.snapshot.availability['input.tap']=structuredClone(typedTarget);return {...observed(),error:code,outcome:'unknown'};});
    await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'Use Native safety feedback.'}]);
    const result=f.events.find(e=>e.type==='tool_result')!;assert.equal(result.data.error,code);assert.equal(result.data.recovery,undefined);assert.equal(f.executions.length,1);
  });
}

test('typed recovery is confined to the Native selected-screen input and its own prerequisite, while registry still filters it out',()=>{
  const f=fixture();ready(f);f.snapshot.availability['input.tap']=structuredClone(typedTarget);
  const r=registry();assert.ok(!r.effective(f.snapshot).some(t=>t.toolId==='input.tap'));assert.equal(unavailableToolReason(input(),f.snapshot),'TARGET_CHANGED');
  for(const t of [input({executor:'foreign-executor'}),input({effectClass:'read'}),input({resources:['assistant-chat-task']}),input({requiredCapabilities:['adb_debug']}),input({platformPrerequisites:['other']})]) {
    f.snapshot.availability.other=structuredClone(typedTarget);assert.equal(unavailableToolReason(t,f.snapshot),'BACKEND_UNAVAILABLE');
  }
  const both=input({platformPrerequisites:['input.tap','other']});f.snapshot.availability.other={status:'backend_missing'};assert.equal(unavailableToolReason(both,f.snapshot),'BACKEND_UNAVAILABLE');
  f.snapshot.availability.other={status:'permission_missing'};assert.equal(unavailableToolReason(both,f.snapshot),'PERMISSION_MISSING');
});
