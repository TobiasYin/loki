import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ThorRuntime } from '../src/runtime.js';
import { ToolRegistry } from '../src/registry.js';
import { descriptor, fixture } from './fixtures.js';

function setup(provider='opencode-zen', ready=true, collision=false) {
  const f=fixture('openai-completions',[{call:{name:provider==='opencode-zen'&&!collision?'read':'skills_read',args:'{"name":"sample"}'}},{text:'Done'}]);
  Object.assign(f.snapshot.session.model,{provider,baseUrl:provider==='opencode-zen'?'https://opencode.ai/zen/v1':'https://fixture.invalid',providerMetadataKnown:provider==='opencode-zen',supportsTools:true,reasoning:false,input:['text']});
  f.snapshot.availability['skills.read']={status:ready?'ready':'backend_missing'};
  const reg=new ToolRegistry();
  reg.register({packId:'thor.skills',packVersion:'1',tools:[{...descriptor('skills_read',[]),toolId:'skills.read',executor:'native-skills',provenance:'builtin',resources:['local-skills'],platformPrerequisites:['skills.read'],inputSchema:{type:'object',properties:{name:{type:'string',maxLength:64},path:{type:'string',maxLength:240}},required:['name'],additionalProperties:false}}]});
  if(collision)reg.register({packId:'test',packVersion:'1',tools:[descriptor('read')]});
  return {f,reg};
}

test('Zen read wire alias executes the real scoped Skill reader with canonical Native identity',async()=>{
  const {f,reg}=setup();
  const result=await new ThorRuntime(f.ports,reg).run(f.lease,[{type:'text',text:'Read the sample skill'}]);
  assert.equal(result.state,'completed');assert.equal(f.executions.length,1);
  assert.equal(f.executions[0]!.toolId,'skills.read');
  assert.deepEqual(f.executions[0]!.arguments,{name:'sample'});
  assert.deepEqual(f.requests[0]!.body.tools.map((t:any)=>t.function.name),['read']);
  assert.ok(f.contexts.some(c=>c.effectiveTools.includes('skills.read')));
  assert.ok(!f.contexts.some(c=>c.effectiveTools.includes('read')));
  const prompt=JSON.stringify(f.requests[0]!.body.messages);
  assert.ok(!prompt.includes('skills_read'));assert.ok(prompt.includes('read'));
});

test('an unavailable Skill reader is never injected merely to satisfy Zen tool admission',async()=>{
  const {f,reg}=setup('opencode-zen',false);
  await new ThorRuntime(f.ports,reg).run(f.lease,[{type:'text',text:'Unavailable reader'}]);
  assert.equal(f.executions.length,0);
  assert.ok(f.requests.every(r=>(r.body.tools?.length??0)===0));
});

test('other providers retain skills_read and an existing read name prevents an alias collision',async()=>{
  for(const [provider,collision] of [['custom',false],['opencode-zen',true]] as const){
    const {f,reg}=setup(provider,true,collision);
    const result=await new ThorRuntime(f.ports,reg).run(f.lease,[{type:'text',text:'Read sample'}]);
    assert.equal(result.state,'completed');assert.equal(f.executions[0]!.toolId,'skills.read');
    const names=f.requests[0]!.body.tools.map((t:any)=>t.function.name);
    assert.ok(names.includes('skills_read'));assert.equal(new Set(names).size,names.length);
  }
});
