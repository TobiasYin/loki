import {test} from 'node:test';
import assert from 'node:assert/strict';
import {runtimeUiEvent,postRuntimeUiEvent} from './runtime-ui-events.mjs';
test('execution UI events project only fixed bounded metadata, never prompt/tool text or functions',()=>{
 const secret={text:'PRIVATE_PROMPT',body:'PRIVATE_MODEL',arguments:{shell:'PRIVATE_TOOL'},callback:'arbitraryFunction'};
 const events=[runtimeUiEvent('ready',secret),runtimeUiEvent('preparationState',{sessionId:'sid',phase:'starting',elapsedMs:20,...secret}),runtimeUiEvent('sendFailure',{sessionId:'sid',clientSendId:'send-id',errorCode:'TIMEOUT',...secret}),runtimeUiEvent('capabilityFailure',{sessionId:'sid',capability:'screen_control',clientMutationId:'mutation',...secret}),runtimeUiEvent('operationFailure',{sessionId:'sid',...secret})];
 assert.ok(events.every(Boolean));const json=JSON.stringify(events);assert.ok(!json.includes('PRIVATE'));assert.ok(!json.includes('arbitraryFunction'));assert.ok(!json.includes('arguments'));
 assert.deepEqual(events[2],{name:'sendFailure',payload:{sessionId:'sid',clientSendId:'send-id',errorCode:'TIMEOUT'}});
});
test('unknown functions, phases, capabilities, oversized IDs and raw upstream error values cannot cross relay',()=>{
 for(const event of [runtimeUiEvent('eval',{}),runtimeUiEvent('preparationState',{sessionId:'sid',phase:'run-arbitrary',elapsedMs:1}),runtimeUiEvent('preparationState',{sessionId:'sid',phase:'starting',elapsedMs:Infinity}),runtimeUiEvent('sendFailure',{sessionId:'sid',clientSendId:'x'.repeat(161)}),runtimeUiEvent('capabilityFailure',{sessionId:'sid',clientMutationId:'mutation',capability:'shell'})])assert.equal(event,undefined);
 assert.equal(runtimeUiEvent('sendFailure',{sessionId:'sid',clientSendId:'send',errorCode:'raw upstream error body'}).payload.errorCode,'IO_ERROR');
 assert.equal(runtimeUiEvent('sendFailure',{sessionId:'sid'}),undefined);
});

test('absent UI/ack never creates pending promises and disconnected or failed delivery drops without retry',()=>{
 const messages=[];const send=raw=>messages.push(JSON.parse(raw));
 for(let i=0;i<1000;i++)assert.equal(postRuntimeUiEvent(send,'connection','preparationState',{sessionId:'sid',phase:'checking',elapsedMs:i}),true);
 assert.equal(messages.length,1000);assert.equal(new Set(messages.map(m=>m.requestId)).size,1000);
 assert.ok(messages.every(m=>m.op==='runtime.ui.event'&&m.data.name==='preparationState'));
 assert.equal(postRuntimeUiEvent(send,'','ready'),false);assert.equal(messages.length,1000);
 let attempts=0;assert.equal(postRuntimeUiEvent(()=>{attempts++;throw new Error('closed view');},'connection','ready'),false);assert.equal(attempts,1);
 assert.equal(postRuntimeUiEvent(send,'connection','arbitraryEval',{source:'private'}),false);assert.equal(messages.length,1000);
});
