import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkProfile, hasOfficialMetadata } from '../src/profile-validation.js';
import { profile, fixture, registry } from './fixtures.js';
import { ThorRuntime } from '../src/runtime.js';
import type { ModelProfile } from '../src/contracts.js';

function official(provider: 'opencode-go'|'deepseek'): ModelProfile {
  return {...profile(),provider,baseUrl:provider==='deepseek'?'https://api.deepseek.com':'https://opencode.ai/zen/go/v1',providerMetadataKnown:true,probeRequired:false,supportsTools:true,reasoning:true,input:['text'],lastProbe:{revision:0,textStream:false,imageInput:false,toolCalling:false,imageToolRoundTrip:false}};
}
test('official metadata allows immediate send without manufacturing probe success',()=>{
  for(const provider of ['opencode-go','deepseek'] as const){const p=official(provider);checkProfile(p);assert.equal(p.lastProbe.textStream,false);p.input=['text','image'];checkProfile(p);}
});
test('preset names, unknown metadata and nonofficial endpoints cannot bypass a custom probe',()=>{
  for(const mutate of [(p:ModelProfile)=>{p.providerMetadataKnown=false;},(p:ModelProfile)=>{p.baseUrl='https://example.invalid/v1';},(p:ModelProfile)=>{p.baseUrl='https://opencode.ai.evil.invalid/zen/go/v1';},(p:ModelProfile)=>{p.provider='custom';},(p:ModelProfile)=>{p.probeRequired=true;}]){const p=official('opencode-go');mutate(p);assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});}
});
test('custom model still requires its real matching text, tools and image probe',()=>{
  const p=profile();checkProfile(p);p.lastProbe.imageInput=false;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});p.input=['text'];checkProfile(p);p.lastProbe.revision=0;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});
});
test('official text-only and no-tool profiles remain honest and valid for chat',()=>{
  const p=official('deepseek');p.supportsTools=false;p.reasoning=false;checkProfile(p);assert.deepEqual(p.input,['text']);
  delete p.supportsTools;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});p.supportsTools=true;delete p.input;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});
});
test('official profiles retain URL, context budget and header protections',()=>{
  for(const mutate of [(p:ModelProfile)=>{p.maxOutput=p.contextWindow;},(p:ModelProfile)=>{p.customHeaders={Authorization:'not-a-key'};},(p:ModelProfile)=>{p.baseUrl='https://user:pass@api.deepseek.com';}]){const p=official('deepseek');mutate(p);assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});}
  assert.equal(hasOfficialMetadata({...official('opencode-go'),baseUrl:'https://opencode.ai/inference/go/openai/v1'}),true);
});
test('explicit configuration probe is allowed to test an unproven custom profile without claiming success',()=>{
  const p=profile();p.lastProbe.textStream=false;p.configurationProbe=true;checkProfile(p);assert.equal(p.lastProbe.textStream,false);
  p.configurationProbe=false;assert.throws(()=>checkProfile(p),{code:'UNSUPPORTED'});
});
test('unprobed official no-tool text model completes chat without publishing functions or image data',async()=>{
  const f=fixture('openai-responses',[{text:'官方文字模型直接回答'}]);
  Object.assign(f.snapshot.session.model,official('deepseek'),{supportsTools:false,reasoning:false,modelId:'fixture-model'});
  const result=await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'只需文字回答'}]);
  assert.equal(result.state,'completed');assert.equal(f.requests.length,1);assert.equal(f.executions.length,0);
  assert.equal((f.requests[0]!.body.tools as unknown[]|undefined)?.length??0,0);
  assert.equal(f.snapshot.session.model.lastProbe.textStream,false);
  assert.equal(f.snapshot.session.model.lastProbe.toolCalling,false);
  assert.ok(f.contexts.every(c=>c.effectiveTools.length===0));
});
test('Go Anthropic preset switches protocol and sends exactly one v1/messages suffix without a probe',async()=>{
  const f=fixture('anthropic-messages',[{text:'官方 Anthropic 路线直接回答'}]);
  Object.assign(f.snapshot.session.model,official('opencode-go'),{protocol:'anthropic-messages',baseUrl:'https://opencode.ai/zen/go',reasoning:false,modelId:'fixture-model'});
  const result=await new ThorRuntime(f.ports,registry()).run(f.lease,[{type:'text',text:'切换官方模型'}]);
  assert.equal(result.state,'completed');assert.equal(new URL(f.requests[0]!.url).pathname,'/zen/go/v1/messages');
  assert.equal(new URL(f.requests[0]!.url).origin,'https://opencode.ai');
  assert.equal(f.snapshot.session.model.lastProbe.textStream,false);
  const wrong={...f.snapshot.session.model,baseUrl:'https://opencode.ai/zen/go/v1'};
  assert.equal(hasOfficialMetadata(wrong),false);
});
